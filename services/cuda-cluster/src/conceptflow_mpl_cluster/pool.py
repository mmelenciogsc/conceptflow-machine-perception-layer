# SPDX-License-Identifier: MIT OR Apache-2.0
"""Bounded worker queue with cancellation, timeout, and failure isolation."""

from __future__ import annotations

import asyncio
from collections import OrderedDict, deque
from dataclasses import dataclass
from typing import Sequence

from conceptflow.mpl.v1 import perception_pb2 as pb

from .errors import ClusterError, overloaded
from .worker import PerceptionWorker


@dataclass(slots=True)
class _WorkerState:
    worker: PerceptionWorker
    consecutive_failures: int = 0
    healthy: bool = True
    busy: bool = False


@dataclass(slots=True, eq=False)
class _WorkItem:
    frame: pb.FramePayload
    future: asyncio.Future[pb.PerceptionResult]
    processing_task: asyncio.Task[pb.PerceptionResult] | None = None


class _FairSessionQueue:
    """Bounded round-robin queue across session-local FIFO lanes."""

    def __init__(self, maxsize: int) -> None:
        self.maxsize = maxsize
        self._size = 0
        self._lanes: OrderedDict[str, deque[_WorkItem]] = OrderedDict()
        self._available = asyncio.Event()

    def qsize(self) -> int:
        return self._size

    def put_nowait(self, item: _WorkItem) -> None:
        if self._size >= self.maxsize:
            raise asyncio.QueueFull
        lane = self._lanes.get(item.frame.session_id)
        if lane is None:
            lane = deque()
            self._lanes[item.frame.session_id] = lane
        lane.append(item)
        self._size += 1
        self._available.set()

    def get_nowait(self) -> _WorkItem:
        if self._size == 0:
            raise asyncio.QueueEmpty
        session_id, lane = self._lanes.popitem(last=False)
        item = lane.popleft()
        self._size -= 1
        if lane:
            self._lanes[session_id] = lane
        if self._size == 0:
            self._available.clear()
        return item

    async def get(self) -> _WorkItem:
        while True:
            try:
                return self.get_nowait()
            except asyncio.QueueEmpty:
                self._available.clear()
                await self._available.wait()

    def task_done(self) -> None:
        # The pool never joins this queue; completion is tracked by item futures.
        return None


@dataclass(frozen=True, slots=True)
class WorkerSnapshot:
    worker_id: str
    device: str
    healthy: bool
    consecutive_failures: int


