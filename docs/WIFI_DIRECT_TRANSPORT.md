# Local wireless transport

The application transport is deliberately split into two layers:

1. A local IP topology provides reachability. The implementation supports
   strict Android Wi-Fi Direct and an infrastructure private-LAN discovery
   mode. These modes never silently substitute for one another.
2. The existing two-lane mutual-TLS protocol authenticates both applications
   and carries realtime/control plus camera traffic. Neither Wi-Fi Direct
   discovery nor a LAN announcement is treated as proof of peer identity.

Strict Wi-Fi Direct remains the intended cable-free topology, but it is not
currently the reliable default for the tested firmware pair. On 2026-08-29 the
Poco could create 2.4 GHz and 5 GHz groups and enter Android's listen state,
while the Rokid saw zero peers. Removing the autonomous group and attempting
ordinary negotiated formation also yielded zero peers. The physically working
route is therefore `private_lan_discovery` on either a trusted infrastructure
WLAN or the Poco's user-enabled personal hotspot, with exact certificate pins
and TLS 1.3 mutual authentication unchanged.

There is no claim of zero-copy across Wi-Fi. The transport minimizes copies
inside each device while retaining bounded framing, input validation, camera
freshness, and independent IMU/audio/touch policies.

## Configuration

`live-link.properties` schema 1 accepts the strict mode:

```properties
network_topology=wifi_direct_required
```

and the resilient private-WLAN mode:

```properties
network_topology=private_lan_discovery
```

In private-LAN discovery mode Android Node emits a fixed 19-byte, content-free
UDP rendezvous beacon once per second to a bounded multicast, limited
broadcast, and interface-directed broadcast destination. Rokid Node holds the
Android multicast receive lock only during an eight-second discovery window.
If the WLAN or firmware filters those packets, Rokid first retains a
same-subnet provisioned address and otherwise tries the current private default
gateway. The latter is the Poco itself when the glasses are a hotspot client.
The data plane still proceeds only after exact-pin mutual TLS succeeds, so a
gateway guess cannot authorize an endpoint. `private_lan` remains the static
diagnostic mode.

An initial physical run delivered none of the discovery datagrams, so the
eight-second static fallback was exercised. The final exact build adds a
bounded 250 ms listener-bind window before its first announcement; a fresh
device run then received the beacon directly and authenticated. Both routes
are therefore exercised, while delivery of multicast/broadcast is not assumed.
A DHCP reservation for the Poco, or re-running the pairing helper after its
private address changes, remains useful on infrastructure WLANs. It is not
required for the hotspot-gateway fallback. The implementation does not scan
the LAN, retain hotspot credentials, or trust an unauthenticated UDP sender.

On the API-36 Poco, Android Node observes public `TetheringManager` callbacks
and binds one announcement socket to each reported Wi-Fi downstream interface.
The TLS listeners remain bound to all local IPv4 interfaces so an interface
change does not require recreating application state. This callback is not
available to the current implementation below API 36; ordinary Wi-Fi route
discovery remains available there. Android Node does not request privileged
tethering authority and cannot silently enable a hotspot. The user enables the
hotspot in system settings; the node then reacts to its appearance or removal.

Required mode never silently falls back to infrastructure WLAN, so a
validation run cannot be mislabeled as Wi-Fi Direct. The static `address`
field remains in schema 1 for backward compatibility but is ignored for socket
routing in required mode; the live group-owner address is used instead.

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

For the currently validated private-WLAN route, provide the Poco's private
address and select discovery explicitly:

```bash
./scripts/android-live-link-pair \
  --rokid-serial ROKID_SERIAL \
  --poco-serial POCO_SERIAL \
  --poco-address POCO_PRIVATE_IP \
  --network-topology private-lan-discovery
```

## Lifecycle and recovery

