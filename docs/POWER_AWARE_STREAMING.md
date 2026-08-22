<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Power-aware glasses streaming

The power-efficient operating model is **subscribe, then push**. A paired host
opens a short, bounded stream lease over an authenticated control channel. The
Rokid client keeps unrequested sensors closed, starts only granted sources, and
pushes gate-approved samples while the lease is alive. Polling a dormant app
does not provide camera, IMU, or microphone data and is not a substitute for a
lease. Repeated application-level pull requests during capture add wakeups and
round trips without removing the sensor, encoding, or radio cost.

The Poco is the intended single glasses hub. Ubuntu or Windows consumers should
normally subscribe through the Poco instead of causing duplicate capture and
radio transmission from the glasses. Bluetooth Low Energy may later provide a
standby wake/pairing signal; it is not implemented as a high-rate media path.

## Implemented boundary

The v1 protobuf schema defines `StreamLeaseRequest`, `StreamLeaseGrant`,
`CameraFrameChunk`, `ImuBatch`, `MicrophoneChunk`, and
`SensorStreamEnvelope`. These messages are transport-neutral. They do not
authenticate a peer or implement signaling.

The directly sideloaded Rokid client implements:

- a single-owner monotonic `StreamLeaseController` with bounded duration,
  explicit microphone authorization, owner-checked close/renew, and no silent
  microphone-consent extension;
- physical Camera2 request cadence that begins near 3 FPS and moves toward a
  5 FPS ceiling only after the image-change gate detects material motion;
- darkness and blur rejection before packetization;
- nominal 100 Hz local IMU acquisition, semantic duplicate suppression,
  absolute-state refresh at most one second apart, and batches held no longer
  than 20 ms;
- 16 KiB camera chunks, bounded IMU batches, and bounded PCM chunks;
- a three-lane `SensorStreamOutbox`: one latest camera frame, one latest
  absolute IMU batch, and one latest microphone chunk, with round-robin drain
  and explicit clear-on-disconnect semantics. Global envelope sequence numbers
  are assigned at dequeue so lane interleaving cannot create false reordering;
- a diagnostic in which `AudioRecord` is created only for an explicit
  microphone-bearing lease; packet admission ends at the exact monotonic
  two-second boundary and recorder shutdown is initiated by the matching timer.

The Android host implements `GlassesStreamIngress`, a bounded receiver that
checks session/lease identity, envelope and sensor ordering, camera chunk
structure and SHA-256, IMU batch order, and microphone authorization. It keeps
only the latest unread camera frame. It deliberately uses the receiver's clock
for assembly timeout; raw monotonic values from two different Android devices
cannot be compared until a clock-offset model has been established.

The packetizer and ingress are executable and unit-tested. The production
wireless adapter, discovery/signaling, authenticated pairing, and key lifecycle
are not implemented yet. In particular, this repository does not currently
claim that `SensorStreamEnvelope` packets have crossed a physical WebRTC data
channel between the glasses and Poco.

## Data policy

| Stream | Default state | Active behavior | Queue policy |
| --- | --- | --- | --- |
| Camera | Camera closed | Native 1920×1080 JPEG where supported; 3 FPS relaxed and up to 5 FPS after material change; dark/blur gate | One frame being assembled and one latest unread frame; newer frame supersedes incomplete/old work |
| IMU | Sensor listener closed | Near-100 Hz local sampling; meaningful changes selected; maximum 20 ms batch delay; one-second absolute refresh | Bounded batch; old/out-of-order sequences rejected |
| Microphone | `AudioRecord` absent | 16 kHz mono PCM only inside explicit, separately bounded user-request window | Latest bounded chunk; no replay after expiry |

No raw camera, IMU, or microphone payload is written or logged by these paths.
Reconnect does not renew consent or replay expired sensor data.

## Power consequences

The camera dominates the observed data volume. A prior 18-frame hardware run
produced 19,417,222 JPEG bytes, approximately 1.08 MB per accepted frame. At
that observed size, 3 FPS is about 26.0 Mbit/s of JPEG payload and 5 FPS is
about 43.1 Mbit/s before framing and link overhead. Those are arithmetic from
one run, not sustained radio or battery measurements. In the final 2026-08-22
leased diagnostic, 12 source-gate frames contained 12,932,054 transient JPEG
bytes; 11 frames reached packetization before lease closure and produced
11,855,622 payload bytes in 726 chunks. Eight of 13 analyzed samples selected
the motion tier. This is a bounded functional measurement, not a sustained
radio, power, or thermal benchmark.

