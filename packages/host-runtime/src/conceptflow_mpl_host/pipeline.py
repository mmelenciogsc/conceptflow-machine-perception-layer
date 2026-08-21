# SPDX-License-Identifier: MIT OR Apache-2.0
"""Host orchestration over generated service and protobuf boundaries."""

from __future__ import annotations

from dataclasses import dataclass
import time
from typing import Awaitable, Callable

from conceptflow.mpl.v1 import perception_pb2 as pb

from .correlation import CorrelationDecision, ResultCorrelator
from .latency import LatencyTracker
from .preprocessing import FramePreprocessor, FrameSequenceValidator
from .renderer import InspectableCueRenderer
from .routing import RouteContext, RouteTarget, choose_route
from .scheduler import CueScheduler, ScheduleOutcome


RemoteProcessor = Callable[[pb.FramePayload], Awaitable[pb.PerceptionResult]]


@dataclass(frozen=True, slots=True)
class PipelineOutcome:
    """Pipeline results with singular compatibility fields and ordered cue details.

    ``schedule`` and ``rendered`` retain the one-cue API and contain the latest
    schedule and rendering for multi-cue results. The tuple fields preserve all
    schedule outcomes and renderings in protocol order.
    """

    result: pb.PerceptionResult
    correlation: CorrelationDecision
    schedule: ScheduleOutcome | None
    rendered: str | None
    schedules: tuple[ScheduleOutcome, ...] = ()
    rendered_cues: tuple[str, ...] = ()


class HostPipeline:
    def __init__(
        self,
        *,
        preprocessor: FramePreprocessor,
        correlator: ResultCorrelator,
        scheduler: CueScheduler,
        renderer: InspectableCueRenderer,
        latency: LatencyTracker,
        cluster_processor: RemoteProcessor,
        local_processor: RemoteProcessor | None = None,
        sequence_validator: FrameSequenceValidator | None = None,
    ) -> None:
        self._preprocessor = preprocessor
        self._correlator = correlator
        self._scheduler = scheduler
        self._renderer = renderer
        self._latency = latency
        self._cluster_processor = cluster_processor
        self._local_processor = local_processor
        self._sequence_validator = sequence_validator or FrameSequenceValidator()

    @staticmethod
    def cue_from_observation(
        result: pb.PerceptionResult,
        observation: pb.PerceptionObservation,
        *,
        now_ns: int,
    ) -> pb.PerceptionCue:
        if observation.azimuth_degrees < -15:
            direction = pb.DIRECTION_LEFT
        elif observation.azimuth_degrees > 15:
            direction = pb.DIRECTION_RIGHT
        else:
            direction = pb.DIRECTION_AHEAD
        return pb.PerceptionCue(
            cue_id=f"cue-{result.result_id}-{observation.observation_id}",
            frame_id=result.frame_id,
            created_monotonic_timestamp_ns=now_ns,
            ttl_ms=1_500,
            category=pb.CUE_CATEGORY_OBJECT,
            description=observation.description,
            confidence=observation.confidence,
            priority=70,
            coordinate_frame=observation.coordinate_frame,
            azimuth_degrees=observation.azimuth_degrees,
            elevation_degrees=observation.elevation_degrees,
            distance_meters=observation.distance_meters,
            direction=direction,
            urgency=pb.URGENCY_NORMAL,
            earcon=pb.Earcon(earcon_id="object", gain=0.5, pitch=1.0, spatialized=True),
            speech=pb.Speech(text=observation.description, language_tag="en", interrupt=False),
            haptic=pb.Haptic(pattern=pb.HAPTIC_PATTERN_PULSE, intensity=0.35, duration_ms=60),
            provenance=result.provenance,
        )

    async def process(
        self,
        frame: pb.FramePayload,
        *,
        route_context: RouteContext,
        now_ns: int | None = None,
    ) -> PipelineOutcome:
        started = time.perf_counter_ns()
        self._preprocessor.validate(frame)
        route = choose_route(route_context)
        if route.target == RouteTarget.DROP:
            raise RuntimeError(route.reason)
        processor = self._local_processor if route.target == RouteTarget.LOCAL else self._cluster_processor
        if processor is None:
            raise RuntimeError("selected processor is unavailable")
        self._correlator.ensure_capacity(frame.request_id)
        self._sequence_validator.validate(frame)
        self._latency.record("preprocess", (time.perf_counter_ns() - started) / 1_000_000)
        self._correlator.register(frame, now_ns=time.monotonic_ns())
        remote_started = time.perf_counter_ns()
        try:
            result = await processor(frame)
        except BaseException:
            self._correlator.cancel(frame.request_id)
            raise
        self._latency.record("inference_rpc", (time.perf_counter_ns() - remote_started) / 1_000_000)
        accepted_ns = now_ns if now_ns is not None else time.monotonic_ns()
        correlation = self._correlator.accept(result, now_ns=accepted_ns)
        if not correlation.accepted or result.error.code != pb.ERROR_CODE_UNSPECIFIED:
            return PipelineOutcome(result, correlation, None, None)
        cues = (
            tuple(cue for cue in result.cues if cue.frame_id == result.frame_id)
            if result.cues
            else (
                (self.cue_from_observation(result, result.observations[0], now_ns=accepted_ns),)
                if result.observations
                else ()
            )
        )
        if not cues:
            return PipelineOutcome(result, correlation, None, None)
        schedule_started = time.perf_counter_ns()
        schedules: list[ScheduleOutcome] = []
        rendered_cues: list[str] = []
        for cue in cues:
            schedule = self._scheduler.schedule(cue, now_ns=accepted_ns)
            schedules.append(schedule)
            if schedule.dispatched_cue is not None:
                rendered_cues.append(self._renderer.render(schedule.dispatched_cue))
        self._latency.record("cue_schedule", (time.perf_counter_ns() - schedule_started) / 1_000_000)
        return PipelineOutcome(
            result,
            correlation,
            schedules[-1],
            rendered_cues[-1] if rendered_cues else None,
            schedules=tuple(schedules),
            rendered_cues=tuple(rendered_cues),
        )
