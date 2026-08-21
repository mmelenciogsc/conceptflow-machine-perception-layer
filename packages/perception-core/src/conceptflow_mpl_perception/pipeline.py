# SPDX-License-Identifier: MIT OR Apache-2.0
"""Executable Map. Morph. Move. headless vertical slices."""

from __future__ import annotations

from dataclasses import dataclass, field

from .audio import AudioDispatch, FmodCommandAdapter
from .body import BodyProfile, BodyProxy
from .frames import FrameGraph
from .geometry import GeometryMapper, MetricGeometryObservation, StableManifold
from .morph import GeometryCue, GeometryMorpher, HapticCue, VirtualSpeakerArray
from .move import MotionGate, MotionSample
from .scheduler import PerceptualPriorityScheduler, PriorityLane, ScheduledCue
from .semantic import (
    AuditoryIconCue,
    AuditoryIconRegistry,
    SceneDescriptionRequest,
    SceneSimilarityGate,
    SegmentedObservation,
    SemanticDepthFuser,
    SemanticSimilarityGate,
    SemanticTrack,
)


@dataclass(frozen=True, slots=True)
class SpatialMap:
    timestamp_ns: int
    manifolds: tuple[StableManifold, ...]
    semantic_tracks: tuple[SemanticTrack, ...]
    degraded_reasons: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class GeometryOutput:
    spatial_map: SpatialMap
    cues: tuple[GeometryCue, ...]
    audio: tuple[AudioDispatch, ...]
    haptics: tuple[HapticCue, ...]


@dataclass(frozen=True, slots=True)
class SemanticOutput:
    track: SemanticTrack
    icon: AuditoryIconCue | None
    suppression_reason: str | None


@dataclass(slots=True)
class PipelineCounters:
    raw_geometry_samples: int = 0
    map_entities: int = 0
    candidate_geometry_cues: int = 0
    rendered_geometry_cues: int = 0
    semantic_tracks: int = 0
    semantic_icons: int = 0
    semantic_suppressed: int = 0
    stale_suppressed: int = 0


@dataclass(slots=True)
class PerceptionEngine:
    """Map geometry/semantics, Morph cues, then Move-gate their expression."""

    profile: BodyProfile = field(default_factory=BodyProfile)
    body: BodyProxy = field(init=False)
    mapper: GeometryMapper = field(init=False)
    speakers: VirtualSpeakerArray = field(init=False)
    morpher: GeometryMorpher = field(init=False)
    motion: MotionGate = field(init=False)
    audio: FmodCommandAdapter = field(init=False)
    semantic_fuser: SemanticDepthFuser = field(init=False)
    semantic_gate: SemanticSimilarityGate = field(init=False)
    icon_registry: AuditoryIconRegistry = field(init=False)
    scene_gate: SceneSimilarityGate = field(init=False)
    scheduler: PerceptualPriorityScheduler = field(init=False)
    tracks: dict[str, SemanticTrack] = field(init=False)
    counters: PipelineCounters = field(init=False)

    def __post_init__(self) -> None:
        self.body = BodyProxy(self.profile)
        self.mapper = GeometryMapper(self.body)
        self.speakers = VirtualSpeakerArray(self.body)
        self.morpher = GeometryMorpher(self.speakers)
        self.motion = MotionGate()
        self.audio = FmodCommandAdapter()
        self.semantic_fuser = SemanticDepthFuser(self.body)
        self.semantic_gate = SemanticSimilarityGate()
        self.icon_registry = AuditoryIconRegistry()
        self.scene_gate = SceneSimilarityGate()
        self.scheduler = PerceptualPriorityScheduler()
        self.tracks: dict[str, SemanticTrack] = {}
        self.counters = PipelineCounters()

    def process_geometry(
        self,
        observations: list[MetricGeometryObservation],
        frames: FrameGraph,
        motion: MotionSample,
        now_ns: int,
    ) -> GeometryOutput:
        self.counters.raw_geometry_samples += len(observations)
        try:
            manifolds = self.mapper.manifolds(observations, frames, now_ns)
        except ValueError:
            self.counters.stale_suppressed += len(observations)
            raise
        self.counters.map_entities += len(manifolds)
        cues: list[GeometryCue] = []
        dispatches: list[AudioDispatch] = []
        haptics: list[HapticCue] = []
        for manifold in manifolds:
            self.counters.candidate_geometry_cues += 1
            activation = self.motion.activation(manifold.stable_id, motion, manifold.nearest.radial_approach_mps)
            cue = self.morpher.morph(manifold, activation)
            if now_ns > cue.expiry_ns:
                self.counters.stale_suppressed += 1
                continue
            cues.append(cue)
            audio_dispatch = self.audio.dispatch(cue, activation)
            dispatches.append(audio_dispatch)
            if cue.haptic is not None:
                haptics.append(cue.haptic)
            lane = PriorityLane.RAPID_GEOMETRY if manifold.nearest.radial_approach_mps > 0.8 else PriorityLane.GEOMETRY
            self.scheduler.submit(
                ScheduledCue(
                    cue.cue_id,
                    cue.cue_id,
                    lane,
                    cue.source_timestamp_ns,
                    cue.expiry_ns,
                    self.morpher.config.cue_ttl_ms,
                    cue,
                    duck_others=0.65,
                    audio_voice_cost=len(audio_dispatch.voices),
                    haptic_slot_cost=1 if cue.haptic is not None else 0,
                ),
                now_ns,
            )
        self.counters.rendered_geometry_cues += len(cues)
        return GeometryOutput(
            SpatialMap(now_ns, manifolds, tuple(self.tracks.values())), tuple(cues), tuple(dispatches), tuple(haptics)
        )

    def process_semantic(self, observation: SegmentedObservation, frames: FrameGraph, now_ns: int) -> SemanticOutput:
        track = self.semantic_fuser.fuse(observation, frames, now_ns)
        self.tracks[track.track_id] = track
        self.counters.semantic_tracks += 1
        emit, _, reason = self.semantic_gate.evaluate(track, now_ns)
        if not emit:
            self.counters.semantic_suppressed += 1
            return SemanticOutput(track, None, reason)
        icon = self.icon_registry.cue(track, now_ns)
        self.counters.semantic_icons += 1
        self.scheduler.submit(
            ScheduledCue(
                icon.cue_id,
                f"icon-{track.track_id}",
                PriorityLane.SALIENT_ICON if track.bubble_overlap else PriorityLane.ORDINARY_ICON,
                now_ns,
                icon.expiry_ns,
                320,
                icon,
                audio_voice_cost=1,
            ),
            now_ns,
        )
        return SemanticOutput(track, icon, None)

    def scene_description(self, now_ns: int, *, on_demand: bool) -> SceneDescriptionRequest | None:
        request = self.scene_gate.request(tuple(self.tracks.values()), now_ns, on_demand=on_demand)
        if request is not None:
            self.scheduler.submit(
                ScheduledCue(
                    request.request_id,
                    "scene-description",
                    PriorityLane.USER_SCENE if on_demand else PriorityLane.PERIODIC_SCENE,
                    now_ns,
                    request.expiry_ns,
                    2_000,
                    request,
                    duck_others=0.45 if on_demand else 0.20,
                    audio_voice_cost=0,
                    speech_slot_cost=1,
                ),
                now_ns,
            )
        return request

    def dispatch(self, now_ns: int) -> tuple[ScheduledCue, ...]:
        """Return the single deterministic cross-modality scheduling decision."""
        return self.scheduler.dispatch(now_ns)

    def trace_counters(self) -> dict[str, int]:
        return {name: getattr(self.counters, name) for name in self.counters.__dataclass_fields__}
