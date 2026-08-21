<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# CUDA cluster

The cluster layer has two related but currently separate parts: an asynchronous
Python gRPC service with pluggable workers, and C++20 native scheduling
primitives with optional CUDA-aware build configuration. The repository does
not contain a real perception model, CUDA kernel, Python/native bridge, or model
weights.

## Python service

`conceptflow_mpl_cluster.server.run` loads `ClusterConfig`, discovers configured
devices, creates deterministic mock workers, starts a bounded `WorkerPool`, and
serves the typed v1 gRPC API. It refuses production startup because production
requires a registered non-synthetic worker implementation.

`discover_devices` handles three policies:

- `cpu`: return the CPU fallback without probing CUDA;
- `auto`: query `nvidia-smi` when available, honor `CUDA_VISIBLE_DEVICES`, and
  fall back to CPU when allowed; or
- `cuda`: use discovered CUDA devices or fail when CPU fallback is disabled.

Discovery reports capability metadata. It does not prove a usable CUDA runtime,
allocate GPU memory, launch a kernel, or execute inference.

`WorkerPool` provides bounded asynchronous admission, round-robin session lanes,
round-robin healthy-worker selection, caller cancellation, execution timeout,
retry on another eligible worker, consecutive-failure thresholds, and health
snapshots. Each pending session receives one dequeue turn before another queued
item from the same session; active work is not preempted. The included
`DeterministicMockWorker` returns synthetic observations and provenance.

After cheap structural validation and negotiated-session admission, the service
fully decodes PNG/JPEG input with Pillow under dimension, pixel, and
decoded-byte bounds. This places decode work behind session, rate, and
in-flight limits. Android and .NET header checks remain structural preflight
only.

Warmup in the current repository is measurement warmup: the synthetic benchmark
discards ten initial iterations before recording samples. The service has no
model/worker warmup hook yet, which a real worker must add and validate.

## Native component

`services/cuda-cluster/native/include/conceptflow/native/scheduler.hpp` exposes:

- `WorkerRegistry` for capability/model/memory/health/load/latency-aware
  selection;
- healthy, degraded, draining, and failed worker states;
- `BoundedRequestQueue` with reject-newest or drop-oldest overflow policy; and
- `RequestHandle` states for queued, dispatched, cancellation requested,
  cancelled, completed, dropped, and rejected work.

The native demo uses synthetic worker descriptors. The CMake
`CONCEPTFLOW_ENABLE_CUDA` option verifies compiler/toolkit discovery and adds a
compile definition. No `.cu` source is present.

## CPU build, test, and demo

Prerequisites are CMake 3.24 or newer, Ninja, a C++20 compiler, and pthread
support. From the repository root:

```bash
cmake -S services/cuda-cluster/native \
  -B services/cuda-cluster/native/build-local \
  -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_TESTING=ON \
  -DCONCEPTFLOW_ENABLE_CUDA=OFF
cmake --build services/cuda-cluster/native/build-local --parallel
ctest --test-dir services/cuda-cluster/native/build-local --output-on-failure
./services/cuda-cluster/native/build-local/conceptflow_native_demo
```

Warnings are errors: GCC/Clang builds use `-Wall -Wextra -Wpedantic
-Wconversion -Wsign-conversion -Wshadow -Werror`; MSVC uses `/W4 /WX
/permissive-`.

## Sanitizer check

For a GCC/Clang host with compatible AddressSanitizer and UndefinedBehaviorSanitizer
runtimes:

```bash
cmake -S services/cuda-cluster/native \
  -B services/cuda-cluster/native/build-sanitized \
  -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug \
  -DBUILD_TESTING=ON \
  -DCONCEPTFLOW_ENABLE_CUDA=OFF \
  -DCMAKE_CXX_FLAGS="-fsanitize=address,undefined -fno-omit-frame-pointer" \
  -DCMAKE_EXE_LINKER_FLAGS="-fsanitize=address,undefined"
cmake --build services/cuda-cluster/native/build-sanitized --parallel
ctest --test-dir services/cuda-cluster/native/build-sanitized --output-on-failure
```

