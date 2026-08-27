<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Spatial beacon anchoring

A spatial beacon is a short-lived supplemental bearing for a user-selected
perception item. It is not route guidance, collision avoidance, or proof that
the source object is still present.

## Anchor tiers

Android Node chooses the strongest truthful tier available at activation:

1. `WORLD_ANCHORED` stores a translated local-world point only when the track
   explicitly carries propagated translation and quantified uncertainty within
   the configured bound.
2. `ORIENTATION_STABILIZED_RELATIVE` stores the metric HEAD vector and a fresh
   activation-time HEAD quaternion when translation is unavailable. Unity
   converts the vector to its handedness, rotates it by that captured
   quaternion, and places it relative to the current listener origin. Later
   head turns therefore preserve the captured bearing. User translation is not
   estimated: the source follows the listener origin.

The fallback is intentional. It supplies the bearing reinforcement requested
by the user without fabricating world coordinates. Android announces
“Translation is not tracked” for the relative tier. Both tiers retain the
source frame/time, label, confidence, approximate distance, uncertainty when
known, activation identity, and a 30-second maximum lifetime. They survive the
short detector-track TTL but are cleared by session reset, explicit Back or
focus movement, or beacon expiry.

## Runtime contract

`CFFS` version 2 carries the immutable anchor. `CFHP` independently carries the
current listener orientation. Unity accepts only a matching session, finite
vectors, normalized reference/current quaternions, a reference pose within
250 ms of activation, a current pose no more than 250 ms old, and an unexpired
beacon. Relative beacons do not require a `CFWS` entity after activation.

The focused FMOD event remains a one-voice lane. `BeaconMode` is `0` for normal
focus, `1` for a world anchor, and `2` for the relative bearing. A beacon is a
brief spatial pulse no more often than once per 1.5 seconds; the listener pose
continues to update between pulses. This avoids a continuous masking sound.

## Known limit

Object orientation is not currently a measured perception output. The beacon
preserves the object's observed location vector and distance, not a fabricated
object-facing direction. A future tracked pose may extend the versioned record
only after the model produces an explicit pose plus uncertainty.
