<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Latency benchmarking

MPL reports measured stages and sample counts; it does not infer physical
glass-to-perception latency from a build or a synthetic loopback run.
“Near-real-time” is a design target. “Zero-touch” describes an interaction goal
after intentional activation. Neither means zero physical latency, perfect
availability, complete perception, or a safety guarantee.

## Implemented measurement

`LatencyTracker` stores local samples per named stage and uses nearest-rank
percentiles. It emits:

- p50 only with at least 2 samples;
- p95 only with at least 20 samples; and
- p99 only with at least 100 samples.

Unavailable percentiles are omitted rather than estimated. Values are rounded
to three decimal places. The tracker is process-local and does not upload data.

`scripts/repository/benchmark.py` performs ten warmup iterations and then times
three CPU-only synthetic host stages with `time.perf_counter_ns`:
`frame_validation`, `protobuf_serialize`, and `protobuf_deserialize`. It requires
100–100,000 measured iterations so p99 is defined.

Run it after bootstrap:

```bash
./scripts/bootstrap
./scripts/benchmark --iterations 100
```

The JSON output includes `iterations`, `stages`, `synthetic`, and
`timing_source`. It does not include camera exposure, image encoding, device
transport, network, queue wait inside a real cluster, model inference, cue
transport, audio buffering, haptic actuation, or human perception.

The Python synthetic gRPC demo separately records `preprocess`, `inference_rpc`,
and `cue_schedule` for 100 successful frames after exercising failures:

```bash
./scripts/demo
```

Those values include a loopback gRPC request to the deterministic CPU mock, not
a physical device or real model. Compare them only within controlled runs on
the same environment.

The perception-core benchmark measures one honestly named stage—the complete
headless synthetic Map/Morph/Move demo—and reports nearest-rank p50/p95/p99:

```bash
./scripts/perception-benchmark --iterations 100
```

It does not subdivide capture, depth inference, transport, FMOD buffering, or
haptic actuation because those operations do not occur in that process. Its
output includes only actual elapsed nanoseconds, Python `tracemalloc` peak bytes,
and resulting pipeline/entity/audio-voice counters. Memory is sampled in one
separate invocation so tracing overhead is not folded into latency percentiles.

## Physical benchmark model

For device work, record distinct monotonic timestamps rather than one aggregate
number:

| Stage | Start | End |
| --- | --- | --- |
| Capture | exposure/sample request | encoded bounded frame available |
| Glasses egress | frame available | final transport byte acknowledged |
| Host ingress/preprocess | first byte or complete frame | validation and route decision complete |
| Queue | admission | worker dispatch |
| Inference | worker start | typed result complete |
| Correlation/schedule | result received | eligible cue selected |
| Cue transport | cue send | glasses receipt |
| Presentation | glasses receipt | audio/haptic API completion or instrumented output |
| End-to-end | exposure/sample event | physical output observation |

Use one clock domain where possible. If device clocks differ, estimate offset
and drift with a documented synchronization protocol and report uncertainty.
Wall-clock timestamps are unsuitable for ordering or subsecond duration.

Physical output measurement should use an external photodiode/audio loopback,
microphone, accelerometer, or equivalent apparatus appropriate to the cue. An
API callback alone does not prove when the user received a sound or vibration.

## Test matrix

Measure at least:

- cold start, warmed steady state, reconnect, and post-failure recovery;
- median load and configured maximum queue pressure;
- each supported frame size/encoding and cue modality;
- local processing and each authorized network route;
- metered, impaired, disconnected, and recovered network states;
- CPU fallback and each real GPU/model configuration;
- power/thermal steady state, not only a brief burst; and
- accessibility modes including TalkBack or a Windows screen reader where they
  can affect speech/audio scheduling.

Record hardware, firmware, OS, app revision, model/artifact digest, service
configuration, frame policy, sample count, warmup, tool versions, power mode,
network topology, clock method, and raw-result location. Use only synthetic or
consented inputs, and keep raw captures out of the repository.

## Reporting rules

- Separate queue, execution, transport, and presentation latency.
- Report sample count with every percentile and include failures, cancellations,
  stale drops, and overload as rates rather than excluding them silently.
- Do not combine unlike devices, models, power modes, or routes into one
  percentile distribution.
- State whether results are synthetic, loopback, emulator, cross-target build,
  attached-device API timing, or externally measured physical output.
- Report the configured deadline and what happens when it is exceeded.
- Preserve units and use monotonic clocks for durations.
- Never label an average or single run as p95/p99.
- Do not publish private scenes, stable device identifiers, network addresses,
  or credentials with a report.

## Acceptance gates

No universal latency target is asserted by this repository. A product owner and
accessibility evaluation must establish per-cue budgets and degradation rules.
A release should fail when stale results render; cancellation produces a later
cue; overload becomes unbounded; timing lacks sufficient samples; clock
uncertainty dominates the result; a required accessibility mode materially
breaks the budget; or the report implies physical timing from synthetic
software stages.

See [ARCHITECTURE.md](ARCHITECTURE.md), [CUDA_CLUSTER.md](CUDA_CLUSTER.md), and
[`VALIDATION.md`](../VALIDATION.md).
