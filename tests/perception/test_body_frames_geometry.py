# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import math

import pytest

from conceptflow_mpl_perception import (
    BUBBLE_RADIUS_METERS,
    BodyProfile,
    BodyProxy,
    BodyRegion,
    CoordinateFrame,
    DepthSource,
    FrameGraph,
    GeometryMapper,
    MetricGeometryObservation,
    Quaternion,
    StaleTransformError,
    TimedTransform,
    Vec3,
)


NOW = 1_000_000_000


def transform(
    parent: CoordinateFrame, child: CoordinateFrame, translation: Vec3, rotation: Quaternion
) -> TimedTransform:
    return TimedTransform(parent, child, translation, rotation, NOW)


def graph(body_rotation: Quaternion | None = None, head_rotation: Quaternion | None = None) -> FrameGraph:
    return FrameGraph(
        transform(
            CoordinateFrame.WORLD, CoordinateFrame.BODY, Vec3(0.0, 0.0, 0.0), body_rotation or Quaternion.identity()
        ),
        transform(
            CoordinateFrame.BODY, CoordinateFrame.HEAD, Vec3(0.0, 1.60, 0.0), head_rotation or Quaternion.identity()
        ),
        transform(CoordinateFrame.HEAD, CoordinateFrame.SENSOR, Vec3(0.0, 0.03, 0.08), Quaternion.identity()),
    )


def test_default_radius_is_exactly_three_feet_in_meters() -> None:
    assert BUBBLE_RADIUS_METERS == 0.9144
    assert BodyProfile().bubble_radius_m == 0.9144


def test_equal_clearance_from_distinct_body_regions() -> None:
    proxy = BodyProxy()
    by_region = {segment.region: segment for segment in proxy.segments}
    left_leg = next(
        segment for segment in proxy.segments if segment.region == BodyRegion.LOWER_BODY and segment.start.x < 0.0
    )
    samples = (
        (BodyRegion.HEAD, Vec3(0.0, 1.0, 0.0)),
        (BodyRegion.LEFT_LATERAL, Vec3(-1.0, 0.0, 0.0)),
        (BodyRegion.LOWER_BODY, Vec3(-1.0, 0.0, 0.0)),
    )
    for region, outward in samples:
        segment = left_leg if region == BodyRegion.LOWER_BODY else by_region[region]
        axis = (segment.start + segment.end) * 0.5
        result = proxy.clearance(axis + outward * (segment.radius_m + 0.30))
        assert result.clearance_m == pytest.approx(0.30)
        assert result.region == region


def test_proximity_endpoints_and_body_regions() -> None:
    proxy = BodyProxy()
    head = next(item for item in proxy.segments if item.region == BodyRegion.HEAD)
    at_surface = head.start + Vec3(0.0, head.radius_m, 0.0)
    at_boundary = at_surface + Vec3(0.0, proxy.profile.bubble_radius_m, 0.0)
    assert proxy.clearance(at_surface).proximity == pytest.approx(1.0)
    boundary = proxy.clearance(at_boundary)
    assert boundary.proximity == pytest.approx(0.0)
    assert boundary.region == BodyRegion.HEAD
    assert proxy.clearance(Vec3(0.0, 2.1, 0.0)).region == BodyRegion.HEAD
    assert proxy.clearance(Vec3(-0.25, 0.25, 0.0)).region == BodyRegion.LOWER_BODY


def test_head_turn_changes_sensor_ray_not_body_envelope() -> None:
    body_point = Vec3(0.30, 1.20, 0.15)
    initial = graph()
    yaw = Quaternion.from_axis_angle(Vec3(0.0, 1.0, 0.0), math.pi / 2.0)
    turned = graph(head_rotation=yaw)
    initial_body_world = initial.transform_point(body_point, CoordinateFrame.BODY, CoordinateFrame.WORLD, NOW, 1)
    turned_body_world = turned.transform_point(body_point, CoordinateFrame.BODY, CoordinateFrame.WORLD, NOW, 1)
    assert turned_body_world == initial_body_world
    sensor_ray = Vec3(0.0, 0.0, 1.0)
    before = initial.transform_vector(sensor_ray, CoordinateFrame.SENSOR, CoordinateFrame.WORLD, NOW, 1)
    after = turned.transform_vector(sensor_ray, CoordinateFrame.SENSOR, CoordinateFrame.WORLD, NOW, 1)
    assert before.distance_to(after) > 1.0


def test_torso_turn_and_forward_motion_are_independent_of_sideways_look() -> None:
    yaw = Quaternion.from_axis_angle(Vec3(0.0, 1.0, 0.0), math.pi / 2.0)
    rotated = graph(body_rotation=yaw, head_rotation=yaw.conjugate())
    forward_body = Vec3(0.0, 0.0, 1.0)
    forward_world = rotated.transform_vector(forward_body, CoordinateFrame.BODY, CoordinateFrame.WORLD, NOW, 1)
    assert forward_world.x == pytest.approx(1.0)
    assert forward_world.z == pytest.approx(0.0, abs=1e-8)
    translated = FrameGraph(
        transform(CoordinateFrame.WORLD, CoordinateFrame.BODY, Vec3(2.0, 0.0, 0.0), yaw),
        rotated.body_from_head,
        rotated.head_from_sensor,
    )
    assert translated.transform_point(
        Vec3(0.0, 1.0, 0.0), CoordinateFrame.BODY, CoordinateFrame.WORLD, NOW, 1
    ).x == pytest.approx(2.0)


def test_stale_transform_and_clock_rollback_are_rejected() -> None:
    frames = graph()
    with pytest.raises(StaleTransformError):
        frames.transform_point(Vec3(0.0, 0.0, 0.0), CoordinateFrame.WORLD, CoordinateFrame.BODY, NOW + 10, 5)
    with pytest.raises(ValueError, match="monotonic"):
        frames.update(
            TimedTransform(
                CoordinateFrame.WORLD, CoordinateFrame.BODY, Vec3(0.0, 0.0, 0.0), Quaternion.identity(), NOW - 1
            )
        )


def observation(
    entity: str, sample: str, point: Vec3, *, source: DepthSource = DepthSource.SYNTHETIC
) -> MetricGeometryObservation:
    return MetricGeometryObservation("synthetic", entity, sample, point, NOW, 0.9, source, 0.08)


def test_broad_wall_keeps_bounded_extent_while_pole_is_concentrated() -> None:
    mapper = GeometryMapper(BodyProxy(), broad_extent_m=0.30)
    wall = [observation("wall-left", f"w{index}", Vec3(-0.65, 0.6 + index * 0.3, 0.1)) for index in range(5)]
    pole = [observation("pole-front", "p0", Vec3(0.2, 1.0, 0.5))]
    manifolds = mapper.manifolds(wall + pole, graph(), NOW)
    by_entity = {item.entity_id: item for item in manifolds}
    assert by_entity["wall-left"].broad_surface
    assert 2 <= len(by_entity["wall-left"].contacts) <= 3
    assert not by_entity["pole-front"].broad_surface
    assert len(by_entity["pole-front"].contacts) == 1


def test_uncertain_monocular_depth_is_preserved_not_upgraded() -> None:
    mapped = GeometryMapper(BodyProxy()).contact(
        observation("branch", "m1", Vec3(0.0, 1.3, 0.5), source=DepthSource.CALIBRATED_MONOCULAR),
        graph(),
        NOW,
    )
    assert mapped.depth_source == DepthSource.CALIBRATED_MONOCULAR
    assert mapped.uncertainty_m == pytest.approx(0.08)
