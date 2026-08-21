# SPDX-License-Identifier: MIT OR Apache-2.0
"""Pure geometry-to-audio/haptic Morph stage."""

from __future__ import annotations

import math
from dataclasses import dataclass
from enum import Enum
from functools import lru_cache

from .body import BodyProfile, BodyProxy
from .geometry import StableManifold, SurfaceContact
from .model import Vec3, clamp01


class SpeakerBank(str, Enum):
    LEFT = "left"
    RIGHT = "right"
    SUPERIOR = "superior"
    INFERIOR = "inferior"


@dataclass(frozen=True, slots=True)
class VirtualEmitter:
    emitter_id: str
    bank: SpeakerBank
    ring: int
    sample: int
    direction_body: Vec3
    position_body: Vec3
    inward_normal_body: Vec3


@dataclass(frozen=True, slots=True)
class WeightedEmitter:
    emitter: VirtualEmitter
    weight: float


@dataclass(frozen=True, slots=True)
class SpeakerArrayConfig:
    ring_angles_deg: tuple[float, ...] = (28.0, 56.0, 82.0)
    samples_per_ring: int = 12
    angular_concentration: float = 8.0
    continuity: float = 0.30

    def __post_init__(self) -> None:
        if not self.ring_angles_deg or any(not 0.0 < angle < 90.0 for angle in self.ring_angles_deg):
            raise ValueError("ring angles must lie between zero and ninety degrees")
        if self.samples_per_ring < 4 or self.angular_concentration <= 0.0 or not 0.0 <= self.continuity < 1.0:
            raise ValueError("invalid speaker-array configuration")


def _basis(bank: SpeakerBank) -> tuple[Vec3, Vec3, Vec3]:
    if bank == SpeakerBank.LEFT:
        return Vec3(-1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0)
    if bank == SpeakerBank.RIGHT:
        return Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, -1.0)
    if bank == SpeakerBank.SUPERIOR:
        return Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0)
    return Vec3(0.0, -1.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, -1.0)


def _shell_point(body: BodyProxy, origin: Vec3, direction: Vec3) -> Vec3:
    """Find the body-offset iso-surface along a ray with bounded bisection."""
    lower = 0.0
    upper = max(5.0, body.profile.bubble_radius_m + body.profile.stature_m + 1.0)
    if body.distance(origin + direction * upper) < body.profile.bubble_radius_m:
        raise ValueError("speaker shell search did not leave the configured body offset")
    for _ in range(48):
        middle = (lower + upper) * 0.5
        if body.distance(origin + direction * middle) < body.profile.bubble_radius_m:
            lower = middle
        else:
            upper = middle
    return origin + direction * ((lower + upper) * 0.5)


@lru_cache(maxsize=32)
def _cached_emitters(profile: BodyProfile, config: SpeakerArrayConfig) -> tuple[VirtualEmitter, ...]:
    """Generate one immutable exact-shell array per bounded calibration/config pair."""
    body = BodyProxy(profile)
    emitters: list[VirtualEmitter] = []
    origin = Vec3(0.0, profile.stature_m * 0.52, 0.0)
    for bank in SpeakerBank:
        axis, tangent_a, tangent_b = _basis(bank)
        for ring, angle_deg in enumerate(config.ring_angles_deg):
            angle = math.radians(angle_deg)
            for sample in range(config.samples_per_ring):
                phase = math.tau * sample / config.samples_per_ring
                direction = (
                    axis * math.cos(angle)
                    + tangent_a * (math.sin(angle) * math.cos(phase))
                    + tangent_b * (math.sin(angle) * math.sin(phase))
                ).normalized()
                position = _shell_point(body, origin, direction)
                inward = (body.clearance(position).surface_point - position).normalized(-direction)
                emitters.append(
                    VirtualEmitter(
                        emitter_id=f"{bank.value}-r{ring:02d}-s{sample:02d}",
                        bank=bank,
                        ring=ring,
                        sample=sample,
                        direction_body=direction,
                        position_body=position,
                        inward_normal_body=inward,
                    )
                )
    return tuple(emitters)


class VirtualSpeakerArray:
    """Four overlapping concentric emitter manifolds on the body-offset shell."""

    def __init__(self, body: BodyProxy, config: SpeakerArrayConfig | None = None) -> None:
        self.body = body
        self.config = config or SpeakerArrayConfig()
        self.emitters = _cached_emitters(self.body.profile, self.config)

    def weights(
        self,
        manifold: StableManifold,
        previous: dict[str, float] | None = None,
    ) -> tuple[WeightedEmitter, ...]:
        directions = [
            (contact.geometry_point_body - contact.body_surface_point).normalized(Vec3(0.0, 0.0, 1.0))
            for contact in manifold.contacts
        ]
        raw: list[float] = []
        for emitter in self.emitters:
            angular = max(
                math.exp(self.config.angular_concentration * (emitter.direction_body.dot(direction) - 1.0))
                for direction in directions
            )
            normal_factor = 1.0
            reliable_normals = [contact.normal_body for contact in manifold.contacts if contact.normal_body is not None]
            if reliable_normals:
                normal_factor = 0.65 + 0.35 * max(
                    abs(emitter.direction_body.dot(normal)) for normal in reliable_normals
                )
            raw.append(angular * normal_factor)
        total = sum(raw)
        if total <= 0.0:
            raw = [1.0 / len(self.emitters)] * len(self.emitters)
        else:
            raw = [value / total for value in raw]
        if previous:
            mixed = [
                (1.0 - self.config.continuity) * value
                + self.config.continuity * max(0.0, previous.get(emitter.emitter_id, 0.0))
                for emitter, value in zip(self.emitters, raw, strict=True)
            ]
            mixed_total = sum(mixed)
            raw = [value / mixed_total for value in mixed]
        return tuple(WeightedEmitter(emitter, weight) for emitter, weight in zip(self.emitters, raw, strict=True))


