# SPDX-License-Identifier: MIT OR Apache-2.0
"""Deterministic, fully textual Sound Bubble training and local preferences."""

from __future__ import annotations

import argparse
import json
import math
import os
import tempfile
from collections.abc import Mapping, Sequence
from dataclasses import asdict, dataclass
from enum import Enum
from pathlib import Path

from .body import BUBBLE_RADIUS_METERS


class SpectralProfile(str, Enum):
    LOW_STIMULATION = "low_stimulation"
    SOFT_BROADBAND = "soft_broadband"
    REDUCED_HIGH_FREQUENCY = "reduced_high_frequency"


class IconVerbosity(str, Enum):
    MINIMAL = "minimal"
    BALANCED = "balanced"
    DETAILED = "detailed"


@dataclass(frozen=True, slots=True)
class TrainingExercise:
    exercise_id: str
    instruction: str
    choices: tuple[str, ...]
    expected_answer: str
    difficulty: int


@dataclass(frozen=True, slots=True)
class TrainingResult:
    exercise_id: str
    passed: bool
    score: int
    normalized_answer: str
    expected_answer: str
    feedback: str


EXERCISES: tuple[TrainingExercise, ...] = (
    TrainingExercise("direction", "Locate the single intrusion.", ("front_right", "rear_right"), "front_right", 1),
    TrainingExercise("left_vs_rear_left", "Distinguish left from rear-left.", ("left", "rear_left"), "rear_left", 1),
    TrainingExercise("above_vs_below", "Distinguish above from below.", ("above", "below"), "above", 1),
    TrainingExercise(
        "boundary_crossing",
        "Identify when the object crosses the outer boundary.",
        ("entered", "outside"),
        "entered",
        2,
    ),
    TrainingExercise(
        "wall_migration",
        "Follow the wall as its nearest point moves.",
        ("left_to_rear", "left_to_front"),
        "left_to_rear",
        2,
    ),
    TrainingExercise(
        "broad_vs_post",
        "Distinguish the extended field from the concentrated anchor.",
        ("broad_wall", "narrow_post"),
        "broad_wall",
        2,
    ),
    TrainingExercise("auditory_icon", "Identify the representational semantic icon.", ("person", "door"), "person", 3),
    TrainingExercise(
        "combined_audio_haptic",
        "Identify the synchronized approach transition.",
        ("approaching_front", "retreating_rear"),
        "approaching_front",
        3,
    ),
)


def _normalize_answer(value: str) -> str:
    return "_".join(value.strip().casefold().replace("-", " ").split())


