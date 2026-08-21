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
DURATION_SECONDS = 0.82


def samples(kind: str) -> list[int]:
    rng = random.Random(0xC0FFEE if kind == "anchor" else 0xBABB1E)
    previous = 0.0
    output = []
    for index in range(round(SAMPLE_RATE * DURATION_SECONDS)):
        time_s = index / SAMPLE_RATE
        envelope = min(1.0, time_s / 0.045, (DURATION_SECONDS - time_s) / 0.12)
        noise = rng.uniform(-1.0, 1.0)
        previous = previous * (0.91 if kind == "anchor" else 0.96) + noise * (0.09 if kind == "anchor" else 0.04)
        center = math.sin(math.tau * (185.0 if kind == "anchor" else 118.0) * time_s)
        overtone = math.sin(math.tau * (370.0 if kind == "anchor" else 236.0) * time_s + 0.3)
        value = envelope * ((0.10 if kind == "anchor" else 0.075) * center + 0.035 * overtone + 0.075 * previous)
        output.append(max(-32767, min(32767, round(value * 32767))))
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
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    for kind in ("anchor", "field"):
        path = args.output / f"intrusion_{kind}.wav"
        print(f"{path.name} {write(path, samples(kind))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