A 2026-08-23 repeat with the 3 FPS relaxed policy passed all three stream
presence checks and selected the motion tier for 12 of 13 analyzed frames, but
still analyzed only 13 frames during the complete 8.44-second cold-start lease.
That interval includes one-second 3A warm-up and JPEG-session creation, and the
older diagnostic did not expose a first-to-last-frame steady-state percentile.
Accordingly, the policy and its deterministic timing tests are validated, but
sustained 3–5 FPS physical throughput is not claimed.

Aggregate timing identified serialized Camera2 request latency—not analysis—as
the limiting stage: 433.8 ms p50 request-to-image, 2.4 ms p50 image acquisition,
40.2 ms p50 processing, and 6.5 ms p50 listener/packetization. The current
source therefore uses a single monotonic opportunity timer with a strict
three-request ceiling. Missed opportunities are counted and discarded rather
than replayed, and request tags are matched to image sensor timestamps. A
subsequent physical run analyzed 26 frames at 4.497 FPS and emitted 23 frames at
3.967 FPS over their respective first-to-last active spans. It recorded no
backpressure, superseded requests, unmatched images, capture failures, or late
callbacks. This is a bounded functional run, not a sustained power or thermal
result.

A final physical run with no motion-tier samples analyzed 18 frames at
3.058 FPS and emitted 17 at 2.885 FPS. It reached two outstanding requests and
recorded zero backpressure, supersession, unmatched images, capture failures,
or late callbacks; terminal telemetry reported zero outstanding requests.
Together the runs exercise the relaxed and motion-responsive paths; longer
thermal and energy tests remain open.

Native Camera2 JPEG avoids a CPU decode/re-encode pass. A future MediaCodec
video mode may reduce radio bytes, but its total sensor, encoder, decoder,
thermal, latency, and perception-quality cost must be measured on the actual
Rokid/Poco pair before it replaces the JPEG reference path.

The relaxed 3 FPS mode can take up to roughly 334 ms to observe motion that is
visible only in camera pixels. IMU motion can be delivered much faster, but it
cannot prove that an external object moved. This tradeoff is why the layer is
supplemental awareness, not a collision-avoidance or safety system.

## Intended transport split

- A reliable ordered WebRTC data channel is the target for bounded camera
  chunks and IMU packets when direct glasses-to-Poco measurements support it.
- A WebRTC audio track is the likely target for a user-authorized continuous
  microphone interval; the current PCM envelope exists for bounded tests and
  short command-like clips, not as a claim that raw PCM is radio-efficient.
- TLS gRPC remains the typed host/backend control and inference RPC boundary.
- Transport authentication must bind a lease to the authenticated peer outside
  protobuf. A client-populated ID is never authentication.

## Validation

Run the deterministic protocol and Android gates:

```bash
./scripts/generate
.venv/bin/python -m pytest tests/test_protocol.py -q
ANDROID_HOME=/usr/lib/android-sdk ./gradlew --no-daemon \
  :apps:rokid-client:testDebugUnitTest \
  :apps:android-host:testDebugUnitTest
```

With the directly connected development glasses and explicit permissions:

```bash
./scripts/rokid-control --serial "$ROKID_SERIAL" stream-test
```

The command is itself the explicit microphone request. It runs for eight
seconds, stops admitting microphone packets and initiates recorder shutdown at
two seconds, closes camera and IMU at the end, emits only aggregate counters,
and leaves the process stopped. See
[`VALIDATION.md`](../VALIDATION.md) for the exact observed run.

## Evidence

Official references checked 2026-08-22:

- [Android sensor overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [`SensorManager.registerListener`](https://developer.android.com/reference/android/hardware/SensorManager#registerListener(android.hardware.SensorEventListener,%20android.hardware.Sensor,%20int,%20int))
- [`AudioRecord`](https://developer.android.com/reference/android/media/AudioRecord)
- [Camera2 capture sessions and requests](https://developer.android.com/media/camera/camera2/capture-sessions-requests)
- [WebRTC data channels](https://webrtc.org/getting-started/data-channels)

The pages were reachable during implementation. The local hardware run, not
the documentation alone, is the evidence for this unit's observed sensor
behavior.
