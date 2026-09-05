<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# YodaOS-Sprite runtime resilience

This document defines the safe operating boundary for the directly sideloaded
**Machine Perception Layer, Rokid Node** on the non-display Rokid AI Glasses
Style. The objective is durable sensing without modifying, disabling, rooting,
or replacing YodaOS-Sprite system components.

No ordinary Android application can guarantee that its process will never be
killed. The implementation instead reduces its contribution to system memory
pressure, runs active work as a foreground service, retains bounded state, and
recovers from process or radio loss without changing the validated camera,
microphone, IMU, or touch gates.

## Observed failure chain

Read-only logs from the attached Android 12/API-32 glasses on 2026-08-27 showed
two related, but distinct, failure sequences.

In the first sequence:

1. Android's low-memory killer terminated the vendor payment helper
   `com.iap.mobile.ar_pay`.
2. Concurrent calls in `com.rokid.os.sprite.assistserver` then received
   `DeadObjectException` from that helper and the assist service process exited.
3. The persistent assist service restarted.
4. During restart, its `SpriteWifiService` loaded the private vendor preference
   `settings_wifi_enable=false` and asked Android's Wi-Fi service to disable the
   radio.
5. Android stopped `wpa_supplicant` and removed the P2P interface. Rokid Node
   remained alive, but Wi-Fi Direct operations returned framework `BUSY` until
   the radio was explicitly restored.

This evidence does not show that Rokid Node caused the vendor helper failure.
It does show that the apparent socket/reconnect failure was downstream of a
system-wide memory event and a vendor radio-policy decision, not a TLS framing
failure or a worn-state transition.

In a later 30-second one-shot diagnostic run, Android terminated Rokid Node
itself. `ApplicationExitInfo` recorded `LOW_MEMORY` with subreason
`TOO_MANY_EMPTY_PROCS`; LMKD recorded approximately 110 MiB RSS at termination.
Android Node then reported `FRAMING_TRUNCATED_RECORD` because the peer process
disappeared while a record was in flight. The diagnostic command had created a
bound service only: it had not persisted the user's idle-control choice and
therefore did not authorize same-boot reconstruction. This was a lifecycle
test-mode limitation, not evidence of a framing decoder defect.

The privileged assist APK was inspected only from a temporary local copy. No
vendor bytecode, resources, credentials, or private configuration are included
in this repository.

## Safe controls implemented

- The active sensor runtime is a foreground service and returns `START_STICKY`.
  During physical streaming its observed `oom_score_adj` was `0`; sensor-off
  standby was observed at `100`. These are measurements, not promises across
  firmware versions.
- `scripts/rokid-background-policy` narrowly provisions this package for
  background execution and the device-idle allowlist. It does not modify other
  packages or disable YodaOS security.
- Camera callbacks retain latest-useful-frame behavior and never block on
  network I/O. The post-gate worker has one replaceable pending slot.
- Accepted JPEG input is decoded directly to the aspect-fill intermediate size
  instead of a full-resolution ARGB bitmap. Cropping is read one scanline at a
  time, eliminating the cropped bitmap and full-frame integer array.
- Freshly owned RGB and Camera2 JPEG arrays transfer immutable ownership into
  protobuf instead of being copied again. Caller-owned synthetic/test arrays
  retain defensive-copy behavior.
- The binary writer streams camera/audio `ByteString` payloads directly to TLS
  rather than allocating one additional byte array per 64-KiB chunk. Framing,
  length limits, hashes, and wire bytes are unchanged.
- Wi-Fi and P2P disable broadcasts move the resolver to
  `WAITING_FOR_RADIO`. Retry and connection-timeout callbacks are removed, stale
  discovery state is discarded, and no P2P operation is attempted while the
  platform reports the radio unavailable. Discovery resumes immediately after
  radio restoration.
- Failure telemetry includes a bounded operation label and symbolic framework
  reason; it never logs peer addresses, frames, certificates, or credentials.
- Production `idle-enable` persists a same-boot capability and creates a
  start-requested runtime. The system-bound accessibility service re-enters the
  short-lived nonvisual Activity broker after process loss, allowing YodaOS to
  recreate the foreground runtime without granting a background service an
  invalid foreground-start exemption. The bounded `live-link-start` diagnostic
  deliberately does not persist this capability.
