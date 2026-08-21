<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Unity and FMOD perception laboratory

The lab is a Unity 6000.3.22f1 reference environment for the same geometry and
render contracts exercised headlessly. It is not a game prototype and does not
require a display for its test path.

## Deterministic scenes

The runtime constructs 18 scenarios: narrow corridor, broad wall, doorway and
doorframe, pillar, table/chair, low obstruction, shoulder obstruction, head
overhang, ceiling feature, step/drop representation, stairs, static pedestrian,
approaching pedestrian, crossing pedestrian, large moving object, cluttered
room, outdoor walkway, and noisy ambient test geometry. Moving scenarios use
deterministic trajectories.

The controller uses `Physics.OverlapSphereNonAlloc` for bounded broadphase and
`Collider.ClosestPoint` for surface points. It evaluates the calibrated
multi-segment body field, computes normalized ring weights without per-frame
emitter allocation, limits output to an anchor plus five field voices, derives
an inspectable nonspatial single-actuator haptic state, and updates at most four
audio command batches per second.

Controls are textual and keyboard accessible:

- digits `0`–`9`: select the corresponding scenario;
- `[` and `]`: cycle all 18 scenarios;
- Space: pause/resume motion;
- `R`: restart the current scenario;
- `E`: export UTF-8 status to Unity's persistent-data directory; and
- `H`: repeat controls in the log.

The export includes clearance, proximity, motion activation, voice count, and
haptic state. The Unity GUI is diagnostic only; the companion
`./scripts/perception-training`, `./scripts/perception-calibration`, and
`./scripts/perception-demo` paths are fully textual for screenreader use. The
calibration command emits 72 paired trials across 12 directions, three
clearances, and two renderer profiles, including head-turn and moving-source
conditions. Its scoring API reports exact/group match rates and angular error;
it does not claim perceptual accuracy without user responses.

## Run tests

```bash
UNITY=/path/to/Unity
$UNITY -batchmode -nographics -projectPath "$PWD/labs/unity-fmod-perception-lab" \
  -runTests -testPlatform EditMode -testResults /tmp/mpl-editmode.xml \
  -logFile /tmp/mpl-editmode.log
$UNITY -batchmode -nographics -projectPath "$PWD/labs/unity-fmod-perception-lab" \
  -runTests -testPlatform PlayMode -testResults /tmp/mpl-playmode.xml \
  -logFile /tmp/mpl-playmode.log
```

Do not add `-quit`; the Unity test runner exits after writing results. Current
tests verify body clearance, head/body separation, ring generation/normalization,
and a running broad-wall status path.

## FMOD authoring

```bash
./scripts/fmod-lab validate
./scripts/fmod-lab build
```

FMOD Studio 2.03.14 was used to create and validate the source project. Generated
tones and banks are intentionally ignored. The public Unity lab compiles without
the proprietary FMOD Unity package and exposes exact runtime commands through
the inspectable adapter. Live Update, listening tests, and FMOD Unity playback
remain hardware/user evaluation work; they are not implied by a successful
headless run.

## Diagnostic visual language

The visualization uses a near-black field, cool charcoal structure, thin gray
frames, off-white labels, and sparse muted-gold signal traces. The Sound Bubble
is wireframe body-offset geometry, never a glowing sphere. Color and animation
are never the sole state channel.
