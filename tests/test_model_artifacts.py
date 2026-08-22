# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

from importlib.util import module_from_spec, spec_from_file_location
import json
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "scripts/repository/model_artifacts.py"
SPEC = spec_from_file_location("model_artifacts", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
model_artifacts = module_from_spec(SPEC)
sys.modules[SPEC.name] = model_artifacts
SPEC.loader.exec_module(model_artifacts)


def test_fixed_vocabulary_matches_android_catalog() -> None:
    kotlin = (ROOT / "apps/android-host/src/main/java/org/conceptflow/mpl/host/vision/BviClassCatalog.kt").read_text(
        encoding="utf-8"
    )
    kotlin_prompts = tuple(re.findall(r'entry\("[^"]+",\s*"([^"]+)"', kotlin))

    assert model_artifacts.load_vocabulary() == kotlin_prompts
    assert len(kotlin_prompts) == 40
    assert model_artifacts.EXPECTED_VOCABULARY_SHA256 == (
        "2ca8ebc9d1b7914e1dfd1d288e517e78e1b24be75ad04cd6bc0df3e0455aca44"
    )


def test_depth_sources_are_pinned_to_official_small_metric_checkpoints() -> None:
    indoor = model_artifacts.DEPTH_SOURCES["depth-indoor"]
    outdoor = model_artifacts.DEPTH_SOURCES["depth-outdoor"]

    assert indoor.repository_id.endswith("Depth-Anything-V2-Metric-Hypersim-Small")
    assert indoor.max_depth_meters == 20.0
    assert indoor.revision == "3bc65d4e14a6786a61acec16453c50e12bf5f338"
    assert outdoor.repository_id.endswith("Depth-Anything-V2-Metric-VKITTI-Small")
    assert outdoor.max_depth_meters == 80.0
    assert outdoor.revision == "c725b8589bdf6ab04072cab74c0467830db80d6d"


def test_synthetic_calibration_is_deterministic_and_explicitly_not_representative(tmp_path: Path) -> None:
    first_output = tmp_path / "first"
    second_output = tmp_path / "second"
    profile = model_artifacts.CalibrationProfile("test", 8, 6, "unit_rgb")

    first = model_artifacts.write_calibration_set(first_output, profile, samples=4, device_runs=3)
    second = model_artifacts.write_calibration_set(second_output, profile, samples=4, device_runs=3)

    assert first["purpose"] == "qnn_converter_compatibility_smoke_only"
    assert first["accuracy_claim"] is False
    assert first["representative_dataset"] is False
    assert [sample["sha256"] for sample in first["samples"]] == [sample["sha256"] for sample in second["samples"]]
    assert all(sample["bytes"] == 1 * 3 * 8 * 6 * 4 for sample in first["samples"])
    manifest = json.loads((first_output / "test-calibration-manifest.json").read_text(encoding="utf-8"))
    assert manifest["layout"] == "NHWC"
    assert manifest["dtype"] == "little_endian_float32"
    assert (first_output / manifest["device_input_list"]).read_text(encoding="utf-8") == (
        "test-device-000.float32.raw\ntest-device-000.float32.raw\ntest-device-000.float32.raw\n"
    )
    assert manifest["device_input"]["bytes"] == 1 * 3 * 8 * 6 * 4
    assert manifest["device_input"]["layout"] == "NHWC"


def test_private_artifacts_cannot_be_written_inside_repository() -> None:
    profile = model_artifacts.CalibrationProfile("test", 8, 6, "unit_rgb")

    try:
        model_artifacts.write_calibration_set(ROOT / "models", profile, samples=4)
    except ValueError as error:
        assert "outside the repository" in str(error)
    else:
        raise AssertionError("repository output guard did not reject private artifact path")


def test_export_manifest_verifies_exact_external_artifacts_and_detects_tampering(tmp_path: Path) -> None:
    names = [
        "yoloe-26s-bvi40-seg.onnx",
        *(f"{source.output_stem}.onnx" for source in model_artifacts.DEPTH_SOURCES.values()),
    ]
    records = []
    for index, name in enumerate(names):
        path = tmp_path / name
        path.write_bytes(f"fixture-{index}".encode())
        records.append({"path": name, "sha256": model_artifacts.sha256_file(path)})
    manifest = {
        "schema_version": 1,
        "distribution": "private_external_artifacts_only",
        "depth_source_revision": model_artifacts.DEPTH_SOURCE_REVISION,
        "vocabulary_sha256": model_artifacts.EXPECTED_VOCABULARY_SHA256,
        "sources": {
            "yoloe": {"checkpoint_sha256": model_artifacts.EXPECTED_YOLOE_CHECKPOINT_SHA256},
            **{name: model_artifacts.asdict(source) for name, source in model_artifacts.DEPTH_SOURCES.items()},
        },
        "artifacts": records,
    }
    (tmp_path / "model-export-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

    assert model_artifacts.verify_export_manifest(tmp_path)["status"] == "verified"
    (tmp_path / names[0]).write_bytes(b"tampered")
    try:
        model_artifacts.verify_export_manifest(tmp_path)
    except ValueError as error:
        assert "checksum mismatch" in str(error)
    else:
        raise AssertionError("tampered export was accepted")


def test_image_calibration_letterboxes_to_qnn_nhwc(tmp_path: Path) -> None:
    from PIL import Image

    images = tmp_path / "images"
    images.mkdir()
    for index in range(8):
        Image.new("RGB", (12 + index, 8), (index * 20, 64, 192)).save(images / f"sample-{index}.png")
    output = tmp_path / "output"
    profile = model_artifacts.CalibrationProfile("image-test", 10, 6, "imagenet_rgb")

    manifest = model_artifacts.write_image_calibration_set(output, profile, images, limit=8, device_runs=2)

    assert manifest["resize"] == "aspect_preserving_letterbox"
    assert manifest["layout"] == "NHWC"
    assert len(manifest["samples"]) == 8
    assert all(sample["bytes"] == 1 * 3 * 10 * 6 * 4 for sample in manifest["samples"])
    assert manifest["device_input"]["sha256"] == manifest["samples"][0]["sha256"]
    assert (output / manifest["device_input_list"]).read_text(encoding="utf-8").count("\n") == 2
