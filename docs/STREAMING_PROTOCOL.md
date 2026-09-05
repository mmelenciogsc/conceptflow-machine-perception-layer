<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Rokid Node ↔ Android Node streaming protocol

This specifies the production hot path in `packages/android-live-transport`.
It replaces routine JPEG/PCM/JSON spool coordination with bounded RAM
publication after the existing Rokid gates. The app-private spool remains an
explicit, disabled-by-default diagnostic mode.

The current implementation identifies itself as protocol version `1.5`. A
production transport epoch is bounded to 14,410 seconds: at most four hours of
sensor workload plus a separate 10-second authenticated-shutdown envelope. The
ordinary development command deliberately closes after 30 seconds; the
explicit soak path runs for 600 seconds. Reconnect never extends an existing
controller deadline.

```text
ROKID PHYSICAL SENSORS
→ EXISTING VALIDATED CAPTURE / GATES / RESIZE
→ BOUNDED IN-MEMORY SENSOR PUBLISHER
→ MUTUAL-TLS REALTIME + CAMERA LANES
→ ANDROID SENSOR INGRESS
→ SYNCHRONIZED SENSOR TIMELINE
→ PERCEPTION RUNTIME
→ PERCEPTION BUS / WORLD STATE
→ UNITY 3D AR GAME
→ FMOD STUDIO ADAPTIVE / SPATIAL AUDIO
```

Bluetooth discovery/bootstrap remains separate from this protocol. With
`network_topology=wifi_direct_required`, Android Node owns the P2P group, Rokid
Node discovers its project-specific DNS-SD service, and both nodes use the
runtime group-owner address for the sockets below. Required mode has no silent
infrastructure-WLAN fallback. `private_lan_discovery` is the currently
validated infrastructure-WLAN topology: its content-free UDP rendezvous
announcement has no authentication authority, and Rokid falls back after eight
seconds to the separately provisioned private address when broadcast/multicast
is filtered. `private_lan` remains an explicit static diagnostic topology.
Neither mode claims Bluetooth carries camera frames or that physical-network
transfer is zero-copy. The direct-sideload path requires no Rokid enterprise
client secret. See [Local wireless transport](WIFI_DIRECT_TRANSPORT.md).

## Security and lanes

Both peers use non-exportable Android Keystore identities, TLS 1.3 mutual
authentication and an exact peer-public-key pin. Session and lease identifiers
are ephemeral bindings, not credentials. The realtime/control lane owns
negotiation, clock sync, IMU, on-demand audio, touch, liveness, recovery and
errors. The camera lane requires a short-lived single-use ticket issued over the
authenticated control lane.

| Lane | Stream ID | Payloads | Queue policy |
| --- | ---: | --- | --- |
| Realtime/control | 0, 2, 3, 4 | control, PCM16 audio, IMU batches, raw touch | touch first; fair audio/IMU alternation |
| Camera | 1 | complete-frame chunks | one pending complete frame; replace stale pending frame |

TCP is an ordered byte stream. Code never assumes a read is a record:
`VersionedLiveFrameReader` retains partial header/body state across short reads
and socket timeouts and accepts coalesced records.

## Version 1.1 record framing

The typed HELLO/capabilities contract is version 1.5. Its outer binary record
framing remains version 1.1 because the telemetry extension uses
forward-compatible Protocol Buffers fields and does not alter the 38-byte
record header or payload placement.

All header integers use network byte order. The header is exactly 38 bytes.

| Offset | Bytes | Field | Validation |
| ---: | ---: | --- | --- |
| 0 | 4 | magic | ASCII `CFMP` |
| 4 | 1 | major | exactly `1` |
| 5 | 1 | minor | `0` or `1`; higher unsupported minors fail closed |
| 6 | 1 | message type | 1 control, 2 camera, 3 audio, 4 IMU, 5 touch |
| 7 | 1 | flags | zero in v1.0 |
| 8 | 4 | stream ID | fixed mapping for message type |
| 12 | 8 | per-lane sequence | positive, strictly increasing per authenticated lane |
| 20 | 8 | source monotonic timestamp ns | positive and equal to nested typed timestamp |
| 28 | 2 | UTF-8 session length | 1–128 bytes |
| 30 | 4 | protobuf metadata length | positive and within configured record limit |
| 34 | 4 | raw payload length | nonnegative and within configured record limit |

The body is `session UTF-8 | LiveLinkEnvelope protobuf metadata | raw payload`.
Camera `chunk_data` and microphone `audio_data` are cleared from metadata and
carried as raw payload. The receiver reconstructs the protobuf only after
checking magic, version, type, stream, lengths, UTF-8, session, sequence,
timestamp and payload placement. Control, IMU and touch require empty raw
payload. Camera and audio require nonempty raw payload. Compiler-native structs
are never dumped onto the wire.

