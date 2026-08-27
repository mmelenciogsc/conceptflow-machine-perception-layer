#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Generate consent-gated private Rokid narration through QUICKPub's worker.

The reference sample, model, runtime, and generated WAV files stay outside Git.
No download is performed. Android uses its capability-selected TextToSpeech
fallback whenever the complete private eight-file voice set is absent.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import tempfile
import threading
import wave


ROOT = Path(__file__).resolve().parents[1]
MODEL_REVISION = "749d1c1a46eb10492095d68fbcf55691ccf137cd"
AUDITED_QUICKPUB_REVISION = "27808e8f9d0ec073af6091a6b9a49f1d021779a9"
AUDITED_WORKER_SHA256 = "7fa492d8d684bf7c01daef8d65e67a1307e7fe3df16268e796abe85610b9801b"
AUDITED_RUNTIME_MANIFEST_SHA256 = "dda48c703de5fc22b997f28921658a6e414a4795ff5e2f4ea3e88b2048389d35"
ENGINE = "chatterbox-turbo-0.1.7"
WATERMARK = "PerTh disclosure watermark retained by Chatterbox"
UTTERANCE_TEXTS = (
    ("concept_flow.wav", "Concept flow."),
    ("machine_intelligence.wav", "Machine Intelligence."),
    ("human_architecture.wav", "Human Architecture."),
    ("machine_perception_layer.wav", "Machine Perception Layer."),
    ("map.wav", "Map."),
    ("morph.wav", "Morph."),
    ("move.wav", "Move."),
    ("supplemental_awareness.wav", "It's just supplemental awareness."),
)
DEFAULT_SEEDS = (1_337, 1_338, 1_339, 1_340, 1_341, 1_342, 1_343, 1_344)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quickpub-root", required=True, type=Path)
    parser.add_argument("--python", required=True, type=Path, help="QUICKPub Chatterbox Python executable")
    parser.add_argument("--model", required=True, type=Path, help="Pinned Chatterbox Turbo model directory")
    parser.add_argument("--voice-sample", required=True, type=Path, help="Permitted 10-30 second local sample")
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cuda")
    parser.add_argument("--ffprobe", default="ffprobe", help="ffprobe executable used to validate sample duration")
    parser.add_argument(
        "--output-dir",
        required=True,
        type=Path,
        help="Explicit private output directory outside the repository",
    )
    parser.add_argument("--replace", action="store_true", help="Replace an existing private generated voice set")
    parser.add_argument(
        "--i-have-voice-permission",
        action="store_true",
        help="Affirm permission for this exact sample and Chatterbox voice cloning",
    )
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_file(path: Path, label: str) -> Path:
    resolved = path.expanduser().resolve()
    if not resolved.is_file():
        raise SystemExit(f"{label} was not found: {resolved}")
    return resolved


def require_executable(path: Path, label: str) -> Path:
    # Preserve a virtual-environment launcher symlink: resolving it to the base
    # interpreter would silently discard the environment's site-packages.
    absolute = path.expanduser().absolute()
    if not absolute.is_file() or not os.access(absolute, os.X_OK):
        raise SystemExit(f"{label} was not found or is not executable: {absolute}")
    return absolute


def require_directory(path: Path, label: str) -> Path:
    resolved = path.expanduser().resolve()
    if not resolved.is_dir():
        raise SystemExit(f"{label} was not found: {resolved}")
    return resolved


def require_sha256(path: Path, expected: str, label: str) -> None:
    actual = sha256(path)
    if actual != expected:
        raise SystemExit(f"{label} differs from the audited QUICKPub revision: {path}")


def validate_wave(path: Path) -> dict[str, int]:
    with wave.open(str(path), "rb") as audio:
        details = {
            "channels": audio.getnchannels(),
            "sampleRateHz": audio.getframerate(),
            "sampleWidthBytes": audio.getsampwidth(),
            "frames": audio.getnframes(),
        }
    if details["channels"] < 1 or details["sampleRateHz"] < 8_000 or details["frames"] < 1:
        raise RuntimeError(f"Generated output is not a usable WAV: {path.name}")
    return details


