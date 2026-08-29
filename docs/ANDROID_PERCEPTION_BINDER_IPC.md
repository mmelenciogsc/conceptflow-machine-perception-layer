<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Android Node to Unity Binder IPC

The standalone Unity Android player reads compact perception state from the separately installed
Android Node application through one explicit bound service. This is an application boundary, not
an in-process JVM call.

## Identity and access

- Android Node package: `org.conceptflow.mpl.androidhost`
- Service: `org.conceptflow.mpl.host.realtime.PerceptionBusIpcService`
- Binder descriptor: `org.conceptflow.mpl.host.realtime.IPerceptionBridge`
- Permission: `org.conceptflow.mpl.androidhost.permission.READ_PERCEPTION_BUS`
- Permission protection level: `signature`

Android Node and the Unity player must therefore be signed by the same trusted certificate. A
default Unity debug key and a different Android/Gradle debug key do not satisfy this condition.
Build automation must configure one shared development signer for device testing and the same
release signing identity for deployment. Install Android Node before the Unity player so Android
can resolve the custom permission when the Unity package is installed.

The Unity plug-in binds with an explicit `ComponentName`; there is no implicit intent filter. The
service manifest permission and a transaction-time permission check both fail closed. A wrong
interface token or unauthorized caller receives Android's `SecurityException` and no bus method is
called.

## Parcel protocol version 1

All calls are synchronous Binder transactions, but the Unity plug-in makes them only on its private
`HandlerThread`. Every request begins with `Parcel.writeInterfaceToken()` using the descriptor
above, followed by exactly one argument. Trailing request data is rejected.

| Code | Operation | Argument | Maximum payload |
| ---: | --- | --- | ---: |
| 1 | poll world state | signed 64-bit last revision, at least 0 | 65,536 bytes |
| 2 | poll focus state | signed 64-bit last revision, at least 0 | 1,024 bytes |
| 3 | poll head pose | signed 64-bit last sequence, at least 0 | 256 bytes |
| 4 | drain touch events | signed 32-bit maximum, 1 through 128 | 8,192 bytes |
| 5 | poll ambient sound profile | signed 64-bit last revision, at least 0 | 256 bytes |

A handled response is `writeNoException()`, one signed 32-bit status, and, only for status `0`, one
`writeByteArray()` payload. Status values are fixed:

| Value | Meaning |
| ---: | --- |
| 0 | payload present |
| 1 | no newer snapshot |
| 2 | argument outside its legal range |
| 3 | source payload exceeds the lane limit |
| 4 | compact source operation failed |
| 5 | malformed request |

Unknown transaction codes are not handled. One-way calls are not handled, which ensures touch
events are never drained without an acknowledgement path. Source exceptions are converted to
status `4`; malformed parcels are converted to status `5`. No exception text or internal state is
returned.

The payload bytes retain the deterministic big-endian ABI: `CFWS` version 1 for world snapshots,
`CFFS` version 3 for focus, immutable beacon state, and a bounded accessibility announcement,
`CFHP` version 1 for head pose, `CFTB` version 1 for touch batches, and `CFAP`
version 1 for a content-free ambient sound profile. Their embedded revision or
sequence is the monotonic cache key. The Unity decoder continues to accept `CFFS` version 1 as a
browsing-only compatibility payload and version 2 as a beacon-capable payload. Binder adds no
alternate sensor framing.

`CFFS` version 2 adds an explicit focus mode and optional bounded beacon record. Beacon anchor
mode `1` is a translated WORLD anchor. Mode `2` is an orientation-stabilized relative bearing:
the vector is in canonical HEAD coordinates and includes the activation-time normalized head
quaternion and its monotonic timestamp. The latter follows the listener origin because no
translation is claimed, but it does not rotate with later head turns. Its lifetime is bounded and
it cannot be presented as a fixed world point or navigation instruction. Presence flags, legal
enum values, finite numeric fields, quaternion normalization, exact track correlation, payload
length, and complete consumption are all validated before Unity accepts either anchor form.

`CFFS` version 3 appends an optional accessibility token and its authoritative plain-text
announcement. The token is at most 96 UTF-8 bytes and the text at most 384 UTF-8 bytes. Both must
be present together. The Unity decoder validates those bounds, rejects control characters and
malformed UTF-8, and forwards a fresh token at most once to Android's accessibility announcement
API. Unity requires the focus timestamp interval to contain the current Android monotonic time;
expired reconnect/rebootstrap state is never spoken. VQA tokens use the non-content request identity,
not a digest of the answer. The plug-in does not derive or rewrite the text. This lets TalkBack
announce dwell, menu, VQA, rejection, and beacon transitions while the Unity activity is foreground.

## Unity client behavior

`Assets/Plugins/Android` provides the same bounded static Java methods expected by the C# bridge. The
first call resolves Unity's application context, starts one background `HandlerThread`, and binds
explicitly to Android Node. World, focus, and head polling uses elapsed-realtime scheduling and
never blocks Unity's calling thread. Published cached byte arrays are cloned on both write and read.

Touch is demand-driven: Unity requests a bounded drain, the background thread performs it only when
the prior batch has been consumed, and the next Unity call receives the cached batch. If a later
caller asks for fewer events, the plug-in splits the fixed-width `CFTB` batch without reordering it.

Ambient profiling remains off the Unity main thread. `CFAP` carries the classified
indoor/outdoor/transition prior, capture interval, PCM format, sample count, relative dBFS
statistics, normalized low/mid/high energy, transient density, and bounded calibration gain and
pulse-spacing recommendations. It contains no PCM, speech, transcript, embedding, or calibrated
SPL claim. The currently validated Rokid capture is 16 kHz PCM16LE mono; array directionality is
not claimed.

Binder death, service disconnection, null binding, and malformed replies clear every cache and all
remote counters. Rebinding uses capped monotonic delays of 250 ms, 500 ms, 1 s, 2 s, 5 s, 15 s, and
then 30 s. A missing Android Node install or signing mismatch therefore produces no snapshot rather
than blocking or crashing Unity.

## Privacy boundary

The service exposes only the five operations in the table. It delegates to
`AndroidPerceptionBridge` for bounded encoded world/focus/head snapshots and ordered raw touch
events and the content-free ambient profile. A version-3 focus payload may contain the bounded user-facing accessibility phrase,
including an explicitly requested VQA answer. Camera frames, microphone samples, raw IMU/sensor
buffers, microphone PCM, model tensors, transport objects, and inference controls have no transaction code and
cannot cross this Binder surface.