The outer message type is deliberately small. Typed `LiveLinkControl` variants
inside CONTROL provide HELLO/version negotiation, explicit CAPABILITIES
exchange, lane authentication, stream lease request/grant, repeated CLOCK_SYNC,
HEARTBEAT/keepalive, microphone authorization, Rokid gesture/command exchange,
aggregate TELEMETRY, typed ERROR and diagnostic spool operations. A version
1.5 glasses peer sends its bounded capabilities immediately after HELLO; the
host validates them and replies with host capabilities before issuing the
single-use camera-lane ticket. Every current pair requires explicit I420 capability;
an older peer that cannot advertise it fails negotiation instead of silently
misinterpreting the payload. Forward-compatible protobuf readers still preserve
unknown fields.

Rokid Node publishes aggregate queue-pressure TELEMETRY once per second. It
contains queue depths, drop/overflow and sent-message totals, plus camera-gate
counts for analyzed/emitted frames, relaxed/motion-tier decisions,
dark/blurry/cadence rejection, and the current target FPS. These are aggregate
counters and per-lane byte totals with a source monotonic sample time—never
sensor content, labels, identities, addresses or credentials. Android Node validates accounting
invariants and exposes the latest sample in its accessible aggregate status.

Protocol 1.4 adds optional, content-free platform power measurements to that
same one-second message: battery percentage, Android charge state, health,
microvolts, instantaneous and average microamps, charge-counter microamp-hours,
deci-degrees Celsius, external-power presence, and energy-counter nanowatt-hours
where the device exposes them. Protobuf presence is authoritative: an absent
field means unavailable and is never interpreted as zero. Peers reject values
outside conservative electrical and temperature bounds. Android Node retains
only session aggregates and the latest sample; it receives no battery-history
file or sensor content.

Protocol 1.5 adds explicit camera-wire-encoding request and grant fields. The
default and backward-compatible value is packed I420. `AVC_ANNEX_B_INTRA` is
negotiated only when both app-private configurations opt in and both peers
advertise it. Every AVC payload is one Annex-B access unit containing SPS, PPS,
and an IDR slice. This deliberately preserves independent decodability: the
camera queue may replace a stale pending access unit and a new session may
begin decoding without an earlier frame. A malformed, dependent, or
unexpectedly encoded frame fails closed. The authenticated protobuf envelope
continues to carry the original frame ID, capture timestamp, dimensions,
intrinsics, payload length, and SHA-256 digest.

Android Node also owns a one-way process-lifetime AVC circuit breaker. The
first decoder failure demotes subsequent lease grants to I420 and closes only
the active authenticated attempt. The persistent listener remains available;
Rokid Node reconnects through the existing bounded policy, requests AVC again,
and accepts the host's I420 grant. The circuit breaker cannot oscillate back to
AVC until Android Node is explicitly restarted. Debug builds expose a
shell-only one-shot fault injector; release builds expose no external fault
command. Aggregate status reports the negotiated camera encoding, failure
count, and fallback count.

## Modality contracts

Camera gate behavior is unchanged. Production acquires the exact device-native
648×648 `YUV_420_888` stream alongside its low-resolution HAL keepalive, applies
the protected luma gate, and converts only an accepted frame into tightly packed
640×640 planar I420. The payload is 409,600 Y bytes followed by 102,400 U bytes
and 102,400 V bytes; width and height are even, luma stride is 640, and the total
payload is exactly 614,400 bytes. Each packet is an independently decodable
frame, so latest-frame replacement and reconnect do not depend on an earlier
frame. This remains the default. The feature-flagged AVC-intra route hardware-
encodes that same admitted I420 frame on the glasses; Android Node validates
the Annex-B structure, hardware-decodes it back to packed I420, and preserves
the source frame ID, monotonic capture timestamp, intrinsics, and session
correlation before timeline admission and model preprocessing. The normal RAM path
creates no JPEG and writes no frame to flash. One worker owns one processing
frame and one latest pending frame; intentional replacement is counted. This
changes acquisition and post-gate handoff, not darkness, blur, motion, hold,
relaxed-rate or motion-rate decisions. Packed 640×640 RGB8 remains a negotiated
diagnostic compatibility format. The disabled-by-default diagnostic spool may
encode an admitted RGB8 frame to bounded JPEG for explicit pull testing; that
persistence route is not the production stream.

- Microphone remains explicit on-demand PCM S16LE, 16 kHz, mono. Its FIFO holds
  eight blocks; overflow rejects newest and counts a gap.
- IMU retains the validated gated HEAD-frame orientation, gyroscope and linear-
  acceleration stream. Its queue holds eight batches and evicts the oldest
  obsolete batch under pressure while counting the gap.
