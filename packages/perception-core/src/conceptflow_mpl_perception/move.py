# SPDX-License-Identifier: MIT OR Apache-2.0
"""Move-stage motion restraint, freshness, hysteresis, and suppression."""

from __future__ import annotations

import math
from dataclasses import dataclass

from .model import clamp01


@dataclass(frozen=True, slots=True)
class MotionSample:
    timestamp_ns: int
    user_translation_mps: float
    torso_angular_dps: float
    head_angular_dps: float
    external_radial_approach_mps: float

    def __post_init__(self) -> None:
        values = (
            self.user_translation_mps,
            self.torso_angular_dps,
            self.head_angular_dps,
            self.external_radial_approach_mps,
        )
        if self.timestamp_ns < 0 or any(not math.isfinite(value) or value < 0.0 for value in values):
            raise ValueError("motion values must be finite and nonnegative")


@dataclass(frozen=True, slots=True)
class MotionGateConfig:
    minimum_stationary_activation: float = 0.12
    walking_full_mps: float = 1.2
    torso_turn_full_dps: float = 90.0
    external_approach_full_mps: float = 1.0
    attack: float = 0.65
    release: float = 0.18

    def __post_init__(self) -> None:
        if not 0.0 <= self.minimum_stationary_activation <= 1.0:
            raise ValueError("stationary activation must be normalized")
        if min(self.walking_full_mps, self.torso_turn_full_dps, self.external_approach_full_mps) <= 0.0:
            raise ValueError("motion thresholds must be positive")
        if not 0.0 < self.attack <= 1.0 or not 0.0 < self.release <= 1.0:
            raise ValueError("smoothing coefficients must be normalized and nonzero")


class MotionGate:
    """Stateful nonlinear activation; head turns alone never imply translation."""

    def __init__(self, config: MotionGateConfig | None = None) -> None:
        self.config = config or MotionGateConfig()
        self._activation: dict[str, float] = {}

    def activation(self, track_id: str, motion: MotionSample, geometry_approach_mps: float = 0.0) -> float:
        if not track_id or not math.isfinite(geometry_approach_mps):
            raise ValueError("track and approach velocity must be valid")
        translation = clamp01(motion.user_translation_mps / self.config.walking_full_mps)
        torso_turn = clamp01(motion.torso_angular_dps / self.config.torso_turn_full_dps)
        external = clamp01(
            max(motion.external_radial_approach_mps, geometry_approach_mps, 0.0)
            / self.config.external_approach_full_mps
        )
        # Head angular velocity is deliberately excluded from whole-body activation.
        intentional_motion = 1.0 - (1.0 - translation * translation) * (1.0 - torso_turn * torso_turn)
        target = max(self.config.minimum_stationary_activation, intentional_motion, external)
        previous = self._activation.get(track_id, self.config.minimum_stationary_activation)
        coefficient = self.config.attack if target > previous else self.config.release
        current = previous + coefficient * (target - previous)
        self._activation[track_id] = clamp01(current)
        return self._activation[track_id]

    def forget(self, track_id: str) -> None:
        self._activation.pop(track_id, None)
