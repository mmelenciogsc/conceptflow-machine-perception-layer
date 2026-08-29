# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess


def test_unity_hrtf_control_uses_run_as_atomic_spool_without_receiver(tmp_path: Path) -> None:
    repository = Path(__file__).resolve().parents[1]
    fake_bin = tmp_path / "bin"
    device_files = tmp_path / "device-files"
    adb_log = tmp_path / "adb.log"
    fake_bin.mkdir()
    device_files.mkdir()
    adb = fake_bin / "adb"
    adb.write_text(
        r"""#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

arguments = sys.argv[1:]
payload = sys.stdin.read() if arguments[2:3] == ["exec-in"] else ""
with Path(os.environ["MOCK_ADB_LOG"]).open("a", encoding="utf-8") as output:
    output.write(json.dumps({"arguments": arguments, "payload": payload}) + "\n")
if arguments[:1] != ["-s"] or len(arguments) < 3:
    raise SystemExit(2)
arguments = arguments[2:]
root = Path(os.environ["MOCK_ADB_FILES"])

def stored(path: str) -> Path:
    return root / path.replace("/", "_")

if arguments == ["get-state"]:
    print("device")
    raise SystemExit(0)
if arguments[:3] == ["shell", "pm", "path"]:
    print("package:/mock/base.apk")
    raise SystemExit(0)
if arguments[:4] == ["shell", "run-as", "org.conceptflow.mpl.unitylab", "id"]:
    print("uid=12345(u0_a123)")
    raise SystemExit(0)
if arguments[:4] == ["shell", "run-as", "org.conceptflow.mpl.unitylab", "mkdir"]:
    raise SystemExit(0)
if arguments[:4] == ["shell", "run-as", "org.conceptflow.mpl.unitylab", "chmod"]:
    raise SystemExit(0)
if arguments[:4] == ["shell", "run-as", "org.conceptflow.mpl.unitylab", "test"]:
    raise SystemExit(0 if stored(arguments[5]).exists() else 1)
if arguments[:4] == ["shell", "run-as", "org.conceptflow.mpl.unitylab", "rm"]:
    stored(arguments[-1]).unlink(missing_ok=True)
    raise SystemExit(0)
if arguments[:4] == ["shell", "run-as", "org.conceptflow.mpl.unitylab", "mv"]:
    source = stored(arguments[4])
    target = stored(arguments[5])
    source.replace(target)
    fields = target.read_text(encoding="utf-8").rstrip("\n").split("\t")
    target.unlink()
    previous_status_path = stored("files/hrtf-calibration/status.json")
    previous_status = json.loads(previous_status_path.read_text(encoding="utf-8")) if previous_status_path.exists() else {}
    session_id = fields[3] if fields[2] == "start" else previous_status.get("session_id", "")
    status = {
        "schema": "conceptflow.hrtf-command-status/v1",
        "state": "Aborted" if fields[2] == "abort" else "ReadyForTrial",
        "session_id": session_id,
        "current_trial_id": "hrtf-01",
        "current_ordinal": 1,
        "answered": 0,
        "total": 24,
        "last_nonce": int(fields[1]),
        "result_file": f"{session_id}.responses.ndjson",
        "error": "",
    }
    stored("files/hrtf-calibration/status.json").write_text(
        json.dumps(status, separators=(",", ":")), encoding="utf-8"
    )
    if fields[2] == "start":
        stored(f"files/hrtf-calibration/{session_id}.responses.ndjson").write_text(
            "{}\n", encoding="utf-8"
        )
    raise SystemExit(0)
if arguments[:3] == ["shell", "am", "start"]:
    raise SystemExit(0)
if arguments[:4] == ["exec-in", "run-as", "org.conceptflow.mpl.unitylab", "tee"]:
    stored(arguments[4]).write_text(payload, encoding="utf-8")
    raise SystemExit(0)
if arguments[:4] == ["exec-out", "run-as", "org.conceptflow.mpl.unitylab", "cat"]:
    path = stored(arguments[4])
    if not path.exists():
        raise SystemExit(1)
    sys.stdout.write(path.read_text(encoding="utf-8"))
    raise SystemExit(0)
raise SystemExit(4)
""",
        encoding="utf-8",
    )
    adb.chmod(0o755)
    environment = os.environ.copy()
    environment.update(
        PATH=f"{fake_bin}:{environment['PATH']}",
        MOCK_ADB_FILES=str(device_files),
        MOCK_ADB_LOG=str(adb_log),
    )
    result = subprocess.run(
        [
            str(repository / "scripts/unity-hrtf-calibration"),
            "--serial",
            "test-device",
            "start",
            "--session",
            "blind-session",
        ],
        cwd=repository,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0, result.stderr
    status = json.loads(result.stdout)
    assert status["current_trial_id"] == "hrtf-01"
    assert not any(key.startswith("target_") for key in status)
    calls = [json.loads(line) for line in adb_log.read_text(encoding="utf-8").splitlines()]
    assert any(call["arguments"][2:3] == ["exec-in"] for call in calls)
    assert any(call["arguments"][2:6] == ["shell", "run-as", "org.conceptflow.mpl.unitylab", "mv"] for call in calls)
    assert any(call["arguments"][2:5] == ["shell", "am", "start"] for call in calls)
    assert not any("broadcast" in call["arguments"] or "content" in call["arguments"] for call in calls)
    transferred = [call["payload"] for call in calls if call["payload"]]
    assert len(transferred) == 1
    assert transferred[0].startswith("v1\t")
    assert transferred[0].endswith("\tstart\tblind-session\n")

    abort = subprocess.run(
        [str(repository / "scripts/unity-hrtf-calibration"), "--serial", "test-device", "abort"],
        cwd=repository,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )
    assert abort.returncode == 0, abort.stderr
    assert json.loads(abort.stdout)["error"] == ""

    delete = subprocess.run(
        [
            str(repository / "scripts/unity-hrtf-calibration"),
            "--serial",
            "test-device",
            "delete",
            "--session",
            "blind-session",
        ],
        cwd=repository,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )
    assert delete.returncode == 0, delete.stderr
    assert delete.stdout == "deleted=blind-session.responses.ndjson\n"
    assert not (device_files / "files_hrtf-calibration_blind-session.responses.ndjson").exists()
