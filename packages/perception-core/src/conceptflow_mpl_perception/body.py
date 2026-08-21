# SPDX-License-Identifier: MIT OR Apache-2.0
"""Calibrated multi-segment body proxy and body-surface clearance field."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from .model import EPSILON, Vec3, clamp01

BUBBLE_RADIUS_METERS = 0.9144


class BodyRegion(str, Enum):
    HEAD = "head"
    SHOULDER_NECK = "shoulder_neck"
    TORSO = "torso"
    LEFT_LATERAL = "left_lateral"
    RIGHT_LATERAL = "right_lateral"
    REAR_TORSO = "rear_torso"
    PELVIS = "pelvis"
    LOWER_BODY = "lower_body"


@dataclass(frozen=True, slots=True)
class BodyProfile:
    """Anonymous dimensional calibration; no identity or imagery is retained."""

    stature_m: float = 1.70
    shoulder_width_m: float = 0.42
    torso_depth_m: float = 0.24
    hip_width_m: float = 0.34
    head_radius_m: float = 0.105
    bubble_radius_m: float = BUBBLE_RADIUS_METERS

    def __post_init__(self) -> None:
        limits = (
            ("stature_m", self.stature_m, 1.20, 2.20),
            ("shoulder_width_m", self.shoulder_width_m, 0.25, 0.75),
            ("torso_depth_m", self.torso_depth_m, 0.12, 0.55),
            ("hip_width_m", self.hip_width_m, 0.20, 0.65),
            ("head_radius_m", self.head_radius_m, 0.07, 0.16),
            ("bubble_radius_m", self.bubble_radius_m, 0.20, 2.00),
        )
        for name, value, minimum, maximum in limits:
            if not minimum <= value <= maximum:
                raise ValueError(f"{name} must be within [{minimum}, {maximum}]")


@dataclass(frozen=True, slots=True)
class Capsule:
    region: BodyRegion
    start: Vec3
    end: Vec3
    radius_m: float

    def closest_axis_point(self, point: Vec3) -> Vec3:
        axis = self.end - self.start
        length_squared = axis.squared_norm()
        if length_squared <= EPSILON:
            return self.start
        t = clamp01((point - self.start).dot(axis) / length_squared)
        return self.start + axis * t

    def clearance(self, point: Vec3) -> tuple[Vec3, float, bool]:
        axis_point = self.closest_axis_point(point)
        delta = point - axis_point
        distance = delta.norm()
        direction = delta.normalized(Vec3(1.0, 0.0, 0.0))
        surface = axis_point + direction * self.radius_m
        return surface, max(0.0, distance - self.radius_m), distance <= self.radius_m


@dataclass(frozen=True, slots=True)
class BodyClearance:
    body_point: Vec3
    surface_point: Vec3
    clearance_m: float
    proximity: float
    region: BodyRegion
    inside: bool


class BodyProxy:
    """Union of local body capsules used to evaluate Dbody(x)."""

    def __init__(self, profile: BodyProfile | None = None) -> None:
        self.profile = profile or BodyProfile()
        p = self.profile
        shoulder_y = p.stature_m - 0.30
        hip_y = p.stature_m * 0.53
        knee_y = p.stature_m * 0.29
        lateral_x = p.shoulder_width_m * 0.5 - 0.065
        rear_z = -p.torso_depth_m * 0.5 + 0.035
        self.segments: tuple[Capsule, ...] = (
            Capsule(
                BodyRegion.HEAD,
                Vec3(0.0, p.stature_m - p.head_radius_m, 0.0),
                Vec3(0.0, p.stature_m - p.head_radius_m, 0.0),
                p.head_radius_m,
            ),
            Capsule(
                BodyRegion.SHOULDER_NECK,
                Vec3(-p.shoulder_width_m * 0.5, shoulder_y, 0.0),
                Vec3(p.shoulder_width_m * 0.5, shoulder_y, 0.0),
                0.075,
            ),
            Capsule(
                BodyRegion.TORSO, Vec3(0.0, hip_y + 0.08, 0.0), Vec3(0.0, shoulder_y - 0.08, 0.0), p.torso_depth_m * 0.5
            ),
            Capsule(
                BodyRegion.LEFT_LATERAL,
                Vec3(-lateral_x, hip_y + 0.08, 0.0),
                Vec3(-lateral_x, shoulder_y - 0.10, 0.0),
                0.075,
            ),
            Capsule(
                BodyRegion.RIGHT_LATERAL,
                Vec3(lateral_x, hip_y + 0.08, 0.0),
                Vec3(lateral_x, shoulder_y - 0.10, 0.0),
                0.075,
            ),
            Capsule(
                BodyRegion.REAR_TORSO, Vec3(0.0, hip_y + 0.08, rear_z), Vec3(0.0, shoulder_y - 0.10, rear_z), 0.075
            ),
            Capsule(
                BodyRegion.PELVIS, Vec3(-p.hip_width_m * 0.5, hip_y, 0.0), Vec3(p.hip_width_m * 0.5, hip_y, 0.0), 0.10
            ),
            Capsule(
                BodyRegion.LOWER_BODY,
                Vec3(-p.hip_width_m * 0.22, 0.08, 0.0),
                Vec3(-p.hip_width_m * 0.22, knee_y, 0.0),
                0.075,
            ),
            Capsule(
                BodyRegion.LOWER_BODY,
                Vec3(p.hip_width_m * 0.22, 0.08, 0.0),
                Vec3(p.hip_width_m * 0.22, knee_y, 0.0),
                0.075,
            ),
        )

    def clearance(self, point_body: Vec3) -> BodyClearance:
        candidates = []
        for index, segment in enumerate(self.segments):
            surface, clearance, inside = segment.clearance(point_body)
            candidates.append((0 if inside else 1, clearance, index, segment, surface, inside))
        _, distance, _, segment, surface, inside = min(candidates, key=lambda item: item[:3])
        return BodyClearance(
            body_point=point_body,
            surface_point=surface,
            clearance_m=distance,
            proximity=clamp01(1.0 - distance / self.profile.bubble_radius_m),
            region=segment.region,
            inside=inside,
        )

    def distance(self, point_body: Vec3) -> float:
        """Return Dbody(x), the nonnegative nearest body-surface clearance."""
        return self.clearance(point_body).clearance_m
