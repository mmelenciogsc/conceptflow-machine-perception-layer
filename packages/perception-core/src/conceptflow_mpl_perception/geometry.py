# SPDX-License-Identifier: MIT OR Apache-2.0
"""Metric observations, body contacts, and stable bounded manifolds."""

from __future__ import annotations

import hashlib
import math
from dataclasses import dataclass
from enum import Enum

from .body import BodyProxy, BodyRegion
from .frames import FrameGraph
from .model import CoordinateFrame, Vec3


class DepthSource(str, Enum):
    VERIFIED_METRIC = "verified_metric"
    CALIBRATED_MONOCULAR = "calibrated_monocular"
    SYNTHETIC = "synthetic"


@dataclass(frozen=True, slots=True)
class MetricGeometryObservation:
    source_id: str
    entity_id: str
    sample_id: str
    point_world: Vec3
    timestamp_ns: int
    confidence: float
    depth_source: DepthSource
    uncertainty_m: float
    normal_world: Vec3 | None = None
    velocity_world_mps: Vec3 = Vec3(0.0, 0.0, 0.0)

    def __post_init__(self) -> None:
        if not self.source_id or not self.entity_id or not self.sample_id:
            raise ValueError("observation identities must be nonempty")
        if self.timestamp_ns < 0 or not 0.0 <= self.confidence <= 1.0 or self.uncertainty_m < 0.0:
            raise ValueError("invalid observation timing, confidence, or uncertainty")


@dataclass(frozen=True, slots=True)
class SurfaceContact:
    stable_id: str
    entity_id: str
    sample_id: str
    geometry_point_body: Vec3
    body_surface_point: Vec3
    body_surface_distance_m: float
    bubble_proximity: float
    body_region: BodyRegion
    azimuth_deg: float
    elevation_deg: float
    radial_approach_mps: float
    normal_body: Vec3 | None
    timestamp_ns: int
    confidence: float
    depth_source: DepthSource
    uncertainty_m: float


@dataclass(frozen=True, slots=True)
class StableManifold:
    stable_id: str
    entity_id: str
    contacts: tuple[SurfaceContact, ...]
    nearest: SurfaceContact
    footprint_extent_m: float
    broad_surface: bool
    confidence: float
    timestamp_ns: int


class GeometryMapper:
    def __init__(
        self,
        body: BodyProxy,
        *,
        max_observation_age_ns: int = 200_000_000,
        adjacency_m: float = 0.45,
        broad_extent_m: float = 0.35,
        max_contacts_per_manifold: int = 3,
    ) -> None:
        if min(max_observation_age_ns, max_contacts_per_manifold) <= 0 or min(adjacency_m, broad_extent_m) <= 0:
            raise ValueError("mapper limits must be positive")
        self.body = body
        self.max_observation_age_ns = max_observation_age_ns
        self.adjacency_m = adjacency_m
        self.broad_extent_m = broad_extent_m
        self.max_contacts_per_manifold = max_contacts_per_manifold

    @staticmethod
    def _stable_id(source_id: str, entity_id: str) -> str:
        digest = hashlib.sha256(f"{source_id}\0{entity_id}".encode()).hexdigest()[:16]
        return f"surface-{digest}"

    def contact(self, observation: MetricGeometryObservation, frames: FrameGraph, now_ns: int) -> SurfaceContact:
        age = now_ns - observation.timestamp_ns
        if age < 0 or age > self.max_observation_age_ns:
            raise ValueError("geometry observation is stale or from the future")
        point_body = frames.transform_point(
            observation.point_world,
            CoordinateFrame.WORLD,
            CoordinateFrame.BODY,
            observation.timestamp_ns,
            self.max_observation_age_ns,
        )
        velocity_body = frames.transform_vector(
            observation.velocity_world_mps,
            CoordinateFrame.WORLD,
            CoordinateFrame.BODY,
            observation.timestamp_ns,
            self.max_observation_age_ns,
        )
        normal_body = None
        if observation.normal_world is not None:
            normal_body = frames.transform_vector(
                observation.normal_world,
                CoordinateFrame.WORLD,
                CoordinateFrame.BODY,
                observation.timestamp_ns,
                self.max_observation_age_ns,
            ).normalized()
        clearance = self.body.clearance(point_body)
        outward = (point_body - clearance.surface_point).normalized(Vec3(0.0, 0.0, 1.0))
        horizontal = math.hypot(outward.x, outward.z)
        azimuth = math.degrees(math.atan2(outward.x, outward.z))
        elevation = math.degrees(math.atan2(outward.y, max(horizontal, 1.0e-9)))
        return SurfaceContact(
            stable_id=self._stable_id(observation.source_id, observation.entity_id),
            entity_id=observation.entity_id,
            sample_id=observation.sample_id,
            geometry_point_body=point_body,
            body_surface_point=clearance.surface_point,
            body_surface_distance_m=clearance.clearance_m,
            bubble_proximity=clearance.proximity,
            body_region=clearance.region,
            azimuth_deg=azimuth,
            elevation_deg=elevation,
            radial_approach_mps=max(0.0, -velocity_body.dot(outward)),
            normal_body=normal_body,
            timestamp_ns=observation.timestamp_ns,
            confidence=observation.confidence,
            depth_source=observation.depth_source,
            uncertainty_m=observation.uncertainty_m,
        )

    def manifolds(
        self, observations: list[MetricGeometryObservation], frames: FrameGraph, now_ns: int
    ) -> tuple[StableManifold, ...]:
        grouped: dict[str, list[SurfaceContact]] = {}
        for observation in observations:
            contact = self.contact(observation, frames, now_ns)
            grouped.setdefault(contact.stable_id, []).append(contact)
        result = []
        for stable_id in sorted(grouped):
            contacts = sorted(grouped[stable_id], key=lambda item: (item.body_surface_distance_m, item.sample_id))
            nearest = contacts[0]
            extent = max(
                (a.geometry_point_body.distance_to(b.geometry_point_body) for a in contacts for b in contacts),
                default=0.0,
            )
            broad = extent >= self.broad_extent_m
            selected = [nearest]
            if broad and len(contacts) > 1:
                for candidate in sorted(
                    contacts[1:],
                    key=lambda item: (
                        -item.geometry_point_body.distance_to(nearest.geometry_point_body),
                        item.sample_id,
                    ),
                ):
                    if all(
                        candidate.geometry_point_body.distance_to(item.geometry_point_body) >= self.adjacency_m * 0.5
                        for item in selected
                    ):
                        selected.append(candidate)
                    if len(selected) >= self.max_contacts_per_manifold:
                        break
            result.append(
                StableManifold(
                    stable_id=stable_id,
                    entity_id=nearest.entity_id,
                    contacts=tuple(selected),
                    nearest=nearest,
                    footprint_extent_m=extent,
                    broad_surface=broad,
                    confidence=min(item.confidence for item in contacts),
                    timestamp_ns=max(item.timestamp_ns for item in contacts),
                )
            )
        return tuple(sorted(result, key=lambda item: (item.nearest.body_surface_distance_m, item.stable_id)))
