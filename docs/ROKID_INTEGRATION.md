<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Direct non-display Rokid AI Glasses Style development

The target is **Rokid AI Glasses Style (Non-Display)**. It has no wearer-facing
display. The Android-reported framebuffer is a compatibility surface inside the
system and must never be treated as a HUD, user interface, or acceptance target.

CONCEPTFlow installs a standalone Android APK directly on the glasses through
the magnetic 5-pin data cable and authorized ADB. The runtime does not use Hi
Rokid, CXR-L, CXR-S, Glasses SDK/Phone SDK, client secrets, or a phone-mediated
installer. The Poco application is a separate CONCEPTFlow host; the planned
glasses/phone link is a project-owned authenticated transport.

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
  [command-line build guide](https://developer.android.com/build/building-cmdline)
  defines Gradle `assembleDebug`; and its
  [Camera2 guide](https://developer.android.com/media/camera/camera2) defines
  the camera API used here.
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
CONCEPTFlow did not disable or modify that system service. A later explicit
capture test acquired camera 0 through Camera2 and produced monotonic frames;
the result is recorded in `VALIDATION.md`. Physical speaker output, haptics,
microphones, and hardware button mappings remain separate empirical tests.

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

The implemented hardware boundaries are:

- `Camera2FrameSource`: bounded latest-only JPEG capture and monotonic frame IDs;
- `SensorManagerPoseSource`: rotation-vector, gyroscope, and acceleration data;
- `InspectableCueRenderer`: stale/duplicate/older-cue rejection;
- Android stereo audio and optional vibrator output; and
- deterministic, package-scoped commands for capture and development cues.

There is no microphone capture implementation yet. There is no touchpad,
screen, launcher, or wearer-facing visual state. Development state is
inspectable through safe ADB status/log commands. A product release must expose
capture state and stop control through the accessible phone host, distinctive
nonvisual feedback, and verified physical controls; ADB is not a user control.

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
of display prevents an operable runtime permission dialog, camera permission is
an explicit and separately logged action:

```bash
./scripts/rokid-install --serial "$ROKID_SERIAL" --no-build --grant-camera
```

The option grants only the APK's declared `android.permission.CAMERA`.

## Nonvisual development controls

```bash
./scripts/rokid-control --serial "$ROKID_SERIAL" status
./scripts/rokid-control --serial "$ROKID_SERIAL" capture-start
./scripts/rokid-control --serial "$ROKID_SERIAL" stop
```

`cue-left` and `cue-right` emit short development audio/haptic cues and must be
used only when the wearer expects them. `capture-start` fails closed when camera
permission is absent or Camera2 cannot acquire the device. `stop` finishes the
nonvisual activity; unbinding then closes the frame source, pose source, cue
transport, and audio output.

Equivalent nonvisual command:

```bash
adb -s "$ROKID_SERIAL" shell am start --user 0 -W \
  -n org.conceptflow.mpl.rokidclient/org.conceptflow.mpl.rokid.RokidCommandActivity \
  -a org.conceptflow.mpl.rokid.action.START_CAPTURE
```

## Diagnostics

Use the bounded helper first:

```bash
./scripts/rokid-control --serial "$ROKID_SERIAL" status
adb -s "$ROKID_SERIAL" logcat -d -s ConceptFlowRokid:I '*:S'
```

Logs contain state and monotonic frame IDs, never frame bytes. If Camera2
reports `CAMERA_IN_USE` or `MAX_CAMERAS_IN_USE`, stop this service and record
the conflict. Do not disable YodaOS security or services.

## Current boundary

The client currently captures locally and routes local development cues through
`InProcessCueTransport`. Direct sideload and nonvisual component execution do
not constitute a phone/glasses data plane. The next slice is an authenticated,
bounded protobuf transport between this service and the Android host, retaining
explicit capture state, TLS, cancellation, reconnect, size limits, and cue TTL.

Verified and unverified physical behavior is recorded without inference in
[`VALIDATION.md`](../VALIDATION.md). No vendor SDK, client secret, proprietary
model weight, captured frame, or private device identifier is included here.
