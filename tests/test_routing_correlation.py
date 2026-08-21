# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import pytest

from conceptflow.mpl.v1 import perception_pb2 as pb
from conceptflow_mpl_host.correlation import ResultCorrelator
from conceptflow_mpl_host.routing import RouteContext, RouteTarget, choose_route


def _result(frame: pb.FramePayload) -> pb.PerceptionResult:
    return pb.PerceptionResult(
        request_id=frame.request_id,
        session_id=frame.session_id,
        stream_id=frame.stream_id,
        frame_id=frame.frame_id,
        capture_monotonic_timestamp_ns=frame.capture_monotonic_timestamp_ns,
    )


def test_route_prefers_cluster_quality_within_budget() -> None:
    decision = choose_route(RouteContext(local_available=True, cluster_available=True))
    assert decision.target == RouteTarget.CLUSTER


def test_private_frame_stays_local() -> None:
    decision = choose_route(RouteContext(local_available=True, cluster_available=True, privacy_sensitive=True))
    assert decision.target == RouteTarget.LOCAL


def test_private_frame_fails_closed_without_local_route() -> None:
    decision = choose_route(RouteContext(local_available=False, cluster_available=True, privacy_sensitive=True))
    assert decision.target == RouteTarget.DROP


def test_route_uses_available_deadline_compliant_fallback() -> None:
    context = RouteContext(
        local_available=True,
        cluster_available=True,
        prefer_cluster_quality=True,
        latency_budget_ms=100,
        cluster_estimated_ms=101,
        local_estimated_ms=90,
    )
    assert choose_route(context).target == RouteTarget.LOCAL


def test_correlation_accepts_exact_match(frame_factory) -> None:
    frame = frame_factory(capture_ns=1_000)
    correlator = ResultCorrelator(max_result_age_ms=1)
    correlator.register(frame, now_ns=1_000)
    assert correlator.accept(_result(frame), now_ns=1_500).accepted


def test_correlation_rejects_unknown_and_mismatch(frame_factory) -> None:
    frame = frame_factory(capture_ns=1_000)
    correlator = ResultCorrelator()
    assert not correlator.accept(_result(frame), now_ns=1_001).accepted
    correlator.register(frame, now_ns=1_000)
    mismatch = _result(frame)
    mismatch.frame_id = 9
    assert not correlator.accept(mismatch, now_ns=1_001).accepted


def test_correlation_rejects_stale_and_out_of_order(frame_factory) -> None:
    correlator = ResultCorrelator(max_result_age_ms=1)
    stale = frame_factory(frame_id=1, capture_ns=1_000)
    correlator.register(stale, now_ns=1_000)
    assert not correlator.accept(_result(stale), now_ns=2_000_001).accepted
    latest = frame_factory(frame_id=3, capture_ns=3_000, request_id="latest")
    older = frame_factory(frame_id=2, capture_ns=2_000, request_id="older")
    correlator.register(latest, now_ns=3_000)
    correlator.register(older, now_ns=2_000)
    assert correlator.accept(_result(latest), now_ns=3_001).accepted
    assert not correlator.accept(_result(older), now_ns=3_002).accepted


def test_correlation_is_bounded(frame_factory) -> None:
    correlator = ResultCorrelator(max_pending=1)
    first = frame_factory(frame_id=1, request_id="first")
    second = frame_factory(frame_id=2, request_id="second")
    assert correlator.register(first, now_ns=1) is None
    with pytest.raises(OverflowError, match="capacity"):
        correlator.register(second, now_ns=2)
    assert correlator.pending_count == 1
    assert correlator.accept(_result(first), now_ns=2).accepted


def test_delivered_stream_history_is_bounded_and_resettable(frame_factory) -> None:
    correlator = ResultCorrelator(max_delivered_streams=2)
    frames = []
    for index, stream_id in enumerate(("one", "two", "three"), start=1):
        frame = frame_factory(frame_id=index, request_id=f"request-{index}", capture_ns=index)
        frame.stream_id = stream_id
        correlator.register(frame, now_ns=index)
        assert correlator.accept(_result(frame), now_ns=index + 1).accepted
        frames.append(frame)

    assert correlator.delivered_stream_count == 2
    assert correlator.reset_stream(frames[1].session_id, frames[1].stream_id) == 1
    assert correlator.delivered_stream_count == 1
    correlator.reset()
    assert correlator.pending_count == 0
    assert correlator.delivered_stream_count == 0
