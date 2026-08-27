<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Protected Rokid sensing contract

This contract began as the pre-refactor inventory captured on 2026-08-25. Its
machine-readable v1 companion remains the historical record of that earlier
1920x1080 acquisition path:
[`docs/contracts/rokid-sensor-contract.v1.json`](contracts/rokid-sensor-contract.v1.json).
The continuous-camera acquisition amendment below was made on 2026-08-27; it
does not change the protected gate, transform, permission, microphone, IMU, or
gesture semantics.

## Protected boundary

The compatibility boundary is:

```text
physical sensor
  -> existing Android capture API
  -> existing validated gate
  -> existing validated camera transform, where applicable
  -> timestamped publication
```

Normal production publication now uses bounded in-memory queues and the
authenticated live link. This document freezes the pre-refactor sensing
semantics; references below to the persistence implementation describe the
retained diagnostic/legacy implementation, not the active hot path.

## Camera

`Camera2FrameSource` requires the device-native 648x648 `YUV_420_888` stream.
One Camera2 session contains both the known-good 640x480 headless YUV preview
and exact 648x648 scheduled-capture outputs. The preview repeats through the
complete session: its first second settles the standard auto-exposure and
white-balance loops, then it keeps the vendor stream alive between scheduled
captures. Its listener immediately acquires and closes the latest preview
image on an independently owned drain thread. Exact-image processing and the
Camera2 control/timer callbacks also use separate owned threads, so conversion
cannot starve preview fences or delay the opportunity timer. All three callback
queues are cancelled during teardown, but their loopers remain alive while the
capture session, camera device, and both readers close; only then are the three
threads quit and joined. A terminal `CameraDevice` callback closes its callback
camera before entering shared teardown. Both two-slot readers remain owned
until that teardown. If exact
648x648 YUV is unavailable, capture fails closed rather than silently reverting
to JPEG or a larger mode.

Here, continuous capture means an ongoing sequence of individually scheduled
`TEMPLATE_STILL_CAPTURE` requests for the exact 648x648 output alongside the
low-resolution repeating preview. The preview request is only a HAL/3A
keepalive and never enters the scheduled-capture accounting. One monotonic
timer owns the 3/5 FPS physical opportunities; a full three-request pipeline
drops and counts that opportunity rather than queuing it for replay. Runtime
snapshots expose submitted, backpressured, outstanding, superseded, unmatched,
failed, and late-callback counts.

`AdaptiveYuv420Processor` analyzes a 90x90 luma image for the square source,
within the existing bounded 160x90 analysis gate. It bilinearly samples the Y
plane directly and expands its declared BT.601 limited range; U/V are not read
for gating. The protected thresholds and state are unchanged. A frame is dark
when mean luma is below 18 or the fraction at luma 16 or below exceeds 0.92. A
frame is blurry when Laplacian variance is below 60. Motion is the maximum of
an exposure-compensated mean residual, changed-pixel fraction, and an 8x6
peak-cell term; material motion begins at 0.06 and holds the fast tier for 1.5
seconds. The working defaults are 3 FPS relaxed and 5 FPS under material
motion. Gate timestamps are strictly increasing.

The YUV planes are borrowed only while the acquired Android `Image` is open;
row stride, pixel stride, and buffer position are checked before synchronous
processing, and `Image.close()` runs on success or failure. No per-frame Y/U/V
heap copies are retained. After admission, a packaged arm64 integer converter
reads those direct buffers and applies the same deterministic fixed-point
sampling and BT.601 limited-range conversion to exactly 640x640 RGB8 (scale
80/81, with no crop). It uses byte-safe scalar accesses that permit safe
compiler auto-vectorization without alignment assumptions. The Kotlin
implementation remains the byte-exact reference and fail-safe fallback for a
non-direct buffer or unavailable native library. Production-size C++ and Kotlin
tests share a golden output hash in addition to the patterned per-byte parity
test. Aggregate diagnostics count native versus total RGB conversions. Camera
intrinsics are transformed to the same 640x640 geometry before publication.

Camera `Image.timestamp`/`SENSOR_TIMESTAMP` is retained and normalized only to
guarantee strict increase. The device's
`SENSOR_INFO_TIMESTAMP_SOURCE` still requires an explicit physical audit; the
contract does not infer it.