def _number(value: object, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{name} must be numeric")
    return float(value)


def _integer(value: object, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{name} must be an integer")
    return value


def _string(value: object, name: str) -> str:
    if not isinstance(value, str):
        raise ValueError(f"{name} must be a string")
    return value


def exercise(exercise_id: str) -> TrainingExercise:
    normalized = _normalize_answer(exercise_id)
    for item in EXERCISES:
        if item.exercise_id == normalized:
            return item
    raise ValueError(f"unknown training exercise: {exercise_id}")


def evaluate(exercise_id: str, answer: str) -> TrainingResult:
    item = exercise(exercise_id)
    normalized = _normalize_answer(answer)
    if normalized not in item.choices:
        raise ValueError(f"answer must be one of: {', '.join(item.choices)}")
    passed = normalized == item.expected_answer
    return TrainingResult(
        item.exercise_id,
        passed,
        item.difficulty if passed else 0,
        normalized,
        item.expected_answer,
        "Correct. Repeat when ready." if passed else "Not yet. Replay the same controlled trial.",
    )


@dataclass(frozen=True, slots=True)
class TrainingPreferences:
    bubble_radius_m: float = BUBBLE_RADIUS_METERS
    spectral_profile: SpectralProfile = SpectralProfile.LOW_STIMULATION
    anchor_field_balance: float = 0.55
    spatializer_profile: str = "fmod_standard"
    icon_verbosity: IconVerbosity = IconVerbosity.MINIMAL
    haptic_strength: float = 0.50
    scene_description_cadence_s: int = 30

    def __post_init__(self) -> None:
        if not 0.2 <= self.bubble_radius_m <= 2.0:
            raise ValueError("bubble_radius_m must be within [0.2, 2.0]")
        for name, value in (
            ("anchor_field_balance", self.anchor_field_balance),
            ("haptic_strength", self.haptic_strength),
        ):
            if not math.isfinite(value) or not 0.0 <= value <= 1.0:
                raise ValueError(f"{name} must be within [0, 1]")
        if self.spatializer_profile not in {"fmod_standard", "resonance_audio", "platform_experimental"}:
            raise ValueError("unsupported spatializer_profile")
        if not 5 <= self.scene_description_cadence_s <= 300:
            raise ValueError("scene_description_cadence_s must be within [5, 300]")

    def to_dict(self) -> dict[str, object]:
        return asdict(self)

    @classmethod
    def from_mapping(cls, data: Mapping[str, object]) -> TrainingPreferences:
        expected = {
            "bubble_radius_m",
            "spectral_profile",
            "anchor_field_balance",
            "spatializer_profile",
            "icon_verbosity",
            "haptic_strength",
            "scene_description_cadence_s",
        }
        unknown = set(data) - expected
        if unknown:
            raise ValueError(f"unknown preference fields: {', '.join(sorted(unknown))}")
        try:
            return cls(
                bubble_radius_m=_number(data.get("bubble_radius_m", BUBBLE_RADIUS_METERS), "bubble_radius_m"),
                spectral_profile=SpectralProfile(
                    _string(data.get("spectral_profile", SpectralProfile.LOW_STIMULATION.value), "spectral_profile")
                ),
                anchor_field_balance=_number(data.get("anchor_field_balance", 0.55), "anchor_field_balance"),
                spatializer_profile=_string(data.get("spatializer_profile", "fmod_standard"), "spatializer_profile"),
                icon_verbosity=IconVerbosity(
                    _string(data.get("icon_verbosity", IconVerbosity.MINIMAL.value), "icon_verbosity")
                ),
                haptic_strength=_number(data.get("haptic_strength", 0.50), "haptic_strength"),
                scene_description_cadence_s=_integer(
                    data.get("scene_description_cadence_s", 30), "scene_description_cadence_s"
                ),
            )
        except (TypeError, ValueError) as error:
            raise ValueError(f"invalid training preferences: {error}") from error


def save_preferences(path: Path, preferences: TrainingPreferences) -> None:
    """Atomically store bounded configuration; no observations or identity are stored."""
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            descriptor = -1
            json.dump(preferences.to_dict(), stream, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_name, path)
        os.chmod(path, 0o600)
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def load_preferences(path: Path) -> TrainingPreferences:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("training preferences must be a JSON object")
    return TrainingPreferences.from_mapping(payload)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Accessible textual Sound Bubble training companion")
    parser.add_argument("--list", action="store_true", help="list the deterministic exercises")
    parser.add_argument("--exercise", help="exercise identifier to score")
    parser.add_argument("--answer", help="answer token from the listed choices")
    parser.add_argument("--json", action="store_true", help="emit machine-readable output")
    args = parser.parse_args(argv)
    if args.exercise or args.answer:
        if not args.exercise or not args.answer:
            parser.error("--exercise and --answer must be supplied together")
        result = evaluate(args.exercise, args.answer)
        print(
            json.dumps(asdict(result), sort_keys=True)
            if args.json
            else f"{result.exercise_id}: {result.feedback} Score {result.score}."
        )
        return 0 if result.passed else 1
    if not args.list:
        parser.error("select --list or provide --exercise and --answer")
    if args.json:
        print(json.dumps([asdict(item) for item in EXERCISES], indent=2, sort_keys=True))
    else:
        for item in EXERCISES:
            print(
                f"Difficulty {item.difficulty}. {item.exercise_id}. {item.instruction} Choices: {', '.join(item.choices)}."
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