Sanitizer availability and options are compiler/platform-specific. A passing
local run does not replace long-running concurrency, fuzz, or GPU checks.

## CUDA-aware build

Prerequisites are a supported NVIDIA driver, CUDA toolkit, `nvcc`, CMake, Ninja,
and a compatible host compiler. Inspect the environment first:

```bash
nvidia-smi
nvcc --version
cmake -S services/cuda-cluster/native \
  -B services/cuda-cluster/native/build-cuda \
  -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_TESTING=ON \
  -DCONCEPTFLOW_ENABLE_CUDA=ON
cmake --build services/cuda-cluster/native/build-cuda --parallel
ctest --test-dir services/cuda-cluster/native/build-cuda --output-on-failure
```

A successful command proves that CMake found a CUDA compiler/toolkit and that
the existing C++ targets compiled with the CUDA build definition. It does not
prove GPU enumeration by the built binary, device memory allocation, a CUDA
kernel launch, model loading, inference correctness, throughput, or thermal
stability.

## Service configuration

Runtime environment keys are listed in `config/development.env.example`,
`config/test.env.example`, and `config/production.env.example`. Production
requires TLS and disables CPU fallback in the example, but the current server
also deliberately refuses production until a non-synthetic worker is
registered.

| Variable | Meaning |
| --- | --- |
| `MPL_PROFILE` | `development`, `test`, or `production`; production rejects plaintext and the bundled synthetic worker. |
| `MPL_BIND_HOST`, `MPL_BIND_PORT` | Listener address and port; plaintext is restricted to loopback. |
| `MPL_INSECURE` | Enables plaintext only for loopback development/test use. |
| `MPL_TLS_CERTIFICATE_FILE`, `MPL_TLS_PRIVATE_KEY_FILE` | TLS material paths required when `MPL_INSECURE=false`; never commit the files. |
| `MPL_MAX_RECEIVE_BYTES`, `MPL_MAX_SEND_BYTES`, `MPL_MAX_FRAME_BYTES` | Serialized message and frame-payload limits. |
| `MPL_QUEUE_CAPACITY`, `MPL_RUNNER_COUNT` | Bounded queue capacity and asynchronous runner count. |
| `MPL_WORKER_TIMEOUT_MS` | Maximum worker execution time per admitted request. |
| `MPL_SHUTDOWN_TIMEOUT_MS` | Bounded graceful-shutdown wait before pending work is cancelled. |
| `MPL_WORKER_FAILURE_THRESHOLD` | Consecutive failures before a worker becomes unhealthy. |
| `MPL_DEVICE` | `auto`, `cuda`, or `cpu` discovery policy. |
| `MPL_ALLOW_CPU_FALLBACK` | Allows `auto`/`cuda` discovery to fall back to the CPU adapter. |

To run the safe CPU development service after bootstrap:

```bash
set -a
. config/development.env.example
set +a
MPL_DEVICE=cpu .venv/bin/conceptflow-mpl-cluster
```

That process listens on the loopback address and port defined by the example.
It is a synthetic worker service, not a production endpoint.

## Real-worker integration requirements

A future worker must declare stable worker/model/artifact provenance; validate
device capability and memory; accept only prevalidated bounded frames; expose
cooperative cancellation; honor effective deadlines; isolate failures; avoid
logging payloads; remain observable through health; define model warmup and
shutdown; and undergo real GPU correctness, memory, concurrency, overload, and
thermal tests.

Any Python/native bridge must keep gRPC lifecycle ownership explicit, map
native request states to protocol errors, and ensure cancellation cannot turn
into a late rendered result.

## Verified status

The supplied record reports a strict Release native build, the CTest-run native
executable’s 15 cases, the native demo, sanitizer checks, and a CUDA-aware
CMake configure/build on CUDA 12.0 as passing. The Python baseline separately
verified device-discovery behavior, pool health, backpressure, timeout,
cancellation, and worker-failure recovery with synthetic workers.

No real GPU kernel, inference engine, model, or weight executed. See
[`VALIDATION.md`](../VALIDATION.md) and
[LATENCY_BENCHMARKING.md](LATENCY_BENCHMARKING.md).
