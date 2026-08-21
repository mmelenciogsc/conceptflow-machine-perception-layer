# SPDX-License-Identifier: MIT OR Apache-2.0
"""Pluggable worker boundary and deterministic synthetic implementation."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import hashlib
from typing import Mapping, Protocol

from conceptflow.mpl.v1 import perception_pb2 as pb

from .device import ComputeDevice


class WorkerFailure(RuntimeError):
    pass


class PerceptionWorker(Protocol):
    worker_id: str
    device: ComputeDevice

    async def process(self, frame: pb.FramePayload) -> pb.PerceptionResult:
        """Return a result or raise a contained worker exception."""
        raise NotImplementedError("worker protocol method")


@dataclass(slots=True)
class DeterministicMockWorker:
    """Synthetic worker for repeatable tests; it performs no real perception."""

    worker_id: str
    device: ComputeDevice
    delay_by_frame_id: Mapping[int, float] = field(default_factory=dict)
    failing_frame_ids: frozenset[int] = frozenset()
    cancelled_frame_ids: list[int] = field(default_factory=list)
    calls: list[int] = field(default_factory=list)

    async def process(self, frame: pb.FramePayload) -> pb.PerceptionResult:
        self.calls.append(frame.frame_id)
        delay = self.delay_by_frame_id.get(frame.frame_id, 0.0)
        try:
            if delay:
                await asyncio.sleep(delay)
        except asyncio.CancelledError:
            self.cancelled_frame_ids.append(frame.frame_id)
            raise
        if frame.frame_id in self.failing_frame_ids:
            raise WorkerFailure("synthetic worker failure")
        digest = hashlib.sha256(frame.frame_data).hexdigest()
        azimuth = float((frame.frame_id % 3 - 1) * 24)
        started = frame.capture_monotonic_timestamp_ns
        finished = started + 1_000_000
        provenance = pb.Provenance(
            component="conceptflow-mpl-deterministic-mock",
            component_version="0.1.0",
            worker_id=self.worker_id,
            model_id="synthetic-deterministic-mock",
            model_version="1",
            artifact_digest=digest,
            processing_started_monotonic_ns=started,
            processing_finished_monotonic_ns=finished,
            synthetic=True,
        )
        observation = pb.PerceptionObservation(
            observation_id=f"observation-{frame.frame_id}",
            category="synthetic_object",
            description="synthetic object",
            confidence=0.875,
            normalized_bounds=pb.BoundingBox(left=0.25, top=0.25, right=0.75, bottom=0.75),
            coordinate_frame=pb.COORDINATE_FRAME_CAMERA_OPTICAL,
            azimuth_degrees=azimuth,
            elevation_degrees=0.0,
            distance_meters=2.0,
            provenance=provenance,
        )
        direction = pb.DIRECTION_AHEAD
        if azimuth < -8.0:
            direction = pb.DIRECTION_LEFT
        elif azimuth > 8.0:
            direction = pb.DIRECTION_RIGHT
        cue = pb.PerceptionCue(
            cue_id=f"synthetic-cue-{frame.frame_id}",
            frame_id=frame.frame_id,
            created_monotonic_timestamp_ns=frame.capture_monotonic_timestamp_ns,
            ttl_ms=2_500,
            category=pb.CUE_CATEGORY_OBSTACLE,
            description="Synthetic obstacle for transport validation",
            confidence=0.875,
            priority=5,
            coordinate_frame=pb.COORDINATE_FRAME_CAMERA_OPTICAL,
            azimuth_degrees=azimuth,
            elevation_degrees=0.0,
            distance_meters=2.0,
            direction=direction,
            urgency=pb.URGENCY_NORMAL,
            earcon=pb.Earcon(
                earcon_id="synthetic-obstacle",
                gain=0.4,
                pitch=1.0,
                spatialized=True,
            ),
            haptic=pb.Haptic(
                pattern=pb.HAPTIC_PATTERN_PULSE,
                intensity=0.3,
                duration_ms=60,
            ),
            provenance=provenance,
        )
        return pb.PerceptionResult(
            result_id=f"result-{frame.request_id}",
            request_id=frame.request_id,
            session_id=frame.session_id,
            stream_id=frame.stream_id,
            frame_id=frame.frame_id,
            capture_monotonic_timestamp_ns=frame.capture_monotonic_timestamp_ns,
            completed_monotonic_timestamp_ns=finished,
            observations=[observation],
            cues=[cue],
            provenance=provenance,
        )
