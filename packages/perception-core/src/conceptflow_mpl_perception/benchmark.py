# SPDX-License-Identifier: MIT OR Apache-2.0
"""Honest timing for the executable headless Map/Morph/Move slice."""

from __future__ import annotations

import argparse
import json
import math
import tracemalloc
from collections.abc import Callable, Sequence
from dataclasses import asdict, dataclass
from time import perf_counter_ns

from .demo import run_demo

Runner = Callable[[], dict[str, object]]
Clock = Callable[[], int]


@dataclass(frozen=True, slots=True)
class PercentilesNs:
    p50: int
    p95: int
    p99: int


@dataclass(frozen=True, slots=True)
class BenchmarkReport:
    stage: str
    iterations: int
    total_ns: int
    peak_traced_bytes: int
    latency_ns: PercentilesNs
    counters: dict[str, int]

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


def _nearest_rank(samples: list[int], percentile: float) -> int:
    if not samples or not 0.0 < percentile <= 1.0:
        raise ValueError("samples must be nonempty and percentile must be within (0, 1]")
    ordered = sorted(samples)
    return ordered[max(0, math.ceil(percentile * len(ordered)) - 1)]


def benchmark(
    iterations: int,
    *,
    runner: Runner = run_demo,
    clock: Clock = perf_counter_ns,
) -> BenchmarkReport:
    """Measure only the complete CPU/headless demo; no stage is inferred."""
    if iterations <= 0:
        raise ValueError("iterations must be positive")
    samples: list[int] = []
    last_result: dict[str, object] = {}
    for _ in range(iterations):
        started = clock()
        last_result = runner()
        elapsed = clock() - started
        if elapsed < 0:
            raise ValueError("monotonic clock moved backwards")
        samples.append(elapsed)
    tracing_was_active = tracemalloc.is_tracing()
    if not tracing_was_active:
        tracemalloc.start()
    tracemalloc.reset_peak()
    try:
        last_result = runner()
        _, peak_traced_bytes = tracemalloc.get_traced_memory()
    finally:
        if not tracing_was_active:
            tracemalloc.stop()
    raw_counters = last_result.get("map_morph_move", {})
    counters = (
        {
            str(name): value
            for name, value in raw_counters.items()
            if isinstance(value, int) and not isinstance(value, bool)
        }
        if isinstance(raw_counters, dict)
        else {}
    )
    raw_audio = last_result.get("audio")
    if isinstance(raw_audio, list):
        counters["audio_voices"] = sum(
            len(voices)
            for item in raw_audio
            if isinstance(item, dict) and isinstance((voices := item.get("voices")), list)
        )
    return BenchmarkReport(
        stage="end_to_end_headless_map_morph_move",
        iterations=iterations,
        total_ns=sum(samples),
        peak_traced_bytes=peak_traced_bytes,
        latency_ns=PercentilesNs(
            p50=_nearest_rank(samples, 0.50),
            p95=_nearest_rank(samples, 0.95),
            p99=_nearest_rank(samples, 0.99),
        ),
        counters=counters,
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--iterations", type=int, default=100)
    args = parser.parse_args(argv)
    try:
        report = benchmark(args.iterations)
    except ValueError as error:
        parser.error(str(error))
    print(json.dumps(report.to_dict(), indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
