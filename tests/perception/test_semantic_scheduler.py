# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

from conceptflow_mpl_perception import (
    AuditoryIconRegistry,
    BodyProxy,
    CoordinateFrame,
    DepthAnythingExternalConfig,
    DepthEnvironment,
    FrameGraph,
    MaskDepthPoint,
    PerceptualPriorityScheduler,
    PriorityLane,
    Quaternion,
    SceneSimilarityGate,
    ScheduledCue,
    SegmentedObservation,
    SemanticDepthFuser,
    SemanticSimilarityGate,
    TimedTransform,
    Vec3,
)


NOW = 2_000_000_000


def frames() -> FrameGraph:
    return FrameGraph(
        TimedTransform(CoordinateFrame.WORLD, CoordinateFrame.BODY, Vec3(0.0, 0.0, 0.0), Quaternion.identity(), NOW),
        TimedTransform(CoordinateFrame.BODY, CoordinateFrame.HEAD, Vec3(0.0, 1.6, 0.0), Quaternion.identity(), NOW),
        TimedTransform(CoordinateFrame.HEAD, CoordinateFrame.SENSOR, Vec3(0.0, 0.03, 0.08), Quaternion.identity(), NOW),
    )


def observation(timestamp: int = NOW, track_id: str = "track-person") -> SegmentedObservation:
    return SegmentedObservation(
        track_id,
        "person",
        0.88,
        timestamp,
        (
            MaskDepthPoint(100, 100, Vec3(-0.2, -0.3, 1.0)),
            MaskDepthPoint(108, 100, Vec3(0.0, -0.2, 1.2)),
            MaskDepthPoint(116, 100, Vec3(0.2, -0.1, 2.0)),
        ),
        Vec3(0.0, 0.0, -0.4),
    )


def test_depth_anything_has_explicit_indoor_and_outdoor_profiles() -> None:
    indoor = DepthAnythingExternalConfig(DepthEnvironment.INDOOR, "http://127.0.0.1:9001")
    outdoor = DepthAnythingExternalConfig(DepthEnvironment.OUTDOOR, "https://inference.invalid")
    assert "Metric-Indoor-Large" in indoor.model_id
    assert "Metric-Outdoor-Large" in outdoor.model_id
    assert indoor.model_id != outdoor.model_id


def test_mask_depth_becomes_layered_semantic_track_and_icon() -> None:
    track = SemanticDepthFuser(BodyProxy()).fuse(observation(), frames(), NOW)
    assert track.nearest_depth_m < track.median_depth_m < track.far_depth_m
    assert track.reliable_point_count == 3
    cue = AuditoryIconRegistry().cue(track, NOW)
    assert cue.asset_key == "procedural/soft_footfall_pair"
    assert cue.nearest_position_world == track.nearest_world
    assert len(cue.extent_positions_world) == 1


def test_semantic_similarity_suppresses_jitter_but_allows_material_transition() -> None:
    fuser = SemanticDepthFuser(BodyProxy())
    track = fuser.fuse(observation(), frames(), NOW)
    gate = SemanticSimilarityGate()
    assert gate.evaluate(track, NOW)[0]
    assert not gate.evaluate(track, NOW + 3_000_000_000)[0]
    changed = fuser.fuse(
        SegmentedObservation(
            "track-person",
            "person",
            0.90,
            NOW,
            (MaskDepthPoint(200, 100, Vec3(1.0, -0.2, 0.25)),),
        ),
        frames(),
        NOW,
    )
    assert gate.evaluate(changed, NOW + 3_100_000_000)[0]


def test_scene_similarity_and_on_demand_bypass() -> None:
    track = SemanticDepthFuser(BodyProxy()).fuse(observation(), frames(), NOW)
    gate = SceneSimilarityGate()
    first = gate.request((track,), NOW, on_demand=False)
    assert first is not None
    assert gate.request((track,), NOW + 20_000_000_000, on_demand=False) is None
    requested = gate.request((track,), NOW + 20_000_000_001, on_demand=True)
    assert requested is not None and requested.on_demand and requested.priority > first.priority


def cue(identifier: str, lane: PriorityLane, created: int, key: str | None = None) -> ScheduledCue:
    return ScheduledCue(identifier, key or identifier, lane, created, created + 1_000_000_000, 100, {"id": identifier})


def test_single_scheduler_prioritizes_geometry_and_bounds_capacity() -> None:
    scheduler = PerceptualPriorityScheduler(max_concurrent_voices=2, cooldown_ms=100)
    assert scheduler.submit(cue("periodic", PriorityLane.PERIODIC_SCENE, NOW), NOW)
    assert scheduler.submit(cue("icon", PriorityLane.ORDINARY_ICON, NOW + 1), NOW)
    assert scheduler.submit(cue("geometry", PriorityLane.GEOMETRY, NOW + 2), NOW)
    dispatched = scheduler.dispatch(NOW + 3)
    assert [item.cue_id for item in dispatched] == ["geometry", "icon"]
    assert scheduler.trace()["suppressed_capacity"] == 1


def test_scheduler_enforces_independent_audio_speech_and_haptic_budgets() -> None:
    scheduler = PerceptualPriorityScheduler(max_concurrent_voices=6, max_concurrent_speech=1, max_concurrent_haptics=1)
    geometry = ScheduledCue(
        "geometry",
        "geometry",
        PriorityLane.GEOMETRY,
        NOW,
        NOW + 1_000_000_000,
        100,
        None,
        audio_voice_cost=6,
        haptic_slot_cost=1,
    )
    speech = ScheduledCue(
        "speech",
        "speech",
        PriorityLane.USER_SCENE,
        NOW + 1,
        NOW + 1_000_000_000,
        100,
        None,
        audio_voice_cost=0,
        speech_slot_cost=1,
    )
    icon = ScheduledCue("icon", "icon", PriorityLane.SALIENT_ICON, NOW + 2, NOW + 1_000_000_000, 100, None)
    for item in (geometry, speech, icon):
        assert scheduler.submit(item, NOW)
    assert [item.cue_id for item in scheduler.dispatch(NOW + 3)] == ["geometry", "speech"]
    assert scheduler.trace()["suppressed_capacity"] == 1


def test_scheduler_cooldown_expiry_and_supersession_counters() -> None:
    scheduler = PerceptualPriorityScheduler()
    assert scheduler.submit(cue("old", PriorityLane.ORDINARY_ICON, NOW, "track"), NOW)
    assert scheduler.submit(cue("new", PriorityLane.SALIENT_ICON, NOW + 1, "track"), NOW)
    assert scheduler.dispatch(NOW + 2)[0].cue_id == "new"
    assert not scheduler.submit(cue("repeat", PriorityLane.SALIENT_ICON, NOW + 3, "track"), NOW + 3)
    expired = ScheduledCue("expired", "expired", PriorityLane.GEOMETRY, NOW, NOW + 1, 20, None)
    assert not scheduler.submit(expired, NOW + 2)
    counters = scheduler.trace()
    assert counters["superseded"] == 1
    assert counters["suppressed_similarity"] == 1
    assert counters["expired"] == 1
