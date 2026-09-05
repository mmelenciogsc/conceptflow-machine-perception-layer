<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Power-aware glasses streaming

The production operating model is **lease, gate, transform, then bounded RAM
publication**. A paired host opens a bounded stream lease over an authenticated
control channel. Rokid Node keeps unrequested sensors closed, starts only
granted sources, and publishes only gate-approved samples to modality-specific
queues. Android Node receives them over the private-WLAN mutual-TLS data plane;
it does not poll files. The former spool/pull route remains available only under
an explicit, disabled-by-default diagnostic flag. Polling or connecting to a
dormant app does not start sensors and is not a substitute for a lease.

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
- exact device-native 648×648 YUV_420_888 acquisition, failing closed if that mode is
  unavailable;
- darkness and blur rejection before packetization;
- nominal 100 Hz local IMU acquisition, semantic duplicate suppression,
  absolute-state refresh at most one second apart, and batches held no longer
  than 20 ms;
- deterministic post-gate conversion to tightly packed 640×640 planar I420 with
  explicit YUV plane, row-stride, and pixel-stride handling; no JPEG BLOB enters
  the continuous path;
- a battery governor that refuses a new camera/radio epoch below 50 percent and
  disarms an active epoch at or below 25 percent or on unhealthy battery state;
- one-second optional battery gauge telemetry carried with existing aggregate
  queue telemetry, with explicit units and protobuf presence for unavailable
  gauges;
- bounded in-memory IMU batches, PCM16 microphone blocks and ordered raw touch
  events with modality-specific overflow policies;
- explicit versioned framing, capabilities, clock synchronization, liveness,
  authenticated close and aggregate queue telemetry on two mutual-TLS lanes;
- a diagnostic in which `AudioRecord` is created only for an explicit
  microphone-bearing lease; packet admission ends at the exact monotonic
  two-second boundary and recorder shutdown is initiated by the matching timer.

The Android host implements `GlassesStreamIngress`, `SensorTimeline`, a
serialized latest-frame perception executor, and `PerceptionBus`. The ingress
checks session/lease identity, per-lane ordering, camera chunk structure and
SHA-256, IMU order, microphone authorization and raw touch order. The timeline
normalizes glasses timestamps through repeated minimum-RTT clock probes rather
than comparing independent device clocks directly.

The publisher, protocol reader/writer, ingress, timeline and compact
Unity-facing bus are executable and unit-tested. The private-WLAN TLS 1.3 link
supplies mutual authentication and bounded control/camera framing. Matching
30-second physical measurements of the RAM and legacy routes are below.

## Data policy

| Stream | Default state | Active behavior | Queue policy |
| --- | --- | --- | --- |
| Camera | Camera closed | Native 648×648 YUV gate; 3 FPS relaxed and up to 5 FPS after material change; dark/blur gate; packed 640×640 I420 output | Two two-slot readers: low-resolution preview is drained immediately, one capture image is borrowed synchronously, and transport retains one pending frame; replace stale and count |
| IMU | Sensor listener closed | Near-100 Hz local sampling; meaningful changes selected; maximum 20 ms batch delay; one-second absolute refresh | Eight batches; discard oldest obsolete batch and count gaps |
| Microphone | `AudioRecord` absent | 16 kHz mono PCM only inside explicit, separately bounded user-request window | Eight PCM blocks; reject newest on overflow and count the continuity gap |
| Touch | Observer inactive with Rokid Node | Existing non-consuming raw key and gesture semantics | 64 ordered events; reject overflow as a serious diagnostic |

No raw camera, IMU, microphone or touch payload is logged or written to storage
by the production route. Reconnect clears pending data, does not renew
microphone consent and cannot replay events from an older authenticated
session. Explicit diagnostic spooling is bounded, app-private and never a
production synchronization primitive.

## Power consequences

### Current guarded transport

The latest production route requests the lowest fixed camera frame range
reported by the tested glasses (`[15,15]`) for both the HAL keepalive preview
and scheduled image output. A bounded partial CPU wake lease remains active for
the sensor epoch. The high-performance Wi-Fi lock is used only for rendezvous
and reconnect, then released after authentication so steady-state streaming
uses Android's platform-default radio policy. The earlier full-low-latency lock
is not used. The capture/radio epoch is sampled every five seconds. Known
battery below 50 percent rejects a new start; known battery at or below 25
percent or unhealthy battery state disarms the current epoch, clears persisted
arming, and prevents the recovery observer from recreating a low-battery reboot
loop. Charging status does not bypass those thresholds because the glasses can
still discharge while attached to a weak source.

