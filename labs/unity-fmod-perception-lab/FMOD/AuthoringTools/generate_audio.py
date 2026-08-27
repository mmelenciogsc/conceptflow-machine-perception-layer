#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Generate original deterministic low-stimulation FMOD test layers."""

from __future__ import annotations

import argparse
import hashlib
import math
from pathlib import Path
import random
import struct
import wave

SAMPLE_RATE = 48_000
LAYER_DURATION_SECONDS = 0.82


def samples(kind: str) -> list[int]:
    rng = random.Random(0xC0FFEE if kind == "anchor" else 0xBABB1E)
    previous = 0.0
    output = []
    for index in range(round(SAMPLE_RATE * LAYER_DURATION_SECONDS)):
        time_s = index / SAMPLE_RATE
        envelope = min(1.0, time_s / 0.045, (LAYER_DURATION_SECONDS - time_s) / 0.12)
        noise = rng.uniform(-1.0, 1.0)
        previous = previous * (0.91 if kind == "anchor" else 0.96) + noise * (0.09 if kind == "anchor" else 0.04)
        center = math.sin(math.tau * (185.0 if kind == "anchor" else 118.0) * time_s)
        overtone = math.sin(math.tau * (370.0 if kind == "anchor" else 236.0) * time_s + 0.3)
        value = envelope * ((0.10 if kind == "anchor" else 0.075) * center + 0.035 * overtone + 0.075 * previous)
        output.append(max(-32767, min(32767, round(value * 32767))))
    return output


ICON_SPECS = {
    "neutral_presence": (0.22, 0x1000, 146.0, "neutral"),
    "soft_footfall_pair": (0.32, 0x1001, 112.0, "footfall"),
    "restrained_latch": (0.26, 0x1002, 730.0, "latch"),
    "short_freewheel": (0.30, 0x1003, 420.0, "freewheel"),
    "subdued_tire_texture": (0.30, 0x1004, 92.0, "tire"),
}

INTERFACE_SPECS = {
    "closed": (0.16, 0x2000, 240.0),
    "open": (0.16, 0x2001, 320.0),
    "vqa": (0.18, 0x2002, 390.0),
    "beacon": (0.18, 0x2003, 470.0),
    "back": (0.16, 0x2004, 285.0),
    "back_unavailable": (0.20, 0x2005, 205.0),
}


def semantic_samples(duration: float, seed: int, frequency: float, shape: str) -> list[int]:
    """Small original motifs; intentionally quiet and non-speech."""
    rng = random.Random(seed)
    output: list[int] = []
    filtered = 0.0
    for index in range(round(SAMPLE_RATE * duration)):
        time_s = index / SAMPLE_RATE
        attack = min(1.0, time_s / 0.012)
        release = min(1.0, max(0.0, duration - time_s) / 0.045)
        envelope = attack * release
        phase = math.tau * frequency * time_s
        noise = rng.uniform(-1.0, 1.0)
        filtered = 0.88 * filtered + 0.12 * noise
        if shape == "footfall":
            pulse = max(0.0, 1.0 - abs(time_s - 0.075) / 0.055) + max(0.0, 1.0 - abs(time_s - 0.235) / 0.055)
            value = pulse * (0.08 * math.sin(phase) + 0.055 * filtered)
        elif shape == "latch":
            transient = math.exp(-time_s * 25.0)
            value = transient * (0.075 * math.sin(phase) + 0.05 * filtered)
        elif shape == "freewheel":
            ratchet = 0.35 + 0.65 * max(0.0, math.sin(math.tau * 18.0 * time_s))
            value = ratchet * (0.052 * math.sin(phase) + 0.036 * filtered)
        elif shape == "tire":
            value = 0.032 * math.sin(phase) + 0.072 * filtered
        else:
            value = 0.052 * math.sin(phase) + 0.022 * math.sin(phase * 1.5)
        output.append(max(-32767, min(32767, round(envelope * value * 32767))))
    return output


def interface_samples(duration: float, seed: int, frequency: float) -> list[int]:
    rng = random.Random(seed)
    output: list[int] = []
    for index in range(round(SAMPLE_RATE * duration)):
        time_s = index / SAMPLE_RATE
        envelope = min(1.0, time_s / 0.008, max(0.0, duration - time_s) / 0.04)
        shimmer = rng.uniform(-1.0, 1.0) * 0.008
        value = (
            0.052 * math.sin(math.tau * frequency * time_s)
            + 0.025 * math.sin(math.tau * frequency * 1.5 * time_s)
            + shimmer
        )
        output.append(max(-32767, min(32767, round(envelope * value * 32767))))
    return output


def write(path: Path, values: list[int]) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as stream:
        stream.setnchannels(1)
        stream.setsampwidth(2)
        stream.setframerate(SAMPLE_RATE)
        stream.writeframes(b"".join(struct.pack("<h", value) for value in values))
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output", type=Path, required=True, help="FMOD Assets root (legacy Assets/SoundBubble is also accepted)"
    )
    args = parser.parse_args()
    output_root = args.output.parent if args.output.name == "SoundBubble" else args.output
    for kind in ("anchor", "field"):
        path = output_root / "SoundBubble" / f"intrusion_{kind}.wav"
        print(f"{path.name} {write(path, samples(kind))}")
    for name, (duration, seed, frequency, shape) in ICON_SPECS.items():
        path = output_root / "AuditoryIcons" / f"{name}.wav"
        print(f"{path.name} {write(path, semantic_samples(duration, seed, frequency, shape))}")
    for name, (duration, seed, frequency) in INTERFACE_SPECS.items():
        path = output_root / "Interface" / f"{name}.wav"
        print(f"{path.name} {write(path, interface_samples(duration, seed, frequency))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
