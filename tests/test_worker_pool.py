# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import asyncio

import pytest

from conceptflow.mpl.v1 import perception_pb2 as pb
from conceptflow_mpl_cluster.device import ComputeDevice
from conceptflow_mpl_cluster.errors import ClusterError
from conceptflow_mpl_cluster.pool import WorkerPool
from conceptflow_mpl_cluster.worker import DeterministicMockWorker


class _ExclusiveProbeWorker:
    def __init__(self) -> None:
        self.worker_id = "exclusive"
        self.device = ComputeDevice("cpu", "0", "CPU")
        self.first_entered = asyncio.Event()
        self.release_first = asyncio.Event()
        self.calls: list[int] = []
        self.active = 0
        self.max_active = 0

    async def process(self, frame: pb.FramePayload) -> pb.PerceptionResult:
        self.calls.append(frame.frame_id)
        self.active += 1
        self.max_active = max(self.max_active, self.active)
        try:
            if frame.frame_id == 1:
                self.first_entered.set()
                await self.release_first.wait()
            return pb.PerceptionResult(
                result_id=f"result-{frame.frame_id}",
                request_id=frame.request_id,
                session_id=frame.session_id,
                stream_id=frame.stream_id,
                frame_id=frame.frame_id,
                capture_monotonic_timestamp_ns=frame.capture_monotonic_timestamp_ns,
            )
        finally:
            self.active -= 1


class _CancellationSwallowingWorker:
    def __init__(self) -> None:
        self.worker_id = "cancellation-swallowing"
        self.device = ComputeDevice("cpu", "0", "CPU")
        self.entered = asyncio.Event()
        self.cancelled = asyncio.Event()
        self.release = asyncio.Event()

    async def process(self, frame: pb.FramePayload) -> pb.PerceptionResult:
        self.entered.set()
        while not self.release.is_set():
            try:
                await self.release.wait()
            except asyncio.CancelledError:
                self.cancelled.set()
        return pb.PerceptionResult(request_id=frame.request_id)


def _pool(
    workers,
    *,
    capacity=2,
    runners=1,
    timeout_ms=100,
    threshold=1,
    shutdown_timeout_ms=2_000,
) -> WorkerPool:
    return WorkerPool(
        workers,
        queue_capacity=capacity,
        runner_count=runners,
        timeout_ms=timeout_ms,
        failure_threshold=threshold,
        shutdown_timeout_ms=shutdown_timeout_ms,
    )


@pytest.mark.asyncio
async def test_deterministic_worker_returns_stable_semantics(frame_factory) -> None:
    worker = DeterministicMockWorker("worker", ComputeDevice("cpu", "0", "CPU"))
    frame = frame_factory()
    first = await worker.process(frame)
    second = await worker.process(frame)
    assert first.result_id == second.result_id
    assert first.observations == second.observations
    assert first.provenance.synthetic


@pytest.mark.asyncio
async def test_worker_failure_isolated_and_next_worker_selected(frame_factory) -> None:
    failing = DeterministicMockWorker(
        "failing",
        ComputeDevice("cuda", "a", "failed GPU"),
        failing_frame_ids=frozenset({1}),
    )
    healthy = DeterministicMockWorker("healthy", ComputeDevice("cpu", "0", "CPU"))
    pool = _pool([failing, healthy], threshold=1)
    await pool.start()
    try:
        result = await pool.submit(frame_factory())
        assert result.provenance.worker_id == "healthy"
        snapshots = {snapshot.worker_id: snapshot for snapshot in pool.snapshots()}
        assert not snapshots["failing"].healthy
        assert snapshots["healthy"].healthy
        second = await pool.submit(frame_factory(frame_id=2))
        assert second.provenance.worker_id == "healthy"
    finally:
        await pool.close()


@pytest.mark.asyncio
async def test_timeout_cancels_worker(frame_factory) -> None:
    worker = DeterministicMockWorker("slow", ComputeDevice("cpu", "0", "CPU"), delay_by_frame_id={1: 1.0})
    pool = _pool([worker], timeout_ms=10)
    await pool.start()
    try:
        with pytest.raises(ClusterError) as captured:
            await pool.submit(frame_factory())
        assert captured.value.code == pb.ERROR_CODE_DEADLINE_EXCEEDED
        await asyncio.sleep(0)
        assert worker.cancelled_frame_ids == [1]
    finally:
        await pool.close()


@pytest.mark.asyncio
async def test_caller_cancellation_reaches_worker(frame_factory) -> None:
    worker = DeterministicMockWorker("slow", ComputeDevice("cpu", "0", "CPU"), delay_by_frame_id={1: 1.0})
    pool = _pool([worker], timeout_ms=2_000)
    await pool.start()
    try:
        task = asyncio.create_task(pool.submit(frame_factory()))
        await asyncio.sleep(0.01)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        await asyncio.sleep(0)
        assert worker.cancelled_frame_ids == [1]
    finally:
        await pool.close()


