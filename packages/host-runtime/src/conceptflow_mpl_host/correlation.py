# SPDX-License-Identifier: MIT OR Apache-2.0
"""Bounded request correlation and stale-result rejection."""

from __future__ import annotations

from collections import OrderedDict
from dataclasses import dataclass
import time

from conceptflow.mpl.v1 import perception_pb2 as pb


@dataclass(frozen=True, slots=True)
class CorrelationDecision:
    accepted: bool
    reason: str


@dataclass(frozen=True, slots=True)
class _PendingFrame:
    session_id: str
    stream_id: str
    frame_id: int
    capture_ns: int
    registered_ns: int


class ResultCorrelator:
    def __init__(
        self,
        *,
        max_pending: int = 64,
        max_result_age_ms: int = 1_500,
        max_delivered_streams: int = 1_024,
    ) -> None:
        if min(max_pending, max_result_age_ms, max_delivered_streams) <= 0:
            raise ValueError("correlator limits must be positive")
        self._max_pending = max_pending
        self._max_age_ns = max_result_age_ms * 1_000_000
        self._max_delivered_streams = max_delivered_streams
        self._pending: OrderedDict[str, _PendingFrame] = OrderedDict()
        self._last_delivered: OrderedDict[tuple[str, str], int] = OrderedDict()

    @property
    def pending_count(self) -> int:
        return len(self._pending)

    @property
    def delivered_stream_count(self) -> int:
        return len(self._last_delivered)

    def ensure_capacity(self, request_id: str) -> None:
        """Reject before dispatch when a request cannot be tracked safely."""

        if request_id in self._pending:
            raise ValueError("request_id is already pending")
        if len(self._pending) >= self._max_pending:
            raise OverflowError("pending correlation capacity is exhausted")

    def register(self, frame: pb.FramePayload, *, now_ns: int | None = None) -> None:
        self.ensure_capacity(frame.request_id)
        self._pending[frame.request_id] = _PendingFrame(
            session_id=frame.session_id,
            stream_id=frame.stream_id,
            frame_id=frame.frame_id,
            capture_ns=frame.capture_monotonic_timestamp_ns,
            registered_ns=time.monotonic_ns() if now_ns is None else now_ns,
        )

    def cancel(self, request_id: str) -> bool:
        return self._pending.pop(request_id, None) is not None

    def reset_stream(self, session_id: str, stream_id: str) -> int:
        """Forget pending and delivered state for one explicit stream reset."""

        key = (session_id, stream_id)
        delivered_removed = self._last_delivered.pop(key, None) is not None
        request_ids = [
            request_id
            for request_id, pending in self._pending.items()
            if (pending.session_id, pending.stream_id) == key
        ]
        for request_id in request_ids:
            del self._pending[request_id]
        return len(request_ids) + int(delivered_removed)

    def reset(self) -> None:
        """Forget all pending and delivered correlation state."""

        self._pending.clear()
        self._last_delivered.clear()

    def accept(self, result: pb.PerceptionResult, *, now_ns: int) -> CorrelationDecision:
        pending = self._pending.pop(result.request_id, None)
        if pending is None:
            return CorrelationDecision(False, "unknown or already completed request")
        if (
            result.session_id != pending.session_id
            or result.stream_id != pending.stream_id
            or result.frame_id != pending.frame_id
            or result.capture_monotonic_timestamp_ns != pending.capture_ns
        ):
            return CorrelationDecision(False, "result correlation fields do not match request")
        if now_ns < pending.registered_ns or now_ns - pending.registered_ns > self._max_age_ns:
            return CorrelationDecision(False, "result exceeded maximum age")
        key = (pending.session_id, pending.stream_id)
        if result.frame_id <= self._last_delivered.get(key, 0):
            return CorrelationDecision(False, "result is older than the latest delivered frame")
        if key in self._last_delivered:
            self._last_delivered.move_to_end(key)
        elif len(self._last_delivered) >= self._max_delivered_streams:
            self._last_delivered.popitem(last=False)
        self._last_delivered[key] = result.frame_id
        return CorrelationDecision(True, "correlated result accepted")
