<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Accessible reality interaction

This document defines the current focused-object interaction boundary across
Android Node, the standalone Unity laboratory, and FMOD. The feature is for
supplemental awareness. It is not a navigation system, safety authority,
mobility aid, object-identification guarantee, or substitute for direct
observation and trained judgment.

## Evidence status

“Implemented” below means that the named contract exists in the repository. It
does not imply physical usability, perceptual accuracy, or release readiness.

| Area | Implemented | Deterministic validation | Physical status |
| --- | --- | --- | --- |
| Android linear focus and accessible phone controls | Focus state machine, four commands, 750 ms dwell, three-option action menu, stale-target rejection, TalkBack-facing state, and shell-only debug controls in debuggable builds | Android JVM tests cover ordering, dwell generations, menu transitions, VQA correlation, beacon admission, expiry, and reset | The debug path was dispatched on the Poco while live detections briefly appeared, but the target expired before the multi-command dwell/menu sequence completed; this is not a target-user or TalkBack acceptance run |
| Android focused VQA | Explicit-only typed request, bounded exact-frame retention, controller-wired asynchronous gateway, bounded correlation and text, one shared VLM slot, and QNN-priority cancellation | Android JVM tests cover task parsing, input/output bounds, exact source lookup, asynchronous gateway behavior, stale response rejection, reset, and HTP admission | Focused VQA through GenieX on HTP has not been run on the target device |
| Android-to-Unity Binder | Signature-protected service and bounded `CFWS`, `CFFS`, `CFHP`, and `CFTB` payloads | Android dispatcher/codec tests, Java lifecycle/version tests, and Unity decoder tests exercise valid and malformed packets; the Java cache explicitly admits legacy `CFFS` v1 and beacon `CFFS` v2 only | Same-signed Android Node and private licensed-lab builds were installed and remained live together on the Poco; no stable live target reached a nonempty focused-state Binder assertion |
| Unity focused sonification | Strict world/focus/head joins, explicit canonical-to-Unity handedness mapping, one active focused icon maximum, and a listener transform driven by the accepted canonical head pose | Unity 6000.3.22f1 ran 28/28 EditMode and 3/3 PlayMode tests and produced an ARM64 IL2CPP Android player | A privately staged licensed FMOD 2.03.14 player loaded its banks and dispatched the deterministic geometry scene to the Bluetooth glasses route without FMOD/Unity runtime errors; focused-icon localization, loudness, and open-ear listening remain unvalidated |
| Beacon | Two-tier admission, immutable bounded anchor, Binder encoding, and Unity/FMOD rendering | Android JVM and Unity tests cover world preference, no-world fallback, reference-pose freshness, source-track expiry, decoder rejection, head-turn stability, pulse cadence, and TTL | The orientation-stabilized relative tier is executable without WORLD translation; it does not track user translation and has not had open-ear localization acceptance testing |
| Glasses input | Ordered touch transport and separately governed Rokid observation code | Kotlin tests cover device identity, sequence grammar, timing, reset, and observe-only policy | Several raw gestures were observed on one RV203 firmware, but focus-command admission remains disabled because collision-free semantics were not established |

The relevant deterministic suites are `SpatialFocusTest`,
`PerceptionFocusCodecTest`, `PerceptionIpcDispatcherTest`,
`LocalVlmFocusedObjectTest`, `FocusedVqaFrameStoreTest`,
`LiveVlmHtpAdmissionTest`,
`AndroidPerceptionBridgeTests`, `FocusedObjectAndInteractionTests`, and
`PerceptionLabPlayModeTests`. Passing them is not device evidence.

## Ownership and data flow

```text
Accessible phone controls
        |
        v
Android SpatialFocusManager -----> Android focus status + TalkBack announcement
        |                                      |
        | CFFS v2 focus + optional beacon      | explicit VQA request
        v                                      v
signature-protected Binder             isolated GenieX VLM service
        |
        | CFWS world + CFFS focus + CFHP head pose
        v
Unity strict join and coordinate adapter -----> one FMOD focused icon
```

