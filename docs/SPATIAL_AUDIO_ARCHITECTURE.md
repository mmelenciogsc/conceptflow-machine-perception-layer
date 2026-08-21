<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Spatial audio architecture

Geometry produces two coordinated layers:

1. **Intrusion Anchor** remains at the nearest stable geometry point and carries
   the strongest directional evidence.
2. **Envelopment Field** distributes bounded energy over the strongest virtual
   ring emitters to communicate proximity and surface extent.

Proximity primarily increases field participation (`proximity * motion` squared)
and sound size (0.06–0.65 m). Anchor gain remains between 0.08 and 0.34 and uses
only 35 percent of the definition range. Field gain is capped at 0.24. This
makes increasing occupation, not loudness, the principal reference cue.

## FMOD route

The authored FMOD Studio 2.03.14 project contains:

- `event:/MachinePerception/SoundBubble/IntrusionAnchor`;
- `event:/MachinePerception/SoundBubble/EnvelopmentField`;
- `BubbleProximity`, `MotionIntensity`, `SoundSize`, and `Envelopment`
  continuous parameters;
- a bounded Sound Bubble bus below Accessible Sonification;
- a limiter, two procedurally generated source assets, and Resonance Audio
  source/listener plug-ins; and
- Desktop and Mobile bank definitions.

`./scripts/fmod-lab validate` interrogates the actual project graph, automation
curves, routing, source count, event 3D state, and voice limits. `build` invokes
FMOD's bank compiler. WAV sources and banks are generated/ignored; proprietary
FMOD binaries are never committed.

[`audio.py`](../packages/perception-core/src/conceptflow_mpl_perception/audio.py)
and Unity's `InspectableFmodBackend` emit the exact event paths, bounded gains,
3D positions, inward normals, sound size, and parameters. The default public
Unity project uses this inspectable adapter because the FMOD Unity integration
is redistributable only under its own terms. Actual event authoring and bank
build were exercised locally; headphone/open-ear perceptual accuracy and live
Unity-to-FMOD playback have not been user-validated.

FMOD Standard, Resonance Audio, and a future platform renderer remain explicit
profiles. The authored baseline selects Resonance Audio, but no profile is
declared perceptually superior until the localization harness is run on the
target output topology.

## Mixing policy

Geometry outranks icons and periodic speech. Lower lanes are ducked, not allowed
to erase the anchor. Maximum voice count, field selection, TTL, cooldown, and
similarity gates prevent an auditory wall. Output calibration must retain
ordinary environmental sound; no spectral profile is claimed universally safe
or fatigue-free.

`PerceptionEngine` submits geometry, icon, and scene work to one
`PerceptualPriorityScheduler`. Its independent budgets default to six audio
voices, one speech slot, and one haptic slot. A complete anchor/field geometry
cue consumes six audio voices and can coexist with one scene-speech request;
an ordinary icon waits instead of exceeding the audio budget. Counters expose
generated, rendered, similarity-suppressed, capacity-suppressed, superseded,
stale, expired, and interrupted decisions for deterministic trace replay.
