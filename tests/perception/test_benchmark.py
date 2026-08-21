# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import pytest

from conceptflow_mpl_perception.benchmark import _nearest_rank, benchmark


def test_nearest_rank_percentiles_are_ordered() -> None:
    samples = [100, 10, 90, 20, 80, 30, 70, 40, 60, 50]
    assert _nearest_rank(samples, 0.50) == 50
    assert _nearest_rank(samples, 0.95) == 100
    assert _nearest_rank(samples, 0.99) == 100


@pytest.mark.parametrize("iterations", [0, -1])
def test_benchmark_rejects_nonpositive_iterations(iterations: int) -> None:
    with pytest.raises(ValueError, match="positive"):
        benchmark(iterations)


def test_benchmark_emits_truthful_schema_with_deterministic_clock() -> None:
    clock_values = iter([10, 110, 200, 500, 700, 1_600])

    def clock() -> int:
        return next(clock_values)

    def runner() -> dict[str, object]:
        return {"map_morph_move": {"map_entities": 1, "label": "not-a-counter"}}

    report = benchmark(3, runner=runner, clock=clock)
    payload = report.to_dict()
    assert payload["stage"] == "end_to_end_headless_map_morph_move"
    assert payload["iterations"] == 3
    assert payload["total_ns"] == 1_300
    assert isinstance(payload["peak_traced_bytes"], int)
    assert payload["peak_traced_bytes"] >= 0
    assert payload["latency_ns"] == {"p50": 300, "p95": 900, "p99": 900}
    assert payload["counters"] == {"map_entities": 1}
