# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import pytest

from conceptflow_mpl_cluster.demo import run_demo


@pytest.mark.asyncio
async def test_full_synthetic_vertical_slice() -> None:
    report = await run_demo()
    events = report["events"]
    assert events["reconnect"] is True
    assert events["cancellation"] is True
    assert events["timeout"] is True
    assert events["stale_rejection"] is True
    assert events["error_propagation"] is True
    assert events["backpressure"] > 0
    assert events["full_slice"] is True
    assert report["rendered_cue"]["assistive_only"] is True
    assert report["synthetic"] is True
