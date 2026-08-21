<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Direct Rokid/YodaOS-Sprite development

The canonical CONCEPTFlow path is a standalone Android APK running directly on
the Rokid glasses. Development deployment is over the magnetic 5-pin USB data
cable and ADB. The runtime does not use Hi Rokid, CXR-L, CXR-S, Glass3 SDKs,
Rokid client secrets, access keys, or a phone-mediated APK installer.

The Poco app is a separate CONCEPTFlow host. It will exchange bounded frames,
metadata, and cues with the glasses app through a project-owned authenticated
network transport. It is not a Rokid companion-SDK bridge.

## Evidence and confidence

The following sources were checked on 2026-08-21:

- Rokid's official
  [Rokid Glasses Prototype product page](https://global.rokid.com/products/rokid-glasses-prototype)
  states that the developer cable enables ADB debugging. This is the strongest
  product-specific evidence for the cable's intended development function.
- Android's official [ADB documentation](https://developer.android.com/tools/adb)
  documents selecting one of multiple devices with `-s`, installing an APK
  with `adb install`, and issuing shell commands.
- Android's official
  [command-line build documentation](https://developer.android.com/build/building-cmdline)
  documents Gradle wrapper builds with `assembleDebug` and the resulting
  installable, debug-signed APK.
- Android's official [Camera2 documentation](https://developer.android.com/media/camera/camera2)
  defines the platform camera API used by this app.
- The community-maintained GlassKit
  [setup reference](https://github.com/RealComputer/GlassKit/blob/246de4790a007f9b9be180eba5e92a6965da3b2a/skills/glasskit/references/rokid-setup.md)
  and
  [input reference](https://github.com/RealComputer/GlassKit/blob/246de4790a007f9b9be180eba5e92a6965da3b2a/skills/glasskit/references/rokid-inputs.md)
  corroborate direct ADB build/install/launch, standard Android camera and
  microphone APIs, portrait presentation, and touchpad key-event mappings.
  These are useful community findings, not official Rokid guarantees.
- Marcin Miazga's independent
  [development-cable guide](https://marcinmiazga.com/rokid-development-cable)
  reports that the ordinary 3-pin charging lead has no ADB data path, while
  the 5-pin development cable supports `adb devices` and `adb install`.

The project decision to exclude credentialed vendor SDKs is a deliberate scope
constraint. Public sources can change and do not establish vendor eligibility
rules for every region or account, so this repository does not make broader
claims about Rokid's commercial program.

## Verified attached-device evidence

Read-only inspection on 2026-08-21 reported:

- manufacturer `Rokid`;
- model `RG-glasses` (ADB's device listing rendered it as `RG_glasses`);
- product and device `glasses`;
- Android 12 / API 32;
- YodaOS Sprite assist service 0.3.5; and
- direct authorized ADB over the magnetic 5-pin cable.

The current debug APK has been installed and its activity started by direct
ADB. The vendor assist service held the camera during that check, so physical
frame capture and audio/cue quality remain unverified. The application fails
closed when Camera2 cannot acquire the device; this repository does not stop or
disable a system service to seize the camera.

## Application model

`apps/rokid-client` is a normal Kotlin/Android application. It has no Rokid
Maven repository, vendor AAR, vendor credential, or reflection against hidden
Rokid APIs. Its current hardware-facing code uses:

- Camera2 for bounded JPEG frames;
- `SensorManager` for rotation vector, gyroscope, and linear acceleration;
- ordinary Android key/focus behavior for touchpad navigation;
- Android audio output with accessibility usage; and
- the platform vibrator when present.

The module has `minSdk = 29`, so the observed API-32 device satisfies its
runtime minimum. `compileSdk` and `targetSdk` are 36; those values select build
and compatibility behavior and do not require the device itself to run API 36.
The activity is portrait-locked. It gives the capture control initial focus so
the usual Rokid `DPAD_UP`, `DPAD_DOWN`, `ENTER`, and `DPAD_CENTER` events can
navigate and activate standard Android buttons. The camera hardware key also
toggles capture.

`Camera2FrameSource` assigns monotonic frame identifiers, uses a two-image
`ImageReader`, consumes only the latest image, bounds frame size, and closes
camera resources on stop or activity pause. `SensorManagerPoseSource` emits
monotonic motion samples. `InspectableCueRenderer` rejects expired, duplicate,
and older cues before bounded stereo/haptic output.

## Cable setup

1. Use the Rokid magnetic **5-pin development/data cable**. The ordinary 3-pin
   charging lead is not an ADB cable.
2. Enable USB debugging on the glasses once through the device-supported setup
   path. A companion application may be needed for this one-time firmware
   setting; it is not part of the CONCEPTFlow build, installation, or runtime.
3. Connect the cable directly to the Ubuntu machine and run:

   ```bash
   adb devices -l
   ```

4. The device must be listed with state `device`. `unauthorized` means the ADB
   host key has not been accepted; `offline` is not a usable connection. Because
   this hardware has no conventional touch UI, preserve an already-authorized
   development host. This repository does not bypass ADB authorization.

When the Poco and glasses are both attached, never run an unqualified install
command. Every ADB mutation must use the glasses serial.

## Build, install, and launch

The serial-safe helper identifies the target as Rokid glasses before it changes
anything:

```bash
read -r -p "Rokid ADB serial: " ROKID_SERIAL
./scripts/rokid-install --serial "$ROKID_SERIAL" --inspect-only
./scripts/rokid-install --serial "$ROKID_SERIAL"
```

The second command builds the debug APK, installs it with `adb -s ... install
-r`, and starts the explicit activity. Always use the serial printed by your
own `adb devices -l`.

Equivalent manual commands are:

```bash
./gradlew --no-daemon --dependency-verification strict \
  :apps:rokid-client:testDebugUnitTest \
  :apps:rokid-client:assembleDebug
adb devices -l
read -r -p "Rokid ADB serial: " ROKID_SERIAL
adb -s "$ROKID_SERIAL" install -r \
  apps/rokid-client/build/outputs/apk/debug/rokid-client-debug.apk
adb -s "$ROKID_SERIAL" shell am start -W \
  -n org.conceptflow.mpl.rokidclient/org.conceptflow.mpl.rokid.MainActivity
```

For a prebuilt debug APK, use `./scripts/rokid-install --serial SERIAL
--no-build`. `--no-launch` installs without starting it.

## Permission provisioning on a non-touch device

The app declares only camera and vibration permissions. It requests camera
permission through the standard Android runtime API and keeps capture stopped
when permission is denied. If the YodaOS build cannot present an operable
runtime permission dialog, the developer may explicitly provision the declared
camera permission through the already-authorized ADB connection:

```bash
./scripts/rokid-install --serial "$ROKID_SERIAL" --grant-camera
```

That option grants only `android.permission.CAMERA` to this package after
installation. It is opt-in, prints what it changed, and is intended for a
controlled development device. Verify the current state without exposing other
package data:

```bash
adb -s "$ROKID_SERIAL" shell dumpsys package \
  org.conceptflow.mpl.rokidclient | grep -F android.permission.CAMERA
```

## Direct diagnostics

```bash
adb -s "$ROKID_SERIAL" shell pidof org.conceptflow.mpl.rokidclient
adb -s "$ROKID_SERIAL" shell dumpsys activity activities | \
  grep -F org.conceptflow.mpl.rokidclient
adb -s "$ROKID_SERIAL" logcat --pid="$(adb -s "$ROKID_SERIAL" shell pidof \
  org.conceptflow.mpl.rokidclient | tr -d '\r')"
```

If Camera2 reports `CAMERA_IN_USE` or `MAX_CAMERAS_IN_USE`, record the conflict
and stop capture in this app. Do not disable YodaOS security or system services
as an installation shortcut. A test build must also avoid raw-frame log output.

## Current boundary and next implementation

Direct sideloading proves installation and standalone execution; it does not
create the application data plane. The current activity captures only locally
and routes manually generated cues through `InProcessCueTransport`.

The next executable slice is a project-owned, authenticated connection between
the glasses APK and the Android host or Ubuntu service, using the canonical
protobuf contract. It must retain explicit capture state, bounded queues and
frame sizes, TLS outside loopback, cancellation, reconnect, stale-result
rejection, and cue TTL. No Hi Rokid or credentialed Rokid SDK is part of that
milestone.

## Verified and unverified

Verified locally: standard Android source, dependency graph with no Rokid SDK,
Gradle/JDK build, 22 Rokid-client JVM tests, Android Lint, debug APK creation,
direct ADB target inspection, and current-revision direct install/activity-start
on the observed glasses. The installed process remained live and camera
permission was granted by the explicit development option.

Not yet verified: foreground rendering or touchpad mappings in this run because
the 480x640 built-in display reported `OFF` and Android kept the activity
sleeping; Camera2 capture while YodaOS services are active; physical
audio/haptics; glasses-to-Poco transport; continuous remote inference; or
perceived latency. See [`VALIDATION.md`](../VALIDATION.md).

No vendor SDK, proprietary model weight, camera capture, or private brand asset
is included in this repository.
