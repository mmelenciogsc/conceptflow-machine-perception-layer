<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Auditory icons

Auditory icons are a semantic layer, not the metric Sound Bubble. Geometry still
operates when recognition fails. The data-driven registry currently maps only:

| Concept | Repository-safe procedural key | Meaning |
| --- | --- | --- |
| person | `procedural/soft_footfall_pair` | restrained representational token |
| door | `procedural/restrained_latch` | restrained representational token |
| bicycle | `procedural/short_freewheel` | restrained representational token |
| vehicle | `procedural/subdued_tire_texture` | restrained representational token |
| unknown | `procedural/neutral_presence` | explicitly non-representational fallback |

These keys specify intent; no copyrighted recordings are included. Unknown
classes are not assigned a misleading real-world sound.

`SemanticDepthFuser` uses reliable samples inside a segmentation mask to retain
nearest, median, and far depths, a 3D centroid, nearest point, angular extent,
velocity, confidence, bubble overlap, and a privacy-safe structural fingerprint.
Large/deep objects receive a nearest anchor plus at most one quieter centroid
extent layer; they are not pinned only to a bounding-box center.

`SemanticSimilarityGate` scores label, track, mask fingerprint, direction,
depth, velocity, confidence, and bubble-transition changes. Stable equivalent
tracks are suppressed. A new track, a material change after cooldown, or a
bubble transition can emit. The registry then computes bounded salience from
distance, confidence, and bubble overlap.

Metric geometry and semantics may describe the same object. The geometry layer
owns immediate clearance; the icon supplies a sparse hypothesis about identity.
Renderers must not duplicate both as spoken warnings.
