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

## Android runtime boundary

`AndroidPerceptionBridgeClient` is the thin Unity consumer for Android Node's
`PerceptionBus`. It polls a compact versioned world snapshot and drains a
bounded ordered touch batch through `AndroidJavaClass`. It never transfers raw
camera/audio buffers, opens sockets, runs QNN or blocks for inference.
`PerceptionBusBinaryDecoder` is exercised in EditMode and fails closed on
truncation, wrong magic/version, invalid enums, excessive entity counts,
invalid numeric fields or trailing bytes. Camera coordinates remain +X right,
+Y down and +Z optical-forward; camera vectors are never mislabeled BODY or
WORLD.

The Android-backed controller installs the named canonical mapping
`conceptflow-canonical-rh-to-unity-lh/z-reflection/v1`. It reflects canonical Z
to cross the right-handed protocol/left-handed Unity boundary. That mapping is
not camera-to-anatomical calibration and does not create world translation.
`CFFS` version 2 relative beacons instead freeze a fresh activation-time HEAD
orientation and metric HEAD vector; Unity can render that bearing without a
live `CFWS` object while accurately reporting that listener translation is not
tracked.

The FMOD-facing backend consumes semantic/spatial commands after world-state
interpretation. Capture, network and inference threads never call FMOD. The
proprietary FMOD Unity package is intentionally not committed.

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

Do not add `-quit`; the Unity test runner exits after writing results. On this
machine the installed `6000.3.22f1` editor—the same version used by the working
FiveAgainstWhen project—ran 27/27 EditMode and 3/3 PlayMode tests. Coverage
includes body clearance, head/body separation, ring generation/normalization,
the broad-wall path, strict Binder decoding, coordinate conversion, focused
voice limits, and relative-beacon head-turn/expiry behavior.

Build the standalone ARM64 Android development player with the same installed
Unity editor:

```bash
$UNITY -batchmode -nographics \
  -projectPath "$PWD/labs/unity-fmod-perception-lab" \
  -executeMethod ConceptFlow.Mpl.PerceptionLab.Editor.PerceptionLabAndroidBuild.Build \
  -quit -logFile /tmp/mpl-unity-android-build.log
```

The output is
`labs/unity-fmod-perception-lab/Builds/Android/MachinePerceptionLab-Development.apk`.
It uses package `org.conceptflow.mpl.unitylab`, Android API 29 minimum, ARM64,
IL2CPP, and an explicit launcher activity. Device-side Binder validation additionally requires this player and
Android Node to use the same development or release signing identity; the
build does not weaken the signature permission to accommodate mismatched keys.

## FMOD authoring

```bash
./scripts/fmod-lab validate
./scripts/fmod-lab build
```

FMOD Studio 2.03.14 was used to create and validate the source project. Its
`FocusedObject` event includes bounded `BeaconMode` values for ordinary focus,
WORLD anchor, and orientation-stabilized relative bearing. Generated tones and
banks are intentionally ignored. The public Unity lab compiles without
the proprietary FMOD Unity package and exposes exact runtime commands through
the inspectable adapter. Live Update, listening tests, and FMOD Unity playback
remain hardware/user evaluation work; they are not implied by a successful
headless run.

The typed `FmodStudioPerceptionAudioBackend` also compiles against the installed
FMOD Unity 2.03.14 assembly used by FiveAgainstWhen. The final public-player
build still excludes that licensed integration and therefore does not constitute
an audible FMOD runtime test.

## Diagnostic visual language

The visualization uses a near-black field, cool charcoal structure, thin gray
frames, off-white labels, and sparse muted-gold signal traces. The Sound Bubble
is wireframe body-offset geometry, never a glowing sphere. Color and animation
are never the sole state channel.
