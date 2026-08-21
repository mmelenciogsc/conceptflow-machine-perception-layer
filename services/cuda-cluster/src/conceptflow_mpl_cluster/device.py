# SPDX-License-Identifier: MIT OR Apache-2.0
"""CUDA discovery that degrades explicitly to CPU when permitted."""

from __future__ import annotations

from dataclasses import dataclass
import os
import shutil
import subprocess
from typing import Callable, Mapping


@dataclass(frozen=True, slots=True)
class ComputeDevice:
    kind: str
    identifier: str
    name: str

    @property
    def label(self) -> str:
        return f"{self.kind}:{self.identifier}"


RunCommand = Callable[..., subprocess.CompletedProcess[str]]


def discover_devices(
    preference: str = "auto",
    *,
    allow_cpu_fallback: bool = True,
    environ: Mapping[str, str] | None = None,
    runner: RunCommand = subprocess.run,
) -> tuple[ComputeDevice, ...]:
    if preference == "cpu":
        return (ComputeDevice("cpu", "0", "CPU fallback"),)
    env = os.environ if environ is None else environ
    visible = env.get("CUDA_VISIBLE_DEVICES")
    cuda_devices: list[ComputeDevice] = []
    executable = shutil.which("nvidia-smi")
    if visible not in {"", "-1", "none", "None"} and executable:
        try:
            completed = runner(
                [executable, "--query-gpu=index,uuid,name", "--format=csv,noheader"],
                check=True,
                capture_output=True,
                text=True,
                timeout=3,
            )
            visible_set = None if visible is None else {part.strip() for part in visible.split(",")}
            for line in completed.stdout.splitlines():
                columns = [column.strip() for column in line.split(",", maxsplit=2)]
                if len(columns) != 3:
                    continue
                index, uuid, name = columns
                if visible_set is not None and index not in visible_set and uuid not in visible_set:
                    continue
                cuda_devices.append(ComputeDevice("cuda", uuid, name))
        except (OSError, subprocess.SubprocessError):
            cuda_devices = []
    if cuda_devices:
        return tuple(cuda_devices)
    if allow_cpu_fallback:
        return (ComputeDevice("cpu", "0", "CPU fallback"),)
    raise RuntimeError("no usable CUDA device was discovered and CPU fallback is disabled")
