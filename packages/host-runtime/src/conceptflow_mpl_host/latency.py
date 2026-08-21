# SPDX-License-Identifier: MIT OR Apache-2.0
"""Local stage latency summaries with honest percentile sample gates."""

from __future__ import annotations

from collections import defaultdict
import math


class LatencyTracker:
    _THRESHOLDS = (("p50_ms", 0.50, 2), ("p95_ms", 0.95, 20), ("p99_ms", 0.99, 100))

    def __init__(self) -> None:
        self._samples: dict[str, list[float]] = defaultdict(list)

    def record(self, stage: str, milliseconds: float) -> None:
        if not stage or not math.isfinite(milliseconds) or milliseconds < 0:
            raise ValueError("latency sample is invalid")
        self._samples[stage].append(milliseconds)

    @staticmethod
    def _nearest_rank(values: list[float], fraction: float) -> float:
        index = max(0, math.ceil(fraction * len(values)) - 1)
        return values[index]

    def summary(self) -> dict[str, dict[str, float | int]]:
        report: dict[str, dict[str, float | int]] = {}
        for stage, samples in sorted(self._samples.items()):
            ordered = sorted(samples)
            row: dict[str, float | int] = {"count": len(ordered)}
            for label, fraction, minimum in self._THRESHOLDS:
                if len(ordered) >= minimum:
                    row[label] = round(self._nearest_rank(ordered, fraction), 3)
            report[stage] = row
        return report