- Every non-null foreground-service start delivery reconfirms
  `startForeground()` before command dispatch. This handles the observed
  YodaOS case in which the in-process service still believed it was foreground
  after the platform had detached its foreground `ServiceRecord`; skipping the
  new acknowledgement produced a delayed foreground-start ANR.
- After the visible broker has completed the foreground handshake, it becomes
  transparent, non-focusable, and non-touchable but remains visible while the
  same idle-control generation is armed. On this non-display target that keeps
  the ordinary app UID camera-eligible without intercepting the right-arm
  input surface. The broker also holds `FLAG_KEEP_SCREEN_ON`; physical testing
  showed that a foreground service alone did not prevent YodaOS from making the
  UID camera-ineligible. It exits, and releases that flag, when idle control is
  disarmed.
- YodaOS rejects the ordinary third-party `BOOT_COMPLETED` receiver. Persisted
  reboot recovery therefore depends on the explicitly enabled, system-bound
  Rokid input AccessibilityService. After user-storage unlock, that service
  re-enters the same visible broker and can restore the runtime and bounded
  Wi-Fi recovery. Installation and operator procedures must verify that the
  observer remains configured and bound; silently enabling an accessibility
  service without user authorization is not an acceptable production policy.

## Platform boundary

The target is a production `user` build with `ro.debuggable=0`,
`ro.secure=1`, and SELinux enforcing. The vendor Wi-Fi preference is stored in
private assist-service state, its exported service does not expose an app
binder, and modern Android does not permit this ordinary application to toggle
Wi-Fi silently. Therefore:

- Do not root the glasses, patch the system image, alter SELinux, replace the
  assist APK, change low-memory-killer thresholds, or disable vendor services.
- Do not request `android:persistent`, a privileged UID, hidden APIs, or an
  oversized heap. Those are unavailable or counterproductive for a public
  sideloaded application.
- Use Rokid's own user-facing Wi-Fi setting or a subsequently verified public
  vendor API in production. `adb shell svc wifi enable` is development recovery
  only.
- Keep restart/reconnect behavior correct even after memory improvements;
  memory optimization cannot make process lifetime unconditional.

## Power-bounded recovery update — 2026-09-05

The recovery observer no longer treats reconnection as more important than the
battery boundary. Rokid Node samples the sticky platform battery state before
arming and every five seconds during an active epoch. A known level below 50
percent rejects a start. A known level at or below 25 percent, or an unhealthy
battery state, disarms the runtime, clears persisted arming, releases camera and
radio resources, and therefore prevents the observer from recreating the same
high-draw epoch after a low-battery reboot. Charging status does not override
the numerical threshold.

The Camera2 session now requests the target's lowest fixed AE range (`[15,15]`)
for both the keepalive preview and scheduled capture. Rendezvous and reconnect
briefly use `WIFI_MODE_FULL_HIGH_PERF`; after authentication the Wi-Fi lock is
released and steady-state streaming returns to Android's platform-default radio
policy while the bounded CPU wake lease remains active. Production post-gate
transfer is self-contained 640×640 planar I420,
614,400 bytes per admitted frame, rather than 1,228,800-byte packed RGB8. These
changes do not modify camera, IMU, microphone, or touch gating and do not turn
sensor callbacks into network writers.

On the connected target, Camera2 request inspection showed `[15,15]` on both
requests. A 45-second sample delivered 143 camera frames and 2,966 selected IMU
samples with no new camera drop or interruption. A subsequent I420/QNN sample
reconstructed 275 frames and completed 17 correlated inference cycles; camera,
IMU, audio, and touch queue-drop counters remained zero. Host-side I420 decode
p95 was 17.9 ms in that sample. A reversible development battery override at 24
percent caused the active runtime to disarm within the five-second monitoring
interval and cleared persisted idle arming; the override was reset immediately.
This proves the guard transition, not real battery endurance. A later charged,
approximately 358-second untethered interval retained boot count 150, reported
zero link interruption or modality queue loss, advanced 1,315 camera frames and
27,710 IMU samples, and moved the platform battery gauge from 100 to 85 percent.
The result is useful comparative evidence but remains one uncalibrated run and
does not establish operating time.

