# SPDX-License-Identifier: MIT OR Apache-2.0
"""Deterministic body-relative localization calibration manifests and scoring."""

from __future__ import annotations

import argparse
import json
import os
import random
import re
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
FOCUSED_HRTF_MANIFEST_SCHEMA = "conceptflow.hrtf-localization-manifest/v1"
FOCUSED_HRTF_RESPONSE_SCHEMA = "conceptflow.hrtf-localization-response/v1"
FOCUSED_HRTF_DISTANCE_METERS = 2.0
FOCUSED_HRTF_PRESENTATION_COUNT = 3
FOCUSED_HRTF_BLOCK_COUNT = 2
FOCUSED_HRTF_SEED = 20_260_828
_SESSION_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}\Z")


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
class FocusedHrtfTrial:
    trial_id: str
    ordinal: int
    block_index: int
    direction: CalibrationDirection
    distance_m: float
    presentation_count: int
    profile: SpatializerProfile


@dataclass(frozen=True, slots=True)
class FocusedHrtfResponse:
    session_id: str
    trial_id: str
    perceived_direction: str
    answered_at_ns: int
    target_direction: str
    target_azimuth_deg: float
    target_elevation_deg: float
    distance_m: float
    presentation_count: int
    profile: SpatializerProfile


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


def generate_focused_hrtf_trials(seed: int = FOCUSED_HRTF_SEED) -> tuple[FocusedHrtfTrial, ...]:
    """Return the fixed, public Resonance baseline consumed by the Unity harness."""
    generator = random.Random(seed)
    ordered: list[CalibrationDirection] = []
    previous: CalibrationDirection | None = None
    for _ in range(FOCUSED_HRTF_BLOCK_COUNT):
        block = list(DIRECTIONS)
        generator.shuffle(block)
        if previous is not None and block[0] == previous:
            block.append(block.pop(0))
        ordered.extend(block)
        previous = block[-1]
    return tuple(
        FocusedHrtfTrial(
            # Opaque IDs keep the target out of the nonvisual command/status channel.
            trial_id=f"hrtf-{ordinal:02d}",
            ordinal=ordinal,
            block_index=((ordinal - 1) // len(DIRECTIONS)) + 1,
            direction=direction,
            distance_m=FOCUSED_HRTF_DISTANCE_METERS,
            presentation_count=FOCUSED_HRTF_PRESENTATION_COUNT,
            profile=SpatializerProfile.RESONANCE_AUDIO,
        )
        for ordinal, direction in enumerate(ordered, start=1)
    )


def focused_hrtf_manifest() -> dict[str, object]:
    return {
        "schema": FOCUSED_HRTF_MANIFEST_SCHEMA,
        "trials": [
            {
                "trial_id": item.trial_id,
                "ordinal": item.ordinal,
                "block_index": item.block_index,
                "direction_label": item.direction.label,
                "azimuth_deg": item.direction.azimuth_deg,
                "elevation_deg": item.direction.elevation_deg,
                "distance_m": item.distance_m,
                "presentation_count": item.presentation_count,
                "profile": item.profile.value,
            }
            for item in generate_focused_hrtf_trials()
        ],
    }


def load_focused_hrtf_responses(
    path: Path,
    trials: Sequence[FocusedHrtfTrial] | None = None,
    *,
    require_complete: bool = False,
) -> tuple[FocusedHrtfResponse, ...]:
    """Load response-only NDJSON and reject drift, extra data, and reordering."""
    expected_trials = tuple(trials or generate_focused_hrtf_trials())
    expected_keys = {
        "schema",
        "session_id",
        "trial_id",
        "perceived_direction",
        "answered_at_ns",
        "target_direction",
        "target_azimuth_deg",
        "target_elevation_deg",
        "distance_m",
        "presentation_count",
        "profile",
    }
    responses: list[FocusedHrtfResponse] = []
    session_id: str | None = None

    def reject_constant(value: str) -> None:
        raise ValueError(f"non-finite JSON number is not allowed: {value}")

    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not raw_line.strip():
            raise ValueError(f"blank NDJSON line: {line_number}")
        try:
            payload = json.loads(raw_line, parse_constant=reject_constant)
        except (json.JSONDecodeError, ValueError) as error:
            raise ValueError(f"invalid NDJSON line: {line_number}") from error
        if not isinstance(payload, dict) or set(payload) != expected_keys:
            raise ValueError(f"response fields do not match schema on line: {line_number}")
        if len(responses) >= len(expected_trials):
            raise ValueError("response count exceeds focused HRTF manifest")
        trial = expected_trials[len(responses)]
        if payload["schema"] != FOCUSED_HRTF_RESPONSE_SCHEMA or payload["trial_id"] != trial.trial_id:
            raise ValueError(f"response order or schema mismatch on line: {line_number}")
        current_session = payload["session_id"]
        if not isinstance(current_session, str) or _SESSION_ID.fullmatch(current_session) is None:
            raise ValueError(f"invalid session ID on line: {line_number}")
        if session_id is None:
            session_id = current_session
        elif session_id != current_session:
            raise ValueError("all focused HRTF responses must belong to one session")
        perceived = payload["perceived_direction"]
        if not isinstance(perceived, str) or perceived not in {item.label for item in DIRECTIONS}:
            raise ValueError(f"invalid perceived direction on line: {line_number}")
        answered_at_ns = payload["answered_at_ns"]
        if type(answered_at_ns) is not int or answered_at_ns <= 0:
            raise ValueError(f"invalid response timestamp on line: {line_number}")
        numeric_matches = (
            payload["target_direction"] == trial.direction.label,
            type(payload["target_azimuth_deg"]) in (int, float)
            and float(payload["target_azimuth_deg"]) == trial.direction.azimuth_deg,
            type(payload["target_elevation_deg"]) in (int, float)
            and float(payload["target_elevation_deg"]) == trial.direction.elevation_deg,
            type(payload["distance_m"]) in (int, float) and float(payload["distance_m"]) == trial.distance_m,
            type(payload["presentation_count"]) is int and payload["presentation_count"] == trial.presentation_count,
            payload["profile"] == trial.profile.value,
        )
        if not all(numeric_matches):
            raise ValueError(f"response target metadata mismatch on line: {line_number}")
        responses.append(
            FocusedHrtfResponse(
                current_session,
                trial.trial_id,
                perceived,
                answered_at_ns,
                trial.direction.label,
                trial.direction.azimuth_deg,
                trial.direction.elevation_deg,
                trial.distance_m,
                trial.presentation_count,
                trial.profile,
            )
        )
    if not responses:
        raise ValueError("at least one focused HRTF response is required")
    if require_complete and len(responses) != len(expected_trials):
        raise ValueError(f"focused HRTF session is incomplete: {len(responses)}/{len(expected_trials)}")
    return tuple(responses)


def evaluate_focused_hrtf(
    trials: Sequence[FocusedHrtfTrial], responses: Sequence[FocusedHrtfResponse]
) -> CalibrationSummary:
    return evaluate(
        trials,
        tuple(CalibrationResponse(item.trial_id, item.perceived_direction, item.answered_at_ns) for item in responses),
    )


def _direction(label: str) -> CalibrationDirection:
    normalized = "_".join(label.strip().casefold().replace("-", " ").split())
    for direction in DIRECTIONS:
        if direction.label == normalized:
            return direction
    raise ValueError(f"unknown perceived direction: {label}")


def _wrapped_azimuth_error(left: float, right: float) -> float:
    return abs((left - right + 180.0) % 360.0 - 180.0)


def evaluate(
    trials: Sequence[CalibrationTrial | FocusedHrtfTrial], responses: Sequence[CalibrationResponse]
) -> CalibrationSummary:
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
    parser.add_argument(
        "--focused-hrtf-json", action="store_true", help="emit the fixed 24-trial Unity Resonance manifest"
    )
    parser.add_argument("--score-focused-hrtf", type=Path, metavar="RESPONSES.ndjson")
    args = parser.parse_args(argv)
    if sum((args.json, args.focused_hrtf_json, args.score_focused_hrtf is not None)) > 1:
        parser.error("choose only one output mode")
    if args.focused_hrtf_json:
        print(json.dumps(focused_hrtf_manifest(), indent=2, sort_keys=True))
        return 0
    if args.score_focused_hrtf is not None:
        focused_trials = generate_focused_hrtf_trials()
        focused_responses = load_focused_hrtf_responses(args.score_focused_hrtf, focused_trials, require_complete=True)
        print(json.dumps(asdict(evaluate_focused_hrtf(focused_trials, focused_responses)), indent=2, sort_keys=True))
        return 0
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