Android is authoritative for the selected item, focus generation, menu state,
VQA correlation, beacon admission, and spoken accessible state. Unity receives
only enough compact state to render a correlated auditory icon or an immutable
time-bounded beacon. Unity does not select an object, interpret raw touch as a
focus command, call VQA, choose an anchor tier, or replace Android TalkBack.

## Exact linear focus model

A new live session builds a snapshot only from unexpired tracks that have both
a HEAD-relative vector and metric depth. Initial order is:

1. nearest metric distance;
2. clock-face hour;
3. class identifier; and
4. stable track identifier.

Later updates preserve the order of surviving stable track IDs and append new
items in the same deterministic order. They do not continuously reorder the
list around the wearer. If the selected target expires or disappears, focus is
cleared rather than silently transferred to a different object.

The state starts `INACTIVE`. `Next` or `Previous` enters `BROWSING` at the first
item. While browsing, `Next` and `Previous` move one item and clamp at the list
ends; they do not wrap. Each real selection change increments the focus
generation and starts a fixed 750 ms dwell. Before the deadline the phone status
displays that it is moving to an item. At `READY`, Android announces this
TalkBack phrase:

```text
<class>. <1-12> o'clock. about <rounded feet> foot/feet away.
```

The clock hour is derived from the HEAD vector and distance is converted from
meters, rounded to the nearest foot, and clamped to at least one foot. This is
an approximate interaction phrase, not a calibrated navigation instruction.
Changing selection cancels the old dwell generation. Repeating a clamped move
at the same item does not restart an already pending or completed dwell.

`Activate` from `BROWSING` or `VQA_RESULT` opens a linear three-item menu at
the first option. Menu order is exactly:

1. `Ask about this object` (`VQA`)
2. `Start beacon` (`BEACON`)
3. `Back`

`Next` and `Previous` also clamp within this menu. `Activate` chooses the
current option. The `Back` command returns from a menu, result, pending VQA, or
active beacon to browsing; a second `Back` from browsing exits focus. Back also
cancels the exactly correlated pending VQA or deactivates the beacon. Movement
commands do nothing while VQA is pending. Moving from a VQA result clears the
answer; moving from an active beacon first deactivates it.

Ordinary focus state is short lived. Published state is bounded to 1.5 seconds
and the selected track's earlier expiry wins. An admitted beacon instead owns
an immutable maximum 30-second lifetime so detector-track expiry does not erase
the user-requested bearing immediately. A new session or explicit reset clears
selection, dwell, VQA, notices, and every beacon.

## Android TalkBack ownership

The phone Activity provides stock `Previous`, `Next`, `Activate`, and `Back`
buttons with explicit directional focus order. D-pad left/right, center or
Enter, and Escape provide the same four commands for attached-key testing.

`MainActivity` renders the authoritative focus state in an Android view and
uses `announceForAccessibility` only when
`SpatialFocusAnnouncementPolicy` identifies a meaningful new state: dwell
ready, menu option, VQA pending/result/rejection, beacon active/rejection,
empty state, or inactive state. High-rate revisions do not each cause an
announcement. The application-wide Android speech adapter suppresses its own
TTS while an accessibility service is active, avoiding a second application
voice over TalkBack.

The Unity `NonvisualInteractionPresenter` is a deterministic lab presenter for
externally supplied menu state. It queues fixed phrases and issues an FMOD duck
command, but it does not invoke a screen reader or accept generated VQA text.
Its separate 1.2-second lab default is not the Android focus model's 750 ms
dwell and is not the source of Android TalkBack speech.

## Signature Binder boundary: CFFS and CFHP

The separately installed Unity player binds explicitly to Android Node's
`PerceptionBusIpcService`. Both applications must be signed with the same
trusted certificate because the service requires
`org.conceptflow.mpl.androidhost.permission.READ_PERCEPTION_BUS` at signature
protection level. The service also checks the permission for each transaction.
A different debug key fails closed.

