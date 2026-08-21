<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Virtual speaker array

The array is four overlapping emitter manifolds, not four channels:

- LEFT and RIGHT banks face inward from lateral rings;
- SUPERIOR rings face downward and inward; and
- INFERIOR rings face upward and inward.

The default generator creates three rings at 28, 56, and 82 degrees with 12
samples per ring: 36 emitters per bank, 144 total. For each emitter, bounded
bisection finds the point along its body-origin ray where `Dbody` equals the
configured bubble radius. Its inward normal points from that shell location to
the actual nearest body-surface point; it is not assumed to equal the negated
bank ray.

For contact directions `d` and emitter direction `e`, the reference angular
term is `exp(k * (dot(e,d) - 1))`, with configurable concentration `k=8`.
Reliable surface normals modulate but never replace the directional term. A
broad manifold contributes multiple directions. All weights are normalized,
then mixed with 30 percent of the prior stable-identity weights and normalized
again. That continuity term avoids a quadrant step at a bank boundary.

The renderer selects only the five strongest field emitters plus one anchor.
Selected field weights are renormalized, keeping total field energy stable while
bounding the reference path to six voices. Tests verify ring counts, inward
normals, normalized weights, and smooth handoff across a sector boundary.

Unity diagnostics render these as thin structural rings and use the restrained
warm-gold signal token only for active paths. No operational state depends on
the visualization.
