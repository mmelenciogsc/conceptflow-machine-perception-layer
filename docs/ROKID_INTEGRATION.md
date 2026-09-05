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
- Android's official
  [alarm scheduling guide](https://developer.android.com/develop/background-work/services/alarms/schedule)
  documents exact-alarm special access and its restrictions. The implementation
  no longer requests that access because physical YodaOS evidence rejected the
  resulting background foreground-service re-entry. Android's
  [wake-lock guide](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock)
  requires finite acquisition and prompt release; the remaining pre-authentication
  wake lease follows that bounded model.
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

Live Camera2 characteristics expose device-native 648×648 output for both
format 35/`YUV_420_888` and format 33/JPEG. Continuous capture uses exact
648×648 YUV; JPEG is not used by this path. The former 1920×1080 mode remains
an enumerated camera capability but is not used by this path. Earlier inspection
reported 16.67 ms minimum frame duration and 13.48 ms JPEG stall duration for the 1920×1080
stream; those historical capability values are not measurements of the current
648×648 mode or a sustained-throughput claim. The game-rotation, gyroscope, and
linear-acceleration sensors all accept a 10,000-microsecond sampling request. A bounded 2026-08-22 run
observed 98.8 fused orientation samples/s and a 10.1 ms maximum orientation
gap. Android sensor periods are requests, so runtime measurements remain the
authority.

## Non-display application model

`apps/rokid-client` has no launcher entry or visual layout.
`RokidRuntimeService` is private and has no intent filter in every build
variant. YodaOS resolves an exported debug service but refuses both shell
`startservice` forms, so no service export is retained. The service independently
rejects its enable action unless the APK is debuggable; restore actions require
app-created proof and unknown actions fail closed. Development commands enter through a nonvisual
`RokidCommandActivity` protected by the same permission. The activity has no content
view, controls, text, launcher entry, or user interaction. It owns the service
binding because the observed YodaOS build blocks third-party background service
and broadcast starts. Android still requires an activity window on its internal
compatibility surface; no physical display exists and no visual interface is
part of the product. The Activity exists only as the short-lived, visible
Android authorization broker required to establish the camera-capable
foreground service. It finishes and unbinds immediately after successful arm.

The debug-only `idle-enable` helper explicitly launches the authorized
nonvisual Activity. The Activity requests lock-screen visibility and screen-on
state, waits until it is both resumed and window-focused, allows a bounded 150
ms settling interval, then starts the private foreground service and binds to
verify its complete persisted/started/foreground state. Verification fails
closed after ten bounded observations. A failed Activity setup does not claim
that idle control is armed. A normal sensor-off rendezvous cooldown remains
armed; readiness does not depend on a connection attempt existing at the exact
instant the Activity checks it. This broker path avoids the tested YodaOS rejection
of direct shell starts while ensuring the app does not request its service from
a sleeping-display/background Activity.
Before the Poco authorizes capture, camera, IMU, and microphone producers are
all absent. The armed service keeps only a bounded outbound mutual-TLS
rendezvous active on the private network. Before the first authenticated
session, one transient connection failure ends that rendezvous epoch. The
service then uses jittered 15-, 30-, and 60-second cooldown levels for genuine
network or rendezvous failures (bounded to plus or minus ten percent and capped
at the final level across later failed epochs) and tries again only while the
same explicit visible-arm generation remains valid. The cooldown callback is
owned by the already-running foreground service. It never submits another
background `startForegroundService` request: physical logs from the target
YodaOS build showed that the platform accepted that request and then rejected
the required camera/microphone `startForeground()` confirmation. Removing that
invalid re-entry also removes the need for exact-alarm special access. If the
process is killed, the explicitly provisioned AccessibilityService re-enters
through the same-boot visible broker instead of treating a timer as capture
authority. Each managed epoch also has a service-owned three-minute total
pre-authentication ceiling covering endpoint discovery, TCP, TLS, lease, clock
synchronization, and camera-lane admission; expiry closes its in-flight
sockets. A definitive network or authentication failure can end the epoch
earlier. A
non-reference-counted partial CPU wake lock and a non-reference-counted Wi-Fi
low-latency lock keep only that deadline and handshake progressing. Both use a
182-second hard timeout, providing a two-second release margin around the
three-minute ceiling, and are released earlier on authentication, terminal,
failed start, disable, or service destruction. The Wi-Fi lock can keep an
already-enabled, associated radio responsive but cannot enable it. On the
exact non-display YodaOS-Sprite product family, Rokid Node now also uses the
physically verified system-assist `open_wifi_p2p` command as a bounded radio
recovery pulse while, and only while, the node is explicitly armed. Attempts
occur after 0, 1, 3, and 8 seconds and then stop. Radio enablement schedules
idempotent public `WifiP2pManager.removeGroup` checks after 4 and 8 seconds.
Only an empty, locally owned group may be removed; a peer-owned group or group
with clients is retained. The vendor `close_wifi_p2p` command is deliberately
not used because a longer physical observation proved that it also turns the
Wi-Fi radio off on this firmware. The recovery command contains no network
identifier, address, credential, or sensor content. Unsupported products fail
closed, and disabling the node unregisters the observer and attempts bounded
cleanup of any pulse. No lock is held during cooldown. The maximum jittered
steady cooldown is 66 seconds;
the pre-authentication ceiling remains separately bounded while the
foreground-service process is runnable. Android or vendor freezing can defer a
process-local callback;
same-boot process recovery therefore requires the validated AccessibilityService
broker. A
successfully authenticated session resets that escalation. Once authenticated,
up to six transport interruptions remain bounded by the original active
deadline. No
20 ms sensor poll runs while merely connecting, and no continuous wake lock or
`FLAG_KEEP_SCREEN_ON` is used for this idle posture. `BOOT_COMPLETED`, sticky
restart, and `MY_PACKAGE_REPLACED` restore only an inert foreground state: no
network rendezvous and no sensor producer begins until an authorized visible
`idle-enable` arms a new generation.

Within that explicitly armed boot, a YodaOS low-memory process eviction is
recovered through the same short-lived nonvisual Activity broker used for the
initial foreground-service authorization. The boot-count capability is checked
before both the broker launch and runtime resume, and branding is not replayed.
The persistent armed-node epoch is bounded to four hours. Its terminal path,
including ordinary expiry and source-open failure, returns to sensor-off
standby and the existing bounded 15/30/60-second recovery cadence. The separate
diagnostic soak remains ten minutes.

The observed API 32 YodaOS build also applied
`RUN_IN_BACKGROUND=ignore` and `RUN_ANY_IN_BACKGROUND=ignore` to the directly
sideloaded Rokid Node. `am get-inactive` still reported `false`, Doze was
disabled, and `START_FOREGROUND` was allowed, but this separate vendor policy
stopped the foreground service at exactly 60 seconds. On that development
target, inspect and explicitly provision the narrow reversible exception with:

```bash
./scripts/rokid-background-policy --serial "$ROKID_SERIAL" status
./scripts/rokid-background-policy --serial "$ROKID_SERIAL" enable
./scripts/rokid-background-policy --serial "$ROKID_SERIAL" disable
```

`enable` changes only this package: it sets `RUN_IN_BACKGROUND` to `allow`,
resets `RUN_ANY_IN_BACKGROUND` to `default`, and adds the package to the
device-idle whitelist. It verifies all three results without printing the ADB
serial. `disable` resets both app-ops to `default` and removes only this package
from that whitelist. This is an explicit ADB provisioning step for the observed
YodaOS behavior, not an APK permission or a production policy assumption. The
helper never launches the app and does not trigger playback, sensors, or a
network connection.

The optional `RokidInputAccessibilityService` observes a narrow allowlist of
candidate right-arm key events and always returns `false`; it never consumes or
remaps a key. Long press, repeat, cancellation, malformed ordering, timing
expiry, or a device/source/scan-code mismatch resets its bounded recognizer, so
the worn long-press remains available to YodaOS's Talk-to-AI behavior. The
service requests neither screen content nor touch exploration. Accessibility
service access is nevertheless broad platform trust and is disabled by
default; provisioning and coexistence requirements are documented below.

Visible arm establishes the camera-capable foreground-service type before the
sensor-off rendezvous begins. On the tested Android 12/API 32 target it requests
the camera type; API 34 and newer request camera plus `specialUse`. Only an
authenticated Poco Start request can produce a valid camera+IMU lease. The
controller publishes a camera-starting notification and establishes the camera
foreground-service type immediately before either sensor factory is called. It
publishes camera-active only after both producers start. A disconnect demotes
the notification to sensor-off standby before reconnect; the next authenticated
session repeats the starting-to-active transition. An ordinary debug run starts
one non-extendable 30-second active deadline. The explicitly selected diagnostic
soak uses a bounded 10-minute deadline; the persistent armed-node path uses a
bounded four-hour deadline. Reconnects cannot reset the active epoch deadline.
Only after the controller reports its bounded transport terminal does the
service return to the sensor-off standby notification.
Disable and the generic `stop` command both clear the persisted enable choice,
stop all producers even if that preference write reports failure, and wait for
the authenticated live-link close. A 12-second service watchdog is longer than
the transport's own 10-second close bound and exists only to prevent an
indefinite foreground shutdown if the terminal callback itself is lost.

Live-link identity initialization and ADB diagnostic start/stop remain
runtime-gated by the app's debuggable flag. The accessible standard Start
control in Machine Perception Layer, Android Node is the production capture
authorization surface. The base lease grants exactly camera, IMU and touch and
is capped at 10 minutes; the ordinary diagnostic controller closes it after 30
seconds. While that bounded, mutually authenticated
session is active, the Android Node exposes a separate accessible **Request
10-second glasses microphone** control. It sends a second mic-only
`StreamLeaseRequest` over the authenticated control lane with the exact active
session and lease identifiers and `user_requested_microphone=true`. Rokid
rejects mismatched, combined, expired, permissionless, repeated, or
longer-than-ten-second requests without stopping camera or IMU. This reuses the
typed request/grant protocol rather than introducing a second control channel.
Packet admission checks the monotonic deadline on every chunk. A dedicated
deadline task closes `AudioRecord` at that boundary, with the existing 20 ms
controller poll as a second shutdown path.
The input integration seam is
`LiveLinkCaptureController.requestMicrophoneFromUserGesture()` and
`stopMicrophoneFromUserGesture()`. Both fail closed without a STREAMING
session. START only sends an authenticated intent and waits for the resulting
sublease; STOP closes local capture first and is idempotent. The transport uses
monotonic intent IDs and a one-second freshness window, and temporarily blocks
older phone- or glasses-originated grants from overtaking STOP. This API does
not assign a physical gesture by itself; that mapping remains in the separately
validated input policy.
This is an intentionally explicit but currently session-coupled sublease. The
v1 transport still requires the authenticated camera lane and the Android
Node's live Machine Vision runtime, so it must not be represented as a
standalone on-demand microphone session.

Node activation and sleep use a distinct application-level round trip. The
validated gesture recognizer first creates an `RokidGestureIntent`; Rokid Node
starts its local ready/branding feedback immediately so a missing phone cannot
silence the user acknowledgement, and retains the newest unsent intent in a
bounded, expiring outbox until a direct private-WLAN mutual-TLS session is
authenticated. Android Node verifies its exact session/lease binding,
clock-normalized freshness, user-origin flag, operation, and replay order, then
returns a separately identified, two-second-TTL `RokidNodeCommand`. Rokid Node
validates that command and returns an exact correlated result. ENABLE maps to
ACTIVATE; DISABLE maps to SLEEP. A phone-originated accessible command can also
request the complete brand sequence. ADB does not carry these operational
messages and remains limited to installation, provisioning, and diagnostics.
The current control plane requires the private WLAN radio and the bounded live
session; Bluetooth/BLE wake or discovery is not implemented or claimed.

The implemented hardware boundaries are:

- `Camera2FrameSource`: bounded latest-only YUV capture, post-gate packed-I420
  conversion for production (RGB8 for explicit legacy diagnostics), and
  monotonic frame IDs;
- `SensorManagerPoseSource`: unbatched nominal 100 Hz game-rotation snapshots,
  each carrying the latest three-axis gyroscope and gravity-compensated linear
  acceleration values plus their source timestamps and sensor accuracy;
- `LiveLinkCaptureController`: a bounded authenticated camera+IMU session that
  starts producers only after the negotiated session/lease is ready, tears them
  down before reconnect, and opens a microphone source only after the separate
  authenticated mic-only grant is written;
- `RokidCaptureSpool`: app-private `camera/` and `microphone/` artifacts plus an
  atomic `manifest.json`; it receives only gate-admitted data and serves the
  Android Node's bounded authenticated poll/fetch/ack flow;
- `AudioRecordInputSource`: bounded 16-kHz mono PCM input with monotonic chunk
  IDs; admitted chunks are temporarily persisted as private WAV files only
  while awaiting Android acknowledgement;
- `InspectableCueRenderer`: stale/duplicate/older-cue rejection;
- `GrpcRemotePerceptionClient`: v1 capability negotiation, ephemeral session
  identity, bounded frame submission, strict result correlation, cancellation,
  and deadlines;
- Android stereo audio and optional vibrator output; and
- deterministic, package-scoped commands for capture and development cues.

## Adaptive camera gate

`Camera2FrameSource` creates one session containing both a bounded 640×480
headless YUV preview and the exact 648×648 YUV output. The preview repeats for
the session lifetime: its first second settles the standard Camera2
auto-exposure and white-balance loops, then it keeps the vendor stream active
between scheduled captures. Its listener only acquires and closes the latest
image on a dedicated drain thread. Exact-image processing is on a second worker,
while session callbacks and the opportunity timer stay on the Camera2 control
thread. The client begins exact-size physical YUV capture near 3 FPS and raises
it toward a 5 FPS ceiling after material pixel change. This is an ongoing
sequence of timer-issued single `TEMPLATE_STILL_CAPTURE` requests alongside the
repeating low-resolution keepalive, not a repeating 648×648 request.
The delay is budgeted from capture-request time so YUV processing is not added
on top of the intended period. A single monotonic opportunity timer permits at
most three outstanding requests, discards rather than replays backpressured
opportunities, and associates request tags with image sensor timestamps. Every
rejected image is discarded after the gate decision; accepted images enter the
bounded live transform (and the private pull spool only when that diagnostic is
enabled). Capture-size selection requires exact device-native 648×648
`YUV_420_888` and fails closed if it is unavailable; there is no continuous
JPEG fallback. The planes are borrowed synchronously only while the Android
`Image` is open, with explicit row/pixel-stride validation and guaranteed
closure. No Y/U/V plane arrays are copied or retained.

The protected gate still evaluates a 90×90 thumbnail for this square source,
but now samples Y directly and expands the declared BT.601 limited range; it
does not read chroma. Its thresholds, state, and 3/5 FPS motion response remain
unchanged. Only after admission, a packaged arm64 integer JNI converter reads
the borrowed direct planes and produces tightly packed 640×640 planar I420 with
the deterministic fixed-point 80/81 scale and no crop. Android Node validates
the 614,400-byte layout and converts it to RGB on the Poco before current model
preprocessing. Kotlin remains the deterministic fallback/reference. RGB8
conversion remains available only to the explicit legacy diagnostic spool.
Cross-language production-size golden tests and Kotlin byte-layout tests cover
padded row strides and pixel-stride-two chroma. One privacy-safe startup log
states the selected output format and whether native conversion was used, so a
physical run cannot silently credit the fallback. The general aspect-fill
geometry remains center-crop capable and never stretches or letterboxes the
image. See
[Rokid pull spool](ROKID_PULL_SPOOL.md).

Live protocol 1.5 describes packed RGB8 compatibility, planar I420, and an
explicit feature-flagged AVC Annex-B all-intra wire format. Production defaults
to the self-contained 640×640 I420 payload. When both peers opt in, the glasses
hardware-encode the exact same admitted post-gate I420 frame and require every
wire access unit to contain SPS, PPS, and IDR; the Poco hardware-decodes back to
I420 before the existing sensor timeline and QNN pipeline. It has no
source-acquisition tier field and therefore cannot request a future exclusive
high-resolution capture. The existing 1920×1080 bounds remain available to
legacy diagnostics/future policy, but they do not alter the continuous 648×648
source and no on-demand 1920 path is added by this change.

If Poco rejects AVC after a decoder failure, the existing client accepts the
next authenticated I420 lease and feeds the unchanged post-gate I420 buffer
directly to transport. No camera, microphone, IMU, or touch gate is restarted
or reinterpreted to implement this codec fallback.

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

The legacy gRPC diagnostic request still advertises a 1920×1080, 2 MiB, 5 FPS
maximum; it is not the continuous live camera acquisition contract. It
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

## Branded audio on explicit activation

Service/process creation, boot restore, package-replacement restore, and wear
state are silent. A successful inactive-to-active explicit Node enable starts
with a short, low-gain deep ready tone generated from bounded PCM at runtime;
no vendor sound or other binary audio asset is committed. On the first such
activation for each readable Android `Settings.Global.BOOT_COUNT`, the parent
brand line, `CONCEPTFlow. Machine Intelligence. Human Architecture.`, follows
the ready tone and is followed by a second deep separator tone. A persisted
boot-count claim prevents later activations, service recreation, or process
recreation during that boot from replaying the parent line. If the global boot
count cannot be read, a process-local first-activation gate is used.

The product line, `Machine Perception Layer. Map. Morph. Move.`, followed by a
brief pause and `It's just supplemental awareness.`, has its own rolling
72-hour persisted claim. It is independent of the per-boot parent line. Wear,
proximity, service restore, and authenticated connection do not claim either
cadence. A successful authenticated Android Node session keeps its existing,
independent connection tone.

For a bench-only immediate check, the debug-only command below exercises the
exact complete sequence without changing either production cadence state:

```bash
./scripts/rokid-control --serial "$ROKID_SERIAL" brand-audio-test
```

Production acceptance must exercise an explicit enable transition. The debug
full-sequence command above remains only a bench playback check and does not
change production cadence.

A private installation may use the consent-gated cloned voice
identified by the operator as the voice from *Connected by Water*. The audited
QUICKPub revision `27808e8f9d0ec073af6091a6b9a49f1d021779a9` does not contain
that private reference sample or a consent or redistribution record for it,
and the repository has no top-level project license. Its third-party notice
identifies Chatterbox code as MIT-licensed, with separate model-card terms and
a retained PerTh disclosure watermark. No QUICKPub code, model, reference
sample, or cloned audio is copied into this repository. Generate a private
eight-file phrase set only with the exact permitted sample and an explicit output
directory outside the repository:

```bash
python3 scripts/generate-private-rokid-brand-voice.py \
  --quickpub-root /path/to/QUICKPub \
  --python /path/to/QUICKPub/chatterbox/python \
  --model /path/to/chatterbox-turbo-model \
  --voice-sample /path/to/permitted-reference.wav \
  --output-dir /private/path/rokid-brand-voice \
  --i-have-voice-permission
```

The script invokes QUICKPub's external Chatterbox worker, records the QUICKPub
revision, worker and runtime-manifest hashes, pinned model revision, output
hashes, consent affirmation, external-source assertion, and retained PerTh
watermark in a private manifest. It records no reference-sample hash or other
stable source fingerprint. It refuses a different QUICKPub revision, a
modified worker/runtime manifest, or any output path inside this repository.
The reference sample is never copied. Each phrase is generated as a separate,
deterministically seeded WAV so the runtime owns the pauses rather than relying
on synthesizer punctuation timing. The spoken input for the product brand is
`Concept flow.` while the public written brand remains `CONCEPTFlow`. Private
voice playback requests a bounded five-times gain lift over the previous
profile; the platform loudness processor compresses over-range peaks before the
system mixer. It does not change the user's system-volume setting.

No Gradle source set includes private voice files. Provision a validated set
only to a debuggable installed app:

```bash
./scripts/provision-private-rokid-brand-voice \
  --serial "$ROKID_SERIAL" \
  --voice-dir /private/path/rokid-brand-voice
```

The provisioner validates the exact manifest and WAV set, stops the app, stages
and hash-verifies every transfer without printing hashes, applies directory
mode `0700` and file mode `0600`, then replaces the app-private
`no_backup/private/rokid_brand_voice` directory. It does not restart the app.
Default debug and release APKs therefore contain no cloned voice. At runtime,
an incomplete, modified, or absent app-private set is ignored.
Use `--validate-only` to exercise the host checks without contacting a device.

Without that private set, Android TextToSpeech selects an installed,
non-network English voice by locale, quality, latency, and stable name. If none
exists, it deterministically selects an available network English voice before
falling back to the engine's US-English/default path. Android does not expose
reliable cross-engine voice-gender metadata, so this fallback makes no female-
voice or other gender promise.

There is no validated Rokid-specific public wear API for this Style unit. The
pinned official SDK demo contains only a commented
`DeviceInfoManager.getWearingStatus()` call in its phone notification sample;
it provides neither that manager's implementation nor a public wear callback.
The Node therefore registers no proximity-based wear listener and neither
activates nor narrates from proximity. The off-head observations below are only
evidence of firmware input gating, not an application wear event.

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
| Two-finger hold with separate fingertip contact | `ROKID,PSOC-TP-R` (`event1`) | often classified as one finger: `KEY_DASHBOARD`, then `KEY_PROG1` and the Talk-to-AI action |
| Two-finger hold with broad side-by-side contact | `ROKID,PSOC-TP-R` (`event1`) | `KEY_DASHBOARD`, then `KEY_PROG2` (scan 149) and `ACTION_SETTINGS_KEY` |

The isolated off-head tap and swipe trials produced no input events, while worn
trials produced the listed key sequences. This is consistent with wear gating
on the observed unit. A repeated/longer swipe can emit multiple volume steps.
`KEY_DASHBOARD` appeared as a consistent gesture preamble/contact event.
Applications should treat the terminal key as the candidate semantic action
and must test whether Android or a YodaOS system component consumes it before
application dispatch. Raw `getevent` visibility alone does not prove that an
ordinary app can intercept every system key.

Rokid's current official Bare Metal input guide and downloadable sample were
also inspected on 2026-08-26. They dynamically register a priority-1000
receiver for documented system actions and call `abortBroadcast()` only when
the delivery is ordered. The public guide is written for a display-equipped
480×640 Rokid Glasses target, so every behavior was re-tested rather than
assumed for this non-display RV203.

On this RV203, broad two-finger holds reliably produced the documented
`com.android.action.ACTION_SETTINGS_KEY` action. The underlying Linux event was
`KEY_PROG2`/scan 149, but Android did not deliver that key to the enabled
AccessibilityService. The semantic broadcast was non-ordered, so an ordinary
sideloaded app cannot suppress it with `abortBroadcast()`. The installed Sprite
firmware's receiver read `settings_shortcuts=false` for every physical trial
and explicitly took no OEM action. This makes the gesture currently usable as
an observed custom input, not an app-owned or universally collision-free key.
If the Shortcuts option is enabled later in Hi Rokid, the same firmware path
opens the OEM shortcut/AI scene and the CONCEPTFlow command mapping must remain
disabled.

The ordered `ACTION_AI_START` broadcast is technically abortable, but it is
reserved for Rokid's one-finger Talk-to-AI behavior and CONCEPTFlow does not
intercept it. Dedicated two-finger double-tap and swipe broadcasts did not fire
in the physical RV203 trials even though their recognizer system properties
were enabled; generic `KEY_DASHBOARD` contact pulses cannot distinguish finger
count. The physical top button remains wholly excluded because its photo,
video, power, and pairing functions are system-owned.

Linux scan labels are not Android `KeyEvent` names. API-32 `Generic.kl`
translates the PSOC candidates as follows: scan 204/`KEY_DASHBOARD` to
`KEYCODE_NOTIFICATION` (83), 115/`KEY_VOLUMEUP` to 24,
114/`KEY_VOLUMEDOWN` to 25, 148/`KEY_PROG1` to `KEYCODE_PROG_BLUE` (186), and
400/`KEY_YELLOW` to `KEYCODE_PROG_YELLOW` (185). The top-button scan 139 is
overridden by YodaOS to vendor `SPRITE_FUNCTION`, so the Node does not map
`KEYCODE_MENU`.

The candidate recognizer accepts only the nonvirtual input device named exactly
`ROKID,PSOC-TP-R` with keyboard source `0x00000101`; runtime device ID is
required to remain consistent within one sequence but is not pinned across
boots. Scan code, source, name, virtual state, down/up pairing, cancellation,
long-press flag, repeat count, and monotonic deadlines are all checked. A
single-tap command has exact grammar `D,V,[same-V repeats],D,P`; a double-tap
command has `D,V,[same-V repeats],D,D,Y`, where `D` is the notification
preamble, `V` is one direction's volume key, `P` is program-blue, and `Y` is
program-yellow. Repeated same-direction volume steps collapse without extending
the total deadline. Any unrelated, late, opposite, cross-device, or malformed
event resets the sequence. With no off-head events, no command can be formed.

The AccessibilityService always returns `false`, including for `PROG_BLUE`, so
YodaOS retains the native Talk-to-AI long press. It does not inspect
accessibility events, text, windows, or touch exploration. Candidate-event logs
are local and limited to a monotonic count, key code, action, repeat/cancel/
long-press state, scan code, ephemeral device ID, vendor/product integers,
source, and allowlist result; the device name and descriptor are not logged.

The dynamic system-broadcast observer also remains non-intercepting. A
physically recognized two-finger hold is published as one typed
`TWO_FINGER_LONG_PRESS`/`TRIGGERED` event with its glasses monotonic observation
time. It is not converted into invented key-down/key-up edges. Publication is
non-buffered before an authenticated live touch lease, and Android's existing
bounded ordered touch ingress remains authoritative. No command is assigned to
this event until the OEM Shortcuts-disabled precondition has a durable
operator-visible enforcement mechanism.

The command gate defaults to observe-only. Install the APK, then use the
reversible helper:

```bash
./scripts/rokid-accessibility-control --serial "$ROKID_SERIAL" status
./scripts/rokid-accessibility-control --serial "$ROKID_SERIAL" enable
# Capture and validate onKeyEvent metadata, exact grammar, OEM Talk-to-AI,
# volume behavior, off-head silence, and foreground-service start behavior.
./scripts/rokid-accessibility-control --serial "$ROKID_SERIAL" commands-enable
./scripts/rokid-accessibility-control --serial "$ROKID_SERIAL" commands-disable
./scripts/rokid-accessibility-control --serial "$ROKID_SERIAL" disable
```

`enable` canonicalizes and appends only this component to user 0's
`enabled_accessibility_services`, preserves every other service, and verifies
both the configured list and the service's bound state. On first addition it
also establishes the app-private command gate as observe-only before exposing
the service. `disable` removes only
the full or short form of this component and leaves unrelated services and an
unusual pre-existing master switch untouched; when it removes the only service,
it disables the master switch. An absent-component disable is a no-op. Both
mutating paths verify the installed component and
`BIND_ACCESSIBILITY_SERVICE` declaration first and restore the original secure
settings if verification fails.

Do not run `commands-enable` until a physical AccessibilityService callback
trace confirms the candidate mapping and tests native-control coexistence.
Even then, direct foreground-service start from the system-bound accessibility
context and the YodaOS background-policy exception remain physical acceptance
gates. Failure is logged and no fallback input interception is attempted. The
double-tap sequences enable or disable the Node. While active only, the
single-tap sequences call the live controller's authenticated, session-bound
microphone start/stop intent path. Start still waits for a valid host-authorized
sublease; stop closes local microphone capture before sending its ordered stop
intent. ENABLE and DISABLE use the authenticated gesture/command/result round
trip described above; DISABLE also has a 750 ms local fail-safe so loss of the
reply cannot prevent the wearer from stopping capture. A missing authenticated
session fails closed for microphone start and cannot silently grant capture.
Disable persists the off state and closes camera, IMU, microphone, and network
producers.

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

Enable, inspect, and disable the cable-independent control posture with:

```bash
./scripts/rokid-control --serial "$ROKID_SERIAL" idle-enable
./scripts/rokid-control --serial "$ROKID_SERIAL" status
./scripts/rokid-control --serial "$ROKID_SERIAL" idle-disable
```

Disconnecting the development cable after `idle-enable` does not itself stop
the foreground service. The Activity has already exited; the live link still
requires valid app-private pairing, a reachable Poco listener, and an explicit
Poco Start. A reboot receiver intentionally restores only inert state, so run
authorized `idle-enable` again before the next private-network rendezvous.

## Build and direct sideload

```bash
./gradlew --no-daemon --dependency-verification strict \
  :apps:rokid-client:testDebugUnitTest \
  :apps:rokid-client:assembleDebug
read -r -p "Rokid ADB serial: " ROKID_SERIAL
./scripts/rokid-install --serial "$ROKID_SERIAL" --inspect-only
./scripts/rokid-install --serial "$ROKID_SERIAL" --no-build
```

The Rokid build pins Android NDK `27.0.12077973` and CMake `3.22.1` for the
arm64 converter; CI installs those exact toolchain versions.

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
  --poco-address "$POCO_PRIVATE_IP" \
  --network-topology private-lan-discovery
```

The helper transfers only public certificates, verifies each private config
byte for byte, and never exports a private key. Its values and certificate
contents are not printed. It does not start either endpoint.

`private_lan_discovery` first listens for a content-free local announcement and
then uses the provisioned same-subnet address, or the private default gateway
when the glasses are a Poco-hotspot client, after eight seconds if the WLAN or
YodaOS filters multicast/broadcast. The current hardware pair physically used
direct discovery and fallback with TLS 1.3 mutual authentication. If the Poco's
DHCP address changes on infrastructure Wi-Fi, reserve it on the trusted LAN or
rerun pairing; hotspot fallback does not depend on that address. Strict
`wifi_direct_required` remains separately selectable and fail-closed; its peer
discovery is not working on the currently tested firmware combination. See
[Local wireless transport](WIFI_DIRECT_TRANSPORT.md).

Arm the Rokid sensor-off rendezvous through authorized development ADB:

```bash
./scripts/rokid-control --serial "$ROKID_SERIAL" idle-enable
```

Then open **Machine Perception Layer, Android Node** on the Poco and activate
the accessible **Start persistent Android Node listener** button (keyboard
`L`). The explicitly started connected-device foreground service, rather than
the Activity, owns the listener and inference resources. Display sleep and
Activity recreation therefore do not close the listener. Each armed-node
capture epoch remains bounded to 10 minutes; after its graceful close, the
Rokid immediately starts the next rendezvous to the same listener. Failure
retries retain their bounded cooldown. For an early stop, use the
Poco's accessible **Stop Android Node** action or control. Disable the Rokid
standby only when needed:

```bash
./scripts/rokid-control --serial "$ROKID_SERIAL" idle-disable
```

To test microphone only after the status reports an authenticated active
session, activate the separate accessible **Request 10-second glasses
microphone** control (keyboard `M`). The button cannot arm standby or start an
ordinary session. Its request is rejected if no authenticated session exists,
another mic window is pending/active, the exact binding differs, or the Rokid
lacks `RECORD_AUDIO`; camera and IMU continue independently after rejection or
when the ten-second microphone deadline expires.

Each ordinary glasses diagnostic runs for at most 30 seconds; an explicit soak
or armed-node epoch runs for at most 10 minutes. The base lease requests camera,
IMU and touch,
uses the negotiated 3 FPS relaxed/5 FPS motion cadence and negotiated bounded
IMU batch-delay/silence values, then releases the sensors before the next
bounded rendezvous. The persistent Android Node listener accepts sequential
authenticated leases and retries network interruption without an Activity-held
90-second deadline. Logcat output for this direct-live test contains only
aggregate counts and categorical status; it does not emit endpoint, session,
lease, certificate, frame, or IMU values. The transport uses independent TLS
1.3 mutual-TLS realtime/control and camera sockets. The second socket is
admitted with a short-lived, single-use ticket issued over the first.

### Persistent-node physical evidence — 2026-08-25

The directly sideloaded Rokid Node and Poco Android Node completed repeated
private-WLAN mutual-TLS leases after the listener was moved from the Activity
to an explicit foreground service. One representative Rokid lease observed and
queued 82 camera frames, produced 5,600 camera chunks, observed 2,910 IMU
samples, and queued 1,581 selected samples in 780 batches. The Poco independently
reported 82 received frames, 780 received batches, and 1,552 accepted pose
samples with zero rejected poses. A later cumulative Poco snapshot reported
252 frames, 2,362 batches, and 4,749 accepted pose samples across three leases.

The Poco display was then put into Doze. Its connected-device foreground
service remained active, and the Rokid completed another 88-frame/796-batch
lease and authenticated the following lease while the display stayed off. An
in-place Android Node APK replacement deliberately interrupted one connection;
the Rokid reported a bounded network failure and subsequently reconnected after
the listener restarted. The earlier YodaOS rejection of a delayed duplicate
`startForeground()` call was removed by retaining the already-established
camera foreground type and updating only the notification during later capture
states. No raw frame, IMU sample, endpoint, certificate, or device identifier
was written to this evidence.

The operator also confirmed that the physical forward-swipe plus quick
one-finger double-tap activation now produces the local Rokid Node audio. This
confirms the physical input-to-local-activation path. It is not a localization,
model-accuracy, sustained-battery, or BVI usability result. The corresponding
typed gesture/command/result protocol remains covered by deterministic JVM
tests; a separately recorded physical phone acknowledgement should still be
captured before making a release claim about every gesture round-trip state.

### Durable-link physical evidence — 2026-08-26

A physical private-WLAN run exposed three causes previously reported together
as timeouts: a disabled diagnostic-spool capability still being polled, a
periodic clock sample exceeding the one-second quality ceiling, and YodaOS
process eviction during system-wide low-memory pressure. The protocol now
negotiates spool support exactly, retains the previous valid clock estimate
when an otherwise well-formed periodic sample is too slow, and re-enters the
visible nonvisual authorization broker after same-boot process recreation.
Malformed records, failed authentication, and impossible timestamps still fail
closed.

The attached devices sustained one session across repeated ten-second clock
rounds. A deliberate 20-second glasses Wi-Fi outage produced exactly one
observable interruption; Android Node stayed listening and the nodes
reauthenticated automatically after the radio returned. Camera and IMU delivery
resumed without either app being manually restarted. A complete ten-minute
lease later closed cleanly and a new session authenticated with no additional
interruption. After the immediate-rotation change, a subsequent physical
ten-minute boundary closed at 18:17:58.019, began rendezvous at 18:17:58.268,
and reported streaming at 18:17:59.408: about 1.14 seconds from the new
rendezvous to streaming, with no failure backoff or interruption increment.
The accessible status identifies a diagnostic retained from an earlier
interruption as previous history while a replacement session is streaming.
That 2026-08-26 evidence used mutual-TLS app traffic over the shared private
WLAN. The subsequently implemented and physically exercised phone-owned Wi-Fi
Direct route is documented in [Wi-Fi Direct transport](WIFI_DIRECT_TRANSPORT.md).

### YodaOS memory/radio hardening evidence — 2026-08-27

Read-only device evidence traced a later outage to system-wide memory pressure:
Android killed a vendor payment helper, concurrent dead binder calls terminated
the persistent Sprite assist service, and its restart loaded a private
`settings_wifi_enable=false` preference and disabled Wi-Fi/P2P. Rokid Node
itself stayed alive. This establishes why repeated socket reconnect attempts
could not succeed while the radio remained disabled.

The resolver now enters `WAITING_FOR_RADIO`, cancels retry/watchdog callbacks,
and performs no P2P operations while either Wi-Fi or P2P is reported disabled.
A physical forced outage produced one wait transition and zero repeated P2P
operation failures; after development-only radio restoration, the group
re-formed, mutual TLS reauthenticated, and capture resumed as session 2.

The post-gate camera path now converts borrowed YUV planes directly to the
640×640 RGB8 output and transfers ownership of that single fresh RGB array into
an immutable protobuf value. Packetization shares protobuf slices rather than
allocating one byte array per 64-KiB camera chunk. The
validated gate decisions, deterministic 648→640 no-crop downscale, intrinsics
transform, source timestamps, IMU gate, microphone gate, and touch semantics
did not change. A
final 30-second physical run delivered 80 of 81 observed frames and 1,277 IMU
samples with zero IMU drops, no disconnect, an authenticated close, sampled RSS
of 86.6--118.4 MiB, and no matching large-object GC message. See
[YodaOS runtime resilience](YODAOS_RUNTIME_RESILIENCE.md) for scope and limits.

A later exact-build one-shot diagnostic was terminated by LMKD, which Android
reported as `LOW_MEMORY`; the host's `FRAMING_TRUNCATED_RECORD` was the expected
secondary symptom of losing the sender mid-record. That one-shot command does
not persist recovery authorization. A subsequent production-mode test first
used `idle-enable`, then terminated only the app process. YodaOS recreated the
accessibility service in about 1.3 seconds, its same-boot broker rearmed the
start-requested runtime, and authenticated camera/IMU streaming resumed in
about 4 seconds without manual restart. Production resilience depends on the
persisted idle-control path; it does not make the process immune to LMKD.

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
this target fingerprint, the centered 648×648 capture derivation is
approximately `[fx, fy, cx, cy, s] = [407.1429, 407.1429, 324, 324, 0]`.
After the 648→640 transport scale it is approximately
`[402.1164, 402.1164, 320, 320, 0]`. It remains `DERIVED` with unquantified
parameter uncertainty. The pinned Depth Anything V2
Metric heads produce native scalar camera-frame metric output without consuming
intrinsics; missing or rejected intrinsics disable pixel-to-ray/vector geometry,
not that scalar inference.

`SENSOR_ORIENTATION` (including the observed 270-degree value) describes the
sensor-to-device-natural/display rotation and is not used as a mounting pose.
The target separately reports Camera2 `LENS_POSE_REFERENCE=PRIMARY_CAMERA`,
`LENS_POSE_ROTATION=[0,0,0,1]` in `(x,y,z,w)` order, and a zero translation.
Android's official semantics make that quaternion a usable rotation between
camera and Android sensor axes. The current Rokid pose producer uses the rigid
Android sensor frame as its glasses/head proxy, so the inverse identity rotation
is published with typed Camera2 provenance and a digest. This enables
capture-time head/world **directional** propagation.

The zero translation is not accepted as camera-to-head translation: under
`PRIMARY_CAMERA` its origin is the sole camera itself. Translation therefore
remains unavailable, and this path does not claim full 6-DoF point anchoring,
an anatomical eye/gaze calibration, or numeric factory alignment uncertainty.
Empirical checkerboard or ChArUco calibration of the exact acquired 648×648 YUV
and published 640×640 I420 geometry is still required before marking intrinsics
`CALIBRATED` or claiming calibrated spatial/angular accuracy. See
[Rokid camera-to-head extrinsic](ROKID_CAMERA_HEAD_EXTRINSIC.md)
and [Research evidence](RESEARCH_EVIDENCE.md).

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
broad adverse-network behavior, production deployment readiness, open-ear
localization quality, camera calibration, representative model accuracy, or
sustained thermal behavior. The later persistent-node run above establishes
one bounded interruption/reconnect path and Poco display-off continuity.

Verified and unverified physical behavior is recorded without inference in
[`VALIDATION.md`](../VALIDATION.md). No vendor SDK, client secret, proprietary
model weight, captured frame, or private device identifier is included here.