- Android Node creates the group and publishes bounded DNS-SD metadata.
- On Android 13 and later, Android Node also uses the public
  `WifiP2pManager.startListening` API during a bounded pre-authentication
  visibility window. The platform documentation defines this as periodic
  social-channel listening until `stopListening` or `stopPeerDiscovery`.
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
- Initial discovery and platform-owned group authorization have a bounded
  three-minute sensor-off rendezvous ceiling. This accommodates the measured
  slow DNS-SD scan cycle on the target glasses without starting camera,
  microphone, or IMU capture before mutual authentication. A definitive
  network failure may end an epoch earlier and enter cooldown.
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

The private-WLAN route uses the same service lifecycle and reconnect policy.
The phone starts its resolver, opens both listeners during a bounded 250 ms
initial announcement delay, and then announces. Route changes rebuild bounded
datagram sockets without stopping the TLS listeners. Rokid tries beacon
discovery, then the same-subnet provisioned address or private default gateway,
and a failed epoch enters jittered 15/30/60-second sensor-off cooldown while
the already-running foreground service stays alive.
Authentication resets the backoff. A normal ten-minute lease rotation starts
the next rendezvous immediately.

Rejected or half-open sockets before `onSessionReady` are recorded but do not
enter Android Node's authenticated-session disconnect policy. This prevents an
unauthenticated device on a shared LAN from stopping the persistent listener.
Protocol, certificate, or framing failures after an authenticated session is
ready retain the fail-closed behavior.

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

Accessed again 2026-08-29. The relevant requirements are `ACCESS_WIFI_STATE` and
`CHANGE_WIFI_STATE`, both coarse and fine location permission through Android
12, and
`NEARBY_WIFI_DEVICES` on Android 13 and later. Both target devices advertise
`android.hardware.wifi.direct`. A 5 GHz group, role assignment, authenticated
data plane, retained-group restart, and ten-minute stream were exercised on the
current Poco F7 Ultra and non-display Rokid hardware in an earlier run.

That earlier success is not a guarantee that every retained group is
discoverable. In the 2026-08-29 regression run, the Poco formed the retained
group as owner and entered the public listen state, while the Rokid completed
six DNS-SD watchdog cycles and two ordinary-peer fallback scans with zero
visible peers. Permissions, Location services, Wi-Fi Direct feature support,
and both radios were present. Guarded recovery then recreated empty
phone-owned groups on both 2.4 GHz and 5 GHz; neither became visible to Rokid.
Finally, the empty group was removed and standard peer-negotiated formation was
attempted, again with zero visible peers. No system confirmation appeared.
These tests locate the blocker before TCP or mutual TLS, in cross-firmware P2P
discovery/visibility.

Group replacement is never automatic. The recovery controller refuses to
remove a group that the phone does not own or that reports any connected
client; its generated credentials are random, bounded, app-transient, and not
logged. Reboot reconstruction and strict-P2P interoperability remain physical
validation gates. The private-LAN discovery/static-fallback route is a truthful
operational fallback, not a claim that strict Wi-Fi Direct succeeded.

### Poco personal-hotspot evidence — 2026-08-30

The API-36 Poco exposed its active Wi-Fi tethering downstream through the
public callback. Android Node announced on that interface; the API-32 Rokid
received the content-free beacon, completed exact-pin mutual TLS, and streamed
camera and IMU data. A clean ten-minute lease delivered 1,835 post-gate frames
and 34,484 selected IMU samples with no link interruption. Eleven camera
frames were deliberately replaced by the latest-frame freshness policy; IMU,
audio, and touch drop counters remained zero. Sampled Android Node PSS stayed
between 60,387 and 71,547 KiB, and the Rokid battery temperature field ranged
from 32.0 to 35.0 °C.

An intentionally invalid pre-authentication TCP/TLS probe was rejected and
recorded without stopping the listener; a legitimate Rokid session
authenticated immediately afterward. The glasses also selected the
hotspot-gateway fallback during a disrupted run. Automatic OS network
selection after a Wi-Fi-radio cycle was not established in this indoor test:
the glasses selected a stronger remembered infrastructure WLAN while both
networks were visible. The intended outdoor condition, where only the Poco
hotspot is available, therefore still needs an untethered field reconnect
test. No saved indoor network was deleted to manufacture that result.
