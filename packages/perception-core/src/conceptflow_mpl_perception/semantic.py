# SPDX-License-Identifier: MIT OR Apache-2.0
"""Depth-associated semantic tracks, auditory icons, and scene similarity."""

from __future__ import annotations

import hashlib
import math
import statistics
from dataclasses import dataclass

from .body import BodyProxy
from .frames import FrameGraph
from .model import CoordinateFrame, Vec3, clamp01


@dataclass(frozen=True, slots=True)
class MaskDepthPoint:
    pixel_x: int
    pixel_y: int
    point_sensor: Vec3
    reliable: bool = True

    def __post_init__(self) -> None:
        if self.pixel_x < 0 or self.pixel_y < 0 or self.point_sensor.z <= 0.0:
            raise ValueError("mask depth point must have valid pixels and positive forward depth")


@dataclass(frozen=True, slots=True)
class SegmentedObservation:
    track_id: str
    label: str
    confidence: float
    timestamp_ns: int
    points: tuple[MaskDepthPoint, ...]
    velocity_sensor_mps: Vec3 = Vec3(0.0, 0.0, 0.0)

    def __post_init__(self) -> None:
        if not self.track_id or not self.label.strip() or not self.points:
            raise ValueError("semantic observation requires track, label, and mask depth points")
        if not 0.0 <= self.confidence <= 1.0 or self.timestamp_ns < 0:
            raise ValueError("invalid semantic confidence or timestamp")


@dataclass(frozen=True, slots=True)
class SemanticTrack:
    track_id: str
    label: str
    confidence: float
    timestamp_ns: int
    nearest_depth_m: float
    median_depth_m: float
    far_depth_m: float
    centroid_world: Vec3
    nearest_world: Vec3
    velocity_world_mps: Vec3
    azimuth_deg: float
    elevation_deg: float
    angular_extent_deg: float
    bubble_overlap: bool
    mask_fingerprint: str
    reliable_point_count: int


class SemanticDepthFuser:
    """Fuse reprojected mask samples with timestamp-matched frame transforms."""

    def __init__(self, body: BodyProxy, max_age_ns: int = 350_000_000) -> None:
        if max_age_ns <= 0:
            raise ValueError("semantic maximum age must be positive")
        self.body = body
        self.max_age_ns = max_age_ns

    def fuse(self, observation: SegmentedObservation, frames: FrameGraph, now_ns: int) -> SemanticTrack:
        age = now_ns - observation.timestamp_ns
        if age < 0 or age > self.max_age_ns:
            raise ValueError("semantic observation is stale or from the future")
        reliable = [sample for sample in observation.points if sample.reliable]
        if not reliable:
            raise ValueError("semantic mask contains no reliable depth samples")
        sensor_points = [sample.point_sensor for sample in reliable]
        world_points = [
            frames.transform_point(
                point,
                CoordinateFrame.SENSOR,
                CoordinateFrame.WORLD,
                observation.timestamp_ns,
                self.max_age_ns,
            )
            for point in sensor_points
        ]
        body_points = [
            frames.transform_point(
                point,
                CoordinateFrame.SENSOR,
                CoordinateFrame.BODY,
                observation.timestamp_ns,
                self.max_age_ns,
            )
            for point in sensor_points
        ]
        depths = sorted(point.norm() for point in sensor_points)
        nearest_index = min(range(len(sensor_points)), key=lambda index: sensor_points[index].norm())
        centroid_world = sum(world_points[1:], world_points[0]) / float(len(world_points))
        centroid_body = sum(body_points[1:], body_points[0]) / float(len(body_points))
        azimuths = [math.degrees(math.atan2(point.x, point.z)) for point in body_points]
        elevations = [
            math.degrees(math.atan2(point.y, max(math.hypot(point.x, point.z), 1.0e-9))) for point in body_points
        ]
        velocity_world = frames.transform_vector(
            observation.velocity_sensor_mps,
            CoordinateFrame.SENSOR,
            CoordinateFrame.WORLD,
            observation.timestamp_ns,
            self.max_age_ns,
        )
        fingerprint_payload = ";".join(
            f"{sample.pixel_x // 8}:{sample.pixel_y // 8}:{round(sample.point_sensor.z, 1)}"
            for sample in sorted(reliable, key=lambda item: (item.pixel_x, item.pixel_y))
        )
        return SemanticTrack(
            track_id=observation.track_id,
            label=observation.label.strip().casefold(),
            confidence=observation.confidence,
            timestamp_ns=observation.timestamp_ns,
            nearest_depth_m=depths[0],
            median_depth_m=statistics.median(depths),
            far_depth_m=depths[-1],
            centroid_world=centroid_world,
            nearest_world=world_points[nearest_index],
            velocity_world_mps=velocity_world,
            azimuth_deg=math.degrees(math.atan2(centroid_body.x, centroid_body.z)),
            elevation_deg=math.degrees(
                math.atan2(centroid_body.y, max(math.hypot(centroid_body.x, centroid_body.z), 1.0e-9))
            ),
            angular_extent_deg=max(max(azimuths) - min(azimuths), max(elevations) - min(elevations)),
            bubble_overlap=any(self.body.distance(point) <= self.body.profile.bubble_radius_m for point in body_points),
            mask_fingerprint=hashlib.sha256(fingerprint_payload.encode()).hexdigest()[:16],
            reliable_point_count=len(reliable),
        )