By default, each admitted production frame is a self-contained 640×640 planar I420 payload
of 614,400 bytes. That is 14.7456 Mbit/s at 3 FPS or 24.576 Mbit/s at 5 FPS
before framing and TLS overhead—exactly half the corresponding packed RGB8
payload rate. Android Node validates and converts I420 on the Poco. Independent
frames preserve the existing latest-frame replacement and reconnect behavior;
there is no inter-frame decoder state to recover.

Protocol 1.5 now provides an explicit `camera_transport=avc_intra` experiment.
It encodes the identical protected post-gate I420 output with a hardware codec,
requires SPS+PPS+IDR in every access unit, and hardware-decodes on Android Node
before the existing timeline and QNN interfaces. All-intra is intentionally
less compression-efficient than a predictive GOP, but it retains the camera
queue's drop-oldest behavior and deterministic reconnect recovery. The parser
default and provisioning-script default remain I420. No deployment should
select AVC merely because it sends fewer bytes: untethered matched-workload
energy, thermal, visual-fidelity, and model-output comparisons remain required.

The power governor, fixed 15 FPS requests, adaptive Wi-Fi-lock policy, and I420
transport are implemented. They reduce known risk; they do not establish a
battery-life claim from a single test.

On 2026-09-05, a cable-disconnected interval bracketed by 358 one-second power
samples ran for approximately 5 minutes 58 seconds. Camera reconstruction
advanced by 1,315 frames, received IMU samples by 27,710, and completed HTP
inference by 592 of 702 new attempts. Android deliberately replaced 52 stale
pending perception frames; the transport reported no link interruption and no
camera, IMU, audio, or touch queue loss. Boot count remained 150. Reported
battery moved from 100 to 85 percent and ended at 35.0 degrees Celsius. After
reconnection, charging resumed, both streams advanced again, and the runtimes
were stopped with CameraService reporting no active camera client. This is one
directionally improved run under nonidentical conditions, not a calibrated
energy or operating-time result.

A content-free, paced MediaCodec diagnostic also exercised 30 synthetic
640×640 I420 frames at 5 FPS on the glasses. Qualcomm hardware AVC completed
30/30 frames in 6.011 seconds with 22.28/24.90 ms p50/p95 enqueue-to-output
latency; hardware HEVC completed 30/30 in 6.007 seconds with 22.44/25.42 ms.
The encoded byte counts came from synthetic content and different configured
bitrates, so they are not a real-scene compression comparison. Production
transport remained I420 at that point.

On 2026-09-05, the subsequent feature-flagged paired AVC-intra test physically
ran `c2.qti.avc.encoder` on Rokid Node and `c2.qti.avc.decoder` on Android Node.
One forced-indoor lease reconstructed 63 frames with zero stale camera rejects,
zero transport queue drops, and zero AVC decode failures. The unchanged QNN
pipeline completed 26 of 29 admitted inference attempts using the configured
indoor depth profile; segmentation/depth graph p95 values were 152.0/74.9 ms.
This proves compatible transport, decode, correlation, and downstream model
consumption—not better battery life. Both devices were cabled, so the run is
invalid for energy comparison. The next power gate is matched untethered I420
and AVC-intra trials after charge/temperature stabilization.

The first matched-gate I420 baseline then completed a continuously monitored
600-second untethered window with the indoor depth profile forced. From the
exact start/end host snapshots, camera reconstruction advanced by 2,137
frames, accepted IMU input advanced by 33,414 samples, QNN completed 1,254 of
1,255 new attempts, and the camera lane sent 1,316,946,405 bytes. Link
interruptions remained zero. Two already-observed camera freshness drops did
not increase during the window; IMU, audio, and touch loss counters remained
zero. Reported battery moved from 98 percent at the first disconnected sample
to 65 percent at 600 seconds while temperature moved from 34.5 to 35.0 C. On
cable reconnection the gauge subsequently settled at 42 percent and 34.5 C;
that delayed reading is retained separately and is not substituted for the
timed endpoint. Boot count remained 150 and CameraService reported no active
client after the runtimes were stopped. This is the I420 half of the A/B gate,
not evidence that AVC is more efficient; the AVC-intra run requires a fresh
matched charge and temperature start.

