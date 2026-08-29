# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import json
import stat
from pathlib import Path

import pytest

from conceptflow_mpl_perception import SpatializerProfile
from conceptflow_mpl_perception.calibration import (
    BUBBLE_RADIUS_METERS,
    FOCUSED_HRTF_DISTANCE_METERS,
    FOCUSED_HRTF_MANIFEST_SCHEMA,
    FOCUSED_HRTF_PRESENTATION_COUNT,
    FOCUSED_HRTF_RESPONSE_SCHEMA,
    CalibrationResponse,
    evaluate,
    evaluate_focused_hrtf,
    export,
    focused_hrtf_manifest,
    generate_focused_hrtf_trials,
    generate_trials,
    load_focused_hrtf_responses,
)


def test_manifest_covers_directions_distances_profiles_and_motion() -> None:
    trials = generate_trials()
    assert len(trials) == 12 * 3 * 2
    assert len({item.direction.label for item in trials}) == 12
    assert {item.clearance_m for item in trials} == {0.15, 0.4572, BUBBLE_RADIUS_METERS}
    assert {item.profile for item in trials} == {
        SpatializerProfile.FMOD_STANDARD,
        SpatializerProfile.RESONANCE_AUDIO,
    }
    assert any(item.head_turn_yaw_deg for item in trials)
    assert any(item.moving_source for item in trials)
    for pair_id in {item.pair_id for item in trials}:
        pair = [item for item in trials if item.pair_id == pair_id]
        assert len(pair) == 2
        assert pair[0].direction == pair[1].direction
        assert pair[0].bubble_proximity == pytest.approx(pair[1].bubble_proximity)


def test_proximity_has_surface_to_boundary_semantics() -> None:
    trials = generate_trials()
    by_clearance = {item.clearance_m: item.bubble_proximity for item in trials}
    assert by_clearance[BUBBLE_RADIUS_METERS] == pytest.approx(0.0)
    assert by_clearance[0.4572] == pytest.approx(0.5)
    assert by_clearance[0.15] == pytest.approx(1.0 - 0.15 / BUBBLE_RADIUS_METERS)


def test_calibration_reports_matches_and_angular_errors_without_accuracy_claim() -> None:
    trials = generate_trials()[:2]
    responses = (
        CalibrationResponse(trials[0].trial_id, trials[0].direction.label, 10, SpatializerProfile.FMOD_STANDARD),
        CalibrationResponse(trials[1].trial_id, "right", 11, SpatializerProfile.RESONANCE_AUDIO),
    )
    summary = evaluate(trials, responses)
    assert summary.exact_match_percent == pytest.approx(50.0)
    assert summary.mean_absolute_azimuth_error_deg >= 0.0
    assert summary.preferred_profile_counts == {"fmod_standard": 1, "resonance_audio": 1}


def test_calibration_rejects_duplicate_and_unknown_responses() -> None:
    trial = generate_trials()[0]
    response = CalibrationResponse(trial.trial_id, "front", 1)
    with pytest.raises(ValueError, match="duplicate"):
        evaluate((trial,), (response, response))
    with pytest.raises(ValueError, match="unknown calibration trial"):
        evaluate((trial,), (CalibrationResponse("missing", "front", 1),))


def test_calibration_export_is_private_and_contains_no_media(tmp_path) -> None:  # type: ignore[no-untyped-def]
    trial = generate_trials()[0]
    responses = (CalibrationResponse(trial.trial_id, trial.direction.label, 1),)
    summary = evaluate((trial,), responses)
    path = tmp_path / "calibration" / "result.json"
    export(path, summary, responses)
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert payload["summary"]["answered"] == 1
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert not {"image", "audio", "identity"} & set(payload)


