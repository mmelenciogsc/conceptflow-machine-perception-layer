<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Sound Bubble specification

The Sound Bubble is a body-surface clearance field, not a sphere centered on a
camera or head. For a calibrated body proxy `B`, its distance is:

```text
Dbody(x) = min ||x - b||, for b on the surface of B
Bubble = { x | Dbody(x) <= BubbleRadiusMeters }
```

The default `BubbleRadiusMeters` is exactly `0.9144` metres (three feet). At a
body surface, normalized proximity is 1. At the nominal outer shell it is 0:

```text
BubbleProximity = clamp(1 - Dbody(x) / BubbleRadiusMeters, 0, 1)
```

## Implemented proxy

[`body.py`](../packages/perception-core/src/conceptflow_mpl_perception/body.py)
uses a union of capsules for the head, shoulder/neck region, torso, left and
right lateral regions, rear torso, pelvis, and both lower-body regions. The
union is an efficient approximation of an offset around a body; it is not one
elongated capsule. `BodyProfile` validates anonymous dimensions and supports
guided calibration without retaining an image or identity.

Every observation is evaluated against every segment. The minimum nonnegative
surface clearance supplies `Pbody`, body region, intrusion status, and
proximity. Tests cover the exact default radius, equal clearance at distinct
body regions, surface/boundary endpoints, head independence, wall extent,
overhead and lower-body observations.

## Geometry contact

`GeometryMapper` accepts timestamped metric points, source type, uncertainty,
confidence, optional surface normal, and velocity. It transforms each point to
BODY, finds `Pgeometry` and `Pbody`, then clusters stable entity IDs into a
bounded contact manifold. A broad wall retains a nearest point plus at most two
extent samples; a narrow post remains concentrated. This prevents an object's
pivot from becoming an artificial emitter and bounds voice count.

The current reference clusters by provider-supplied entity identity and sampled
extent. Production depth adapters must establish stable identities and reject
invalid depth before calling this layer. The implementation does not infer
metric precision from monocular output.

## Product boundary

This field supplies supplemental awareness. It does not certify free space,
safe passage, collision avoidance, or navigation correctness and does not
replace a cane, guide dog, orientation-and-mobility technique, ordinary
environmental hearing, or human judgment.
