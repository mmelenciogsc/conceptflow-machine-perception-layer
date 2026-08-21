# SPDX-License-Identifier: MIT OR Apache-2.0
"""Deterministic body-relative localization calibration manifests and scoring."""

from __future__ import annotations

import argparse
import json
import os
import tempfile
from collections.abc import Sequence
from dataclasses import asdict, dataclass
from pathlib import Path

from .audio import SpatializerProfile
from .body import BUBBLE_RADIUS_METERS


@dataclass(frozen=True, slots=True)
class CalibrationDirection:
    label: str
    azimuth_deg: float
    elevation_deg: float
    horizontal_group: str
    elevation_group: str


DIRECTIONS: tuple[CalibrationDirection, ...] = (
    CalibrationDirection("front", 0.0, 0.0, "front", "level"),
    CalibrationDirection("front_left", -45.0, 0.0, "left", "level"),
    CalibrationDirection("left", -90.0, 0.0, "left", "level"),
    CalibrationDirection("rear_left", -135.0, 0.0, "left", "level"),
    CalibrationDirection("rear", 180.0, 0.0, "rear", "level"),
    CalibrationDirection("rear_right", 135.0, 0.0, "right", "level"),
    CalibrationDirection("right", 90.0, 0.0, "right", "level"),
    CalibrationDirection("front_right", 45.0, 0.0, "right", "level"),
    CalibrationDirection("above_front", 0.0, 45.0, "front", "above"),
    CalibrationDirection("above", 0.0, 90.0, "vertical", "above"),
    CalibrationDirection("below_front", 0.0, -45.0, "front", "below"),
    CalibrationDirection("below", 0.0, -90.0, "vertical", "below"),
)

CALIBRATION_CLEARANCES_M = (0.15, 0.4572, BUBBLE_RADIUS_METERS)
CALIBRATION_PROFILES = (SpatializerProfile.FMOD_STANDARD, SpatializerProfile.RESONANCE_AUDIO)


@dataclass(frozen=True, slots=True)
class CalibrationTrial:
    trial_id: str
    pair_id: str
    direction: CalibrationDirection
    clearance_m: float
    bubble_proximity: float
    head_turn_yaw_deg: float
    moving_source: bool
    profile: SpatializerProfile


@dataclass(frozen=True, slots=True)
class CalibrationResponse:
    trial_id: str
    perceived_direction: str
    answered_at_ns: int
    preferred_profile: SpatializerProfile | None = None

    def __post_init__(self) -> None:
        if not self.trial_id or self.answered_at_ns < 0:
            raise ValueError("calibration response identity and timestamp must be valid")


@dataclass(frozen=True, slots=True)
class CalibrationSummary:
    answered: int
    exact_matches: int
    horizontal_group_matches: int
    elevation_group_matches: int
    exact_match_percent: float
    horizontal_group_percent: float
    elevation_group_percent: float
    mean_absolute_azimuth_error_deg: float
    mean_absolute_elevation_error_deg: float
    preferred_profile_counts: dict[str, int]


def generate_trials() -> tuple[CalibrationTrial, ...]:
    trials: list[CalibrationTrial] = []
    for direction_index, direction in enumerate(DIRECTIONS):
        for clearance_index, clearance in enumerate(CALIBRATION_CLEARANCES_M):
            pair_id = f"{direction.label}-d{clearance_index}"
            proximity = max(0.0, min(1.0, 1.0 - clearance / BUBBLE_RADIUS_METERS))
            head_turn = 30.0 if (direction_index + clearance_index) % 3 == 0 else 0.0
            moving = (direction_index + clearance_index) % 2 == 1
            for profile in CALIBRATION_PROFILES:
                trials.append(
                    CalibrationTrial(
                        f"{pair_id}-{profile.value}",
                        pair_id,
                        direction,
                        clearance,
                        proximity,
                        head_turn,
                        moving,
                        profile,
                    )
                )
    return tuple(trials)


def _direction(label: str) -> CalibrationDirection:
    normalized = "_".join(label.strip().casefold().replace("-", " ").split())
    for direction in DIRECTIONS:
        if direction.label == normalized:
            return direction
    raise ValueError(f"unknown perceived direction: {label}")


def _wrapped_azimuth_error(left: float, right: float) -> float:
    return abs((left - right + 180.0) % 360.0 - 180.0)


def evaluate(trials: Sequence[CalibrationTrial], responses: Sequence[CalibrationResponse]) -> CalibrationSummary:
    by_id = {trial.trial_id: trial for trial in trials}
    if len(by_id) != len(trials):
        raise ValueError("calibration trial IDs must be unique")
    if not responses:
        raise ValueError("at least one calibration response is required")
    seen: set[str] = set()
    exact = horizontal = elevation = 0
    azimuth_errors: list[float] = []
    elevation_errors: list[float] = []
    preferences: dict[str, int] = {}
    for response in responses:
        if response.trial_id in seen:
            raise ValueError(f"duplicate response for trial: {response.trial_id}")
        seen.add(response.trial_id)
        try:
            trial = by_id[response.trial_id]
        except KeyError as error:
            raise ValueError(f"unknown calibration trial: {response.trial_id}") from error
        perceived = _direction(response.perceived_direction)
        exact += perceived.label == trial.direction.label
        horizontal += perceived.horizontal_group == trial.direction.horizontal_group
        elevation += perceived.elevation_group == trial.direction.elevation_group
        azimuth_errors.append(_wrapped_azimuth_error(perceived.azimuth_deg, trial.direction.azimuth_deg))
        elevation_errors.append(abs(perceived.elevation_deg - trial.direction.elevation_deg))
        if response.preferred_profile is not None:
            key = response.preferred_profile.value
            preferences[key] = preferences.get(key, 0) + 1
    count = len(responses)
    return CalibrationSummary(
        count,
        exact,
        horizontal,
        elevation,
        100.0 * exact / count,
        100.0 * horizontal / count,
        100.0 * elevation / count,
        sum(azimuth_errors) / count,
        sum(elevation_errors) / count,
        preferences,
    )


def export(path: Path, summary: CalibrationSummary, responses: Sequence[CalibrationResponse]) -> None:
    """Write only structured answers and aggregate results, atomically and privately."""
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            descriptor = -1
            json.dump(
                {"responses": [asdict(item) for item in responses], "summary": asdict(summary)},
                stream,
                indent=2,
                sort_keys=True,
            )
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


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Export a deterministic, nonvisual localization calibration manifest")
    parser.add_argument("--json", action="store_true", help="emit JSON instead of accessible text")
    args = parser.parse_args(argv)
    trials = generate_trials()
    if args.json:
        print(json.dumps([asdict(item) for item in trials], indent=2, sort_keys=True))
    else:
        for item in trials:
            motion = "moving source" if item.moving_source else "stationary source"
            print(
                f"{item.trial_id}. Target {item.direction.label}; clearance {item.clearance_m:.4f} metres; "
                f"proximity {item.bubble_proximity:.3f}; head yaw {item.head_turn_yaw_deg:.0f}; {motion}; "
                f"profile {item.profile.value}."
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
