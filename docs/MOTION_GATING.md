<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Motion gating

Move controls information density; it never changes the measured geometry.
[`move.py`](../packages/perception-core/src/conceptflow_mpl_perception/move.py)
separates user translation, torso angular speed, head angular speed, and
external radial approach.

The target activation is the maximum of:

- a quiet but nonzero stationary floor (`0.12` by default);
- nonlinear user translation/torso-turn activation; and
- external or geometry-derived radial approach.

Attack (`0.65`) is faster than release (`0.18`). Head angular speed is
deliberately excluded from whole-body activation, so looking sideways does not
pretend that the body advanced. An object approaching a stationary person
overrides stationary restraint. Retreat decays rather than dropping abruptly.

The Unity lab derives approach from successive clearances for its deterministic
scenes. Production providers can supply object-relative velocity directly. Both
paths are bounded and keyed by stable surface identity.

Tests cover stationary/static state, stationary user with an approaching
object, walking, walking parallel, retreat/decay, turning in place, and looking
sideways while walking. Output remains supplemental and does not imply that an
unreported direction is clear.
