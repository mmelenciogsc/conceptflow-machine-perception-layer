# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import stat

import pytest

from conceptflow_mpl_perception.training import (
    EXERCISES,
    TrainingPreferences,
    evaluate,
    load_preferences,
    save_preferences,
)


def test_training_has_exactly_eight_ordered_exercises() -> None:
    assert len(EXERCISES) == 8
    assert [item.difficulty for item in EXERCISES] == sorted(item.difficulty for item in EXERCISES)
    assert len({item.exercise_id for item in EXERCISES}) == 8


def test_training_scores_normalized_answers_objectively() -> None:
    result = evaluate("left-vs-rear-left", " Rear-Left ")
    assert result.passed
    assert result.score == 1
    wrong = evaluate("left_vs_rear_left", "left")
    assert not wrong.passed
    assert wrong.score == 0


def test_training_rejects_unknown_answer() -> None:
    with pytest.raises(ValueError, match="answer must be one of"):
        evaluate("above_vs_below", "somewhere")


def test_preferences_are_validated_and_saved_privately(tmp_path) -> None:  # type: ignore[no-untyped-def]
    path = tmp_path / "profiles" / "training.json"
    expected = TrainingPreferences(bubble_radius_m=1.0, haptic_strength=0.25)
    save_preferences(path, expected)
    assert load_preferences(path) == expected
    assert stat.S_IMODE(path.stat().st_mode) == 0o600


@pytest.mark.parametrize("radius", [0.19, 2.01, float("nan")])
def test_preferences_reject_invalid_radius(radius: float) -> None:
    with pytest.raises(ValueError, match="bubble_radius_m"):
        TrainingPreferences(bubble_radius_m=radius)
