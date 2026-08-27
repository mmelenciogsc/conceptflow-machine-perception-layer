# Wi-Fi Direct transport

The production topology is deliberately split into two layers:

1. Android Wi-Fi Direct creates and maintains a local IP link. Android Node on
   the phone is the group owner. Rokid Node discovers the exact DNS-SD service
   identity `_cf-mpl._tcp` and joins as a client.
2. The existing two-lane mutual-TLS protocol authenticates both applications
   and carries realtime/control plus camera traffic. A DNS-SD advertisement is
   never treated as proof of peer identity.

There is no claim of zero-copy across Wi-Fi. The transport minimizes copies
inside each device while retaining bounded framing, input validation, camera
freshness, and independent IMU/audio/touch policies.

## Configuration

`live-link.properties` schema 1 accepts:

```properties
network_topology=wifi_direct_required
```

`private_lan` remains available as an explicit diagnostic fallback. Required
mode never silently falls back to infrastructure WLAN, so a validation run
cannot be mislabeled as Wi-Fi Direct. The static `address` field remains in
schema 1 for backward compatibility but is ignored for socket routing in
required mode; the live group-owner address is used instead.

For debug-sideloaded builds, provision both certificate pins and the topology:

```bash
./scripts/android-live-link-pair \
  --rokid-serial ROKID_SERIAL \
  --poco-serial POCO_SERIAL \
  --network-topology wifi-direct-required
```

The script verifies that each device advertises Wi-Fi Direct and grants only
the discovery permission appropriate to its Android version. Android 12 and
earlier also require Location services to be enabled for peer discovery. A
release onboarding flow must request these runtime permissions accessibly; it
must not rely on ADB.

## Lifecycle and recovery

- Android Node creates the group and publishes bounded DNS-SD metadata.
- Rokid Node discovers the service, requests group membership with group-owner
  intent zero, and obtains the group-owner address from `WifiP2pInfo`.
- Both lanes bind/connect only after the role and private/link-local address
  checks pass.
- When either Wi-Fi or the P2P framework reports disabled, both nodes enter an
  explicit `WAITING_FOR_RADIO` state, clear stale discovery state, cancel
  connection watchdogs, and issue no P2P operations. When Android reports the
  radio and P2P stack available again, discovery resumes immediately and then
  returns to the bounded 1/2/5/10/15-second cadence only for genuine operation
  failures.
- Initial discovery and platform-owned group authorization use a bounded
  three-minute sensor-off rendezvous lease. This accommodates the measured slow
  DNS-SD scan cycle on the target glasses without starting camera, microphone,
  or IMU capture before mutual authentication.
- Transport keepalive uses a separate one-second cadence and declares a dead
  authenticated peer after 15 missed intervals. Failed service rendezvous
  epochs continue indefinitely with capped jittered backoff.
- Normal ten-minute authenticated lease rotation is immediate and may reuse an
  already-formed group.
- Routine node shutdown releases sockets, discovery requests, and DNS-SD
  advertisements but intentionally leaves an established operating-system P2P
  group intact. App/service restarts can therefore reconnect without repeating
  radio negotiation or Android's system-owned join confirmation.
- Disabling Wi-Fi, rebooting a peer, forgetting the peer in system settings, or
  otherwise destroying the group requires discovery and Android confirmation
  again. The applications do not bypass that platform security boundary.
- Stale sensor TTLs remain independent of link liveness; extending liveness
  never makes old perception data current.

The group-owner address and peer device address are operational metadata and
must not be logged. Runtime diagnostics report only role, phase, bounded retry
count, operation name, radio availability, and a symbolic plus numeric Android
framework reason code.

The applications cannot silently enable a disabled Wi-Fi radio on current
Android target SDKs. A Wi-Fi lock keeps an already-enabled radio responsive; it
does not override YodaOS or user radio policy. Development recovery may use
`adb shell svc wifi enable`, but production recovery must use a user-visible
platform/vendor control surface or a verified vendor API. See
[YodaOS runtime resilience](YODAOS_RUNTIME_RESILIENCE.md).

## Verified Android requirements

The implementation follows the Android Wi-Fi Direct APIs documented at:

- <https://developer.android.com/develop/connectivity/wifi/wifip2p>
- <https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager>
- <https://developer.android.com/reference/android/net/Network>

Accessed 2026-08-26. The relevant requirements are `ACCESS_WIFI_STATE` and
`CHANGE_WIFI_STATE`, both coarse and fine location permission through Android
12, and
`NEARBY_WIFI_DEVICES` on Android 13 and later. Both target devices advertise
`android.hardware.wifi.direct`. A 5 GHz group, role assignment, authenticated
data plane, retained-group restart, and ten-minute stream were exercised on the
current Poco F7 Ultra and non-display Rokid hardware; other firmware revisions
still require device validation.
