# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import json
from pathlib import Path

import pytest

from conceptflow_mpl_perception import (
    AudioOutputTopology,
    BodyProxy,
    CoordinateFrame,
    DepthSource,
    FrameGraph,
    GeometryMapper,
    MetricGeometryObservation,
    MotionSample,
    PerceptionEngine,
    Quaternion,
    SpeakerBank,
    SpatializerProfile,
    TimedTransform,
    Vec3,
    VirtualSpeakerArray,
)
from conceptflow_mpl_perception.audio import FMOD_ANCHOR_EVENT, FMOD_FIELD_EVENT
from conceptflow_mpl_perception.demo import run_demo


ROOT = Path(__file__).resolve().parents[2]


def frames(timestamp_ns: int) -> FrameGraph:
    return FrameGraph(
        TimedTransform(
            CoordinateFrame.WORLD, CoordinateFrame.BODY, Vec3(0.0, 0.0, 0.0), Quaternion.identity(), timestamp_ns
        ),
        TimedTransform(
            CoordinateFrame.BODY, CoordinateFrame.HEAD, Vec3(0.0, 1.6, 0.0), Quaternion.identity(), timestamp_ns
        ),
        TimedTransform(
            CoordinateFrame.HEAD, CoordinateFrame.SENSOR, Vec3(0.0, 0.03, 0.08), Quaternion.identity(), timestamp_ns
        ),
    )


def geometry(point: Vec3, timestamp_ns: int, *, entity: str = "surface") -> MetricGeometryObservation:
    return MetricGeometryObservation(
        "synthetic-depth",
        entity,
        f"{entity}-sample",
        point,
        timestamp_ns,
        0.95,
        DepthSource.SYNTHETIC,
        0.01,
        Vec3(0.0, 0.0, -1.0),
        Vec3(0.0, 0.0, -0.4),
    )


def test_three_slices_are_executable_and_inspectable() -> None:
    result = run_demo()
    assert result["geometry_cues"] == 1
    assert result["haptic_cues"] == 1
    assert result["semantic_icon"] == "procedural/soft_footfall_pair"
    assert "person" in str(result["scene_request"])
    assert len(result["scheduled"]) == 2
    assert result["scheduler"]["suppressed_capacity"] == 1
    audio = result["audio"]
    assert isinstance(audio, list) and audio
    voices = audio[0]["voices"]
    assert voices[0]["layer"] == "Intrusion Anchor"
    assert any(voice["layer"] == "Envelopment Field" for voice in voices[1:])


def test_audio_adapter_uses_authored_fmod_events_and_bounded_field_voices() -> None:
    timestamp = 5_000_000_000
    engine = PerceptionEngine()
    output = engine.process_geometry(
        [geometry(Vec3(-0.55, 1.25, 0.1), timestamp)],
        frames(timestamp),
        MotionSample(timestamp, 0.8, 0.0, 0.0, 0.0),
        timestamp,
    )
    dispatch = output.audio[0]
    assert dispatch.topology == AudioOutputTopology.UNKNOWN
    assert dispatch.spatializer == SpatializerProfile.RESONANCE_AUDIO
    assert dispatch.voices[0].event_path == FMOD_ANCHOR_EVENT
    assert all(voice.event_path == FMOD_FIELD_EVENT for voice in dispatch.voices[1:])
    assert len(dispatch.voices) <= 6
    assert sum(voice.weight for voice in dispatch.voices[1:]) == pytest.approx(1.0)


@pytest.mark.parametrize(("delay_ms", "accepted"), [(50, True), (100, True), (200, True), (400, False), (800, False)])
def test_delay_injection_rejects_contextually_stale_geometry(delay_ms: int, accepted: bool) -> None:
    capture_ns = 8_000_000_000
    now_ns = capture_ns + delay_ms * 1_000_000
    engine = PerceptionEngine()
    call = lambda: engine.process_geometry(  # noqa: E731
        [geometry(Vec3(0.4, 1.0, 0.4), capture_ns)],
        frames(capture_ns),
        MotionSample(now_ns, 0.5, 0.0, 0.0, 0.0),
        now_ns,
    )
    if accepted:
        assert len(call().cues) == 1
    else:
        with pytest.raises(ValueError, match="stale"):
            call()
        assert engine.counters.stale_suppressed == 1


def test_golden_intrusion_traces_select_expected_dominant_bank() -> None:
    payload = json.loads(
        (ROOT / "labs/unity-fmod-perception-lab/Assets/ConceptFlow/Resources/golden_intrusions.json").read_text()
    )
    body = BodyProxy()
    mapper = GeometryMapper(body)
    array = VirtualSpeakerArray(body)
    timestamp = 0
    for trace in payload["traces"]:
        point = Vec3(*trace["pointBody"])
        velocity = Vec3(*trace["velocityBody"])
        manifold = mapper.manifolds(
            [
                MetricGeometryObservation(
                    "golden",
                    trace["id"],
                    "sample",
                    point,
                    timestamp,
                    1.0,
                    DepthSource.SYNTHETIC,
                    0.0,
                    velocity_world_mps=velocity,
                )
            ],
            frames(timestamp),
            timestamp,
        )[0]
        weighted = array.weights(manifold)
        totals = {bank: sum(item.weight for item in weighted if item.emitter.bank == bank) for bank in SpeakerBank}
        dominant = max(totals, key=totals.__getitem__)
        assert dominant.value == trace["expectedBank"], trace["id"]
