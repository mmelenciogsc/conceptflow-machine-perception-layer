# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import wave


VOICE_NAMES = (
    "concept_flow.wav",
    "machine_intelligence.wav",
    "human_architecture.wav",
    "machine_perception_layer.wav",
    "map.wav",
    "morph.wav",
    "move.wav",
    "supplemental_awareness.wav",
)


def _write_private_voice_fixture(directory: Path) -> None:
    directory.mkdir()
    for index, name in enumerate(VOICE_NAMES, start=1):
        with wave.open(str(directory / name), "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(16_000)
            audio.writeframes(bytes((0, index, 255, 0)) * 128)
    outputs = [
        {
            "file": name,
            "sha256": hashlib.sha256((directory / name).read_bytes()).hexdigest(),
        }
        for name in VOICE_NAMES
    ]
    (directory / "manifest.json").write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "sourceRepository": "https://github.com/mmelenciogsc/QUICKPub",
                "sourceRevision": "27808e8f9d0ec073af6091a6b9a49f1d021779a9",
                "workerSha256": "7fa492d8d684bf7c01daef8d65e67a1307e7fe3df16268e796abe85610b9801b",
                "engine": "chatterbox-turbo-0.1.7",
                "modelRevision": "749d1c1a46eb10492095d68fbcf55691ccf137cd",
                "runtimeManifestSha256": "dda48c703de5fc22b997f28921658a6e414a4795ff5e2f4ea3e88b2048389d35",
                "voicePermissionAffirmed": True,
                "voiceSampleKeptExternal": True,
                "watermark": "PerTh disclosure watermark retained by Chatterbox",
                "outputs": outputs,
            },
            sort_keys=True,
        ),
        encoding="utf-8",
    )


def test_private_voice_provisioning_uses_binary_exact_run_as_cat(tmp_path: Path) -> None:
    repository = Path(__file__).resolve().parents[1]
    voice_directory = tmp_path / "private-voice"
    device_root = tmp_path / "device"
    fake_bin = tmp_path / "bin"
    adb_log = tmp_path / "adb.log"
    _write_private_voice_fixture(voice_directory)
    device_root.mkdir()
    fake_bin.mkdir()

    adb = fake_bin / "adb"
    adb.write_text(
        r"""#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys

arguments = sys.argv[1:]
with Path(os.environ["MOCK_ADB_LOG"]).open("a", encoding="utf-8") as output:
    output.write(json.dumps(arguments) + "\n")
if arguments[:1] != ["-s"] or len(arguments) < 3:
    raise SystemExit(2)
arguments = arguments[2:]
root = Path(os.environ["MOCK_ADB_ROOT"])

def remote(value):
    path = Path(value)
    if path.is_absolute() or ".." in path.parts:
        raise SystemExit(64)
    return root / path

if arguments == ["get-state"]:
    print("device")
    raise SystemExit(0)
if arguments[:2] == ["shell", "getprop"]:
    values = {
        "ro.product.model": "Rokid Style",
        "ro.product.name": "rokid",
        "ro.product.device": "glasses",
        "ro.product.manufacturer": "Rokid",
        "ro.build.version.sdk": "32",
    }
    print(values[arguments[2]])
    raise SystemExit(0)
if arguments[:3] == ["shell", "pm", "path"]:
    print("package:/mock/base.apk")
    raise SystemExit(0)
if arguments[:3] == ["shell", "am", "force-stop"]:
    raise SystemExit(0)
if arguments[:2] == ["shell", "run-as"]:
    command = arguments[3:]
    if command == ["id"]:
        print("uid=10000(mock)")
    elif command[:1] == ["mkdir"]:
        remote(command[-1]).mkdir(parents="-p" in command, exist_ok="-p" in command)
    elif command[:1] == ["chmod"]:
        if not remote(command[-1]).exists():
            raise SystemExit(1)
    elif command[:2] == ["rm", "-rf"]:
        target = remote(command[2])
        if target.is_dir():
            shutil.rmtree(target)
        elif target.exists():
            target.unlink()
    elif command[:2] == ["test", "-d"]:
        raise SystemExit(0 if remote(command[2]).is_dir() else 1)
    elif command[:1] == ["mv"]:
        remote(command[1]).rename(remote(command[2]))
    elif command[:1] == ["sha256sum"]:
        target = remote(command[1])
        print(hashlib.sha256(target.read_bytes()).hexdigest(), command[1])
    else:
        raise SystemExit(3)
    raise SystemExit(0)
if arguments[:3] == ["exec-in", "run-as", "org.conceptflow.mpl.rokidclient"]:
    command = arguments[3:]
    if command[:4] != ["sh", "-c", 'cat > "$1"', "sh"] or len(command) != 5:
        raise SystemExit(4)
    target = remote(command[4])
    payload = sys.stdin.buffer.read()
    if target.name == os.environ.get("MOCK_ADB_TRUNCATE_FILE"):
        payload = payload[: max(1, len(payload) // 2)]
    target.write_bytes(payload)
    raise SystemExit(0)
raise SystemExit(5)
""",
        encoding="utf-8",
    )
    adb.chmod(0o755)
    environment = os.environ.copy()
    environment.update(
        PATH=f"{fake_bin}:{environment['PATH']}",
        MOCK_ADB_LOG=str(adb_log),
        MOCK_ADB_ROOT=str(device_root),
    )

    result = subprocess.run(
        [
            str(repository / "scripts/provision-private-rokid-brand-voice"),
            "--serial",
            "rokid-test",
            "--voice-dir",
            str(voice_directory),
        ],
        cwd=repository,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0, result.stderr
    target = device_root / "no_backup/private/rokid_brand_voice"
    assert {path.name for path in target.iterdir()} == {*VOICE_NAMES, "manifest.json"}
    for name in (*VOICE_NAMES, "manifest.json"):
        assert (target / name).read_bytes() == (voice_directory / name).read_bytes()
    assert not (device_root / "no_backup/private/rokid_brand_voice.staging").exists()
    assert not (device_root / "no_backup/private/rokid_brand_voice.previous").exists()

    calls = [json.loads(line) for line in adb_log.read_text(encoding="utf-8").splitlines()]
    transfers = [call for call in calls if "exec-in" in call]
    assert len(transfers) == 9
    assert all("tee" not in call for call in transfers)
    assert all(call[7] == 'cat > "$1"' for call in transfers)
    assert all(call[8] == "sh" for call in transfers)

    installed_before_failure = {name: (target / name).read_bytes() for name in (*VOICE_NAMES, "manifest.json")}
    environment["MOCK_ADB_TRUNCATE_FILE"] = "machine_perception_layer.wav"
    failed_result = subprocess.run(
        [
            str(repository / "scripts/provision-private-rokid-brand-voice"),
            "--serial",
            "rokid-test",
            "--voice-dir",
            str(voice_directory),
        ],
        cwd=repository,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )

    assert failed_result.returncode != 0
    assert "private voice transfer verification failed: machine_perception_layer.wav" in failed_result.stderr
    assert {name: (target / name).read_bytes() for name in (*VOICE_NAMES, "manifest.json")} == installed_before_failure
    assert not (device_root / "no_backup/private/rokid_brand_voice.staging").exists()
    assert not (device_root / "no_backup/private/rokid_brand_voice.previous").exists()