class WorkerPool:
    def __init__(
        self,
        workers: Sequence[PerceptionWorker],
        *,
        queue_capacity: int,
        runner_count: int,
        timeout_ms: int,
        failure_threshold: int,
        shutdown_timeout_ms: int = 2_000,
    ) -> None:
        if not workers:
            raise ValueError("at least one worker is required")
        if min(queue_capacity, runner_count, timeout_ms, failure_threshold, shutdown_timeout_ms) <= 0:
            raise ValueError("pool limits must be positive")
        self._states = [_WorkerState(worker) for worker in workers]
        self._queue = _FairSessionQueue(queue_capacity)
        self._runner_count = runner_count
        self._timeout_seconds = timeout_ms / 1000
        self._shutdown_timeout_seconds = shutdown_timeout_ms / 1000
        self._failure_threshold = failure_threshold
        self._runners: list[asyncio.Task[None]] = []
        self._active_items: set[_WorkItem] = set()
        self._active_tasks: set[asyncio.Task[pb.PerceptionResult]] = set()
        self._round_robin = 0
        self._closed = False
        self._worker_available = asyncio.Condition()
        self._close_lock = asyncio.Lock()

    @property
    def queue_depth(self) -> int:
        return self._queue.qsize()

    @property
    def queue_capacity(self) -> int:
        return self._queue.maxsize

    @property
    def running(self) -> bool:
        return bool(self._runners) and not self._closed

    def snapshots(self) -> tuple[WorkerSnapshot, ...]:
        return tuple(
            WorkerSnapshot(
                state.worker.worker_id,
                state.worker.device.label,
                state.healthy,
                state.consecutive_failures,
            )
            for state in self._states
        )

    async def start(self) -> None:
        if self._closed:
            raise RuntimeError("worker pool is closed")
        if not self._runners:
            self._runners = [
                asyncio.create_task(self._run(), name=f"mpl-worker-runner-{index}")
                for index in range(self._runner_count)
            ]

    @staticmethod
    def _shutdown_error() -> ClusterError:
        return ClusterError(pb.ERROR_CODE_CANCELLED, "worker pool is shutting down", False, 0)

    @staticmethod
    def _complete_with_error(item: _WorkItem, error: ClusterError) -> None:
        if not item.future.done():
            item.future.set_exception(error)

    @staticmethod
    def _consume_detached_task(task: asyncio.Task[pb.PerceptionResult]) -> None:
        if not task.cancelled():
            task.exception()

    async def close(self) -> None:
        async with self._close_lock:
            if self._closed:
                return
            self._closed = True
            while True:
                try:
                    queued = self._queue.get_nowait()
                except asyncio.QueueEmpty:
                    break
                self._complete_with_error(queued, self._shutdown_error())
                self._queue.task_done()
            for item in tuple(self._active_items):
                self._complete_with_error(item, self._shutdown_error())
                if item.processing_task is not None:
                    item.processing_task.cancel()
            for task in tuple(self._active_tasks):
                task.cancel()
            for runner in self._runners:
                runner.cancel()
            await self._notify_worker_waiters()

            waiters: set[asyncio.Task[object]] = set(self._runners)
            waiters.update(self._active_tasks)
            if waiters:
                _, pending = await asyncio.wait(waiters, timeout=self._shutdown_timeout_seconds)
                for pending_task in pending:
                    pending_task.cancel()
            for task in tuple(self._active_tasks):
                if not task.done():
                    task.add_done_callback(self._consume_detached_task)
            self._runners.clear()

    async def _reserve(
        self,
        attempted: set[str],
        future: asyncio.Future[pb.PerceptionResult],
    ) -> _WorkerState | None:
        async with self._worker_available:
            while True:
                if self._closed or future.done():
                    return None
                eligible = [
                    state for state in self._states if state.healthy and state.worker.worker_id not in attempted
                ]
                available = [state for state in eligible if not state.busy]
                if available:
                    state = available[self._round_robin % len(available)]
                    self._round_robin += 1
                    state.busy = True
                    return state
                if not eligible:
                    return None
                await self._worker_available.wait()

    async def _release(self, state: _WorkerState, *, succeeded: bool | None) -> None:
        async with self._worker_available:
            if succeeded is True:
                state.consecutive_failures = 0
            elif succeeded is False:
                state.consecutive_failures += 1
                if state.consecutive_failures >= self._failure_threshold:
                    state.healthy = False
            state.busy = False
            self._worker_available.notify_all()

    async def _notify_worker_waiters(self) -> None:
        async with self._worker_available:
            self._worker_available.notify_all()

    async def submit(
        self,
        frame: pb.FramePayload,
        *,
        timeout_seconds: float | None = None,
    ) -> pb.PerceptionResult:
        if self._closed or not self._runners:
            raise ClusterError(pb.ERROR_CODE_WORKER_UNAVAILABLE, "worker pool is not running", True, 100)
        loop = asyncio.get_running_loop()
        item = _WorkItem(frame=frame, future=loop.create_future())
        try:
            self._queue.put_nowait(item)
        except asyncio.QueueFull as exc:
            raise overloaded() from exc
        timeout = self._timeout_seconds if timeout_seconds is None else min(self._timeout_seconds, timeout_seconds)
        try:
            return await asyncio.wait_for(asyncio.shield(item.future), timeout=timeout)
        except TimeoutError as exc:
            item.future.cancel()
            if item.processing_task is not None:
                item.processing_task.cancel()
            await self._notify_worker_waiters()
            raise ClusterError(
                pb.ERROR_CODE_DEADLINE_EXCEEDED,
                "worker processing deadline exceeded",
                True,
                10,
            ) from exc
        except asyncio.CancelledError:
            item.future.cancel()
            if item.processing_task is not None:
                item.processing_task.cancel()
            await self._notify_worker_waiters()
            raise

    async def _run(self) -> None:
        while True:
            try:
                item = await self._queue.get()
            except asyncio.CancelledError:
                return
            self._active_items.add(item)
            try:
                if item.future.done():
                    continue
                attempted: set[str] = set()
                while True:
                    state = await self._reserve(attempted, item.future)
                    if state is None:
                        if not item.future.done():
                            item.future.set_exception(
                                ClusterError(
                                    pb.ERROR_CODE_WORKER_UNAVAILABLE,
                                    "no healthy worker is available",
                                    True,
                                    100,
                                )
                            )
                        break
                    attempted.add(state.worker.worker_id)
                    succeeded: bool | None = None
                    try:
                        if item.future.done():
                            break
                        processing_task = asyncio.create_task(state.worker.process(item.frame))
                        item.processing_task = processing_task
                        self._active_tasks.add(processing_task)
                        processing_task.add_done_callback(self._active_tasks.discard)
                        result = await asyncio.shield(processing_task)
                    except asyncio.CancelledError:
                        if item.processing_task is not None:
                            item.processing_task.cancel()
                        if self._closed:
                            return
                        if not item.future.done():
                            item.future.cancel()
                        break
                    except Exception:
                        succeeded = False
                        continue
                    else:
                        succeeded = True
                        if not item.future.done():
                            item.future.set_result(result)
                        break
                    finally:
                        item.processing_task = None
                        await self._release(state, succeeded=succeeded)
            finally:
                self._active_items.discard(item)
                self._queue.task_done()