All calls use descriptor
`org.conceptflow.mpl.host.realtime.IPerceptionBridge`. The Unity Android plug-in
performs synchronous Binder work only on its private `HandlerThread`; Unity
callers read defensive copies of cached payloads. Binder death, disconnection,
or a malformed Binder reply that raises in the plug-in clears the caches and
revision counters before bounded rebind. The C# decoders independently reject
malformed cached payloads.

The focus and head signatures are deterministic big-endian payloads:

| Signature | Transaction | Bound | Payload |
| --- | ---: | ---: | --- |
| `CFFS` v2 | 2 | 1,024 bytes | v1 focus fields plus explicit mode and an optional immutable world or relative beacon record |
| `CFHP` | 3 | 256 bytes | reserved flags, sequence, session generation, monotonic sample time, accuracy, and normalized quaternion `w,x,y,z` |

Both decoders require exact magic and version, complete consumption with no
trailing bytes, legal lengths and numeric fields, and coherent presence flags.
Outside beacon mode, `CFFS` is a focus pointer into `CFWS`. In beacon mode it
also carries the exact frozen anchor needed after the source track expires:
anchor tier, label and stable identity, source frame/time, activation and
expiry, confidence, distance, optional uncertainty, vector, and, for a relative
anchor, the activation-time head quaternion. It never carries menu state,
spoken phrase, VQA content, raw IMU history, camera frames, microphone samples,
or model tensors. The Unity decoder accepts v1 only as legacy browsing state.
The Java cache in front of that decoder independently accepts `CFFS` versions
1 and 2 while retaining version 1 for every other snapshot type. Unknown or
cross-message versions fail closed before reaching Unity.

## Unity and FMOD focused-object contract

Unity joins `CFFS` to a fresh `CFWS` entity by exact stable track ID and session
generation. It rejects expired world or focus state, a focus reference to a
future world revision, a missing position, invalid numbers, stale entities,
and unmatched tracks. Only HEAD and WORLD positions are eligible; CAMERA
positions are deliberately not guessed.

Direct `FocusedObjectSonification` construction still defaults to
`unverified/fail-closed/v1`, which maps nothing. The Android-backed laboratory
controller explicitly installs
`conceptflow-canonical-rh-to-unity-lh/z-reflection/v1`. That adapter performs
the documented protocol handedness conversion and nothing more. Rendering
requires a successfully mapped, matching-session head pose no more than 250 ms
old. Ordinary focused entities also require a pose within 250 ms of their
output. If any check fails, Unity stops the focused icon.

`FocusedObjectSonification` creates at most one `FocusedIconCommand`.
Changing the track or event replaces the prior FMOD event; clearing or expiry
stops it. The authored `FocusedObject` event and its `AuditoryIcons` bus are
also configured for one instance. The 3D event accepts only the bounded
parameters `IconConcept`, `IconSalience`, `IconConfidence`, `DistanceMeters`,
`BeaconMode`, and `DwellSpeechActive`; distance is clamped to the authored
0–8 m range. `BeaconMode` distinguishes ordinary focus, world anchors, and
relative bearings. The
authored `DwellSpeechActive` curve is the sole focused-icon gain reduction, and
the runtime initializes that parameter even when an icon is created during
active dwell speech. The interface-state event is nonspatial.
`FmodStudioPerceptionAudioBackend` is compiled only when a consumer installs
the licensed FMOD Unity integration and defines `CONCEPTFLOW_FMOD_UNITY`;
otherwise the lab uses the inspectable command backend.
The backend source was also compiled against the locally installed FMOD Unity
2.03.14 assembly from the existing FiveAgainstWhen project. That verifies the
typed API surface, not runtime playback or listening quality.

The registry contains four representational concepts and one neutral fallback:

