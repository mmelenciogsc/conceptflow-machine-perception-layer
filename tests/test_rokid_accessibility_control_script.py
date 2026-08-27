# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess


COMPONENT = "org.conceptflow.mpl.rokidclient/org.conceptflow.mpl.rokid.RokidInputAccessibilityService"
SHORT_COMPONENT = "org.conceptflow.mpl.rokidclient/.RokidInputAccessibilityService"


def test_accessibility_control_preserves_other_services_and_is_reversible(tmp_path: Path) -> None:
    repository = Path(__file__).resolve().parents[1]
    fake_bin = tmp_path / "bin"
    adb_log = tmp_path / "adb.log"
    state_path = tmp_path / "state.json"
    fake_bin.mkdir()
    original_services = "com.example.reader/.Service:com.example.switch/.Service"
    state_path.write_text(
        json.dumps(
            {
                "enabled_accessibility_services": original_services,
                "accessibility_enabled": "1",
                "bind": True,
            },
        ),
        encoding="utf-8",
    )
    adb = fake_bin / "adb"
    adb.write_text(
        r"""#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

arguments = sys.argv[1:]
with Path(os.environ["MOCK_ADB_LOG"]).open("a", encoding="utf-8") as output:
    output.write(json.dumps(arguments) + "\n")
if arguments[:1] != ["-s"] or len(arguments) < 3:
    raise SystemExit(2)
arguments = arguments[2:]
state_path = Path(os.environ["MOCK_ADB_STATE"])
state = json.loads(state_path.read_text(encoding="utf-8"))

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
if arguments[:5] == ["shell", "pm", "path", "--user", "0"]:
    print("package:/mock/base.apk")
    raise SystemExit(0)
if arguments[:3] == ["shell", "dumpsys", "package"]:
    print("Service org.conceptflow.mpl.rokid.RokidInputAccessibilityService")
    print("  permission android.permission.BIND_ACCESSIBILITY_SERVICE")
    raise SystemExit(0)
if arguments[:4] == ["shell", "dumpsys", "activity", "services"]:
    services = state.get("enabled_accessibility_services", "")
    if state.get("bind") and state.get("accessibility_enabled") == "1":
        if "RokidInputAccessibilityService" in services:
            print(f"ServiceRecord{{mock u0 {arguments[4]}}}")
            print("requested=true received=true hasBound=true doRebind=false")
    raise SystemExit(0)
if arguments[:4] == ["shell", "run-as", "org.conceptflow.mpl.rokidclient", "cat"]:
    if "command_gate" not in state:
        raise SystemExit(1)
    value = "true" if state["command_gate"] else "false"
    print(f'<map><boolean name="enabled" value="{value}" /></map>')
    raise SystemExit(0)
if arguments[:3] == ["shell", "am", "start"]:
    action = arguments[arguments.index("-a") + 1]
    if action.endswith("ENABLE_VALIDATED_GESTURE_COMMANDS"):
        state["command_gate"] = True
    elif action.endswith("DISABLE_GESTURE_COMMANDS"):
        state["command_gate"] = False
    else:
        raise SystemExit(5)
    state_path.write_text(json.dumps(state), encoding="utf-8")
    print("Status: ok")
    raise SystemExit(0)
if arguments[:6] == ["shell", "settings", "--user", "0", "get", "secure"]:
    print(state.get(arguments[6], "null"))
    raise SystemExit(0)
if arguments[:6] == ["shell", "settings", "--user", "0", "put", "secure"]:
    state[arguments[6]] = arguments[7]
    state_path.write_text(json.dumps(state), encoding="utf-8")
    raise SystemExit(0)
if arguments[:6] == ["shell", "settings", "--user", "0", "delete", "secure"]:
    state.pop(arguments[6], None)
    state_path.write_text(json.dumps(state), encoding="utf-8")
    raise SystemExit(0)
raise SystemExit(4)
""",
        encoding="utf-8",
    )
    adb.chmod(0o755)
    environment = os.environ.copy()
    environment.update(
        PATH=f"{fake_bin}:{environment['PATH']}",
        MOCK_ADB_LOG=str(adb_log),
        MOCK_ADB_STATE=str(state_path),
    )

    def run(command: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                str(repository / "scripts/rokid-accessibility-control"),
                "--serial",
                "private-test-serial",
                command,
            ],
            cwd=repository,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
        )

    status = run("status")
    assert status.returncode == 0, status.stderr
    assert "gesture observer configured: no" in status.stdout
    assert "gesture observer bound: false" in status.stdout
    assert "enabled accessibility service count: 2" in status.stdout
    assert "gesture command gate: observe-only-unverified" in status.stdout

    enabled = run("enable")
    assert enabled.returncode == 0, enabled.stderr
    enabled_state = json.loads(state_path.read_text(encoding="utf-8"))
    assert enabled_state["enabled_accessibility_services"] == f"{original_services}:{COMPONENT}"
    assert enabled_state["accessibility_enabled"] == "1"
    assert enabled_state["command_gate"] is False
    assert "gesture command gate: observe-only" in enabled.stdout

    commands_enabled = run("commands-enable")
    assert commands_enabled.returncode == 0, commands_enabled.stderr
    assert "gesture command gate: commands-enabled" in commands_enabled.stdout
    assert json.loads(state_path.read_text(encoding="utf-8"))["command_gate"] is True
    commands_disabled = run("commands-disable")
    assert commands_disabled.returncode == 0, commands_disabled.stderr
    assert "gesture command gate: observe-only" in commands_disabled.stdout
    assert json.loads(state_path.read_text(encoding="utf-8"))["command_gate"] is False

    disabled = run("disable")
    assert disabled.returncode == 0, disabled.stderr
    disabled_state = json.loads(state_path.read_text(encoding="utf-8"))
    assert disabled_state["enabled_accessibility_services"] == original_services
    assert disabled_state["accessibility_enabled"] == "1"

    disabled_state["enabled_accessibility_services"] = f"{SHORT_COMPONENT}:{original_services}"
    disabled_state["accessibility_enabled"] = "1"
    state_path.write_text(json.dumps(disabled_state), encoding="utf-8")
    canonicalized = run("enable")
    assert canonicalized.returncode == 0, canonicalized.stderr
    canonical_state = json.loads(state_path.read_text(encoding="utf-8"))
    assert canonical_state["enabled_accessibility_services"] == f"{original_services}:{COMPONENT}"

    before_idempotent = len(adb_log.read_text(encoding="utf-8").splitlines())
    idempotent = run("enable")
    assert idempotent.returncode == 0, idempotent.stderr
    idempotent_calls = [
        json.loads(line) for line in adb_log.read_text(encoding="utf-8").splitlines()[before_idempotent:]
    ]
    assert not any("put" in call or "delete" in call for call in idempotent_calls)

    canonical_state["enabled_accessibility_services"] = original_services
    canonical_state["accessibility_enabled"] = "1"
    state_path.write_text(json.dumps(canonical_state), encoding="utf-8")
    before_noop = len(adb_log.read_text(encoding="utf-8").splitlines())
    noop = run("disable")
    assert noop.returncode == 0, noop.stderr
    noop_state = json.loads(state_path.read_text(encoding="utf-8"))
    assert noop_state["enabled_accessibility_services"] == original_services
    assert noop_state["accessibility_enabled"] == "1"
    noop_calls = [json.loads(line) for line in adb_log.read_text(encoding="utf-8").splitlines()[before_noop:]]
    assert not any("put" in call or "delete" in call for call in noop_calls)

    noop_state["enabled_accessibility_services"] = ""
    noop_state["accessibility_enabled"] = "0"
    state_path.write_text(json.dumps(noop_state), encoding="utf-8")
    assert run("enable").returncode == 0
    assert run("disable").returncode == 0
    empty_state = json.loads(state_path.read_text(encoding="utf-8"))
    assert "enabled_accessibility_services" not in empty_state
    assert empty_state["accessibility_enabled"] == "0"

    empty_state["enabled_accessibility_services"] = ""
    empty_state["accessibility_enabled"] = "0"
    empty_state["bind"] = False
    state_path.write_text(json.dumps(empty_state), encoding="utf-8")
    failed_enable = run("enable")
    assert failed_enable.returncode != 0
    assert "configured but not bound" in failed_enable.stderr
    rolled_back = json.loads(state_path.read_text(encoding="utf-8"))
    assert "enabled_accessibility_services" not in rolled_back
    assert rolled_back["accessibility_enabled"] == "0"

    combined_output = status.stdout + enabled.stdout + disabled.stdout
    assert "private-test-serial" not in combined_output
    calls = [json.loads(line) for line in adb_log.read_text(encoding="utf-8").splitlines()]
    assert all("root" not in call and "su" not in call for call in calls)
    activity_calls = [call for call in calls if "am" in call]
    assert len(activity_calls) >= 2
    assert all("RokidCommandActivity" in " ".join(call) for call in activity_calls)
    assert all("GESTURE_COMMANDS" in " ".join(call) for call in activity_calls)