@pytest.mark.asyncio
async def test_queue_backpressure_is_explicit(frame_factory) -> None:
    worker = DeterministicMockWorker(
        "slow",
        ComputeDevice("cpu", "0", "CPU"),
        delay_by_frame_id={1: 1.0, 2: 1.0},
    )
    pool = _pool([worker], capacity=1, timeout_ms=2_000)
    await pool.start()
    first = asyncio.create_task(pool.submit(frame_factory(frame_id=1)))
    await asyncio.sleep(0.01)
    second = asyncio.create_task(pool.submit(frame_factory(frame_id=2)))
    await asyncio.sleep(0)
    try:
        with pytest.raises(ClusterError) as captured:
            await pool.submit(frame_factory(frame_id=3))
        assert captured.value.code == pb.ERROR_CODE_OVERLOADED
    finally:
        first.cancel()
        second.cancel()
        await asyncio.gather(first, second, return_exceptions=True)
        await pool.close()


@pytest.mark.asyncio
async def test_pending_work_is_round_robin_fair_between_sessions(frame_factory) -> None:
    worker = _ExclusiveProbeWorker()
    pool = _pool([worker], capacity=3, timeout_ms=2_000)
    await pool.start()

    def frame(frame_id: int, session_id: str):
        value = frame_factory(frame_id=frame_id, request_id=f"request-{frame_id}")
        value.session_id = session_id
        return value

    active = asyncio.create_task(pool.submit(frame(1, "session-a")))
    await worker.first_entered.wait()
    same_session_first = asyncio.create_task(pool.submit(frame(2, "session-a")))
    same_session_second = asyncio.create_task(pool.submit(frame(3, "session-a")))
    competing_session = asyncio.create_task(pool.submit(frame(4, "session-b")))
    await asyncio.sleep(0)

    worker.release_first.set()
    await asyncio.gather(active, same_session_first, same_session_second, competing_session)

    assert worker.calls == [1, 2, 4, 3]
    await pool.close()


@pytest.mark.asyncio
async def test_multiple_runners_never_execute_one_worker_concurrently(frame_factory) -> None:
    worker = _ExclusiveProbeWorker()
    pool = _pool([worker], capacity=2, runners=2, timeout_ms=1_000)
    await pool.start()
    first = asyncio.create_task(pool.submit(frame_factory(frame_id=1)))
    await worker.first_entered.wait()
    second = asyncio.create_task(pool.submit(frame_factory(frame_id=2)))
    await asyncio.sleep(0.01)
    close = asyncio.create_task(pool.close())
    await asyncio.sleep(0)
    try:
        assert worker.calls == [1]
        assert worker.max_active == 1
        await close
        failures = await asyncio.gather(first, second, return_exceptions=True)
        assert all(isinstance(error, ClusterError) for error in failures)
        assert all(error.code == pb.ERROR_CODE_CANCELLED for error in failures)
        assert worker.calls == [1]
        assert worker.max_active == 1
    finally:
        worker.release_first.set()
        await asyncio.gather(first, second, close, return_exceptions=True)


@pytest.mark.asyncio
async def test_close_cancels_active_and_full_queue_without_blocking(frame_factory) -> None:
    worker = _ExclusiveProbeWorker()
    pool = _pool([worker], capacity=1, timeout_ms=5_000, shutdown_timeout_ms=100)
    await pool.start()
    active = asyncio.create_task(pool.submit(frame_factory(frame_id=1)))
    await worker.first_entered.wait()
    queued = asyncio.create_task(pool.submit(frame_factory(frame_id=2)))
    await asyncio.sleep(0)

    await asyncio.wait_for(pool.close(), timeout=0.25)
    failures = await asyncio.gather(active, queued, return_exceptions=True)

    assert all(isinstance(error, ClusterError) for error in failures)
    assert all(error.code == pb.ERROR_CODE_CANCELLED for error in failures)
    assert pool.queue_depth == 0
    assert not pool.running
    worker.release_first.set()


@pytest.mark.asyncio
async def test_close_hard_timeout_survives_worker_suppressing_cancellation(frame_factory) -> None:
    worker = _CancellationSwallowingWorker()
    pool = _pool([worker], capacity=1, timeout_ms=5_000, shutdown_timeout_ms=20)
    await pool.start()
    submission = asyncio.create_task(pool.submit(frame_factory()))
    await worker.entered.wait()
    loop = asyncio.get_running_loop()
    started = loop.time()

    await asyncio.wait_for(pool.close(), timeout=0.2)

    assert loop.time() - started < 0.15
    assert worker.cancelled.is_set()
    with pytest.raises(ClusterError) as captured:
        await submission
    assert captured.value.code == pb.ERROR_CODE_CANCELLED
    worker.release.set()
    await asyncio.sleep(0)
