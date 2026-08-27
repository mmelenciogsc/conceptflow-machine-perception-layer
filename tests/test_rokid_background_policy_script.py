# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess


PACKAGE_NAME = "org.conceptflow.mpl.rokidclient"


def test_background_policy_is_scoped_reversible_and_does_not_launch(tmp_path: Path) -> None:
    repository = Path(__file__).resolve().parents[1]
    fake_bin = tmp_path / "bin"
    adb_log = tmp_path / "adb.log"
    state_path = tmp_path / "state.json"
    fake_bin.mkdir()
    state_path.write_text(
        json.dumps(
            {
                "ops": {
                    "RUN_IN_BACKGROUND": "ignore",
                    "RUN_ANY_IN_BACKGROUND": "ignore",
                },
                "whitelist": ["com.example.existing"],
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
if arguments[:3] == ["shell", "pm", "path"]:
    print("package:/mock/base.apk")
    raise SystemExit(0)
if arguments[:4] == ["shell", "cmd", "appops", "get"]:
    operation = arguments[5]
    mode = state["ops"].get(operation, "default")
    if operation == "RUN_IN_BACKGROUND" and mode == "allow":
        print("No operations.")
        print("Default mode: allow")
    else:
        print(f"{operation}: {mode}")
    raise SystemExit(0)
if arguments[:4] == ["shell", "cmd", "appops", "set"]:
    operation = arguments[5]
    state["ops"][operation] = arguments[6]
    state_path.write_text(json.dumps(state), encoding="utf-8")
    raise SystemExit(0)
if arguments[:3] == ["shell", "dumpsys", "deviceidle"] and arguments[3:4] == ["whitelist"]:
    if len(arguments) == 4:
        for package_name in state["whitelist"]:
            print(f"user,{package_name},10069")
    elif len(arguments) == 5 and arguments[4].startswith("+"):
        package_name = arguments[4][1:]
        if package_name not in state["whitelist"]:
            state["whitelist"].append(package_name)
        state_path.write_text(json.dumps(state), encoding="utf-8")
    elif len(arguments) == 5 and arguments[4].startswith("-"):
        package_name = arguments[4][1:]
        state["whitelist"] = [item for item in state["whitelist"] if item != package_name]
        state_path.write_text(json.dumps(state), encoding="utf-8")
    else:
        raise SystemExit(3)
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
                str(repository / "scripts/rokid-background-policy"),
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
    assert "RUN_IN_BACKGROUND: ignore" in status.stdout
    assert "RUN_ANY_IN_BACKGROUND: ignore" in status.stdout
    assert "device-idle whitelist: absent" in status.stdout
    assert "background policy: partial or custom" in status.stdout

    enabled = run("enable")
    assert enabled.returncode == 0, enabled.stderr
    assert "RUN_IN_BACKGROUND: allow" in enabled.stdout
    assert "RUN_ANY_IN_BACKGROUND: default" in enabled.stdout
    assert "device-idle whitelist: present" in enabled.stdout
    assert "background policy: enabled" in enabled.stdout
    enabled_state = json.loads(state_path.read_text(encoding="utf-8"))
    assert enabled_state["ops"] == {
        "RUN_IN_BACKGROUND": "allow",
        "RUN_ANY_IN_BACKGROUND": "default",
    }
    assert enabled_state["whitelist"] == ["com.example.existing", PACKAGE_NAME]

    disabled = run("disable")
    assert disabled.returncode == 0, disabled.stderr
    assert "RUN_IN_BACKGROUND: default" in disabled.stdout
    assert "RUN_ANY_IN_BACKGROUND: default" in disabled.stdout
    assert "device-idle whitelist: absent" in disabled.stdout
    assert "background policy: disabled" in disabled.stdout
    disabled_state = json.loads(state_path.read_text(encoding="utf-8"))
    assert disabled_state["ops"] == {
        "RUN_IN_BACKGROUND": "default",
        "RUN_ANY_IN_BACKGROUND": "default",
    }
    assert disabled_state["whitelist"] == ["com.example.existing"]

    combined_output = (
        status.stdout + status.stderr + enabled.stdout + enabled.stderr + disabled.stdout + disabled.stderr
    )
    assert "private-test-serial" not in combined_output
    calls = [json.loads(line) for line in adb_log.read_text(encoding="utf-8").splitlines()]
    mutating_calls = [
        call[2:] for call in calls if "set" in call or any(argument.startswith(("+", "-")) for argument in call[2:])
    ]
    assert mutating_calls == [
        ["shell", "cmd", "appops", "set", PACKAGE_NAME, "RUN_IN_BACKGROUND", "allow"],
        ["shell", "cmd", "appops", "set", PACKAGE_NAME, "RUN_ANY_IN_BACKGROUND", "default"],
        ["shell", "dumpsys", "deviceidle", "whitelist", f"+{PACKAGE_NAME}"],
        ["shell", "cmd", "appops", "set", PACKAGE_NAME, "RUN_IN_BACKGROUND", "default"],
        ["shell", "cmd", "appops", "set", PACKAGE_NAME, "RUN_ANY_IN_BACKGROUND", "default"],
        ["shell", "dumpsys", "deviceidle", "whitelist", f"-{PACKAGE_NAME}"],
    ]
    assert all("START_FOREGROUND" not in call for call in calls)
    assert all("am" not in call and "root" not in call and "su" not in call for call in calls)