class HapticPattern(str, Enum):
    BOUNDARY = "boundary"
    APPROACH = "approach"
    URGENT_APPROACH = "urgent_approach"
    SEMANTIC = "semantic"


@dataclass(frozen=True, slots=True)
class HapticCue:
    cue_id: str
    direction_body: Vec3
    pattern: HapticPattern
    intensity: float
    duration_ms: int
    timestamp_ns: int
    ttl_ms: int
    confidence: float


@dataclass(frozen=True, slots=True)
class IntrusionAnchor:
    position_body: Vec3
    direction_body: Vec3
    proximity: float
    sound_size_m: float
    gain_linear: float


@dataclass(frozen=True, slots=True)
class EnvelopmentField:
    emitters: tuple[WeightedEmitter, ...]
    participation: float
    sound_size_m: float
    gain_linear: float


@dataclass(frozen=True, slots=True)
class GeometryCue:
    cue_id: str
    source_timestamp_ns: int
    expiry_ns: int
    priority: int
    anchor: IntrusionAnchor
    field: EnvelopmentField
    haptic: HapticCue | None
    region: str
    confidence: float


@dataclass(frozen=True, slots=True)
class MorphConfig:
    minimum_gain: float = 0.08
    maximum_gain: float = 0.34
    maximum_field_gain: float = 0.24
    base_sound_size_m: float = 0.06
    maximum_sound_size_m: float = 0.65
    cue_ttl_ms: int = 220

    def __post_init__(self) -> None:
        if not 0.0 <= self.minimum_gain <= self.maximum_gain <= 1.0:
            raise ValueError("anchor gains must be bounded and ordered")
        if not 0.0 <= self.maximum_field_gain <= 1.0 or self.cue_ttl_ms <= 0:
            raise ValueError("invalid field gain or cue TTL")


class GeometryMorpher:
    def __init__(self, speakers: VirtualSpeakerArray, config: MorphConfig | None = None) -> None:
        self.speakers = speakers
        self.config = config or MorphConfig()
        self._previous: dict[str, dict[str, float]] = {}

    def morph(self, manifold: StableManifold, activation: float) -> GeometryCue:
        activation = clamp01(activation)
        contact: SurfaceContact = manifold.nearest
        direction = (contact.geometry_point_body - contact.body_surface_point).normalized(Vec3(0.0, 0.0, 1.0))
        prior = self._previous.get(manifold.stable_id)
        weights = self.speakers.weights(manifold, prior)
        self._previous[manifold.stable_id] = {item.emitter.emitter_id: item.weight for item in weights}
        proximity = contact.bubble_proximity
        definition = clamp01(proximity * activation)
        anchor_gain = (
            self.config.minimum_gain + (self.config.maximum_gain - self.config.minimum_gain) * definition * 0.35
        )
        field_participation = definition * definition
        size = (
            self.config.base_sound_size_m
            + (self.config.maximum_sound_size_m - self.config.base_sound_size_m) * proximity
        )
        haptic = None
        if proximity > 0.05 or contact.radial_approach_mps > 0.15:
            pattern = HapticPattern.URGENT_APPROACH if contact.radial_approach_mps > 0.8 else HapticPattern.APPROACH
            haptic = HapticCue(
                cue_id=f"haptic-{manifold.stable_id}",
                direction_body=direction,
                pattern=pattern,
                intensity=clamp01(0.15 + 0.65 * definition + 0.20 * min(contact.radial_approach_mps, 1.0)),
                duration_ms=min(180, 45 + round(95 * definition)),
                timestamp_ns=contact.timestamp_ns,
                ttl_ms=min(self.config.cue_ttl_ms, 180),
                confidence=contact.confidence,
            )
        priority = min(100, round(45 + 40 * proximity + 15 * min(contact.radial_approach_mps, 1.0)))
        return GeometryCue(
            cue_id=f"geometry-{manifold.stable_id}",
            source_timestamp_ns=contact.timestamp_ns,
            expiry_ns=contact.timestamp_ns + self.config.cue_ttl_ms * 1_000_000,
            priority=priority,
            anchor=IntrusionAnchor(contact.geometry_point_body, direction, proximity, size, anchor_gain),
            field=EnvelopmentField(
                weights,
                field_participation,
                size,
                self.config.maximum_field_gain * field_participation,
            ),
            haptic=haptic,
            region=contact.body_region.value,
            confidence=contact.confidence,
        )
