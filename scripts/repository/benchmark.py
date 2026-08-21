#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Benchmark deterministic synthetic host stages and emit honest percentiles."""

from __future__ import annotations

import argparse
import json
import time

from conceptflow.mpl.v1 import perception_pb2 as pb
from conceptflow_mpl_cluster.demo import synthetic_frame
from conceptflow_mpl_host.latency import LatencyTracker
from conceptflow_mpl_host.preprocessing import FramePreprocessor


def run(iterations: int) -> dict[str, object]:
    tracker = LatencyTracker()
    preprocessor = FramePreprocessor(max_frame_bytes=65_536)
    for frame_id in range(1, iterations + 11):
        frame = synthetic_frame(frame_id, capture_ns=time.monotonic_ns())

        started = time.perf_counter_ns()
        preprocessor.validate(frame)
        preprocess_ms = (time.perf_counter_ns() - started) / 1_000_000

        started = time.perf_counter_ns()
        payload = frame.SerializeToString(deterministic=True)
        serialize_ms = (time.perf_counter_ns() - started) / 1_000_000

        started = time.perf_counter_ns()
        parsed = pb.FramePayload.FromString(payload)
        deserialize_ms = (time.perf_counter_ns() - started) / 1_000_000
        if parsed.frame_id != frame_id or not parsed.synthetic:
            raise RuntimeError("protobuf round trip changed the synthetic frame")

        if frame_id > 10:
            tracker.record("frame_validation", preprocess_ms)
            tracker.record("protobuf_serialize", serialize_ms)
            tracker.record("protobuf_deserialize", deserialize_ms)

    summary = tracker.summary()
    for stage, values in summary.items():
        missing = {"p50_ms", "p95_ms", "p99_ms"} - values.keys()
        if missing:
            raise RuntimeError(f"{stage} lacks percentiles: {sorted(missing)}")
    return {
        "iterations": iterations,
        "stages": summary,
        "synthetic": True,
        "timing_source": "time.perf_counter_ns",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--iterations", type=int, default=200)
    args = parser.parse_args()
    if args.iterations < 100 or args.iterations > 100_000:
        parser.error("--iterations must be between 100 and 100000 so p99 is meaningful")
    print(json.dumps(run(args.iterations), sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