@dataclass(frozen=True, slots=True)
class SemanticGateConfig:
    cooldown_ms: int = 2_500
    change_threshold: float = 0.34
    direction_material_deg: float = 24.0
    depth_material_m: float = 0.55
    confidence_material: float = 0.20


class SemanticSimilarityGate:
    def __init__(self, config: SemanticGateConfig | None = None) -> None:
        self.config = config or SemanticGateConfig()
        self._last: dict[str, tuple[SemanticTrack, int]] = {}

    def evaluate(self, track: SemanticTrack, now_ns: int) -> tuple[bool, float, str]:
        previous_entry = self._last.get(track.track_id)
        if previous_entry is None:
            self._last[track.track_id] = (track, now_ns)
            return True, 1.0, "new_track"
        previous, emitted_ns = previous_entry
        label_change = 1.0 if previous.label != track.label else 0.0
        direction_change = clamp01(
            max(abs(previous.azimuth_deg - track.azimuth_deg), abs(previous.elevation_deg - track.elevation_deg))
            / self.config.direction_material_deg
        )
        depth_change = clamp01(abs(previous.nearest_depth_m - track.nearest_depth_m) / self.config.depth_material_m)
        velocity_change = clamp01(previous.velocity_world_mps.distance_to(track.velocity_world_mps) / 1.0)
        confidence_change = clamp01(abs(previous.confidence - track.confidence) / self.config.confidence_material)
        bubble_transition = 1.0 if previous.bubble_overlap != track.bubble_overlap else 0.0
        mask_change = 0.0 if previous.mask_fingerprint == track.mask_fingerprint else 0.25
        change = max(
            label_change,
            bubble_transition,
            0.30 * direction_change
            + 0.25 * depth_change
            + 0.15 * velocity_change
            + 0.10 * confidence_change
            + mask_change,
        )
        cooldown_elapsed = now_ns - emitted_ns >= self.config.cooldown_ms * 1_000_000
        emit = (
            label_change > 0.0
            or bubble_transition > 0.0
            or (change >= self.config.change_threshold and cooldown_elapsed)
        )
        if emit:
            self._last[track.track_id] = (track, now_ns)
        return emit, change, "material_change" if emit else "similarity_suppressed"


@dataclass(frozen=True, slots=True)
class AuditoryIconDefinition:
    concept: str
    asset_key: str
    representational: bool
    maximum_ms: int


@dataclass(frozen=True, slots=True)
class AuditoryIconCue:
    cue_id: str
    track_id: str
    asset_key: str
    nearest_position_world: Vec3
    extent_positions_world: tuple[Vec3, ...]
    gain_linear: float
    priority: int
    timestamp_ns: int
    expiry_ns: int
    confidence: float