The corresponding first-pass AVC-intra candidate then ran for the same 600
seconds from a thermally stabilized 100-percent start with the same forced
indoor profile. Camera reconstruction and hardware decode advanced by 2,202
frames with zero decode failures. Received IMU advanced by 40,266 samples. The
QNN completion counter advanced by 1,196 while its attempt counter advanced by
1,195 because one request was already in flight at the start snapshot. Camera
lane traffic advanced by 53,193,655 bytes, or approximately 0.709 Mbit/s over
the timed window, compared with 1,316,946,405 bytes and approximately 17.559
Mbit/s for I420. That is a 95.96-percent byte reduction for these two real-scene
runs. Link interruptions and all queue-loss counters remained zero. AVC decode
p95 was 28.6 ms at the timed endpoint. Reported battery moved from 100 to 71
percent and temperature remained 34.5 C. After cable reconnection its delayed
gauge settled at 52 percent and 34.0 C, with a session minimum of 50 percent;
as with the I420 run, that reading is recorded separately. Boot count remained
150 and CameraService was idle after shutdown.

This initial pair favors AVC-intra for radio traffic and shows a directionally
smaller timed percentage drop (29 rather than 33 points), without a functional
failure or material graph-latency change. It does not establish calibrated
energy savings: the fuel gauge corrected after both reconnects, route motion
was human-repeated rather than mechanically identical, and the order was not
counterbalanced. I420 therefore remains the configured default. The next gate
is an alternated repeat beginning with AVC, followed by I420 after recharge and
thermal stabilization, before a production-default decision.

The counterbalanced repeat began with AVC-intra after the glasses held 100
percent at 32.5 C while sensor work was stopped. Its exact 600-second snapshots
advanced camera/decode by 2,178 frames, received IMU by 37,109 samples, QNN
completion and attempt counters by 1,206 each, and camera-lane traffic by
53,507,678 bytes (approximately 0.713 Mbit/s). Decoder failures and link
interruptions stayed zero. Five IMU queue drops occurred during cabled startup
and did not increase during the timed window; all other queue-loss counters
remained zero. Battery again moved from 100 to 71 percent, while temperature
moved from 34.0 to 34.5 C. The delayed post-reconnect gauge read 67 percent
with a session minimum of 64 percent; boot count remained 150. The 0.59-percent
camera-byte difference and identical timed percentage endpoint make the two
AVC trials internally repeatable at this screening level. The concluding
counterbalanced I420 run remains required.

The concluding counterbalanced I420 run then completed from a 100-percent,
33.5-C cabled state. Its exact disconnected snapshots began at 98 percent and
35.5 C and ended at 63 percent and 37.0 C. Camera reconstruction advanced by
2,202 frames, received IMU by 39,156 samples, QNN completion and attempt
counters by 1,219 each, and camera-lane traffic by 1,357,657,628 bytes
(approximately 18.102 Mbit/s). Link interruptions and all queue-loss counters
remained zero. The delayed post-reconnect gauge read 52 percent and 35.0 C with
a session minimum of 49 percent; boot count remained 150 and CameraService was
idle after shutdown.

The completed two-run-per-mode screening summary is:

| Transport | Mean frames / 600 s | Mean camera bytes | Mean wire rate | Mean timed percentage drop | Failures or timed-run queue growth |
| --- | ---: | ---: | ---: | ---: | --- |
| I420 | 2,169.5 | 1,337,302,016.5 | 17.831 Mbit/s | 34 points | None |
| AVC-intra | 2,190 | 53,350,666.5 | 0.711 Mbit/s | 29 points | None |

Across the counterbalanced pair, AVC-intra reduced mean camera-wire bytes by
96.01 percent (25.07 times smaller), delivered 0.94 percent more frames, and
used five fewer reported battery-percentage points. The percentage result is
directional rather than calibrated energy: this Android gauge is nonlinear and
corrected materially after reconnection. Session-wide capture-to-receive and
end-to-end p95 averages were approximately 10.5 and 24.9 ms higher with AVC;
segmentation and depth graph p95 values were effectively unchanged. The two
AVC timed endpoints reported 28.6 and 34.5 ms decode p95 and zero failures.

This evidence supported AVC-intra as a production candidate with I420 retained
as the mandatory negotiated fallback. It was not sufficient to change the
default because battery and throughput measurements cannot prove
compression-induced perception fidelity or runtime codec recovery.

