# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import json
import stat

import pytest

from conceptflow_mpl_perception import SpatializerProfile
from conceptflow_mpl_perception.calibration import (
    BUBBLE_RADIUS_METERS,
    CalibrationResponse,
    evaluate,
    export,
    generate_trials,
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
