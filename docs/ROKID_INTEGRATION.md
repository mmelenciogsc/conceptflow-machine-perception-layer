<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Rokid integration

The public Rokid client is an Android-standard capture and cue-rendering
baseline with explicit seams for proprietary SDKs. It can be built and
installed without a vendor AAR. A complete physical Rokid-to-Poco transport is
not implemented in this repository.

## Verified attached-device evidence

An attached consumer device was observed on 2026-08-21 with:

- ADB model `RG_glasses`;
- Android 12 / API 32;
- YodaOS Sprite assist service 0.3.5;
- CXR service package `com.rokid.cxrservice`, version 12, target API 32; and
- direct ADB connectivity over the magnetic 5-pin cable.

This establishes device identity and a working development connection. It does
not establish CXR-S API compatibility, phone relay transport, continuous frame
delivery, cue delivery, performance, accessibility, or production readiness.

## Public implementation

`apps/rokid-client` targets standard Android APIs:

- `Camera2FrameSource` selects a Camera2 device and bounded JPEG size, uses a
  two-image `ImageReader`, consumes the latest frame, applies a byte limit, and
  emits monotonic `FramePayload` values.
- `SensorManagerPoseSource` uses rotation-vector, gyroscope, and linear
  acceleration sensors to produce a head-frame pose and motion sample.
- `SyntheticFrameSource` and `SyntheticPoseSource` support deterministic tests.
- `FrameValidator` checks identity, dimensions, JPEG metadata, byte count, and
  monotonic frame/timestamp ordering.
- `InspectableCueRenderer` applies TTL, frame ordering, and duplicate checks,
  records a bounded event history, and maps directional earcons to stereo
  balance with optional haptics.
- `PlatformStereoAudioOutput` and `PlatformHapticOutput` use Android
  accessibility audio usage and the platform vibrator.

`MainActivity` currently displays local capture status and routes manually
triggered test cues through `InProcessCueTransport`. Captured Camera2 frames are
not sent to the phone or Python service.

## Proprietary adapter boundaries

The source intentionally keeps two SDK families distinct:

- `CxrSpriteBridge` / `CxrSpriteAdapter` is the seam for a licensed CXR-S-style
  device integration in a private build.
- `Glass3EnterpriseBridge` / `Glass3EnterpriseAdapter` is a separate seam for
  the modern Glass3 enterprise SDK family.

`attach(null)` fails with an explicit message in both cases. No proprietary
CXR-S AAR, vendor credential, or invented vendor method is included. A private
adapter must implement only APIs verified in its licensed SDK and must preserve
the public `CueTransport` behavior.

CXR-L refers to the Hi Rokid phone bridge. It is not the generic name for the
modern Glass3 SDK and must not be described as one.

## Glass3 reference boundary

The modern official repository
[`RokidSuuport/glass3_sdk_demo`](https://github.com/RokidSuuport/glass3_sdk_demo)
was observed at commit `16380658` on 2026-08-21. At that revision it distinguishes:

- `glass3.open.sdk:2.2.0-E`, the glasses SDK;
- `phone.sdk:2.2.0-E`, the phone SDK;
- `GlassSdk` and `PSecuritySDK`; and
- Bluetooth and Wi-Fi P2P responsibilities.

These observations define a separate optional Glass3 adapter investigation.
They do not prove binary, protocol, service, or hardware compatibility with the
attached consumer `RG_glasses` / YodaOS device. Do not put Glass3 dependencies
into the CXR-S adapter or present Glass3 samples as validation of that device.

## Build, install, and launch

Prerequisites are JDK 17, an Android SDK with API 36 build components, ADB, and
an authorized device. Build from the repository root:

```bash
./gradlew --no-daemon :apps:rokid-client:testDebugUnitTest \
  :apps:rokid-client:assembleDebug
adb devices -l
read -r -p "Rokid ADB serial: " ROKID_SERIAL
adb -s "$ROKID_SERIAL" install -r \
  apps/rokid-client/build/outputs/apk/debug/rokid-client-debug.apk
adb -s "$ROKID_SERIAL" shell am start -W \
  -n org.conceptflow.mpl.rokidclient/org.conceptflow.mpl.rokid.MainActivity
```

When both glasses and phone are attached, always pass the intended serial.
These commands install and launch the public standard-API app. They do not
install a vendor SDK or create the missing phone-to-glasses transport.

## Physical Rokid/Poco validation sequence

Use only synthetic or consented non-sensitive scenes.

1. Record exact glasses firmware, Android API, service package versions, phone
   model/OS, app revision, cable, and ADB route.
2. Build both APKs and install the Rokid client on the glasses and Android host
   on the Poco using explicit serials.
3. On the glasses, verify launch, camera permission denial/grant, capture start,
   monotonic frame status, stop, pause/background stop, sensor fallback, and
   left/right local test cues.
4. On the Poco, verify capability reporting and the in-process synthetic flow.
5. Treat the two checks as independent. The current public build has no
   inter-device exchange to observe.
6. After a real private adapter exists, separately validate pairing,
   authentication, bounded frame exchange, disconnect/reconnect, cancellation,
   stale rejection, cue delivery, capture stop, and accessible status.
7. Measure physical glass-to-cue latency using
   [LATENCY_BENCHMARKING.md](LATENCY_BENCHMARKING.md); software timestamps alone
   cannot prove perceived latency.

The attached consumer device’s direct magnetic-cable ADB path is verified. A
Poco-mediated install, CXR-L flow, or Glass3 P2P flow is outside this repository
and must not be inferred from that fact.

## Adapter acceptance criteria

A vendor adapter is acceptable only when it is isolated in a private licensed
build; pins SDK and firmware compatibility; has no hard-coded credentials;
requires explicit capture state; bounds frame size/rate/queue; propagates stop
and cancellation; maps timestamps and coordinate frames explicitly; rejects
stale cues; handles link loss without hidden retry storms; exposes accessible
state; and has device evidence for the exact hardware family.

## Verified and unverified

Verified locally: the shared Android build uses Gradle 8.11.1, AGP 8.10.1,
Kotlin 2.0.21, and JDK 17; 23 Rokid-client JVM tests and 50 tests across all
Android modules passed, along with both debug APK builds. Source and tests cover
Camera2/SensorManager adapters, synthetic adapters, proprietary seam
separation, and inspectable cue behavior.

Not verified: a proprietary CXR-S implementation, Glass3 integration, physical
phone-to-glasses data transport, real remote inference, physical cue quality,
or TalkBack usability. See [`VALIDATION.md`](../VALIDATION.md).

No proprietary model weights, SDK AARs, camera captures, or private brand media
are included in this repository.