class AuditoryIconRegistry:
    """Small explicit vocabulary; unknown concepts use a neutral token."""

    def __init__(self, definitions: tuple[AuditoryIconDefinition, ...] | None = None) -> None:
        defaults = (
            AuditoryIconDefinition("person", "procedural/soft_footfall_pair", True, 320),
            AuditoryIconDefinition("door", "procedural/restrained_latch", True, 260),
            AuditoryIconDefinition("bicycle", "procedural/short_freewheel", True, 300),
            AuditoryIconDefinition("vehicle", "procedural/subdued_tire_texture", True, 300),
        )
        self._definitions = {item.concept.casefold(): item for item in definitions or defaults}
        self._neutral = AuditoryIconDefinition("neutral", "procedural/neutral_presence", False, 220)

    def cue(self, track: SemanticTrack, now_ns: int) -> AuditoryIconCue:
        definition = self._definitions.get(track.label, self._neutral)
        extent: tuple[Vec3, ...] = ()
        if track.angular_extent_deg >= 18.0 or track.far_depth_m - track.nearest_depth_m >= 0.75:
            extent = (track.centroid_world,)
        salience = clamp01(
            0.35 * (1.0 - min(track.nearest_depth_m, 8.0) / 8.0)
            + 0.30 * track.confidence
            + 0.35 * (1.0 if track.bubble_overlap else 0.0)
        )
        return AuditoryIconCue(
            cue_id=f"icon-{track.track_id}-{track.timestamp_ns}",
            track_id=track.track_id,
            asset_key=definition.asset_key,
            nearest_position_world=track.nearest_world,
            extent_positions_world=extent,
            gain_linear=0.08 + 0.20 * salience,
            priority=35 + round(35 * salience),
            timestamp_ns=track.timestamp_ns,
            expiry_ns=now_ns + 900_000_000,
            confidence=track.confidence,
        )


@dataclass(frozen=True, slots=True)
class SceneDescriptionRequest:
    request_id: str
    created_ns: int
    on_demand: bool
    summary_context: str
    fingerprint: str
    priority: int
    expiry_ns: int


class SceneSimilarityGate:
    def __init__(self, periodic_cooldown_ms: int = 15_000) -> None:
        if periodic_cooldown_ms <= 0:
            raise ValueError("scene cooldown must be positive")
        self.periodic_cooldown_ns = periodic_cooldown_ms * 1_000_000
        self._last_fingerprint = ""
        self._last_emitted_ns = -self.periodic_cooldown_ns

    def request(
        self, tracks: tuple[SemanticTrack, ...], now_ns: int, *, on_demand: bool
    ) -> SceneDescriptionRequest | None:
        ordered = sorted(tracks, key=lambda item: (item.nearest_depth_m, item.track_id))[:8]
        context = (
            "; ".join(
                f"{track.label} azimuth {round(track.azimuth_deg / 15) * 15:+d} degrees, "
                f"distance band {self._distance_band(track.nearest_depth_m)}"
                for track in ordered
            )
            or "No reliable structured objects available; do not infer a clear or safe scene."
        )
        fingerprint = hashlib.sha256(context.encode()).hexdigest()[:16]
        if not on_demand and (
            fingerprint == self._last_fingerprint or now_ns - self._last_emitted_ns < self.periodic_cooldown_ns
        ):
            return None
        self._last_fingerprint = fingerprint
        self._last_emitted_ns = now_ns
        return SceneDescriptionRequest(
            request_id=f"scene-{now_ns}",
            created_ns=now_ns,
            on_demand=on_demand,
            summary_context=context,
            fingerprint=fingerprint,
            priority=75 if on_demand else 15,
            expiry_ns=now_ns + (3_000_000_000 if on_demand else 8_000_000_000),
        )

    @staticmethod
    def _distance_band(distance_m: float) -> str:
        if distance_m < 1.0:
            return "near"
        if distance_m < 3.0:
            return "mid"
        return "far"
