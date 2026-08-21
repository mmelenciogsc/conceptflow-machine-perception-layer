# SPDX-License-Identifier: MIT OR Apache-2.0
"""Renderer-neutral spatial-audio commands and an inspectable FMOD fallback."""

from __future__ import annotations

import json
from dataclasses import dataclass
from enum import Enum

from .model import Vec3
from .morph import GeometryCue

FMOD_ANCHOR_EVENT = "event:/MachinePerception/SoundBubble/IntrusionAnchor"
FMOD_FIELD_EVENT = "event:/MachinePerception/SoundBubble/EnvelopmentField"


class SpatializerProfile(str, Enum):
    FMOD_STANDARD = "fmod_standard"
    RESONANCE_AUDIO = "resonance_audio"
    PLATFORM_EXPERIMENTAL = "platform_experimental"


class AudioOutputTopology(str, Enum):
    OPEN_EAR_GLASSES = "open_ear_glasses"
    HEADPHONES = "headphones"
    SPEAKERS = "speakers"
    UNKNOWN = "unknown"


@dataclass(frozen=True, slots=True)
class SpatialVoiceCommand:
    voice_id: str
    event_path: str
    layer: str
    position_body: Vec3
    forward_body: Vec3
    gain_linear: float
    sound_size_m: float
    weight: float
    parameters: tuple[tuple[str, float], ...]


@dataclass(frozen=True, slots=True)
class AudioDispatch:
    cue_id: str
    spatializer: SpatializerProfile
    topology: AudioOutputTopology
    voices: tuple[SpatialVoiceCommand, ...]
    source_timestamp_ns: int
    expiry_ns: int


class FmodCommandAdapter:
    """Build commands shared by an FMOD integration and deterministic fallback."""

    def __init__(
        self,
        spatializer: SpatializerProfile = SpatializerProfile.FMOD_STANDARD,
        topology: AudioOutputTopology = AudioOutputTopology.UNKNOWN,
        max_field_voices: int = 5,
    ) -> None:
        if not 1 <= max_field_voices <= 12:
            raise ValueError("field voice limit must be within [1, 12]")
        self.spatializer = spatializer
        self.topology = topology
        self.max_field_voices = max_field_voices

    def dispatch(self, cue: GeometryCue, motion_intensity: float) -> AudioDispatch:
        common = (
            ("BubbleProximity", cue.anchor.proximity),
            ("Envelopment", cue.field.participation),
            ("MotionIntensity", max(0.0, min(1.0, motion_intensity))),
        )
        voices = [
            SpatialVoiceCommand(
                f"{cue.cue_id}:anchor",
                FMOD_ANCHOR_EVENT,
                "Intrusion Anchor",
                cue.anchor.position_body,
                -cue.anchor.direction_body,
                cue.anchor.gain_linear,
                cue.anchor.sound_size_m,
                1.0,
                common,
            )
        ]
        strongest = sorted(cue.field.emitters, key=lambda item: (-item.weight, item.emitter.emitter_id))[
            : self.max_field_voices
        ]
        selected_total = sum(item.weight for item in strongest) or 1.0
        for item in strongest:
            weight = item.weight / selected_total
            voices.append(
                SpatialVoiceCommand(
                    f"{cue.cue_id}:field:{item.emitter.emitter_id}",
                    FMOD_FIELD_EVENT,
                    "Envelopment Field",
                    item.emitter.position_body,
                    item.emitter.inward_normal_body,
                    cue.field.gain_linear * weight,
                    cue.field.sound_size_m,
                    weight,
                    common,
                )
            )
        return AudioDispatch(
            cue.cue_id, self.spatializer, self.topology, tuple(voices), cue.source_timestamp_ns, cue.expiry_ns
        )


class InspectableAudioFallback:
    """Non-audible renderer used when FMOD banks/runtime are unavailable."""

    @staticmethod
    def render(dispatch: AudioDispatch) -> str:
        payload = {
            "cue_id": dispatch.cue_id,
            "expiry_ns": dispatch.expiry_ns,
            "source_timestamp_ns": dispatch.source_timestamp_ns,
            "spatializer": dispatch.spatializer.value,
            "topology": dispatch.topology.value,
            "voices": [
                {
                    "gain": round(voice.gain_linear, 6),
                    "id": voice.voice_id,
                    "layer": voice.layer,
                    "position_body": [
                        round(voice.position_body.x, 6),
                        round(voice.position_body.y, 6),
                        round(voice.position_body.z, 6),
                    ],
                    "sound_size_m": round(voice.sound_size_m, 6),
                    "weight": round(voice.weight, 6),
                }
                for voice in dispatch.voices
            ],
        }
        return json.dumps(payload, separators=(",", ":"), sort_keys=True)
