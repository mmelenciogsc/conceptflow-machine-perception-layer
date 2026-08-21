# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def public_candidate_paths() -> tuple[Path, ...]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    return tuple(Path(item.decode()) for item in result.stdout.split(b"\0") if item)


def test_optional_copyleft_model_is_not_a_project_dependency() -> None:
    dependency_files = [
        ROOT / path
        for path in public_candidate_paths()
        if path.name == "pyproject.toml" or (path.name.startswith("requirements") and path.suffix == ".lock")
    ]
    assert dependency_files
    assert all("ultralytics" not in path.read_text(encoding="utf-8").casefold() for path in dependency_files)


def test_no_model_weight_is_tracked_like_source() -> None:
    forbidden = {".pt", ".pth", ".onnx", ".engine", ".safetensors", ".gguf"}
    assert not [path for path in public_candidate_paths() if path.suffix.casefold() in forbidden]
