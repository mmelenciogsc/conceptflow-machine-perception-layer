<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Direct non-display Rokid AI Glasses Style development

The target is **Rokid AI Glasses Style (Non-Display)**. It has no wearer-facing
display. The Android-reported framebuffer is a compatibility surface inside the
system and must never be treated as a HUD, user interface, or acceptance target.

The installed application label is **Machine Perception Layer, Rokid Node**.
CONCEPTFlow installs this standalone Android APK directly on the glasses through
the magnetic 5-pin data cable and authorized ADB. The runtime does not use Hi
Rokid, CXR-L, CXR-S, Glasses SDK/Phone SDK, client secrets, or a phone-mediated
installer. The Poco application is a separate CONCEPTFlow host. The repository
contains both the physically exercised Ubuntu-loopback ADB-reverse path and a
bounded direct private-WLAN mutual-TLS camera+IMU path physically exercised
with the Poco in two consecutive no-reinstall runs on 2026-08-23.

## Evidence and confidence

Sources checked on 2026-08-21:

- Rokid's official
  [AI Glasses Style product page](https://global.rokid.com/products/rokid-ai-glasses-style)
  identifies this product as “Non-Display” and lists a 12 MP first-person
  camera, four-microphone array, dual open-ear speakers, Wi-Fi 6, Bluetooth
  5.3, and 32 GB storage. These are product specifications, not proof that
  every Android API is exposed to sideloaded applications.
- Rokid's official
  [prototype/developer cable page](https://global.rokid.com/products/rokid-glasses-prototype)
  states that its developer cable enables ADB debugging. Live inspection of
  this Style unit establishes that its seated 5-pin cable also exposes an
  authorized ADB transport.
- Android's official [ADB documentation](https://developer.android.com/tools/adb)
  defines serial-selected installation and shell commands; its
  [hardware-device setup guide](https://developer.android.com/studio/run/device)
  documents Ubuntu `plugdev` membership and packaged udev rules; its
  [command-line build guide](https://developer.android.com/build/building-cmdline)
  defines Gradle `assembleDebug`; and its
  [Camera2 guide](https://developer.android.com/media/camera/camera2) and
  [capture-session/request guide](https://developer.android.com/media/camera/camera2/capture-sessions-requests)
  define the camera API used here. Android's
  [motion-sensor guide](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion)
  defines the rotation-vector, gyroscope, and linear-acceleration sources.
- The Linux kernel's official
  [USB error-code documentation](https://docs.kernel.org/driver-api/usb/error-codes.html)
  defines `-EPROTO` as a protocol-level failure such as no response or a
  low-level signaling error; it is not an ADB authorization result.
- The independent `aimindseye/rokid-ai-glasses` research repository documents
  the same non-display device class in its pinned
  [architecture notes](https://github.com/aimindseye/rokid-ai-glasses/blob/c2483fae87d637ebf12bd1bf643365d80bf2438d/docs/architecture/non-display-system-architecture.md),
  [ADB notes](https://github.com/aimindseye/rokid-ai-glasses/blob/c2483fae87d637ebf12bd1bf643365d80bf2438d/docs/developer/adb-and-developer-mode/README.md),
  and
  [on-glasses application notes](https://github.com/aimindseye/rokid-ai-glasses/blob/c2483fae87d637ebf12bd1bf643365d80bf2438d/docs/developer/on-glasses-app/README.md).
  These are useful corroboration, not official API guarantees.

Display-glasses SDKs and community HUD/touchpad guidance are intentionally
excluded: they describe a different product category.

## Verified attached-device facts

Read-only inspection on 2026-08-21 reported manufacturer `Rokid`, model
`RG-glasses`, product/device `glasses`, Android 12/API 32, and YodaOS Sprite
assist service 0.3.5. Direct authorized ADB works over the magnetic cable. The
system reports a 480×640 Android display object in state `OFF`; this is not a
physical display and has no product interaction role.

The vendor Sprite assist service held camera 0 during the first inspection.
CONCEPTFlow did not disable or modify that system service. Later explicit tests
acquired camera 0 through Camera2 and concurrently received game-rotation,
gyroscope/linear-acceleration, and 16-kHz mono PCM microphone data through
standard Android APIs. A later exact-APK trace sent one frame with a
timestamp-matched HEAD pose to the loopback gRPC service and dispatched the
returned synthetic cue through the glasses audio path. The bounded results are
recorded in `VALIDATION.md`. The device exposes no Android vibrator service;
microphone-array beam selection, acoustic quality, spatial localization, and
human perception remain separate empirical tests. The physical button and
right-arm touch surface mappings below were measured directly with Linux
`getevent`.

Live Camera2 characteristics report an exact 1920×1080 JPEG output on this
unit, so the normal path transmits that native size without a resize/re-encode.
The camera also reports 16.67 ms minimum frame duration and 13.48 ms JPEG stall
duration for that stream; these are capability metadata, not a sustained
throughput claim. The game-rotation, gyroscope, and linear-acceleration sensors
all accept a 10,000-microsecond sampling request. A bounded 2026-08-22 run
observed 98.8 fused orientation samples/s and a 10.1 ms maximum orientation
gap. Android sensor periods are requests, so runtime measurements remain the
authority.

## Non-display application model

`apps/rokid-client` has no launcher entry or visual layout.
`RokidRuntimeService` is a private bound Android service. Explicit development
commands enter through a nonvisual `RokidCommandActivity`, protected by the
shell-held `android.permission.DUMP` permission. The activity has no content
view, controls, text, launcher entry, or user interaction. It owns the service
binding because the observed YodaOS build blocks third-party background service
and broadcast starts. Android still requires an activity window on its internal
compatibility surface; no physical display exists and no visual interface is
part of the product. The activity keeps that logical surface awake while
capture runs because Android 12 otherwise classifies the non-display process as
background and CameraService rejects access.

Live-link identity initialization, start, and explicit live-link stop are also
runtime-gated by the app's debuggable flag. A release build rejects those
commands before binding the private service. This is defense in depth beyond
the exported activity's `DUMP` permission; it is not a product consent surface.

The implemented hardware boundaries are:

- `Camera2FrameSource`: bounded latest-only JPEG capture and monotonic frame IDs;
- `SensorManagerPoseSource`: unbatched nominal 100 Hz game-rotation snapshots,
  each carrying the latest three-axis gyroscope and gravity-compensated linear
  acceleration values plus their source timestamps and sensor accuracy;
- `LiveLinkCaptureController`: a bounded authenticated camera+IMU session that
  starts producers only after the negotiated session/lease is ready, tears them
  down before reconnect, and never opens a microphone source;
- `AudioRecordInputSource`: bounded 16-kHz mono PCM input with monotonic chunk
  IDs and no persistence;
- `InspectableCueRenderer`: stale/duplicate/older-cue rejection;
- `GrpcRemotePerceptionClient`: v1 capability negotiation, ephemeral session
  identity, bounded frame submission, strict result correlation, cancellation,
  and deadlines;
- Android stereo audio and optional vibrator output; and
- deterministic, package-scoped commands for capture and development cues.

## Adaptive camera gate

`Camera2FrameSource` first runs a bounded 640×480 headless YUV preview for one
second so the standard Camera2 auto-exposure and white-balance loops can
settle. It then closes that preview session and creates a JPEG-only session;
this sequence is required because the tested vendor HAL stalls JPEG output
when preview and JPEG requests overlap. The client begins physical JPEG capture
near 3 FPS and raises it toward a 5 FPS ceiling after material pixel change.
The delay is budgeted from capture-request time so JPEG processing is not added
on top of the intended period. A single monotonic opportunity timer permits at
most three outstanding requests, discards rather than replays backpressured
opportunities, and associates request tags with image sensor timestamps. Every
image is processed in memory and discarded
after the current decision. Capture-size selection prefers an exact 1920×1080
source. If it is unavailable, it chooses the closest aspect-compatible source
and aspect-fits it inside 1920×1080 using one uniform scale; it never crops,
stretches, or upscales. A 4:3 source such as 4032×3024 therefore becomes
1440×1080 rather than distorted 1920×1080.

The gate analyzes a bounded luma thumbnail (maximum 160×90):

- mean luma below 18 or more than 92% of pixels at luma 16 or below is rejected
  as dark;
- four-neighbour Laplacian variance below 60 is rejected as blurred or lacking
  usable spatial detail; and
- an exposure-compensated temporal residual combines whole-frame change,
  changed-pixel fraction, and the strongest cell in an 8×6 grid. The local
  cell term preserves small moving actions that would be diluted by a highly
  similar full frame.

Usable relaxed imagery is emitted at approximately 3 FPS. Material temporal
change raises the target to approximately 5 FPS for a 1.5-second hysteresis
window, which is extended by continued change. The 5 FPS value is a ceiling,
not a guarantee: exposure time, Camera2 scheduling, processing load, and
thermal policy can reduce the observed rate. Thresholds are deterministic
engineering defaults and still require representative indoor/outdoor
calibration; they are not evidence that a rejected frame contains nothing
important.

The current gRPC request advertises the 1920×1080, 2 MiB, 5 FPS maximum. It
still carries one timestamp-matched HEAD pose per emitted image. Independently,
the stream packetizer can select meaningful samples from nominal 100 Hz IMU
input, bound a batch to 20 ms, and send an absolute refresh at least once per
second. The matching Poco ingress validates those typed batches. The direct
private-WLAN mutual-TLS transport and end-to-end pose delivery were physically
exercised in the two bounded runs recorded below. Unity/FMOD listener
interpolation remains unvalidated, so this is not a claim of live wireless
spatial-audio tracking.

There is no screen, launcher, or wearer-facing visual state. The right arm does
have a capacitive touch surface; it reports firmware-recognized key events, not
raw coordinates. Development state is inspectable through safe ADB status/log
commands. A product release must expose capture state and stop control through
the accessible phone host, distinctive nonvisual feedback, and verified
physical controls; ADB is not a user control.

## Verified physical input mappings

The following mappings were captured on the attached Style unit while an
authorized ADB session ran `getevent -lt`. They are observations for this
firmware, not a cross-version Rokid API guarantee.

| Physical action | Linux input source | Observed key sequence |
| --- | --- | --- |
| Top-right physical button near the lens, short press | `qpnp_pon` (`event0`) | `KEY_MENU` down/up |
| Touch-surface single tap, while worn | `ROKID,PSOC-TP-R` (`event1`) | `KEY_DASHBOARD` down/up, then `KEY_PROG1` down/up after about 478 ms |
| Touch-surface long press, while worn | `ROKID,PSOC-TP-R` (`event1`) | `KEY_DASHBOARD` down/up, then `KEY_PROG1` held for about 766 ms |
| Touch-surface double tap, while worn | `ROKID,PSOC-TP-R` (`event1`) | two `KEY_DASHBOARD` pulses, then `KEY_YELLOW` down/up |
| Touch-surface swipe toward lenses, while worn | `ROKID,PSOC-TP-R` (`event1`) | `KEY_DASHBOARD` down/up, then `KEY_VOLUMEUP` down/up |
| Touch-surface swipe toward ear, while worn | `ROKID,PSOC-TP-R` (`event1`) | `KEY_DASHBOARD` down/up, then one or more `KEY_VOLUMEDOWN` pulses |

The isolated off-head tap and swipe trials produced no input events, while worn
trials produced the listed key sequences. This is consistent with wear gating
on the observed unit. A repeated/longer swipe can emit multiple volume steps.
`KEY_DASHBOARD` appeared as a consistent gesture preamble/contact event.
Applications should treat the terminal key as the candidate semantic action
and must test whether Android or a YodaOS system component consumes it before
application dispatch. Raw `getevent` visibility alone does not prove that an
ordinary app can intercept every system key.

## Cable and authorization

1. Use the magnetic **5-pin data/development cable**, not a charge-only lead.
2. Enable USB debugging through the device-supported setup path and authorize
   this Ubuntu host. Preserve that authorization because the glasses have no
   display on which to operate a conventional prompt.
3. Confirm state `device`:

   ```bash
   adb devices -l
   ```

4. With multiple attached devices, pass the glasses serial on every command.
   The scripts refuse an ambiguous target and validate Rokid product properties.

On the tested Ubuntu host, the direct USB-C path repeatedly failed before USB
descriptor enumeration with Linux error `-71` (`EPROTO`). Because the device
was absent from `lsusb`, neither udev permissions nor restarting ADB could fix
that state. No custom udev rule was created: Ubuntu's packaged Android rule
already matched vendor `18d1`, and the user was already in `plugdev`.

Cycling **Glasses ADB debugging** off and back on through Hi Rokid and using its
supported **Restart** action reinitialized device-side state, but did not repair
the failing physical USB-C path. Moving the same 5-pin data cable to a USB-A
port through a data-capable adapter enumerated immediately at 480 Mbit/s and
remained authorized after temporary host compatibility settings were restored.
Treat that as a verified recovery for this host/cable combination, not a
universal adapter requirement. Hi Rokid was used only to enable/recover the
device's development setting; it is not part of the CONCEPTFlow runtime,
installation path, protocol, or application architecture.

The repository does not bypass Android Debug Bridge authorization.

## Build and direct sideload

```bash
./gradlew --no-daemon --dependency-verification strict \
  :apps:rokid-client:testDebugUnitTest \
  :apps:rokid-client:assembleDebug
read -r -p "Rokid ADB serial: " ROKID_SERIAL
./scripts/rokid-install --serial "$ROKID_SERIAL" --inspect-only
./scripts/rokid-install --serial "$ROKID_SERIAL" --no-build
```

Installation never starts capture. On a controlled development unit whose lack
of display prevents an operable runtime permission dialog, camera and
microphone permissions are explicit and separately logged actions:

```bash
./scripts/rokid-install --serial "$ROKID_SERIAL" --no-build \
  --grant-camera --grant-microphone
```

Each option grants only its named APK permission. Neither option starts a
sensor.

## Nonvisual development controls

```bash
./scripts/rokid-control --serial "$ROKID_SERIAL" status
./scripts/rokid-control --serial "$ROKID_SERIAL" capture-start
./scripts/rokid-control --serial "$ROKID_SERIAL" stream-test
./scripts/rokid-control --serial "$ROKID_SERIAL" physical-trace
./scripts/rokid-control --serial "$ROKID_SERIAL" stop
```

`stream-test` explicitly opens a local diagnostic lease for camera, IMU, and
microphone. Camera and IMU run for eight seconds; microphone packet admission
ends at the separately authorized two-second monotonic boundary, when
`AudioRecord` shutdown is also initiated. All sources stop automatically. Its
pass criterion requires at least one item from every
stream after quality gates. It reports only dimensions, counts,
aggregate byte totals, camera drop-reason counts, requested-tier counts,
observed IMU rate/maximum gap, aggregate PCM nonzero/peak evidence, and
packetization/IMU-suppression counters; it neither logs nor writes images,
sensor values, or audio samples. `cue-left` and
`cue-right` emit short development
audio/haptic cues and must be used only when the wearer expects them.
`capture-start` fails closed when camera permission is absent or Camera2 cannot
acquire the device. `stop` finishes the nonvisual activity; unbinding then
closes camera, pose, microphone, cue-transport, and audio-output resources.

`physical-trace` additionally requires the repository's development service to
be listening on Ubuntu loopback port 50051:

```bash
MPL_PROFILE=development MPL_BIND_HOST=127.0.0.1 MPL_BIND_PORT=50051 \
  MPL_INSECURE=true MPL_DEVICE=cuda MPL_ALLOW_CPU_FALLBACK=false \
  MPL_RUNNER_COUNT=2 .venv/bin/python -m conceptflow_mpl_cluster.server
./scripts/rokid-control --serial "$ROKID_SERIAL" physical-trace
```

The helper refuses to start without a local listener and creates
`adb reverse tcp:50051 tcp:50051` only on the selected authorized Rokid. The
debug variant permits cleartext solely for `localhost`/`127.0.0.1`; the main
and release network-security configuration denies cleartext. Production
endpoints use the TLS client factory and require a separate authenticated
deployment design. ADB host authorization protects this local USB test path,
but it is not application-layer peer authentication.

The one-shot gate retains at most one JPEG and bounded pose history, requires a
HEAD pose within 250 ms of Camera2's monotonic capture timestamp, and requires
nonzero microphone signal before transmission. Raw microphone bytes are
counted locally and immediately discarded; they are not put in the protobuf
request. The request advertises one in-flight frame and a two-second RPC
deadline. The client caps inbound messages at 1 MiB and rejects results with
more than four cues or identifiers that do not match the issued session,
request, stream, and frame. The renderer then applies cue TTL and ordering
checks.

Equivalent nonvisual command:

```bash
adb -s "$ROKID_SERIAL" shell am start --user 0 -W \
  -n org.conceptflow.mpl.rokidclient/org.conceptflow.mpl.rokid.RokidCommandActivity \
  -a org.conceptflow.mpl.rokid.action.START_CAPTURE
```

The authenticated private-WLAN path is an explicit, debug-only bounded test.
Both debuggable apps must be installed, the Rokid camera permission must be
granted, and the private model/runtime prerequisites described in
[Android private QNN runtime](ANDROID_QNN_PRIVATE_RUNTIME.md) must be provisioned
on the Poco. `rokid-control live-link-init` creates or reuses only the Rokid
identity and public-certificate export; it does not pair both apps or start
capture.

The Android Keystore EC identity authorizes both `SHA-256` and `NONE` digest
modes. Android Conscrypt uses `NONEwithECDSA` for the already-hashed TLS 1.3
CertificateVerify transcript on the tested API range; this is still ECDSA over
the TLS-defined SHA-256 digest and does not export the private key. Identity
initialization detects an older app-owned alias that lacks either required
authorization and replaces that alias. Replacement changes its public
certificate and therefore invalidates the opposite node's exact pin. After an
upgrade from the earlier key specification, rerun the pairing helper so both
current public certificates are exported and installed together:

```bash
./scripts/android-live-link-pair \
  --rokid-serial "$ROKID_SERIAL" \
  --poco-serial "$POCO_SERIAL" \
  --poco-address "$POCO_PRIVATE_IP"
```

The helper transfers only public certificates, verifies each private config
byte for byte, and never exports a private key. Its values and certificate
contents are not printed. It does not start either endpoint.

Start the Poco listener before the Rokid client:

```bash
adb -s "$POCO_SERIAL" shell am start --user 0 -W \
  -n org.conceptflow.mpl.androidhost/org.conceptflow.mpl.host.MainActivity \
  -a org.conceptflow.mpl.host.action.START_LIVE_TEST \
  --es environment automatic --ei duration_seconds 30 --ei maximum_frames 150
./scripts/rokid-control --serial "$ROKID_SERIAL" live-link-start
```

For an early stop, stop the producer and then the listener:

```bash
./scripts/rokid-control --serial "$ROKID_SERIAL" live-link-stop
adb -s "$POCO_SERIAL" shell am start --user 0 -W \
  -n org.conceptflow.mpl.androidhost/org.conceptflow.mpl.host.MainActivity \
  -a org.conceptflow.mpl.host.action.STOP_LIVE_TEST
```

The start command runs for at most 30 seconds, requests only camera and IMU,
uses the negotiated 3 FPS relaxed/5 FPS motion cadence and negotiated bounded
IMU batch-delay/silence values, and terminates on the sixth disconnect (at most
five reconnections). Logcat output for this direct-live test contains only
aggregate counts and categorical status; it does not emit endpoint, session,
lease, certificate, frame, or IMU values. The transport uses independent TLS
1.3 mutual-TLS realtime/control and camera sockets. The second socket is
admitted with a short-lived, single-use ticket issued over the first.

### Final direct-path evidence — 2026-08-23

Without reinstalling either app between them, two consecutive physical runs
completed through the private-WLAN transport and app-process QNN HTP path. Both
processes remained alive, no crash was observed, and each run recorded zero
interruptions plus an authenticated close with no failure lane.

| Selected 392 profile | Frames received | Inference succeeded/attempted | Positive depth outputs | Poses accepted/received | p95 end-to-end | p95 capture-to-receive | p95 segmentation | p95 depth | p95 executor | p95 clock uncertainty |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Indoor Hypersim | 80 | 61/61 | 9,373,504 | 1,400/1,400 | 941.7 ms | 586.1 ms | 84.1 ms | 70.2 ms | 287.5 ms | 5.3 ms |
| Outdoor VKITTI | 81 | 61/62 | 9,373,504 | 1,438/1,438 | 1,183.5 ms | 591.0 ms | 107.8 ms | 74.4 ms | 373.2 ms | 5.8 ms |

Both reported metric status
`profile_bound_native_metric_derived_intrinsics_present` and reason
`CAMERA_METRIC_TRACKS_READY_PROPAGATION_INTRINSICS_UNQUANTIFIED`. These are
bounded execution and latency traces, not representative depth-accuracy,
camera-calibration, long-duration thermal, or BVI-user validation.

The first physical attempt identified a framing root cause: the 200 ms socket
timeout could interrupt a length-prefixed record after consuming part of its
prefix or payload, and the prior stateless retry discarded that partial state.
The stream then became misaligned. The stateful reader now retains its byte
offset and resumes that record across timeouts. The final runs also exercised
authenticated close after it was moved to a bounded dedicated worker, keeping
the close operation off the Android main thread.

When a device supplies complete Camera2 calibration metadata, the implementation
validates and scales it. The observed Style metadata do not establish verified
factory calibration. Android does not define a zero principal point as an
unknown-value sentinel. The client therefore permits metadata-based derivation
only for the exact, device-observed Rokid fingerprint: focal length, physical
sensor size, pixel and pre-correction arrays, orientation, `CENTER_ONLY`
cropping, zero distortion vector, and the observed invalid zero principal-point
values must all agree. That path is always marked `DERIVED`.
Its uncertainty remains unquantified (the protobuf standard-deviation field is
omitted rather than populated with heuristic percentages). The request pins
unit zoom, no rotate-and-crop, distortion correction off, video stabilization
off, optical stabilization off, the full crop, and the declared focal length
where Camera2 reports those controls. A safely
timestamp-correlated result must agree with every verifiable requested value;
contradictions remove intrinsics from that frame. If the result callback is not
correlatable, the client preserves availability with the documented static
center-crop derivation. Missing or contradictory static inputs are rejected,
and no calibration claim follows merely from receiving derived intrinsics. For
this target fingerprint, the centered 1920×1080 derivation is approximately
`[fx, fy, cx, cy, s] = [904.7619, 904.7619, 960, 540, 0]`. It remains
`DERIVED` with unquantified parameter uncertainty. The pinned Depth Anything V2
Metric heads produce native scalar camera-frame metric output without consuming
intrinsics; missing or rejected intrinsics disable pixel-to-ray/vector geometry,
not that scalar inference.

`SENSOR_ORIENTATION` (including the observed 270-degree value) describes the
sensor-to-device-natural/display rotation. It does not establish the physical
`HEAD <- CAMERA` mounting rotation or translation. Consequently, the host's
`head_camera_extrinsic_missing` gate remains explicit until authoritative
mounting/calibration metadata is available; model-name hardcoding is not used.
The official Rokid product/SDK material and Android Camera2 semantics checked on
2026-08-23 publish no Style factory matrix, distortion residual, or
`HEAD <- CAMERA` extrinsic. The linked source record is in
[Research evidence](RESEARCH_EVIDENCE.md). Empirical checkerboard or ChArUco
calibration of the emitted 1920×1080 path is still required before marking the
matrix `CALIBRATED` or claiming calibrated spatial/angular accuracy; a separate
mounting/extrinsic measurement is still required for head-frame projection.

## Diagnostics

Use the bounded helper first:

```bash
./scripts/rokid-control --serial "$ROKID_SERIAL" status
adb -s "$ROKID_SERIAL" logcat -d -s ConceptFlowRokid:I '*:S'
```

General capture/diagnostic logs contain state, bounded identifiers, counts,
sizes, and coarse signal evidence, including aggregate maximum mean luma,
minimum dark-pixel fraction, maximum focus score, and maximum motion score;
never frame bytes, individual IMU values, or PCM samples. Direct-live logs are
narrower and omit endpoint/session/lease/frame identifiers as described above.
If Camera2 reports
`CAMERA_IN_USE` or `MAX_CAMERAS_IN_USE`, stop this service and record the
conflict. Do not disable YodaOS security or services.

## Current boundary

The client has a physically exercised, bounded glasses-to-Ubuntu development
slice: real Camera2/IMU/microphone activity, canonical protobuf negotiation and
frame/result correlation, deterministic mock processing, stale-aware cue
scheduling, and real glasses audio dispatch. Its bounded direct private-WLAN
mutual-TLS client, with distinct camera and realtime IMU lanes and microphone
disabled, was also physically exercised with app-process HTP inference and
authenticated close in the two final runs above. Those runs do not establish
forced-reconnect or adverse-network behavior, production deployment readiness,
open-ear localization quality, camera calibration, representative model
accuracy, or sustained thermal behavior.

Verified and unverified physical behavior is recorded without inference in
[`VALIDATION.md`](../VALIDATION.md). No vendor SDK, client secret, proprietary
model weight, captured frame, or private device identifier is included here.
