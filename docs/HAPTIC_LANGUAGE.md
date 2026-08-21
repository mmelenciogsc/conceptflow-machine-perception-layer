<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Haptic language

`HapticCue` is derived from the same nearest body/geometry points, proximity,
approach velocity, confidence, and timestamp as its audio cue. It is not a
second independent detector.

| Pattern | Intended transition | Default treatment |
| --- | --- | --- |
| boundary/pulse | meaningful boundary entry | one brief pulse |
| approach | increasing proximity | bounded pulse/ramp |
| urgent approach | rapid relative approach | brief stronger pattern |
| semantic | optional chosen category | lower-priority token |

The Android adapter queries the actual `VibratorManager` topology on API 31+
and the default vibrator on earlier releases. On API 30+ it queries primitive
and predefined-effect support, preferring supported primitives, then supported
predefined effects, then a short waveform. Amplitude is used only when the
actuator reports amplitude control. Durations remain within 20–500 ms, generated
waveforms contain at most five segments, and runtime failures degrade to no
haptic instead of crashing.

The current adapter deliberately marks every plan `spatiallyLocalized=false`.
Even when Android exposes multiple vibrator IDs, it drives only the default
actuator and never claims physical left/right/up/down localization. A future
wearable adapter may distribute the canonical vector only after independently
addressable actuators are verified.

Tests cover primitive, predefined, waveform and no-actuator paths, finite and
bounded inputs, and the nonspatial invariant. Device intensity, comfort, thermal
behavior, and synchronization remain physical validation tasks.
