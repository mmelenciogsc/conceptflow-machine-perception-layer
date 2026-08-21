# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import pytest

from conceptflow_mpl_perception import (
    BodyProfile,
    BodyProxy,
    BodyRegion,
    DepthSource,
    GeometryMorpher,
    HapticPattern,
    MotionGate,
    MotionSample,
    SpeakerBank,
    StableManifold,
    SurfaceContact,
    Vec3,
    VirtualSpeakerArray,
)


def contact(point: Vec3, proximity: float, approach: float = 0.0, sample: str = "s") -> SurfaceContact:
    return SurfaceContact(
        "surface-a",
        "entity-a",
        sample,
        point,
        Vec3(0.0, 1.0, 0.0),
        (1.0 - proximity) * 0.9144,
        proximity,
        BodyRegion.TORSO,
        0.0,
        0.0,
        approach,
        Vec3(0.0, 0.0, -1.0),
        1_000_000_000,
        0.9,
        DepthSource.SYNTHETIC,
        0.01,
    )


def manifold(point: Vec3, proximity: float, approach: float = 0.0) -> StableManifold:
    nearest = contact(point, proximity, approach)
    return StableManifold("surface-a", "entity-a", (nearest,), nearest, 0.05, False, 0.9, nearest.timestamp_ns)


def test_four_banks_have_multiple_rings_and_inward_normals() -> None:
    array = VirtualSpeakerArray(BodyProxy())
    for bank in SpeakerBank:
        selected = [item for item in array.emitters if item.bank == bank]
        assert len(selected) == 36
        assert {item.ring for item in selected} == {0, 1, 2}


def test_every_virtual_emitter_is_on_exact_body_offset_shell() -> None:
    body = BodyProxy()
    array = VirtualSpeakerArray(body)
    for emitter in array.emitters:
        assert body.distance(emitter.position_body) == pytest.approx(body.profile.bubble_radius_m, abs=1.0e-9)
        surface = body.clearance(emitter.position_body).surface_point
        assert emitter.inward_normal_body == (surface - emitter.position_body).normalized()


def test_speaker_geometry_cache_reuses_only_equal_immutable_calibrations() -> None:
    first = VirtualSpeakerArray(BodyProxy())
    second = VirtualSpeakerArray(BodyProxy())
    custom_body = BodyProxy(BodyProfile(stature_m=1.82, shoulder_width_m=0.48))
    custom = VirtualSpeakerArray(custom_body)

    assert first.emitters is second.emitters
    assert custom.emitters is not first.emitters
    for emitter in custom.emitters:
        assert custom_body.distance(emitter.position_body) == pytest.approx(
            custom_body.profile.bubble_radius_m,
            abs=1.0e-9,
        )


def test_weights_are_normalized_and_continuous_across_sector_boundary() -> None:
    array = VirtualSpeakerArray(BodyProxy())
    first = array.weights(manifold(Vec3(0.49, 1.0, 0.51), 0.5))
    previous = {item.emitter.emitter_id: item.weight for item in first}
    second = array.weights(manifold(Vec3(0.51, 1.0, 0.49), 0.5), previous)
    assert sum(item.weight for item in first) == pytest.approx(1.0)
    assert sum(item.weight for item in second) == pytest.approx(1.0)
    l1_change = sum(abs(a.weight - b.weight) for a, b in zip(first, second, strict=True))
    assert l1_change < 0.10


def test_proximity_expands_field_more_than_anchor_gain() -> None:
    morpher = GeometryMorpher(VirtualSpeakerArray(BodyProxy()))
    far = morpher.morph(manifold(Vec3(0.0, 1.0, 1.0), 0.2), 1.0)
    near = morpher.morph(manifold(Vec3(0.0, 1.0, 0.2), 0.9), 1.0)
    assert near.anchor.gain_linear - far.anchor.gain_linear < 0.10
    assert near.field.participation > far.field.participation * 10
    assert near.anchor.sound_size_m > far.anchor.sound_size_m
    assert near.haptic is not None


def test_moving_geometry_overrides_stationary_restraint() -> None:
    gate = MotionGate()
    stationary = MotionSample(1, 0.0, 0.0, 180.0, 0.0)
    baseline = gate.activation("wall", stationary)
    approaching = MotionSample(2, 0.0, 0.0, 0.0, 1.0)
    active = gate.activation("person", approaching)
    assert baseline == pytest.approx(0.12)
    assert active > 0.65


@pytest.mark.parametrize(
    "motion",
    [
        MotionSample(1, 1.2, 0.0, 0.0, 0.0),
        MotionSample(1, 0.7, 0.0, 90.0, 0.0),
        MotionSample(1, 0.8, 0.0, 0.0, 0.0),
        MotionSample(1, 0.0, 90.0, 0.0, 0.0),
    ],
)
def test_walking_parallel_retreat_turning_and_sideways_look_do_not_hide_updates(motion: MotionSample) -> None:
    assert MotionGate().activation("track", motion) > 0.12


def test_rapid_approach_produces_bounded_synchronized_haptic() -> None:
    cue = GeometryMorpher(VirtualSpeakerArray(BodyProxy())).morph(manifold(Vec3(0.1, 1.0, 0.2), 0.8, 1.2), 1.0)
    assert cue.haptic is not None
    assert cue.haptic.pattern == HapticPattern.URGENT_APPROACH
    assert 0.0 <= cue.haptic.intensity <= 1.0
    assert cue.haptic.duration_ms <= 180
    assert cue.haptic.timestamp_ns == cue.source_timestamp_ns