The next paired gate ran on 2026-09-05 using a deterministic, content-free
640×640 I420 fixture. Rokid's `c2.qti.avc.encoder` produced the same 16,640-byte
independently decodable access unit in two repetitions, and Poco's
`c2.qti.avc.decoder` reconstructed it at 43.076720 dB luma PSNR, 60.790482 dB
chroma PSNR, and 0.733869 overall byte MAE. Repeated QNN inference on the
identical uncompressed reference was bit-stable at the reported precision
(worst cosine 1.0; normalized RMSE 0.0), so codec comparisons were not confused
by graph nondeterminism. Both metric-depth graphs remained close: indoor
cosine/normalized-RMSE was 0.999985/0.005460 and outdoor was
0.999983/0.013385. The YOLOE tensors did not pass the deliberately explicit
gate: detection cosine/normalized-RMSE was 0.866488/0.532932 and prototype
cosine/normalized-RMSE was 0.983710/0.182759. This synthetic fixture does not
claim real-scene task accuracy, but the failed model-output criterion is enough
to keep I420 as the production default. The gate must be repeated on a
privacy-safe representative semantic fixture before testing a higher-quality
AVC profile or reconsidering that decision; thresholds were not weakened to
manufacture a pass.

Automatic recovery was then physically fault-injected during a live AVC lease.
The exact final implementation counted exactly one decode failure and one
fallback, synchronously closed only the failing active authenticated attempt,
retained its listener, and granted I420 on the automatic Rokid reconnect.
Camera reconstruction advanced to 380 total frames while the AVC-decoded
counter remained fixed at 65, proving 315 subsequent I420 frames. IMU reception
reached 5,566 samples and QNN execution reached 234 of 236 attempts. There was
no second decode failure and the reported camera, IMU, audio, and touch
transport queues had zero loss. Both private device configurations were
restored to I420, Android Node was stopped, Rokid capture was disabled, and
CameraService had no active client after the test.

### Representative semantic profile gate

The next attached-device gate swept 1.5, 3, 6, 9, and 12 Mbit/s over three
transient, non-personal 640×640 I420 fixtures: two locally generated indoor
scenes and one content-free person/desk diagram. The sweep used the physical
Rokid encoder, physical Poco decoder, and the provisioned YOLOE-26S plus both
Depth Anything QNN graphs. It compared postprocessed YOLO instances by class,
box overlap, precision, and recall because the model's 300 post-NMS rows form
an unordered set; prototype and depth tensors retained the strict numeric
fidelity gate.

At 6 Mbit/s and above, the bedroom retained 4/4 instances with 0.965239 mean
box IoU and the diagram retained 6/6 with 0.998107 mean box IoU. The kitchen
retained only 3/4 instances at every tested rate. For every fixture, 6, 9, and
12 Mbit/s produced identical access-unit sizes and decoded pixels. Higher
nominal rates therefore did not recover the lost semantic result on this
encoder. No AVC profile qualified across the fixture suite, I420 remains the
production default, and the later untethered energy/thermal promotion gate was
not run because semantic fidelity is an earlier mandatory gate. Full aggregate
evidence is in [`../VALIDATION.md`](../VALIDATION.md).

Run the same bounded profile gate with a consented or public-safe image using:

```bash
./scripts/avc-semantic-sweep \
  --fixture /path/to/consented-or-public-safe-image \
  --rokid-serial "$ROKID_SERIAL" \
  --android-serial "$POCO_SERIAL"
```

The source remains outside the repository. Its packed-I420 derivative and all
device-side probe files are temporary and erased after each run.

### Matched untethered I420 versus AVC-intra gate

Run the two modes as separate trials; never change the encoding inside a live
lease. For each trial, use the same forced depth profile, route, movement
script, duration, starting charge, and starting temperature band. Provision
with `scripts/android-live-link-pair --camera-transport i420` for the baseline
or `--camera-transport avc-intra` for the candidate, start Android Node, then
start the Rokid ten-minute soak immediately before disconnecting its cable.
Retain only the content-free status snapshots from the start and after
reconnection.

Compare elapsed time, charge-counter change when the gauge exposes it, battery
percentage as a lower-quality fallback, temperature change, emitted and
reconstructed frames, reported camera-lane bytes/messages, capture-to-receive
latency, AVC decode latency/failures, QNN successes, intentional stale replacement, and
all queue/link loss counters. Reject AVC if either run reboots, loses a
modality, accumulates frames, fails decoding, materially degrades model output,
or violates the same thermal and latency envelope. One run per mode is only a
screening result; repeat alternated trials before changing the default.