## Physical validation on 2026-08-27

The build installed on both attached nodes completed these tests:

| Test | Observed result |
| --- | --- |
| 30-second optimized stream | 81 frames observed, 80 queued, one deliberate latest-frame drop, 1,277 IMU samples queued, zero IMU drops, authenticated close |
| Camera transform | p50 105.9 ms, p95 117.2 ms, p99 143.3 ms for that run |
| Process memory | sampled RSS 86.6–118.4 MiB during the run; it repeatedly fell after collection rather than growing monotonically |
| Large-object GC after direct payload writing | zero matching LOS collection messages in the bounded 30-second run |
| Radio outage | Wi-Fi reached disabled state; Rokid Node remained alive and entered one `WAITING_FOR_RADIO` transition |
| Disabled interval | zero repeated P2P operation failures during the observed interval |
| Radio recovery | P2P restoration was detected, the group re-formed, mutual TLS reauthenticated, and streaming resumed as session 2 |
| Interrupted 10-minute soak | 1,703 camera frames queued, 26,282 selected IMU samples queued, one camera drop, zero IMU drops, bounded 19-frame latest-worker replacement, and authenticated terminal close |
| One-shot process loss | YodaOS killed the nonpersistent diagnostic run for low memory; Android Node correctly surfaced a truncated in-flight record and waited for a new session |
| Persisted production recovery | after terminating only Rokid Node PID 14757, the accessibility service recreated PID 15078 in about 1.3 seconds, the broker rearmed the runtime, and mutual-TLS streaming resumed in about 4 seconds without operator interaction |
| Recovered delivery | Android Node advanced from 133 to 201 reconstructed frames and from 2,146 to 3,237 received IMU samples after recovery; its interruption counter advanced once with `NETWORK_IO` |
| Camera-eligibility guard | after the foreground reconfirmation and transparent broker change, one observed session remained connected for more than 135 seconds while delivering 509 camera frames and 10,551 IMU samples with zero link interruptions |
| Extended attached run sampled 2026-08-28 | both node processes and the runtime service remained active after 4,489 reconstructed camera frames and 83,923 received IMU samples; one recorded `SOCKET_TIMEOUT` recovered automatically, current queues were empty, and inference had completed 1,412 of 1,420 attempts |
| Cable-powered reboot sampled 2026-09-05 | the enabled system-bound observer survived reboot, rebound after user-storage unlock, restored Wi-Fi and the foreground runtime, and returned to authenticated streaming without an ADB launch; a following 20-second sample delivered 69 camera frames, 41 inference results, and 852 IMU samples |

The 10-minute soak preceded the final direct-`ByteString` framing optimization;
the final no-copy framing path received the focused 30-second physical run.
Neither result proves indefinite uptime, battery life, thermal stability under
all ambient conditions, or immunity from unrelated vendor-process failures.

## Operator checks

```bash
./scripts/rokid-background-policy --serial ROKID_SERIAL status
./scripts/android-node-control --serial POCO_SERIAL start-automatic
./scripts/rokid-control --serial ROKID_SERIAL live-link-soak
```

During an authorized development recovery only:

```bash
adb -s ROKID_SERIAL shell svc wifi enable
```

Acceptance requires a start-requested runtime with observable BFGS/notification
evidence on this YodaOS build, bounded RSS without monotonic growth, no repeated
P2P calls while radio-disabled, automatic process and group/session recovery in
persisted production mode, bounded camera replacement rather than backlog,
ordered IMU/touch delivery, and a clean authenticated close. The observed
`oom_score_adj` varies with lifecycle state (`0` during the earlier visible run
and `100` after recovered production streaming), so it is telemetry rather than
an acceptance constant.

## Sources

- Android process lifecycle:
  <https://developer.android.com/guide/components/activities/process-lifecycle>
- Android foreground services:
  <https://developer.android.com/develop/background-work/services/fgs>
- Android memory-management overview:
  <https://developer.android.com/topic/performance/memory-overview>
- Android Wi-Fi Direct:
  <https://developer.android.com/develop/connectivity/wifi/wifip2p>

Accessed 2026-08-27. Device-specific YodaOS behavior above is based on the
attached hardware and must be revalidated after firmware changes.
