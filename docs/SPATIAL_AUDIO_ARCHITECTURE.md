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
profiles for comparison and diagnostics. Production world-positioned geometry,
focused-object icons, and beacons require an HRTF-capable binaural profile; the
model-neutral command adapter, training preferences, and authored baseline all
default to Resonance Audio. The authored events use Resonance Audio Source and
the master bus uses Resonance Audio Listener.
Plain stereo panning or FMOD Standard 3D is not an acceptable production route
for those cues. No HRTF profile is declared perceptually superior until the
localization harness is run on the target open-ear output topology.

The focused localization harness therefore fixes the current authored
Resonance route, one neutral voice, 2 m distance, and three short presentations
while varying only 12 canonical listener-relative directions across two
balanced blocks. It uses the current listener pose and suppresses every
ordinary scene/focus audio lane for the trial. A fresh pose and compatible
headset route are mandatory, as is explicit runtime-backend attestation of the
authored Resonance profile; the inspectable fallback cannot produce labelled
results. Android checks the active game-audio route through the API 33+
per-attributes route query rather than treating every connected sink as active;
older APIs fail closed. Route loss, stale pose, backend failure, or the hard 15-second trial
deadline aborts instead of silently changing conditions.
Its 24 response-only records can establish a per-user baseline, but do not by
themselves compare renderer profiles or establish clinical, safety, or
perceptual superiority.

## Environment-aware calibration

The scene classifier establishes an indoor, outdoor, or transition prior first.
Each newly accepted VLM classification result may then authorize exactly one
bounded three-second Rokid microphone window. Carrying that classification
forward onto newer stable camera frames cannot retrigger capture. Indoor and outdoor defaults establish the
starting calibration gain and pulse spacing; a streaming profiler refines them
from relative digital noise floor and transient density while also publishing
low-, mid-, and high-band energy ratios for later renderer policy.

No PCM is persisted, logged, or exposed to Unity. The analyzer discards sample
content after accumulating bounded statistics. Current hardware validation is
16 kHz PCM16LE mono, so the profile describes masking and spectral balance but
does not claim mic-array directionality or calibrated sound-pressure level.
The profile expires after 60 seconds and is cleared across session loss. HRTF
calibration falls back to its bounded default when no fresh profile exists.

Calibration pulses use maximum authored salience and a runtime gain normally in
the 0.62–0.94 range. This is intentionally scoped to the localization harness;
it does not globally amplify Sound Bubble, focused-object, speech, or branding
events. A live Android source also suppresses all synthetic lab geometry, so a
finished or aborted trial cannot fall through to the lab's continuous test bed.

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