Camera2 disconnect, capture failure, and the documented in-use,
maximum-in-use, device, service, and `CameraAccessException` disconnected/error
conditions request bounded camera-only recovery. `LiveLinkCaptureController`
keeps the authenticated lease and the existing pose, microphone, touch, and
IMU state alive while attempting at most three replacements, 500 ms apart.
The replacement uses the same session-owned `MonotonicFrameSequence`, which
preserves both frame-ID and timestamp monotonicity across the source boundary.
The host can therefore accept frame 159 after frame 158 within the same live
session. A disabled camera and unknown error codes remain terminal, as does
exhausting the restart bound. This recovery policy is covered by deterministic
JVM tests; the Camera HAL failure and recovery cycle still requires a physical
repeat.

## Microphone

Microphone capture is user-gated and off by default. Rokid Node opens
`AudioRecord` only when camera/IMU streaming is active, permission is present,
the wearer performs the active-node forward-swipe plus quick-single-tap
sequence, and Android Node grants the exact authenticated sublease. The
backward-swipe plus quick-single-tap sequence stops locally before waiting for
network acknowledgement. A sublease is at most ten seconds.

The stream is 16 kHz, mono, signed 16-bit little-endian PCM from
`MediaRecorder.AudioSource.MIC`. The audio thread uses blocking reads and
Android audio priority. Current chunks are timestamped with
`SystemClock.elapsedRealtimeNanos()` after the read completes; this limitation
must remain explicit when estimating the beginning of a block. The legacy
diagnostic path wraps each admitted chunk in a WAV file.

## IMU

`SensorManagerPoseSource` requests unbatched 10,000-microsecond delivery for a
game rotation vector, falling back to a rotation vector, plus gyroscope and
linear acceleration. It emits one HEAD-frame pose when a rotation event has
both component samples at or before its hardware timestamp. Translation is
zero/unavailable; it is not inferred from the IMU.

`ImuTransmissionGate` preserves strictly increasing sequence and timestamp
order, rejects invalid/non-finite data, and selects a sample when orientation
changes by 0.5 degrees, angular velocity changes by 0.02 rad/s, linear
acceleration changes by 0.05 m/s2, accuracy changes, or one second has elapsed.
Selected samples form batches of at most eight and wait at most 20 ms.

## Touch and gestures

`RokidInputAccessibilityService` observes but never consumes platform key
events. It accepts only the physical non-virtual `ROKID,PSOC-TP-R` keyboard
source and the exact validated key-code/scan-code pairs in the machine-readable
contract. The state machine retains press/release checks, cancellation and
long-press rejection, timeout behavior, repeated-swipe collapse, device
identity continuity, and the YodaOS-specific double-tap completion behavior.

The currently exposed meanings are activate, sleep, microphone start, and
microphone stop. These meanings and their command authorization remain intact.
Raw ordered touch records are now carried as distinct bounded sensor messages.
This transport does not replace or reinterpret the existing command state
machine.

`KeyEvent.eventTime` is in the uptime-millisecond domain, while camera, IMU,
audio, and live-link synchronization use elapsed-realtime nanoseconds. Merely
multiplying touch uptime by one million does not prove a common clock across
suspend. The transport refactor will capture a same-process uptime/elapsed
anchor at event receipt and publish a converted elapsed-realtime timestamp,
while continuing to run gesture timing against the original event time.

## Existing downstream dependencies

- `LiveLinkCaptureController` owns camera, IMU, and on-demand microphone
  lifecycle and applies the sensor gates before either handoff.
- Under the diagnostic flag, `RokidCaptureSpool` converts admitted camera frames to 640x640,
  writes JPG/WAV artifacts, stores inline IMU records, and rewrites a canonical
  JSON manifest plus protobuf recovery sidecar.
- `RokidLiveLinkClient` and `PocoLiveLinkServer` already provide two ordered
  mutual-TLS private-WLAN lanes, authenticated session/lease binding,
  capability/lease negotiation, liveness, lane-local sequence validation,
  repeated clock synchronization, reconnect support, and bounded protobuf
  records.
- `GlassesStreamIngress` reconstructs bounded camera chunks and accepts typed
  IMU and microphone messages. `LiveMachineVisionController` owns the current
  Android perception path.
- `SensorTimeline`, `PerceptionBus`, the Android static bridge and the Unity C#
  decoder now connect compact timestamped world state and ordered touch events.
  They deliberately do not expose raw camera/audio buffers or perform network
  and inference work on Unity's main thread.

## Frozen regression gate

Before this inventory was added, the Rokid Node, Android live-transport, and
Android Node JVM suites completed successfully in one Gradle run. The test
classes listed in the JSON inventory cover the camera gate and geometry,
camera lifecycle, audio lifecycle, IMU readiness/gating, touch grammar,
packetization, bounded queues, framing, synchronization, and Android ingress.
They are the minimum compatibility gate for every streaming change.
