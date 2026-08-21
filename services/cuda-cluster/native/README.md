<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Native worker primitives

This directory contains a small C++20 scheduling component that complements
the Python service in the parent directory. The Python service continues to
own protobuf/gRPC, asynchronous request execution, timeouts, and its existing
worker pool. This native library does not replace or modify that logic. It
provides reusable, dependency-free primitives for a future native model worker:

- typed device capabilities, model and memory constraints;
- deterministic least-loaded, latency-tier-aware worker selection;
- explicit healthy, degraded, draining, and failed worker states;
- bounded queue admission with reject-newest or drop-oldest backpressure; and
- observable queued, dispatched, cancellation, completion, drop, and rejection
  request states.

The demo uses synthetic device metadata and performs no inference or hardware
discovery. The CUDA option only validates that a usable CUDA compiler and
toolkit are available and marks the build for future adapters. It does not add
kernels, model weights, or claim that a GPU was exercised.

## CPU build, test, and demo

From this directory:

```bash
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build
ctest --test-dir build --output-on-failure
./build/conceptflow_native_demo
```

The executable writes inspectable JSON Lines to standard output and exits
nonzero if a synthetic invariant fails.

## Optional CUDA-aware configure

```bash
cmake -S . -B build-cuda -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCONCEPTFLOW_ENABLE_CUDA=ON
cmake --build build-cuda
```

Configuration fails with a descriptive error when the CUDA compiler or toolkit
cannot be resolved. Real CUDA device discovery, kernel execution, and model
testing require a self-hosted runner with the intended NVIDIA hardware and
driver/toolkit combination; CPU CI must not report those checks as passing.

## Integration boundary

A future Python/native adapter can translate the Python worker's device and
request metadata into `WorkerDescriptor` and `SelectionRequest`, then use the
returned stable worker identifier to dispatch through the existing service.
Such an adapter should keep transport cancellation and execution timeout
ownership in Python while forwarding cancellation to a dispatched native
worker through `RequestHandle::cancellation_requested()`.
