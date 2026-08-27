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

The payload bytes retain the existing deterministic big-endian ABI: `CFWS` for world snapshots,
`CFFS` for focus, `CFHP` for head pose, and `CFTB` for touch batches. Their embedded revision or
sequence is the monotonic cache key. Binder adds no alternate sensor framing.

## Unity client behavior

`Assets/Plugins/Android` provides the same four static Java methods expected by the C# bridge. The
first call resolves Unity's application context, starts one background `HandlerThread`, and binds
explicitly to Android Node. World, focus, and head polling uses elapsed-realtime scheduling and
never blocks Unity's calling thread. Published cached byte arrays are cloned on both write and read.

Touch is demand-driven: Unity requests a bounded drain, the background thread performs it only when
the prior batch has been consumed, and the next Unity call receives the cached batch. If a later
caller asks for fewer events, the plug-in splits the fixed-width `CFTB` batch without reordering it.

Binder death, service disconnection, null binding, and malformed replies clear every cache and all
remote counters. Rebinding uses capped monotonic delays of 250 ms, 500 ms, 1 s, 2 s, 5 s, 15 s, and
then 30 s. A missing Android Node install or signing mismatch therefore produces no snapshot rather
than blocking or crashing Unity.

## Privacy boundary

The service exposes only the four operations in the table. It delegates to
`AndroidPerceptionBridge` for bounded encoded world/focus/head snapshots and ordered raw touch
events. Camera frames, microphone samples, raw IMU/sensor buffers, model tensors, transport
objects, and inference controls have no transaction code and cannot cross this Binder surface.
