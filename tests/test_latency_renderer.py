# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import json

from conceptflow_mpl_host.latency import LatencyTracker
from conceptflow_mpl_host.renderer import InspectableCueRenderer


def test_percentiles_require_sufficient_samples() -> None:
    tracker = LatencyTracker()
    tracker.record("stage", 1.0)
    assert tracker.summary()["stage"] == {"count": 1}
    tracker.record("stage", 2.0)
    assert tracker.summary()["stage"] == {"count": 2, "p50_ms": 1.0}
    for value in range(3, 21):
        tracker.record("stage", float(value))
    assert "p95_ms" in tracker.summary()["stage"]
    assert "p99_ms" not in tracker.summary()["stage"]
    for value in range(21, 101):
        tracker.record("stage", float(value))
    assert tracker.summary()["stage"]["p99_ms"] == 99.0


def test_renderer_is_inspectable_and_has_no_frame_bytes(cue_factory) -> None:
    rendered = InspectableCueRenderer().render(cue_factory())
    payload = json.loads(rendered)
    assert payload["assistive_only"] is True
    assert payload["modalities"] == ["earcon", "speech", "haptic"]
    assert "frame_data" not in rendered
    assert "bytes" not in rendered
