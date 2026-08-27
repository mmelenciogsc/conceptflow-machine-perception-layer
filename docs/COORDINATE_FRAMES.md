<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Coordinate frames

The Python reference uses a right-handed local convention: `+x` right, `+y` up,
and `+z` forward. Azimuth is `atan2(x,z)` and elevation is measured above the
horizontal plane. Unity uses its native left-handed `+x` right, `+y` up, `+z`
forward convention, recorded in `perception_math.json`; a transport adapter must
perform the explicit handedness conversion before exchanging rotations or
cross products. Device adapters must record every native-to-canonical mapping.

| Frame | Owns | Must not own |
| --- | --- | --- |
| WORLD | Short-lived reconstructed geometry and motion | A claim of geographic-map completeness |
| BODY | Calibrated body proxy and Sound Bubble | Listener orientation |
| HEAD | Ears/listener and head orientation | Whole-torso heading |
| SENSOR | Glasses camera/IMU observations | Body dimensions or world truth |

The transform chain is `WORLD <- BODY <- HEAD <- SENSOR`. Each rigid transform
has a monotonic timestamp. [`frames.py`](../packages/perception-core/src/conceptflow_mpl_perception/frames.py)
composes only matching adjacent transforms and rejects a transform/observation
pair outside its configured age. Points include translation; vectors do not.

Turning the head changes SENSOR and HEAD directions without rotating the BODY
envelope. Torso heading must come from a calibrated or estimated BODY transform,
not from camera yaw. Forward walking while looking sideways therefore retains
body-forward motion and a separately rotated listener. When torso heading is
uncertain, providers must lower confidence or withhold a direction-sensitive
cue rather than silently using head heading.

The Unity boundary uses the named mapping
`conceptflow-canonical-rh-to-unity-lh/z-reflection/v1`: `(x, y, z)` becomes
`(x, y, -z)`. This is the exact handedness conversion between the canonical
protocol basis and Unity's basis. It does not establish anatomical camera
alignment, body heading, or a translated tracking origin. An
orientation-stabilized relative beacon therefore rotates its captured HEAD
vector by the activation-time head quaternion and follows the current listener
origin. A true WORLD beacon additionally requires a translated world/listener
origin supplied by the AR runtime.

Implemented tests cover head-only yaw, torso yaw, translated walking while the
head counter-rotates, and stale/rollback timestamps. Crouch and bend are
represented through future calibrated body-profile/pose updates; the current
fixed proxy does not claim to infer either state.

## Rokid head-motion sampling

The direct glasses adapter prefers Android's game-rotation vector and requests
an unbatched 10,000-microsecond period (nominal 100 Hz). It emits one HEAD
snapshot per rotation-vector event with a monotonic sensor timestamp, sequence
ID, quaternion, accuracy, and the latest three-axis angular velocity and
gravity-compensated linear acceleration together with their own timestamps.
Those timestamps let a receiver reject or down-weight stale component vectors.
The Android period is a request rather than a delivery guarantee; the bounded
device diagnostic measures observed rate and maximum gap.

The current adapter labels the rigidly worn glasses orientation as HEAD, and a
Camera2 pose record now establishes the camera rotation relative to those
Android sensor axes on the physical target. The reported inverse identity is
therefore a rotation-only `HEAD <- CAMERA` transform for this rigid proxy; its
`PRIMARY_CAMERA` zero translation is not a camera-to-head measurement. See
[Rokid camera-to-head extrinsic](ROKID_CAMERA_HEAD_EXTRINSIC.md). A future
guided calibration is still needed for anatomical alignment, numeric rotation
uncertainty, and translation. The adapter must not infer BODY or torso yaw from
head rotation. Rendering in
Unity/FMOD requires a separate ordered low-latency IMU transport and receiver
interpolation; the current unary frame request carries only its nearest HEAD
pose and is deliberately not presented as a 100 Hz renderer feed.
