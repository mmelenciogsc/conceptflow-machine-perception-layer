#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Prepare and inspect private Android Machine Vision model artifacts.

Model checkpoints, generated ONNX graphs, QNN binaries, and calibration data
must be written outside the repository. The synthetic calibration set in this
module proves converter/runtime compatibility only; it is not evidence of
quantized model accuracy.
"""

from __future__ import annotations

import argparse
import ast
from array import array
from contextlib import contextmanager
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from typing import Any, Sequence


@contextmanager
def chdir(path: Path):
    """Python 3.10-compatible equivalent of contextlib.chdir."""
    previous = Path.cwd()
    os.chdir(path)
    try:
        yield
    finally:
        os.chdir(previous)


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
VOCABULARY_PATH = REPOSITORY_ROOT / "config/machine-vision/bvi_classes.txt"
EXPECTED_VOCABULARY_SHA256 = "f4d5aee2124ee9a65f337337004062b15273939ff0ce7f96740fc3cb28d6a9a6"
EXPECTED_YOLOE_CHECKPOINT_SHA256 = "48f24206bc8680d60cbbfa296b0140da849669b9515058b72f5a945142df0654"
DEPTH_SOURCE_REVISION = "a561b849ebae10a6f5ef49e26c83cbbcd36c71bf"
DEPTH_INPUT_SIZES = (336, 392, 518)


@dataclass(frozen=True)
class DepthArtifactSource:
    profile: str
    repository_id: str
    revision: str
    checkpoint_file: str
    checkpoint_sha256: str
    max_depth_meters: float
    output_stem: str


DEPTH_SOURCES = {
    "depth-indoor": DepthArtifactSource(
        profile="indoor_hypersim",
        repository_id="depth-anything/Depth-Anything-V2-Metric-Hypersim-Small",
        revision="3bc65d4e14a6786a61acec16453c50e12bf5f338",
        checkpoint_file="depth_anything_v2_metric_hypersim_vits.pth",
        checkpoint_sha256="b782898d8a3e8be1f639de33837ed85e9b4b73e40f8f5e5cd99067588d722545",
        max_depth_meters=20.0,
        output_stem="depth-anything-v2-metric-indoor-hypersim-vits",
    ),
    "depth-outdoor": DepthArtifactSource(
        profile="outdoor_vkitti",
        repository_id="depth-anything/Depth-Anything-V2-Metric-VKITTI-Small",
        revision="c725b8589bdf6ab04072cab74c0467830db80d6d",
        checkpoint_file="depth_anything_v2_metric_vkitti_vits.pth",
        checkpoint_sha256="9203e538d35255c90dda4b7fedb47ff33fe725497bcca3b1e53b3a65ee63f0cb",
        max_depth_meters=80.0,
        output_stem="depth-anything-v2-metric-outdoor-vkitti-vits",
    ),
}


@dataclass(frozen=True)
class CalibrationProfile:
    name: str
    width: int
    height: int
    normalization: str


CALIBRATION_PROFILES = {
    "yoloe": CalibrationProfile("yoloe", 640, 640, "unit_rgb"),
    "depth": CalibrationProfile("depth", 518, 518, "imagenet_rgb"),
}


def depth_profile(input_size: int) -> CalibrationProfile:
    if input_size not in DEPTH_INPUT_SIZES:
        supported = ", ".join(str(value) for value in DEPTH_INPUT_SIZES)
        raise ValueError(f"unsupported depth input size {input_size}; expected one of: {supported}")
    return CalibrationProfile(f"depth-{input_size}", input_size, input_size, "imagenet_rgb")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_vocabulary(path: Path = VOCABULARY_PATH) -> tuple[str, ...]:
    prompts = tuple(
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    )
    if len(prompts) != 330 or len(set(prompts)) != len(prompts):
        raise ValueError("BVI vocabulary must contain exactly 330 unique prompts")
    fingerprint = hashlib.sha256("\n".join(prompts).encode()).hexdigest()
    if fingerprint != EXPECTED_VOCABULARY_SHA256:
        raise ValueError(f"BVI vocabulary fingerprint mismatch: {fingerprint}")
    return prompts


def ensure_external_output(path: Path) -> Path:
    resolved = path.expanduser().resolve()
    if resolved == REPOSITORY_ROOT or REPOSITORY_ROOT in resolved.parents:
        raise ValueError("private/generated model artifacts must be written outside the repository")
    resolved.mkdir(parents=True, exist_ok=True)
    return resolved


def _pattern_value(sample: int, channel: int, x: int, y: int, width: int, height: int) -> float:
    horizontal = x / max(width - 1, 1)
    vertical = y / max(height - 1, 1)
    if sample % 4 == 0:
        value = horizontal
    elif sample % 4 == 1:
        value = vertical
    elif sample % 4 == 2:
        value = 0.15 if ((x // 32) + (y // 32) + channel) % 2 else 0.85
    else:
        mixed = (x * 73_856_093) ^ (y * 19_349_663) ^ (channel * 83_492_791) ^ (sample * 2_654_435_761)
        value = (mixed & 0xFFFF) / 65_535.0
    return min(1.0, max(0.0, value * (0.82 + channel * 0.07)))


def _normalize(value: float, channel: int, normalization: str) -> float:
    if normalization == "unit_rgb":
        return value
    if normalization == "imagenet_rgb":
        means = (0.485, 0.456, 0.406)
        standard_deviations = (0.229, 0.224, 0.225)
        return (value - means[channel]) / standard_deviations[channel]
    raise ValueError(f"unsupported normalization: {normalization}")


def _write_pattern(path: Path, profile: CalibrationProfile, sample: int, layout: str) -> None:
    if layout == "NCHW":
        coordinates = (
            (channel, x, y) for channel in range(3) for y in range(profile.height) for x in range(profile.width)
        )
    elif layout == "NHWC":
        coordinates = (
            (channel, x, y) for y in range(profile.height) for x in range(profile.width) for channel in range(3)
        )
    else:
        raise ValueError(f"unsupported layout: {layout}")
    with path.open("wb") as target:
        values = array(
            "f",
            (
                _normalize(
                    _pattern_value(sample, channel, x, y, profile.width, profile.height),
                    channel,
                    profile.normalization,
                )
                for channel, x, y in coordinates
            ),
        )
        if sys.byteorder != "little":
            values.byteswap()
        values.tofile(target)


def write_calibration_set(
    output: Path,
    profile: CalibrationProfile,
    samples: int = 4,
    device_runs: int = 1,
) -> dict[str, Any]:
    if samples < 4 or samples > 64:
        raise ValueError("sample count must be between 4 and 64")
    if device_runs < 1 or device_runs > 1_000:
        raise ValueError("device run count must be between 1 and 1000")
    output = ensure_external_output(output)
    records: list[dict[str, Any]] = []
    input_lines: list[str] = []
    for sample in range(samples):
        raw_path = output / f"{profile.name}-{sample:03d}.float32.raw"
        # QAIRT calibrates against the converted QNN graph. Its default ONNX
        # conversion exposes spatial-last NHWC client buffers even when the
        # source graph is NCHW.
        _write_pattern(raw_path, profile, sample, "NHWC")
        records.append(
            {
                "file": raw_path.name,
                "bytes": raw_path.stat().st_size,
                "sha256": sha256_file(raw_path),
            }
        )
        input_lines.append(str(raw_path))

    input_list = output / f"{profile.name}-input-list.txt"
    input_list.write_text("\n".join(input_lines) + "\n", encoding="utf-8")
    device_input = output / f"{profile.name}-device-000.float32.raw"
    _write_pattern(device_input, profile, 0, "NHWC")
    device_input_list = output / f"{profile.name}-device-input-list.txt"
    device_input_list.write_text((device_input.name + "\n") * device_runs, encoding="utf-8")
    manifest = {
        "schema_version": 1,
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": "qnn_converter_compatibility_smoke_only",
        "accuracy_claim": False,
        "representative_dataset": False,
        "profile": asdict(profile),
        "layout": "NHWC",
        "dtype": "little_endian_float32",
        "samples": records,
        "input_list": input_list.name,
        "device_input_list": device_input_list.name,
        "device_input": {
            "file": device_input.name,
            "bytes": device_input.stat().st_size,
            "sha256": sha256_file(device_input),
            "layout": "NHWC",
        },
        "device_runs": device_runs,
    }
    manifest_path = output / f"{profile.name}-calibration-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


def _letterbox_rgb(image: Any, profile: CalibrationProfile) -> Any:
    from PIL import Image

    source = image.convert("RGB")
    scale = min(profile.width / source.width, profile.height / source.height)
    resized_size = (
        max(1, round(source.width * scale)),
        max(1, round(source.height * scale)),
    )
    resized = source.resize(resized_size, Image.Resampling.LANCZOS)
    fill = (114, 114, 114) if profile.normalization == "unit_rgb" else (124, 116, 104)
    canvas = Image.new("RGB", (profile.width, profile.height), fill)
    canvas.paste(resized, ((profile.width - resized.width) // 2, (profile.height - resized.height) // 2))
    return canvas


def write_image_calibration_set(
    output: Path,
    profile: CalibrationProfile,
    image_directory: Path,
    limit: int = 32,
    device_runs: int = 1,
) -> dict[str, Any]:
    try:
        from PIL import Image
    except ImportError as error:
        raise RuntimeError("calibration-images requires Pillow") from error
    if limit < 8 or limit > 1_024:
        raise ValueError("image limit must be between 8 and 1024")
    if device_runs < 1 or device_runs > 1_000:
        raise ValueError("device run count must be between 1 and 1000")
    source_root = image_directory.expanduser().resolve()
    candidates = sorted(
        path
        for path in source_root.rglob("*")
        if path.is_file() and path.suffix.lower() in {".jpeg", ".jpg", ".png", ".webp"}
    )[:limit]
    if len(candidates) < 8:
        raise ValueError("at least eight calibration images are required")
    output = ensure_external_output(output)
    records: list[dict[str, Any]] = []
    input_lines: list[str] = []
    for index, source_path in enumerate(candidates):
        raw_path = output / f"{profile.name}-image-{index:03d}.float32.raw"
        with Image.open(source_path) as image:
            converted = _letterbox_rgb(image, profile)
            with raw_path.open("wb") as target:
                components = converted.tobytes()
                values = array(
                    "f",
                    (
                        _normalize(component / 255.0, index % 3, profile.normalization)
                        for index, component in enumerate(components)
                    ),
                )
                if sys.byteorder != "little":
                    values.byteswap()
                values.tofile(target)
        records.append(
            {
                "file": raw_path.name,
                "bytes": raw_path.stat().st_size,
                "sha256": sha256_file(raw_path),
                "source_file": source_path.name,
                "source_sha256": sha256_file(source_path),
            }
        )
        input_lines.append(str(raw_path))

    input_list = output / f"{profile.name}-image-input-list.txt"
    input_list.write_text("\n".join(input_lines) + "\n", encoding="utf-8")
    device_input = output / f"{profile.name}-image-device-000.float32.raw"
    shutil.copyfile(output / records[0]["file"], device_input)
    device_input_list = output / f"{profile.name}-image-device-input-list.txt"
    device_input_list.write_text((device_input.name + "\n") * device_runs, encoding="utf-8")
    manifest = {
        "schema_version": 1,
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": "qnn_representative_range_candidate",
        "accuracy_claim": False,
        "representative_dataset": False,
        "source_redistribution": "prohibited_without_separate_source-license-review",
        "profile": asdict(profile),
        "resize": "aspect_preserving_letterbox",
        "layout": "NHWC",
        "dtype": "little_endian_float32",
        "samples": records,
        "input_list": input_list.name,
        "device_input_list": device_input_list.name,
        "device_runs": device_runs,
        "device_input": {
            "file": device_input.name,
            "bytes": device_input.stat().st_size,
            "sha256": sha256_file(device_input),
            "layout": "NHWC",
        },
    }
    manifest_path = output / f"{profile.name}-image-calibration-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


def _onnx_metadata(model: Any) -> dict[str, str]:
    return {entry.key: entry.value for entry in model.metadata_props}


def inspect_onnx(path: Path, profile_name: str, input_size: int | None = None) -> dict[str, Any]:
    try:
        import onnx
        import onnxruntime as ort
    except ImportError as error:
        raise RuntimeError("inspect-onnx requires onnx and onnxruntime") from error

    if profile_name == "yoloe":
        if input_size not in (None, CALIBRATION_PROFILES["yoloe"].width):
            raise ValueError("YOLOE input size is fixed at 640")
        expected = CALIBRATION_PROFILES["yoloe"]
    else:
        expected = depth_profile(
            input_size if input_size is not None else CALIBRATION_PROFILES["depth"].width,
        )
    model = onnx.load(path, load_external_data=True)
    onnx.checker.check_model(model)
    inputs = [
        (value.name, [dimension.dim_value for dimension in value.type.tensor_type.shape.dim])
        for value in model.graph.input
    ]
    outputs = [
        (value.name, [dimension.dim_value for dimension in value.type.tensor_type.shape.dim])
        for value in model.graph.output
    ]
    expected_input = [1, 3, expected.height, expected.width]
    if len(inputs) != 1 or inputs[0][1] != expected_input:
        raise ValueError(f"unexpected ONNX input shape: {inputs}")
    metadata = _onnx_metadata(model)
    if profile_name == "yoloe":
        names = ast.literal_eval(metadata.get("names", "{}"))
        prompts = load_vocabulary()
        if not isinstance(names, dict) or tuple(names.values()) != prompts:
            raise ValueError("YOLOE ONNX metadata does not contain the exact ordered BVI vocabulary")
        if outputs != [("output0", [1, 300, 38]), ("output1", [1, 32, 160, 160])]:
            raise ValueError(f"YOLOE export must expose detection and mask outputs: {outputs}")
    elif outputs != [("depth_meters", [1, expected.height, expected.width])]:
        raise ValueError(f"unexpected metric-depth output shape: {outputs}")

    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    return {
        "path": path.name,
        "sha256": sha256_file(path),
        "opsets": {item.domain or "onnx": item.version for item in model.opset_import},
        "inputs": inputs,
        "outputs": outputs,
        "runtime_inputs": [item.name for item in session.get_inputs()],
        "runtime_outputs": [item.name for item in session.get_outputs()],
        "vocabulary_sha256": EXPECTED_VOCABULARY_SHA256 if profile_name == "yoloe" else None,
    }


def verify_export_manifest(directory: Path) -> dict[str, Any]:
    resolved = directory.expanduser().resolve()
    if resolved == REPOSITORY_ROOT or REPOSITORY_ROOT in resolved.parents:
        raise ValueError("private/generated model artifacts must remain outside the repository")
    manifest_path = resolved / "model-export-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1 or manifest.get("distribution") != "private_external_artifacts_only":
        raise ValueError("unsupported or unsafe model export manifest")
    if manifest.get("vocabulary_sha256") != EXPECTED_VOCABULARY_SHA256:
        raise ValueError("model export vocabulary fingerprint mismatch")
    if manifest.get("depth_source_revision") != DEPTH_SOURCE_REVISION:
        raise ValueError("model export Depth Anything source revision mismatch")

    sources = manifest.get("sources")
    if not isinstance(sources, dict):
        raise ValueError("model export sources are missing")
    yoloe_source = sources.get("yoloe")
    if not isinstance(yoloe_source, dict) or yoloe_source.get("checkpoint_sha256") != EXPECTED_YOLOE_CHECKPOINT_SHA256:
        raise ValueError("model export YOLOE checkpoint mismatch")
    for name, expected in DEPTH_SOURCES.items():
        actual = sources.get(name)
        if not isinstance(actual, dict):
            raise ValueError(f"model export source is missing: {name}")
        for field in ("repository_id", "revision", "checkpoint_file", "checkpoint_sha256"):
            if actual.get(field) != getattr(expected, field):
                raise ValueError(f"model export source mismatch: {name}.{field}")

    expected_files = {
        "yoloe-26s-bvi330-seg.onnx",
        *(f"{source.output_stem}.onnx" for source in DEPTH_SOURCES.values()),
    }
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list):
        raise ValueError("model export artifact records are missing")
    records = {
        record.get("path"): record
        for record in artifacts
        if isinstance(record, dict) and isinstance(record.get("path"), str)
    }
    if set(records) != expected_files:
        raise ValueError("model export artifact set mismatch")
    for name, record in records.items():
        artifact = resolved / name
        declared = record.get("sha256")
        if not artifact.is_file() or not isinstance(declared, str) or sha256_file(artifact) != declared:
            raise ValueError(f"model export artifact checksum mismatch: {name}")
    return {
        "status": "verified",
        "manifest": manifest_path.name,
        "artifacts": sorted(expected_files),
        "vocabulary_sha256": EXPECTED_VOCABULARY_SHA256,
    }


def verify_depth_variant_manifest(directory: Path) -> dict[str, Any]:
    resolved = directory.expanduser().resolve()
    if resolved == REPOSITORY_ROOT or REPOSITORY_ROOT in resolved.parents:
        raise ValueError("private/generated model artifacts must remain outside the repository")
    manifest_path = resolved / "depth-variant-export-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1 or manifest.get("distribution") != "private_external_artifacts_only":
        raise ValueError("unsupported or unsafe depth variant manifest")
    if manifest.get("depth_source_revision") != DEPTH_SOURCE_REVISION:
        raise ValueError("depth variant source revision mismatch")
    if manifest.get("position_embedding") != "baked_static_bicubic_equivalent":
        raise ValueError("depth variant position embedding is not HTP-safe")
    raw_sizes = manifest.get("input_sizes")
    if not isinstance(raw_sizes, list) or not raw_sizes or any(type(value) is not int for value in raw_sizes):
        raise ValueError("depth variant input sizes are missing")
    sizes = tuple(raw_sizes)
    if tuple(sorted(set(sizes))) != sizes:
        raise ValueError("depth variant input sizes must be unique and sorted")
    for size in sizes:
        depth_profile(size)

    sources = manifest.get("sources")
    if not isinstance(sources, dict):
        raise ValueError("depth variant sources are missing")
    for name, expected in DEPTH_SOURCES.items():
        actual = sources.get(name)
        if not isinstance(actual, dict):
            raise ValueError(f"depth variant source is missing: {name}")
        for field in ("repository_id", "revision", "checkpoint_file", "checkpoint_sha256"):
            if actual.get(field) != getattr(expected, field):
                raise ValueError(f"depth variant source mismatch: {name}.{field}")

    expected_artifacts = {
        f"{source.output_stem}-{size}.onnx": (size, source.profile)
        for source in DEPTH_SOURCES.values()
        for size in sizes
    }
    expected_files = set(expected_artifacts)
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list):
        raise ValueError("depth variant artifact records are missing")
    records = {
        record.get("path"): record
        for record in artifacts
        if isinstance(record, dict) and isinstance(record.get("path"), str)
    }
    if set(records) != expected_files or len(records) != len(artifacts):
        raise ValueError("depth variant artifact set mismatch")
    for name, record in records.items():
        artifact = resolved / name
        declared = record.get("sha256")
        expected_size, expected_profile = expected_artifacts[name]
        if record.get("input_size") != expected_size or record.get("profile") != expected_profile:
            raise ValueError(f"depth variant input size mismatch: {name}")
        if not artifact.is_file() or not isinstance(declared, str) or sha256_file(artifact) != declared:
            raise ValueError(f"depth variant artifact checksum mismatch: {name}")
    return {
        "status": "verified",
        "manifest": manifest_path.name,
        "input_sizes": list(sizes),
        "artifacts": sorted(expected_files),
    }


def _git_revision(path: Path) -> str:
    result = subprocess.run(
        ["git", "-C", str(path), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def _validate_depth_inputs(
    depth_source: Path,
    indoor_checkpoint: Path,
    outdoor_checkpoint: Path,
) -> None:
    if _git_revision(depth_source) != DEPTH_SOURCE_REVISION:
        raise ValueError("Depth Anything V2 source checkout is not at the pinned revision")
    for source, checkpoint in (
        (DEPTH_SOURCES["depth-indoor"], indoor_checkpoint),
        (DEPTH_SOURCES["depth-outdoor"], outdoor_checkpoint),
    ):
        if sha256_file(checkpoint) != source.checkpoint_sha256:
            raise ValueError(f"unexpected checkpoint SHA-256 for {source.profile}")


def _load_depth_model(depth_source: Path, source: DepthArtifactSource, checkpoint: Path, torch: Any) -> Any:
    module_path = str(depth_source / "metric_depth")
    if module_path not in sys.path:
        sys.path.insert(0, module_path)
    from depth_anything_v2.dpt import DepthAnythingV2

    config = {"encoder": "vits", "features": 64, "out_channels": [48, 96, 192, 384]}
    model = DepthAnythingV2(**config, max_depth=source.max_depth_meters)
    model.load_state_dict(torch.load(checkpoint, map_location="cpu", weights_only=True), strict=True)
    model.eval()
    return model


def _bake_depth_position_embedding(model: Any, input_size: int, torch: Any) -> None:
    """Replace DINO's native grid with its fixed-size interpolated equivalent.

    Smaller static inputs otherwise leave a bicubic positional-embedding Resize
    in the ONNX graph. QAIRT can convert that operation, but the target HTP V79
    rejects the graph during composition. Baking the constant preserves the
    upstream interpolation while removing it from the device graph.
    """
    import math

    target_grid = input_size // 14
    position = model.pretrained.pos_embed.detach().float()
    patch_count = position.shape[1] - 1
    source_grid = math.isqrt(patch_count)
    if source_grid * source_grid != patch_count:
        raise ValueError("depth position embedding does not contain a square patch grid")
    if source_grid == target_grid:
        return
    class_position = position[:, :1]
    patch_position = position[:, 1:].reshape(1, source_grid, source_grid, position.shape[-1])
    patch_position = patch_position.permute(0, 3, 1, 2)
    interpolation_offset = model.pretrained.interpolate_offset
    scale = float(target_grid + interpolation_offset) / source_grid
    patch_position = torch.nn.functional.interpolate(
        patch_position,
        scale_factor=(scale, scale),
        mode="bicubic",
        align_corners=False,
        antialias=model.pretrained.interpolate_antialias,
    )
    if patch_position.shape[-2:] != (target_grid, target_grid):
        raise ValueError("depth position embedding interpolation produced an unexpected grid")
    patch_position = patch_position.permute(0, 2, 3, 1).reshape(1, target_grid * target_grid, -1)
    baked = torch.cat((class_position, patch_position), dim=1).to(model.pretrained.pos_embed.dtype)
    model.pretrained.pos_embed = torch.nn.Parameter(baked, requires_grad=False)


def _export_depth_model(model: Any, output: Path, input_size: int, torch: Any) -> None:
    with torch.inference_mode():
        torch.onnx.export(
            model,
            torch.zeros(1, 3, input_size, input_size),
            output,
            input_names=["images"],
            output_names=["depth_meters"],
            opset_version=17,
            dynamo=False,
            do_constant_folding=True,
        )


def export_depth_variants(args: argparse.Namespace) -> dict[str, Any]:
    output = ensure_external_output(args.output_dir)
    sizes = tuple(sorted(set(args.sizes)))
    if not sizes:
        raise ValueError("at least one depth input size is required")
    for size in sizes:
        depth_profile(size)
    _validate_depth_inputs(
        args.depth_source,
        args.depth_indoor_checkpoint,
        args.depth_outdoor_checkpoint,
    )
    try:
        import onnx
        import onnxruntime
        import torch
    except ImportError as error:
        raise RuntimeError("export-depth-variants requires torch, onnx, and onnxruntime") from error

    artifacts: list[dict[str, Any]] = []
    for source, checkpoint in (
        (DEPTH_SOURCES["depth-indoor"], args.depth_indoor_checkpoint),
        (DEPTH_SOURCES["depth-outdoor"], args.depth_outdoor_checkpoint),
    ):
        for size in sizes:
            model = _load_depth_model(args.depth_source, source, checkpoint, torch)
            _bake_depth_position_embedding(model, size, torch)
            depth_output = output / f"{source.output_stem}-{size}.onnx"
            _export_depth_model(model, depth_output, size, torch)
            record = inspect_onnx(depth_output, "depth", size)
            record["input_size"] = size
            record["profile"] = source.profile
            artifacts.append(record)

    manifest = {
        "schema_version": 1,
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "distribution": "private_external_artifacts_only",
        "purpose": "static_metric_depth_resolution_comparison",
        "accuracy_claim": False,
        "position_embedding": "baked_static_bicubic_equivalent",
        "torch_version": torch.__version__,
        "onnx_version": onnx.__version__,
        "onnxruntime_version": onnxruntime.__version__,
        "depth_source_revision": DEPTH_SOURCE_REVISION,
        "input_sizes": list(sizes),
        "sources": {name: asdict(source) for name, source in DEPTH_SOURCES.items()},
        "artifacts": artifacts,
    }
    (output / "depth-variant-export-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return manifest


def export_models(args: argparse.Namespace) -> dict[str, Any]:
    output = ensure_external_output(args.output_dir)
    if not args.acknowledge_ultralytics_terms:
        raise ValueError("YOLOE export requires --acknowledge-ultralytics-terms")
    if sha256_file(args.yoloe_checkpoint) != EXPECTED_YOLOE_CHECKPOINT_SHA256:
        raise ValueError("unexpected YOLOE-26S checkpoint SHA-256")
    _validate_depth_inputs(args.depth_source, args.depth_indoor_checkpoint, args.depth_outdoor_checkpoint)

    try:
        import onnx
        import onnxruntime
        import torch
        import ultralytics
        from ultralytics import YOLOE
    except ImportError as error:
        raise RuntimeError("export requires torch, ultralytics, onnx, and onnxruntime") from error

    if ultralytics.__version__ != "8.4.90":
        raise RuntimeError(f"expected ultralytics 8.4.90, found {ultralytics.__version__}")

    prompts = list(load_vocabulary())
    fixed_checkpoint = output / "yoloe-26s-bvi330-seg.pt"
    with chdir(output):
        yoloe = YOLOE(str(args.yoloe_checkpoint))
        yoloe.set_classes(prompts)
        if tuple(yoloe.names.values()) != tuple(prompts):
            raise RuntimeError("YOLOE did not retain the exact fixed vocabulary")
        yoloe.save(fixed_checkpoint)
        # Reload so Ultralytics derives its export destination from the private
        # fixed-vocabulary checkpoint rather than from the caller's source.
        yoloe = YOLOE(str(fixed_checkpoint))
        if tuple(yoloe.names.values()) != tuple(prompts):
            raise RuntimeError("saved YOLOE checkpoint lost the fixed vocabulary")
        exported = Path(
            yoloe.export(
                format="onnx",
                imgsz=640,
                batch=1,
                dynamic=False,
                simplify=True,
                opset=17,
                nms=False,
                device="cpu",
            )
        )
    yoloe_onnx = output / "yoloe-26s-bvi330-seg.onnx"
    if exported.resolve() != yoloe_onnx.resolve():
        shutil.move(exported, yoloe_onnx)

    depth_outputs: list[Path] = []
    for source, checkpoint in (
        (DEPTH_SOURCES["depth-indoor"], args.depth_indoor_checkpoint),
        (DEPTH_SOURCES["depth-outdoor"], args.depth_outdoor_checkpoint),
    ):
        model = _load_depth_model(args.depth_source, source, checkpoint, torch)
        depth_output = output / f"{source.output_stem}.onnx"
        _export_depth_model(model, depth_output, 518, torch)
        depth_outputs.append(depth_output)

    artifacts = [inspect_onnx(yoloe_onnx, "yoloe")]
    artifacts.extend(inspect_onnx(path, "depth") for path in depth_outputs)
    manifest = {
        "schema_version": 1,
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "distribution": "private_external_artifacts_only",
        "ultralytics_version": ultralytics.__version__,
        "torch_version": torch.__version__,
        "onnx_version": onnx.__version__,
        "onnxruntime_version": onnxruntime.__version__,
        "depth_source_revision": DEPTH_SOURCE_REVISION,
        "vocabulary_sha256": EXPECTED_VOCABULARY_SHA256,
        "sources": {
            "yoloe": {
                "checkpoint_sha256": EXPECTED_YOLOE_CHECKPOINT_SHA256,
                "license_gate": "AGPL-3.0-or-commercial-authorization",
            },
            **{name: asdict(source) for name, source in DEPTH_SOURCES.items()},
        },
        "artifacts": artifacts,
    }
    (output / "model-export-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return manifest


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    vocabulary = commands.add_parser("vocabulary", help="print and validate the fixed vocabulary")
    vocabulary.add_argument("--json", action="store_true")

    calibration = commands.add_parser("calibration", help="generate deterministic converter-smoke inputs")
    calibration.add_argument("--profile", choices=tuple(CALIBRATION_PROFILES), required=True)
    calibration.add_argument("--output-dir", type=Path, required=True)
    calibration.add_argument("--samples", type=int, default=4)
    calibration.add_argument("--device-runs", type=int, default=1)
    calibration.add_argument("--size", type=int)

    calibration_images = commands.add_parser(
        "calibration-images",
        help="prepare external image files as NHWC QNN calibration inputs",
    )
    calibration_images.add_argument("--profile", choices=tuple(CALIBRATION_PROFILES), required=True)
    calibration_images.add_argument("--images", type=Path, required=True)
    calibration_images.add_argument("--output-dir", type=Path, required=True)
    calibration_images.add_argument("--limit", type=int, default=32)
    calibration_images.add_argument("--device-runs", type=int, default=1)
    calibration_images.add_argument("--size", type=int)

    inspect = commands.add_parser("inspect-onnx", help="validate an exported ONNX contract")
    inspect.add_argument("--profile", choices=("yoloe", "depth"), required=True)
    inspect.add_argument("--model", type=Path, required=True)
    inspect.add_argument("--size", type=int)

    verify = commands.add_parser("verify-export", help="verify a pinned external export manifest")
    verify.add_argument("--directory", type=Path, required=True)

    verify_variants = commands.add_parser(
        "verify-depth-variants",
        help="verify a pinned external metric-depth variant manifest",
    )
    verify_variants.add_argument("--directory", type=Path, required=True)

    export_variants = commands.add_parser(
        "export-depth-variants",
        help="export pinned metric-depth checkpoints at static comparison sizes",
    )
    export_variants.add_argument("--depth-source", type=Path, required=True)
    export_variants.add_argument("--depth-indoor-checkpoint", type=Path, required=True)
    export_variants.add_argument("--depth-outdoor-checkpoint", type=Path, required=True)
    export_variants.add_argument("--output-dir", type=Path, required=True)
    export_variants.add_argument("--sizes", type=int, nargs="+", default=[336, 392])

    export = commands.add_parser("export", help="export pinned checkpoints to static ONNX")
    export.add_argument("--yoloe-checkpoint", type=Path, required=True)
    export.add_argument("--depth-source", type=Path, required=True)
    export.add_argument("--depth-indoor-checkpoint", type=Path, required=True)
    export.add_argument("--depth-outdoor-checkpoint", type=Path, required=True)
    export.add_argument("--output-dir", type=Path, required=True)
    export.add_argument("--acknowledge-ultralytics-terms", action="store_true")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "vocabulary":
            prompts = load_vocabulary()
            value: Any = list(prompts) if args.json else "\n".join(prompts)
        elif args.command == "calibration":
            profile = CALIBRATION_PROFILES[args.profile]
            if args.size is not None:
                if args.profile != "depth":
                    raise ValueError("--size is supported only for the depth profile")
                profile = depth_profile(args.size)
            value = write_calibration_set(
                args.output_dir,
                profile,
                args.samples,
                args.device_runs,
            )
        elif args.command == "calibration-images":
            profile = CALIBRATION_PROFILES[args.profile]
            if args.size is not None:
                if args.profile != "depth":
                    raise ValueError("--size is supported only for the depth profile")
                profile = depth_profile(args.size)
            value = write_image_calibration_set(
                args.output_dir,
                profile,
                args.images,
                args.limit,
                args.device_runs,
            )
        elif args.command == "inspect-onnx":
            value = inspect_onnx(args.model, args.profile, args.size)
        elif args.command == "verify-export":
            value = verify_export_manifest(args.directory)
        elif args.command == "verify-depth-variants":
            value = verify_depth_variant_manifest(args.directory)
        elif args.command == "export-depth-variants":
            value = export_depth_variants(args)
        else:
            value = export_models(args)
    except (OSError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        print(f"model-artifacts: {error}", file=sys.stderr)
        return 1
    if isinstance(value, str):
        print(value)
    else:
        print(json.dumps(value, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