| Concept | Covered labels | Procedural asset key |
| --- | --- | --- |
| person | person, adult, child | `procedural/soft_footfall_pair` |
| door | door, doorway | `procedural/restrained_latch` |
| bicycle | bicycle | `procedural/short_freewheel` |
| vehicle | vehicle, car, van, bus, truck, motorcycle | `procedural/subdued_tire_texture` |
| neutral | every unknown or blank class | `procedural/neutral_presence` |

The neutral sound is explicitly non-representational; an unknown class is not
assigned a plausible real-world sound. The mapping is a small interaction
vocabulary, not evidence that the classifier is correct. FMOD project
structure and bounded voice behavior are testable, but actual focused-icon
playback, direction, loudness, masking, and learnability need physical and
target-user evaluation.

## VQA boundary

Focused VQA uses task identifier `FOCUSED_OBJECT_VQA_V1` and can be admitted
only by explicit activation of the VQA menu item. It is not driven by camera
cadence. The current generated question asks for a brief description of the
selected class and forbids safety or unseen-detail inference.

Admission requires an unexpired source frame no more than 1.5 seconds old, a
two-second cooldown, and no active VQA. The gateway and isolated service carry
the exact focus request ID, session generation, snapshot ID, focus generation,
stable track ID, source frame ID, source capture time, and request time. A
mismatch, stale result, cancellation, or superseded session cannot become an
answer.

The question is normalized and limited to 192 characters and 384 UTF-8 bytes.
The answer is plain text limited to 240 characters, 512 UTF-8 bytes, and 20
words. Inference is limited to 48 output tokens and eight seconds; the client
rejects a response older than nine seconds. The implementation logs task and
timing metadata, not the question, answer, or image content.

VQA and environment classification share the pinned Qwen3-VL-2B GenieX
wrapper. Binding no longer starts eager prewarm because that allowed the VLM to
win the shared HTP lease while the first YOLO/depth frame waited. The first
admitted environment or focused-VQA request now starts prewarm after the
caller's QNN dispatch has released its lease. One gate allows only one total prewarm or inference
operation, and one atomic slot allows only one environment or VQA request.
There is no request queue and no silent replacement: contention returns busy.
QNN work retains priority through the existing HTP lease and cooperative
cancellation path.

The live controller retains only exact source frames in process memory: at
most eight aspect-preserved RGB8 frames, each with a longest edge of 640
pixels, with a 1.75-second TTL and a 9,830,400-byte aggregate ceiling. Buffers
are zero-filled on eviction and reset. Exact lookup requires both the source
frame ID and capture timestamp. Optional context cropping and JPEG encoding
run only after an explicit VQA request on the gateway's single worker with a
one-item queue; they are not part of camera-cadence admission.

The JPEG is published through an app-private temporary file, atomically named,
size- and SHA-256-correlated, canonical-path checked by the isolated service,
and deleted on terminal paths. A submission result means only accepted work;
it is not an answer. The focus manager accepts a VQA result only after an exact
correlated response, and failures remain failures rather than synthetic
successes.

The live controller now installs this gateway, retains decoded source frames,
publishes correlated answers or typed rejections back into the focus manager,
and resets the path on session changes. The typed lane, parser, lifecycle,
exact-frame store, and asynchronous gateway have JVM coverage. No focused VQA
request has yet been validated through the provisioned GenieX runtime on the
Poco HTP, so no device latency, answer quality, thermal, or reliability claim
is made.

The revised startup order was physically observed on the Poco: the first
YOLO/depth QNN lease completed before VLM prewarm, prewarm then completed in
approximately 8.5--9.0 seconds, and an indoor/outdoor classification completed
in approximately 4.3--4.4 seconds before QNN processing resumed. These are two
short runs, not sustained latency, energy, or thermal results.

## Beacon boundary

The `Start beacon` option is a request for a supplemental spatial bearing, not
a navigation claim. Every admission requires a non-expired observation no
more than 500 ms old, confidence of at least 0.65, fresh metric depth, and a
usable HEAD vector. Android then selects one of two truthful tiers:

- `WORLD_ANCHORED` is preferred only when a WORLD point is marked
  `TRANSLATION_EVIDENCE_PROPAGATED` and its quantified uncertainty is no more
  than the smaller of 0.75 m and 25% of object distance.
- `ORIENTATION_STABILIZED_RELATIVE` is admitted otherwise, but only with a
  normalized HEAD orientation sampled no more than 250 ms before activation.
  It freezes the metric HEAD vector in the activation orientation. Later head
  turns do not rotate the bearing, but the origin follows the listener because
  translation is unavailable.

The second tier directly supports bearing retention on current rotation-only
hardware without inventing WORLD coordinates. It is announced as a relative
bearing with translation not tracked. It remains active for at most 30 seconds,
survives the source detector track's short TTL, emits a brief spatial pulse at
most every 1.5 seconds, and stops on Back, focus movement, session change, or
expiry. Object-facing pose is not inferred because the current perception
contract does not measure it. See [Spatial beacon anchoring](BEACON_ANCHORING.md).

## Why glasses focus gestures are disabled

The Rokid input work provides physical observations, not a safe focus mapping.
On the tested RV203 firmware:

- touch-surface swipes produced Android volume keys, so consuming them could
  interfere with system volume;
- one-finger tap/hold sequences used `PROG1`, and the long-press path belongs
  to native Talk-to-AI behavior;
- a broad two-finger hold produced a non-ordered Settings action whose OEM
  shortcut behavior depends on a device setting;
- dedicated two-finger double-tap and swipe broadcasts were not observed even
  though recognizer properties were enabled, while generic preamble pulses
  could not establish finger count; and
- raw `getevent` visibility did not prove that the same event reaches an
  ordinary application or `AccessibilityService`.

The top-right physical button is wholly excluded. Its camera/photo, video,
power, and pairing behavior is system-owned; raw scan 139 is overridden by
YodaOS to vendor `SPRITE_FUNCTION` and is not mapped to focus.

Accordingly, `LiveMachineVisionController` defaults to
`DisabledSpatialFocusTouchAdmission`, and the Unity controller does not
interpret `CFTB` events. Glasses events may still be transported for bounded,
ordered inspection, but none currently means `Next`, `Previous`, `Activate`,
or `Back`. The existing Rokid command recognizer is a separate, observe-only by
default path for Node and microphone control; it must not be repurposed as
focus input without collision-free physical evidence and an explicit policy.

Current focus control is therefore the accessible Android phone UI and its
attached-key equivalents. A future hardware mapping must preserve native
Talk-to-AI, volume, Settings, camera, video, power, pairing, accessibility, and
immediate-stop behavior before it can be enabled.

For physical diagnosis only, a debuggable Android Node also exposes
`focus-status`, `focus-next`, `focus-previous`, `focus-activate`, and
`focus-back` through `scripts/android-node-control`. These endpoints are absent
from release builds and do not change TalkBack or device settings. They test
the same authoritative command path; they are not an alternate production
input system.

## Release boundary

Do not describe this work as collision avoidance, wayfinding, safe navigation,
object truth, or autonomous assistance. Before a release claim, run the full
TalkBack workflow with blind and low-vision participants; exercise the signed
two-APK Binder path; measure focused-icon localization and masking with the
actual open-ear output; establish a collision-free glasses input vocabulary;
validate relative-beacon localization and, separately, WORLD translation and beacon uncertainty; and run focused VQA through
GenieX/HTP under representative latency, cancellation, thermal, and failure
conditions.

Related detail: [Android perception Binder IPC](ANDROID_PERCEPTION_BINDER_IPC.md),
[Accessibility](ACCESSIBILITY.md), [Rokid integration](ROKID_INTEGRATION.md),
[Coordinate frames](COORDINATE_FRAMES.md), [Auditory icons](AUDITORY_ICONS.md),
[Unity/FMOD lab](UNITY_FMOD_LAB.md), and
[Android QNN private runtime](ANDROID_QNN_PRIVATE_RUNTIME.md).