- Touch retains raw DOWN/UP event ID, observed timestamp, source uptime, key,
  action, repeat, cancellation, long-press and scan code. A YodaOS-recognized
  two-finger hold is represented separately as one `TWO_FINGER_LONG_PRESS` /
  `TRIGGERED` event; no synthetic raw edges are created. The Rokid queue holds
  64 and Android ingress/timeline each hold 128. Full touch queues reject new
  input and surface a serious diagnostic; accepted input is never evicted,
  reordered, duplicated or replayed after reconnect.

Sensor callbacks never perform socket I/O.

## Monotonic time

Source capture/observation timestamps are preserved. Android KeyEvent uptime is
retained for replay but converted at receipt to glasses elapsed-realtime using
a dual-clock anchor. Fusion never treats uptime as elapsed-realtime directly.

Android establishes time with eight four-timestamp probes. The minimum-RTT
valid sample supplies remote-minus-host offset and half-RTT uncertainty.
Resynchronization rejects impossible values, large jumps and high-RTT outliers.
One poor periodic sample does not terminate an otherwise live authenticated
session: the prior accepted estimate is retained until a later round improves
it. A response that arrives just after its bounded probe window may be accepted
only into a small one-shot late-response window when its probe ID and original
initiator timestamp exactly match a timed-out request and its responder
timestamps are ordered. It is then discarded as a correlated late sample; it
cannot update the clock estimate, become a malformed-control failure, or be
replayed. Unsolicited and mismatched responses still fail normal validation.
A one-second heartbeat cadence permits 15 missed intervals before declaring
the peer dead; content TTLs independently reject stale sensor/perception data
during that window.
`SensorTimeline` retains raw typed records with normalized host times and
uncertainty, associates modalities by time windows, and rejects stale
camera/IMU input. Wall clock is never used for latency math.

## Hard bounds

| Owner | Bound | Overflow behavior |
| --- | ---: | --- |
| Rokid camera transform | processing 1 + pending 1 | replace pending; count |
| Rokid camera transport | one complete frame | replace old frame; count |
| Rokid IMU transport | 8 batches | drop oldest obsolete batch; count |
| Rokid microphone transport | 8 blocks | reject newest; count gap |
| Rokid touch transport | 64 events | reject newest; serious diagnostic |
| Android camera ingress | one complete frame | replace old frame; count |
| Android microphone ingress | 8 blocks | reject newest; count gap |
| Android touch ingress | 128 events | reject newest; serious diagnostic |
| Android timeline | 1 camera, 512 IMU, 16 audio, 128 touch | 1.20 s measured camera ingress TTL; modality-specific counters |
| Android PerceptionBus | one world state, 128 touch | latest state; reject touch overflow |

Disconnect clears pending records, invalidates `SensorTimeline` and
`PerceptionBus`, resets ordering/inference tracking and begins bounded
exponential reconnect. Session IDs exclude stale records from a prior process.
An authenticated normal session close returns the persistent host to listening
state without incrementing the user-visible interruption counter. Rokid begins
the next bounded lease immediately after a graceful expiry or close; actual
network/rendezvous failures use jittered 15-, 30-, then 60-second retry delays,
with 60 seconds as the continuing ceiling. Only network loss and liveness
timeout count as unexpected interruptions.

## Unity and FMOD boundary

`AndroidPerceptionBridge` exposes a versioned big-endian compact world snapshot
(`CFWS`) and ordered touch batch (`CFTB`). It never exposes raw camera or audio.
The Unity decoder validates magic, version, lengths, enums, bounds and trailing
bytes. Unity polls without socket reads, QNN calls or blocking waits. FMOD
consumes later gameplay/semantic parameters, never sensor callbacks.

Camera coordinates are +X image-right, +Y image-down and +Z optical-forward.
HEAD, BODY and Unity WORLD transforms remain explicit. See
[coordinate frames](COORDINATE_FRAMES.md).

## Legacy diagnostic route

`./scripts/rokid-control legacy-spool-enable` enables private JPEG/PCM/JSON
spooling for the next session; `legacy-spool-disable` restores RAM transport.
The switch is for bounded A/B diagnosis only. Production defaults disabled and
files are not a synchronization primitive.

## Deterministic replay

`DeterministicSensorReplay` feeds bounded, explicitly constructed
`LiveSensorDelivery` records through the same `PocoLiveLinkObserver` boundary as
the network transport. A caller drives elapsed monotonic time at original,
0.1–1× slowed, 1–16× accelerated, or one-record-at-a-time stepwise timing. It
does not sleep, access files, log payloads, copy payload bytes, or run in the
production path. This makes sanitized fixtures deterministic without making
routine sensor retention a prerequisite.