Run the content-free fidelity gate only with Android Node stopped, the private
QNN runtime and all three model graphs already provisioned, and both debug
devices attached:

```bash
./scripts/avc-fidelity-probe \
  --rokid-serial "$ROKID_SERIAL" \
  --android-serial "$POCO_SERIAL"
```

The debug-only providers create one bounded synthetic AVC access unit in
app-private no-backup storage, transfer it through ADB, erase it after reading,
and report only aggregate pixel/model comparisons. No camera image, model
tensor, or model weight is logged or copied into the repository.

The camera dominates the observed data volume. The measurements in the next
four paragraphs predate the 648×648 acquisition change and describe the former
1920×1080 JPEG source; they are retained as historical evidence, not as a size,
throughput, power, or thermal estimate for the current mode. A prior 18-frame
hardware run
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
40.2 ms p50 processing, and 6.5 ms p50 listener/packetization. The source
therefore uses a single monotonic opportunity timer with a strict
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

A 2026-08-27 exact 648×648 JPEG run proved that reducing BLOB dimensions did
not solve the device-wide allocation problem. It delivered 358 frames over
112.371 seconds (3.177 FPS overall; 293 relaxed intervals and 64 fast
intervals), while whole-device DMA-BUF rose from about 33,264 K after stop to
629,364 K active: roughly +596,100 K. App PSS was only 51,430 K. Camera
metadata exposes exact 648×648 output for both format 33/JPEG and format
35/YUV_420_888, so continuous capture now selects format 35 explicitly and
reserves JPEG for a future exclusive on-demand mode. These measurements motivate
the format change.

The first physical exact-YUV build reduced active whole-device DMA-BUF to
109,972 K versus 33,264 K after stop, an approximately 76,708 K delta. It
delivered only 70 frames over 52.309 seconds (1.319 FPS), however, because the
vendor HAL stopped and restarted streaming around each one-shot request when
the preview session had already been closed. The current implementation keeps
the known-good 640×480 preview repeating in the same two-output session as the
scheduled 648×648 captures. The measured memory reduction belongs to the
one-output build; the combined-session DMA-BUF and sustained 3/5 FPS behavior
remain physical validation gates.

The next combined-session build removed per-frame session churn and retained
one 640×480 preview plus one 648×648 scheduled output. Active DMA-BUF measured
262,528 K versus 29,360 K idle, an approximately 233,168 K delta and about 61%
less delta than the prior exact-JPEG run. It emitted 89 frames over 58.596
seconds, approximately 1.50 FPS. CamX simultaneously reported repeated preview
sink-fence errors because the pure-Kotlin RGB loop and preview drain shared a
handler. The current build gives preview drain, exact-image processing, and
Camera2 control/timing separate owned threads. It also replaced per-pixel
coordinate division and twelve plane reads with cached coordinates, direct-Y
gate analysis, and fixed two-row sampling caches. The resulting physical run
held exactly 3.000 FPS (170 analyzed frames over 56.335 seconds), used one
camera open/close, and produced no preview fence errors. Processor time was
243.3 ms p50, 258.8 ms p95, and 364.0 ms maximum; request-to-image was 312.1 ms
p50. Active DMA-BUF was 234,136 K versus 29,360 K idle, an approximately
204,776 K delta. This passes the relaxed tier but cannot sustain the 200 ms
motion-tier interval.

The native candidate therefore moves only the admitted-frame RGB conversion
into a packaged arm64 integer JNI library. It reads the same borrowed direct
planes, applies the byte-exact fixed-point bilinear and limited-range BT.601
contract, and writes the one required RGB8 byte array. The Kotlin implementation
remains the deterministic reference and fallback for non-direct buffers or a
missing native library. Aggregate stream diagnostics report total RGB
conversions and native RGB conversions so a physical result cannot silently
credit the fallback. Acceptance requires native conversions for every emitted
frame, processor p95 comfortably below 180 ms, sustained motion-tier behavior
near 5 FPS, and no regression in preview fences, DMA-BUF, or thermal behavior.

The exact native combined-session build was then exercised physically from
2026-08-27 14:39:15.166 through 14:40:15.484. Before a Camera2 device failure,
it held 3.000 FPS, submitted 159 scheduled requests, and delivered 158 frames;
every RGB conversion was native. Processor time was 50 ms p50 and 67 ms p95,
while request-to-image latency was 316 ms p50 and 348 ms p95. There were zero
backpressured opportunities, superseded requests, or capture failures before
the device failure. This passes the processor-latency and relaxed-cadence gates,
but it does not prove sustained 5 FPS or long-run stability: after roughly one
minute the vendor Camera HAL reported request, result, and buffer errors and
Camera2 delivered serious device error 3.