def validate_voice_sample_duration(sample: Path, ffprobe_command: str) -> float:
    executable = shutil.which(ffprobe_command)
    if executable is None:
        candidate = Path(ffprobe_command).expanduser()
        if not candidate.is_file():
            raise SystemExit("ffprobe is required to enforce QUICKPub's 10-30 second sample contract.")
        executable = str(candidate.resolve())
    result = subprocess.run(
        [
            executable,
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(sample),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    duration_seconds = float(result.stdout.strip())
    if not 10.0 <= duration_seconds <= 30.0:
        raise SystemExit("Voice sample must be between 10 and 30 seconds for the QUICKPub contract.")
    return duration_seconds


def expected_manifest_values() -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "sourceRepository": "https://github.com/mmelenciogsc/QUICKPub",
        "sourceRevision": AUDITED_QUICKPUB_REVISION,
        "workerSha256": AUDITED_WORKER_SHA256,
        "engine": ENGINE,
        "modelRevision": MODEL_REVISION,
        "runtimeManifestSha256": AUDITED_RUNTIME_MANIFEST_SHA256,
        "voicePermissionAffirmed": True,
        "voiceSampleKeptExternal": True,
        "watermark": WATERMARK,
    }


def validate_existing_voice_set(output_directory: Path) -> None:
    manifest_path = require_file(output_directory / "manifest.json", "Existing private manifest")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected_values = expected_manifest_values()
    if set(manifest) != set(expected_values) | {"outputs"}:
        raise SystemExit("Existing private manifest does not match the current schema.")
    for key, expected in expected_values.items():
        if type(manifest.get(key)) is not type(expected) or manifest.get(key) != expected:
            raise SystemExit(f"Existing private manifest field is invalid: {key}")
    outputs = manifest.get("outputs")
    if not isinstance(outputs, list) or len(outputs) != len(UTTERANCE_TEXTS):
        raise SystemExit("Existing private manifest output set is incomplete.")
    declared: dict[str, str] = {}
    expected_names = {filename for filename, _ in UTTERANCE_TEXTS}
    for output in outputs:
        if not isinstance(output, dict) or set(output) != {"file", "sha256"}:
            raise SystemExit("Existing private manifest output entry is invalid.")
        filename = output.get("file")
        digest = output.get("sha256")
        if (
            not isinstance(filename, str)
            or filename not in expected_names
            or filename in declared
            or not isinstance(digest, str)
            or len(digest) != 64
            or any(character not in "0123456789abcdef" for character in digest)
        ):
            raise SystemExit("Existing private manifest output identity is invalid.")
        declared[filename] = digest
    if set(declared) != expected_names:
        raise SystemExit("Existing private manifest output names are incomplete.")
    for filename in expected_names:
        path = require_file(output_directory / filename, "Existing private voice output")
        validate_wave(path)
        if sha256(path) != declared[filename]:
            raise SystemExit(f"Existing private voice output failed validation: {filename}")


def verify_model(quickpub_root: Path, model_directory: Path) -> dict[str, object]:
    manifest_path = require_file(
        quickpub_root / "dependencies" / "runtime-manifest.json",
        "QUICKPub runtime manifest",
    )
    require_sha256(
        manifest_path,
        AUDITED_RUNTIME_MANIFEST_SHA256,
        "QUICKPub runtime manifest",
    )
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    matches = [
        item
        for item in manifest.get("models", [])
        if item.get("engineId") == "chatterbox-turbo-local" and item.get("revision") == MODEL_REVISION
    ]
    if len(matches) != 1:
        raise SystemExit(f"QUICKPub manifest does not uniquely pin model revision {MODEL_REVISION}.")
    model = matches[0]
    files = model.get("files", [])
    if not files:
        raise SystemExit("QUICKPub manifest does not contain pinned Chatterbox model files.")
    for entry in files:
        path = require_file(model_directory / str(entry["path"]), "Pinned Chatterbox model file")
        if path.stat().st_size != int(entry["size"]) or sha256(path) != str(entry["sha256"]):
            raise SystemExit(f"Chatterbox model verification failed for {path.name}.")
    return {
        "revision": MODEL_REVISION,
        "runtimeManifestSha256": sha256(manifest_path),
    }


class Worker:
    def __init__(self, python: Path, worker: Path) -> None:
        environment = os.environ.copy()
        environment["HF_HUB_OFFLINE"] = "1"
        environment["TRANSFORMERS_OFFLINE"] = "1"
        environment["PYTHONUTF8"] = "1"
        self._diagnostics: queue.Queue[str] = queue.Queue(maxsize=80)
        self._process = subprocess.Popen(
            [str(python), "-u", str(worker)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            env=environment,
        )
        threading.Thread(target=self._drain_stderr, daemon=True).start()

    def _drain_stderr(self) -> None:
        assert self._process.stderr is not None
        for line in self._process.stderr:
            try:
                self._diagnostics.put_nowait(line.rstrip())
            except queue.Full:
                self._diagnostics.get_nowait()
                self._diagnostics.put_nowait(line.rstrip())

    def request(self, payload: dict[str, object]) -> dict[str, object]:
        assert self._process.stdin is not None
        assert self._process.stdout is not None
        self._process.stdin.write(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n")
        self._process.stdin.flush()
        line = self._process.stdout.readline()
        if not line:
            details = "\n".join(self._diagnostics.queue)
            raise RuntimeError(f"QUICKPub voice worker stopped without a response.\n{details}")
        response = json.loads(line)
        if response.get("ok") is not True:
            raise RuntimeError(f"QUICKPub voice worker failed: {response.get('error', 'unknown error')}")
        return response

    def close(self) -> None:
        if self._process.poll() is not None:
            return
        try:
            self.request({"command": "shutdown"})
            self._process.wait(timeout=5)
        except (BrokenPipeError, RuntimeError, subprocess.TimeoutExpired):
            self._process.terminate()
            try:
                self._process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._process.kill()
                self._process.wait(timeout=5)


def git_revision(repository: Path) -> str:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=repository,
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def main() -> int:
    args = parse_args()
    if not args.i_have_voice_permission:
        raise SystemExit(
            "Generation refused: pass --i-have-voice-permission only after confirming permission "
            "for the exact reference sample and Chatterbox engine."
        )
    quickpub_root = require_directory(args.quickpub_root, "QUICKPub repository")
    quickpub_revision = git_revision(quickpub_root)
    if quickpub_revision != AUDITED_QUICKPUB_REVISION:
        raise SystemExit(
            f"QUICKPub checkout is not the audited revision {AUDITED_QUICKPUB_REVISION}: {quickpub_revision}"
        )
    python = require_executable(args.python, "QUICKPub Python executable")
    model = require_directory(args.model, "Chatterbox model")
    sample = require_file(args.voice_sample, "Permitted voice sample")
    worker_path = require_file(quickpub_root / "workers" / "chatterbox" / "worker.py", "QUICKPub worker")
    require_sha256(worker_path, AUDITED_WORKER_SHA256, "QUICKPub worker")
    validate_voice_sample_duration(sample, args.ffprobe)
    verify_model(quickpub_root, model)
    output_directory = args.output_dir.expanduser().resolve()
    try:
        output_directory.relative_to(ROOT)
    except ValueError:
        pass
    else:
        raise SystemExit("Private voice output must be outside the repository.")
    output_directory.mkdir(parents=True, exist_ok=True)
    utterances = tuple((*utterance, seed) for utterance, seed in zip(UTTERANCE_TEXTS, DEFAULT_SEEDS, strict=True))
    destinations = [output_directory / filename for filename, _ in UTTERANCE_TEXTS]
    existing = [path for path in destinations if path.exists()]
    if existing and not args.replace:
        raise SystemExit("Private voice outputs already exist; pass --replace to regenerate them.")
    else:
        selected_utterances = utterances

    worker = Worker(python, worker_path)
    try:
        worker.request({"command": "initialize", "modelPath": str(model), "device": args.device})
        with tempfile.TemporaryDirectory(prefix="rokid-brand-", dir=output_directory) as temporary:
            temporary_directory = Path(temporary)
            for filename, text, seed in selected_utterances:
                temporary_path = temporary_directory / filename
                worker.request(
                    {
                        "command": "synthesize",
                        "text": text,
                        "voiceSamplePath": str(sample),
                        "outputPath": str(temporary_path),
                        "seed": seed,
                    }
                )
                validate_wave(temporary_path)
            for filename, _, _ in selected_utterances:
                os.replace(temporary_directory / filename, output_directory / filename)
    finally:
        worker.close()

    generated = [{"file": path.name, "sha256": sha256(path)} for path in destinations]
    manifest = {**expected_manifest_values(), "outputs": generated}
    manifest_path = output_directory / "manifest.json"
    temporary_manifest = output_directory / ".manifest.json.tmp"
    temporary_manifest.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary_manifest, manifest_path)
    print(f"Generated {len(selected_utterances)} private branded utterance(s) in {output_directory}")
    print("The reference sample was not copied; provision this external set explicitly.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