def test_focused_hrtf_manifest_is_fixed_balanced_and_resonance_only() -> None:
    first = generate_focused_hrtf_trials()
    second = generate_focused_hrtf_trials()
    assert first == second
    assert len(first) == 24
    assert [item.ordinal for item in first] == list(range(1, 25))
    assert {item.block_index for item in first} == {1, 2}
    assert all(item.distance_m == FOCUSED_HRTF_DISTANCE_METERS for item in first)
    assert all(item.presentation_count == FOCUSED_HRTF_PRESENTATION_COUNT for item in first)
    assert all(item.profile is SpatializerProfile.RESONANCE_AUDIO for item in first)
    assert all(left.direction != right.direction for left, right in zip(first, first[1:]))
    for block in (first[:12], first[12:]):
        assert {item.direction.label for item in block} == {item.direction.label for item in first}
    manifest = focused_hrtf_manifest()
    assert manifest["schema"] == FOCUSED_HRTF_MANIFEST_SCHEMA
    assert len(manifest["trials"]) == 24  # type: ignore[arg-type]
    resource = (
        Path(__file__).resolve().parents[2]
        / "labs/unity-fmod-perception-lab/Assets/ConceptFlow/Resources/focused_hrtf_trials.json"
    )
    assert json.loads(resource.read_text(encoding="utf-8")) == manifest


def test_focused_hrtf_ndjson_is_strict_ordered_and_scorable(tmp_path) -> None:  # type: ignore[no-untyped-def]
    trials = generate_focused_hrtf_trials()
    path = tmp_path / "responses.ndjson"
    lines = [
        _focused_response(trial, "session-1", trial.direction.label, 1_000 + index)
        for index, trial in enumerate(trials)
    ]
    path.write_text("\n".join(json.dumps(item, sort_keys=True) for item in lines) + "\n", encoding="utf-8")
    responses = load_focused_hrtf_responses(path, require_complete=True)
    summary = evaluate_focused_hrtf(trials, responses)
    assert summary.answered == 24
    assert summary.exact_match_percent == pytest.approx(100.0)
    assert summary.preferred_profile_counts == {}


@pytest.mark.parametrize(
    ("mutation", "message"),
    [
        (lambda value: value.update(extra="forbidden"), "fields"),
        (lambda value: value.update(schema="future"), "schema"),
        (lambda value: value.update(trial_id="out-of-order"), "order"),
        (lambda value: value.update(perceived_direction="up-left-ish"), "direction"),
        (lambda value: value.update(target_azimuth_deg=12.0), "metadata"),
        (lambda value: value.update(answered_at_ns=True), "timestamp"),
    ],
)
def test_focused_hrtf_ndjson_rejects_schema_drift(tmp_path, mutation, message) -> None:  # type: ignore[no-untyped-def]
    trial = generate_focused_hrtf_trials()[0]
    payload = _focused_response(trial, "session-1", "front", 1)
    mutation(payload)
    path = tmp_path / "invalid.ndjson"
    path.write_text(json.dumps(payload) + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match=message):
        load_focused_hrtf_responses(path)


def test_focused_hrtf_ndjson_rejects_blank_partial_and_cross_session(tmp_path) -> None:  # type: ignore[no-untyped-def]
    trials = generate_focused_hrtf_trials()
    path = tmp_path / "responses.ndjson"
    first = _focused_response(trials[0], "session-1", "front", 1)
    path.write_text(json.dumps(first) + "\n\n", encoding="utf-8")
    with pytest.raises(ValueError, match="blank"):
        load_focused_hrtf_responses(path)
    path.write_text(json.dumps(first) + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="incomplete"):
        load_focused_hrtf_responses(path, require_complete=True)
    second = _focused_response(trials[1], "session-2", "rear", 2)
    path.write_text(json.dumps(first) + "\n" + json.dumps(second) + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="one session"):
        load_focused_hrtf_responses(path)


def _focused_response(trial, session_id: str, perceived_direction: str, answered_at_ns: int) -> dict[str, object]:  # type: ignore[no-untyped-def]
    return {
        "schema": FOCUSED_HRTF_RESPONSE_SCHEMA,
        "session_id": session_id,
        "trial_id": trial.trial_id,
        "perceived_direction": perceived_direction,
        "answered_at_ns": answered_at_ns,
        "target_direction": trial.direction.label,
        "target_azimuth_deg": trial.direction.azimuth_deg,
        "target_elevation_deg": trial.direction.elevation_deg,
        "distance_m": trial.distance_m,
        "presentation_count": trial.presentation_count,
        "profile": trial.profile.value,
    }