The recovery candidate classifies Camera2 disconnect, in-use, maximum-in-use,
device, service, and capture-failure signals as restartable. Teardown closes the
camera, session, and readers before quitting their owned loopers. The production
controller then performs at most three camera-only restart attempts with a
500 ms delay, without replacing the authenticated lease or stopping IMU,
microphone, or touch handling. Replacement sources share one monotonic camera
frame sequence, so a source following delivered frame 158 begins at 159 rather
than being rejected by the host high-water mark. Disabled-camera and unknown
error codes remain terminal. These recovery semantics and the 158-to-159 host
acceptance are deterministic-test results; a post-failure physical restart has
not yet been validated.

The exact repaired APK then completed a clean 600-second physical run from
2026-08-27 15:20:10 through 15:30:11. It observed and queued 1,759 camera
frames, all through the native RGB converter, and observed 59,088 IMU samples,
selecting 29,388 samples in 15,902 batches. Camera, IMU, microphone, and touch
transport drops were all zero; the run ended by its time limit with authenticated
request/write/drain/ack closure. Relaxed camera throughput was approximately
2.93 FPS. Camera request-to-image p50/p95/p99 was 349.4/392.9/445.0 ms;
gate/resize p50/p95/p99 was 54.9/67.3/74.9 ms; acquisition p95 was 1.62 ms; and
listener p95 was 11.1 ms. Two subsequent 30-second sessions each delivered 85
camera frames and also closed cleanly, with no dead-handler warning. No camera
failure occurred in these runs, so the new camera-only restart remains covered
by deterministic injection tests rather than a forced physical HAL-failure
claim. Sustained 5 FPS motion-tier behavior also remains an explicit hardware
validation gate.

The current 648×648 source borrows the three YUV planes synchronously under the
Android `Image` lifetime, runs the 90×90 gate, and allocates a 640×640 I420
buffer only after admission. Direct limited-range Y analysis avoids chroma work
for the gate. Cached deterministic fixed-point sampling performs the
aspect-preserving 80/81 downscale; no crop is needed for this square source.
The normal route publishes I420 directly and avoids the diagnostic path's RGB
conversion, JPEG encode, and manifest durability. A future MediaCodec video mode
may further reduce radio bytes, but its total sensor, encoder, decoder, thermal,
latency, recovery, and perception-quality cost must be measured on the actual
Rokid/Poco pair before it replaces the independent-I420 live route.

The relaxed 3 FPS mode can take up to roughly 334 ms to observe motion that is
visible only in camera pixels. IMU motion can be delivered much faster, but it
cannot prove that an external object moved. This tradeoff is why the layer is
supplemental awareness, not a collision-avoidance or safety system.

## Transport split

- The implemented private-WLAN mutual-TLS realtime lane carries negotiation,
  selected IMU, on-demand PCM blocks, touch, clock sync and control. A distinct
  authenticated lane carries chunked complete camera frames.
- On-demand microphone is bounded PCM16 streaming, not a continuous audio track
  and not a claim that uncompressed PCM is the most radio-efficient codec.
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
cmake -S apps/rokid-client/src/main/cpp -B /tmp/conceptflow-yuv-native \
  -G Ninja -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=ON
cmake --build /tmp/conceptflow-yuv-native --parallel
ctest --test-dir /tmp/conceptflow-yuv-native --output-on-failure
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

### Measured handoff comparison

A matched 30-second hardware comparison on 2026-08-25 found:

| Route | Camera/IMU result | Persistent-write behavior |
| --- | --- | --- |
| RAM production route | 76 camera frames reconstructed; 762 IMU batches / 1,463 samples received; no transport queue drops | no routine sensor files or manifests |
| Legacy diagnostic spool | 80 camera records and 692 IMU records created, but no camera reached Android before shutdown and 717 of 793 pulled poses were stale | 6.84 MB artifacts plus about 82.16 MB of manifest/recovery-state rewrites; 47 camera and 302 IMU records remained backlogged |

The comparison establishes why the spool is not the normal route. It is a
single bounded engineering run, not a battery-life claim. The diagnostic files
from that run were deleted from app-private storage after aggregate metrics were
retained. See [Rokid pull spool](ROKID_PULL_SPOOL.md) for the isolated fallback.

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
