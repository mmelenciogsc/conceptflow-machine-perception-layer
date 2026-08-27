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
| Android linear focus and accessible phone controls | Focus state machine, four commands, 750 ms dwell, three-option action menu, stale-target rejection, and TalkBack-facing state | Android JVM tests cover ordering, dwell generations, menu transitions, VQA correlation, beacon admission, expiry, and reset | The complete focus workflow has not had a target-user or TalkBack device acceptance run |
| Android focused VQA | Explicit-only typed request, bounded exact-frame retention, controller-wired asynchronous gateway, bounded correlation and text, one shared VLM slot, and QNN-priority cancellation | Android JVM tests cover task parsing, input/output bounds, exact source lookup, asynchronous gateway behavior, stale response rejection, reset, and HTP admission | Focused VQA through GenieX on HTP has not been run on the target device |
| Android-to-Unity Binder | Signature-protected service and bounded `CFWS`, `CFFS`, `CFHP`, and `CFTB` payloads | Android dispatcher/codec tests and Unity decoder tests exercise valid and malformed packets | A same-certificate, two-APK Binder session has not been physically accepted |
| Unity focused sonification | Strict world/focus/head joins, fail-closed coordinate adapter, and at most one active focused icon | Unity EditMode and PlayMode tests cover correlation, freshness, mapping failure, replacement, dwell cancellation, and voice limits | Focused-icon FMOD playback, localization, loudness, and open-ear listening are not physically validated |
| Beacon | Quality gate and menu state/effect | Android JVM tests cover eligible and rejected quality states | No end-to-end guidance renderer is claimed, and current physical data lacks verified WORLD translation |
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
        | CFFS focus identity                  | explicit VQA request
        v                                      v
signature-protected Binder             isolated GenieX VLM service
        |
        | CFWS world + CFFS focus + CFHP head pose
        v
Unity strict join and coordinate adapter -----> one FMOD focused icon
```

Android is authoritative for the selected item, focus generation, menu state,
VQA correlation, beacon admission, and spoken accessible state. Unity receives
only enough compact state to render a correlated auditory icon. Unity does not
select an object, interpret raw touch as a focus command, call VQA, start
beacon guidance, or replace Android TalkBack.

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

Focus state is short lived. Published state is bounded to 1.5 seconds and the
selected track's earlier expiry wins. A new session or explicit reset clears
selection, dwell, VQA, notices, and the prior snapshot.

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

The focus and head signatures are version-1, big-endian payloads:

| Signature | Transaction | Bound | Payload |
| --- | ---: | ---: | --- |
| `CFFS` | 2 | 1,024 bytes | flags, focus revision, session generation, source world revision, update time, expiry, and focused stable track ID |
| `CFHP` | 3 | 256 bytes | reserved flags, sequence, session generation, monotonic sample time, accuracy, and normalized quaternion `w,x,y,z` |

Both decoders require exact magic and version, complete consumption with no
trailing bytes, legal lengths and numeric fields, and coherent presence flags.
`CFFS` is a focus pointer into `CFWS`; it does not carry a duplicate position,
menu state, spoken phrase, VQA question, or VQA answer. `CFHP` carries no raw
IMU history. Camera frames, microphone samples, model tensors, and inference
controls have no Binder transaction.

## Unity and FMOD focused-object contract

Unity joins `CFFS` to a fresh `CFWS` entity by exact stable track ID and session
generation. It rejects expired world or focus state, a focus reference to a
future world revision, a missing position, invalid numbers, stale entities,
and unmatched tracks. Only HEAD and WORLD positions are eligible; CAMERA
positions are deliberately not guessed.

The default coordinate adapter is `unverified/fail-closed/v1` and maps
nothing. A usable adapter must provide a named, finite, affine, orthonormal,
handedness-changing HEAD and WORLD basis. Rendering in either frame requires a
successfully mapped, matching-session head pose within 250 ms of the entity and
no more than 250 ms old. If any check fails, Unity stops the focused icon.

`FocusedObjectSonification` creates at most one `FocusedIconCommand`.
Changing the track or event replaces the prior FMOD event; clearing or expiry
stops it. The authored `FocusedObject` event and its `AuditoryIcons` bus are
also configured for one instance. The 3D event accepts only the bounded
parameters `IconConcept`, `IconSalience`, `IconConfidence`, `DistanceMeters`,
and `DwellSpeechActive`; distance is clamped to the authored 0–8 m range. The
authored `DwellSpeechActive` curve is the sole focused-icon gain reduction, and
the runtime initializes that parameter even when an icon is created during
active dwell speech. The interface-state event is nonspatial.
`FmodStudioPerceptionAudioBackend` is compiled only when a consumer installs
the licensed FMOD Unity integration and defines `CONCEPTFLOW_FMOD_UNITY`;
otherwise the lab uses the inspectable command backend.

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

VQA and environment classification share the already prewarmed, pinned
Qwen3-VL-2B GenieX wrapper. One gate allows only one total prewarm or inference
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

## Beacon boundary

The `Start beacon` option is an admission request, not a navigation claim.
`BeaconQualityGate` requires all of the following:

- a non-expired observation no more than 500 ms old;
- confidence of at least 0.65 and fresh metric depth;
- a usable HEAD-relative vector;
- quantified WORLD uncertainty no greater than the smaller of 0.75 m and 25%
  of object distance; and
- a WORLD position explicitly marked
  `TRANSLATION_EVIDENCE_PROPAGATED`.

Orientation-only head propagation cannot satisfy the WORLD requirement. The
current physical camera-to-head evidence is rotation-only and its zero
translation is not a measured camera-to-anatomical-head translation. Until a
VIO or external-tracking origin supplies verified translation and uncertainty,
the live target must fail with `WORLD_ANCHOR_UNAVAILABLE`. No end-to-end beacon
guidance or navigation behavior is claimed.

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

## Release boundary

Do not describe this work as collision avoidance, wayfinding, safe navigation,
object truth, or autonomous assistance. Before a release claim, run the full
TalkBack workflow with blind and low-vision participants; exercise the signed
two-APK Binder path; measure focused-icon localization and masking with the
actual open-ear output; establish a collision-free glasses input vocabulary;
validate WORLD translation and beacon uncertainty; and run focused VQA through
GenieX/HTP under representative latency, cancellation, thermal, and failure
conditions.

Related detail: [Android perception Binder IPC](ANDROID_PERCEPTION_BINDER_IPC.md),
[Accessibility](ACCESSIBILITY.md), [Rokid integration](ROKID_INTEGRATION.md),
[Coordinate frames](COORDINATE_FRAMES.md), [Auditory icons](AUDITORY_ICONS.md),
[Unity/FMOD lab](UNITY_FMOD_LAB.md), and
[Android QNN private runtime](ANDROID_QNN_PRIVATE_RUNTIME.md).
