# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import json
import time

import pytest

from conceptflow.mpl.v1 import perception_pb2 as pb
from conceptflow_mpl_host.correlation import ResultCorrelator
from conceptflow_mpl_host.latency import LatencyTracker
from conceptflow_mpl_host.pipeline import HostPipeline
from conceptflow_mpl_host.preprocessing import FramePreprocessor
from conceptflow_mpl_host.renderer import InspectableCueRenderer
from conceptflow_mpl_host.routing import RouteContext
from conceptflow_mpl_host.scheduler import CueScheduler


def _result(frame: pb.FramePayload, cues: list[pb.PerceptionCue]) -> pb.PerceptionResult:
    return pb.PerceptionResult(
        result_id=f"result-{frame.frame_id}",
        request_id=frame.request_id,
        session_id=frame.session_id,
        stream_id=frame.stream_id,
        frame_id=frame.frame_id,
        capture_monotonic_timestamp_ns=frame.capture_monotonic_timestamp_ns,
        cues=cues,
        provenance=pb.Provenance(component="test", component_version="1", synthetic=True),
    )


def _pipeline(result: pb.PerceptionResult, scheduler: CueScheduler) -> HostPipeline:
    async def processor(frame: pb.FramePayload) -> pb.PerceptionResult:
        assert frame.request_id == result.request_id
        return result

    return HostPipeline(
        preprocessor=FramePreprocessor(max_frame_bytes=1024),
        correlator=ResultCorrelator(),
        scheduler=scheduler,
        renderer=InspectableCueRenderer(),
        latency=LatencyTracker(),
        cluster_processor=processor,
    )


def _rendered_ids(rendered_cues: tuple[str, ...]) -> list[str]:
    return [json.loads(rendered)["cue_id"] for rendered in rendered_cues]


@pytest.mark.asyncio
async def test_pipeline_rejects_correlation_capacity_before_dispatch(frame_factory) -> None:
    dispatched: list[str] = []

    async def processor(frame: pb.FramePayload) -> pb.PerceptionResult:
        dispatched.append(frame.request_id)
        return _result(frame, [])

    correlator = ResultCorrelator(max_pending=1)
    blocker = frame_factory(request_id="blocker")
    correlator.register(blocker)
    pipeline = HostPipeline(
        preprocessor=FramePreprocessor(max_frame_bytes=1_024),
        correlator=correlator,
        scheduler=CueScheduler(),
        renderer=InspectableCueRenderer(),
        latency=LatencyTracker(),
        cluster_processor=processor,
    )
    frame = frame_factory(frame_id=2, request_id="candidate")

    with pytest.raises(OverflowError, match="capacity"):
        await pipeline.process(
            frame,
            route_context=RouteContext(local_available=False, cluster_available=True),
        )
    assert not dispatched

    assert correlator.cancel(blocker.request_id)
    outcome = await pipeline.process(
        frame,
        route_context=RouteContext(local_available=False, cluster_available=True),
    )
    assert outcome.correlation.accepted
    assert dispatched == ["candidate"]


@pytest.mark.asyncio
async def test_pipeline_does_not_render_accepted_pending_cue(frame_factory, cue_factory) -> None:
    accepted_ns = time.monotonic_ns() + 1_000_000
    frame = frame_factory()
    active = cue_factory("active", now_ns=accepted_ns - 1, priority=90)
    queued = cue_factory("queued", now_ns=accepted_ns - 1, priority=10, description="wall")
    scheduler = CueScheduler(capacity=3, cooldown_ms=0)

    outcome = await _pipeline(_result(frame, [active, queued]), scheduler).process(
        frame,
        route_context=RouteContext(local_available=False, cluster_available=True),
        now_ns=accepted_ns,
    )

    assert len(outcome.schedules) == 2
    assert outcome.schedules[0].dispatched_cue.cue_id == "active"
    assert outcome.schedules[1].accepted
    assert outcome.schedules[1].cue.cue_id == "queued"
    assert outcome.schedules[1].dispatched_cue is None
    assert _rendered_ids(outcome.rendered_cues) == ["active"]
    assert outcome.schedule is outcome.schedules[-1]
    assert outcome.rendered == outcome.rendered_cues[-1]
    assert scheduler.active.cue_id == "active"
    assert scheduler.pending_count == 1


@pytest.mark.asyncio
async def test_pipeline_processes_multi_cue_cancellation_and_priority_in_order(
    frame_factory,
    cue_factory,
) -> None:
    accepted_ns = time.monotonic_ns() + 1_000_000
    frame = frame_factory()
    active = cue_factory("active", now_ns=accepted_ns - 1, priority=60)
    queued = cue_factory("queued", now_ns=accepted_ns - 1, priority=20, description="wall")
    cancellation = cue_factory("cancel", now_ns=accepted_ns - 1, description="")
    cancellation.cancel.cue_ids.append("active")
    high = cue_factory("high", now_ns=accepted_ns - 1, priority=100, description="stop")
    scheduler = CueScheduler(capacity=4, cooldown_ms=0)

    outcome = await _pipeline(_result(frame, [active, queued, cancellation, high]), scheduler).process(
        frame,
        route_context=RouteContext(local_available=False, cluster_available=True),
        now_ns=accepted_ns,
    )

    assert len(outcome.schedules) == 4
    assert outcome.schedules[1].dispatched_cue is None
    assert outcome.schedules[2].cancelled_ids == ("active",)
    assert outcome.schedules[2].dispatched_cue.cue_id == "queued"
    assert outcome.schedules[3].preempted_ids == ("queued",)
    assert outcome.schedules[3].dispatched_cue.cue_id == "high"
    assert _rendered_ids(outcome.rendered_cues) == ["active", "queued", "high"]
    assert scheduler.active.cue_id == "high"
    assert scheduler.pending_count == 0


@pytest.mark.asyncio
async def test_pipeline_drops_cues_from_a_different_frame_before_scheduling(frame_factory, cue_factory) -> None:
    accepted_ns = time.monotonic_ns() + 1_000_000
    frame = frame_factory()
    mismatched = cue_factory("wrong-frame", now_ns=accepted_ns - 1, priority=100)
    mismatched.frame_id = frame.frame_id + 1
    matching = cue_factory("matching", now_ns=accepted_ns - 1, priority=50, description="matching")
    scheduler = CueScheduler(capacity=3, cooldown_ms=0)

    outcome = await _pipeline(_result(frame, [mismatched, matching]), scheduler).process(
        frame,
        route_context=RouteContext(local_available=False, cluster_available=True),
        now_ns=accepted_ns,
    )

    assert [schedule.cue.cue_id for schedule in outcome.schedules] == ["matching"]
    assert _rendered_ids(outcome.rendered_cues) == ["matching"]
    assert scheduler.active.cue_id == "matching"
