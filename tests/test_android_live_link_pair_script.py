# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import os
from pathlib import Path
import subprocess


def test_pairing_uses_direct_atomic_stream_without_remote_shell(tmp_path: Path) -> None:
    repository = Path(__file__).resolve().parents[1]
    fake_bin = tmp_path / "bin"
    state = tmp_path / "state"
    fake_bin.mkdir()
    state.mkdir()
    adb_log = tmp_path / "adb.log"
    adb = fake_bin / "adb"
    adb.write_text(
        r"""#!/usr/bin/env bash
set -euo pipefail
printf '%q ' "$@" >>"$MOCK_ADB_LOG"
printf '\n' >>"$MOCK_ADB_LOG"
[[ "$1" == "-s" ]]
serial="$2"
shift 2
if [[ "$1" == "get-state" ]]; then printf 'device\n'; exit 0; fi
if [[ "$1" == "exec-in" ]]; then
    [[ "$2" == "run-as" && "$4" == "tee" ]]
    path="${5//\//_}"
    cat >"$MOCK_ADB_STATE/${serial}-${3}-${path}"
    exit 0
fi
if [[ "$1" == "exec-out" ]]; then
    [[ "$2" == "run-as" && "$4" == "cat" ]]
    path="${5//\//_}"
    stored="$MOCK_ADB_STATE/${serial}-${3}-${path}"
    if [[ -f "$stored" ]]; then cat "$stored"; else printf 'public-cert-%s' "$serial"; fi
    exit 0
fi
if [[ "$1" == "shell" && "$2" == "pm" && "$3" == "path" ]]; then
    printf 'package:/mock/base.apk\n'
    exit 0
fi
if [[ "$1" == "shell" && "$2" == "pm" && "$3" == "list" && "$4" == "features" ]]; then
    printf 'feature:android.hardware.wifi.direct\n'
    exit 0
fi
if [[ "$1" == "shell" && "$2" == "pm" && "$3" == "grant" ]]; then exit 0; fi
if [[ "$1" == "shell" && "$2" == "getprop" && "$3" == "ro.build.version.sdk" ]]; then
    printf '32\n'
    exit 0
fi
if [[ "$1" == "shell" && "$2" == "settings" && "$3" == "get" ]]; then
    printf '3\n'
    exit 0
fi
if [[ "$1" == "shell" && "$2" == "content" && "$3" == "query" ]]; then
    printf 'Row: 0 status=identity_ready\n'
    exit 0
fi
if [[ "$1" == "shell" && "$2" == "am" ]]; then exit 0; fi
if [[ "$1" == "shell" && "$2" == "run-as" ]]; then
    package_name="$3"
    operation="$4"
    if [[ "$operation" == "mv" ]]; then
        source_path="${5//\//_}"
        target_path="${6//\//_}"
        mv "$MOCK_ADB_STATE/${serial}-${package_name}-${source_path}" \
            "$MOCK_ADB_STATE/${serial}-${package_name}-${target_path}"
    elif [[ "$operation" == "rm" ]]; then
        path="${6//\//_}"
        rm -f "$MOCK_ADB_STATE/${serial}-${package_name}-${path}"
    fi
    exit 0
fi
exit 1
""",
        encoding="utf-8",
    )
    adb.chmod(0o755)
    openssl = fake_bin / "openssl"
    openssl.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
    openssl.chmod(0o755)

    environment = os.environ.copy()
    environment.update(
        PATH=f"{fake_bin}:{environment['PATH']}",
        MOCK_ADB_LOG=str(adb_log),
        MOCK_ADB_STATE=str(state),
    )
    result = subprocess.run(
        [
            str(repository / "scripts/android-live-link-pair"),
            "--rokid-serial",
            "rokid-test",
            "--poco-serial",
            "poco-test",
            "--poco-address",
            "192.168.100.89",
        ],
        cwd=repository,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0, result.stderr
    calls = adb_log.read_text(encoding="utf-8")
    assert " sh " not in calls
    assert calls.count(" tee ") == 2
    assert calls.count(" mv ") == 2
    assert " content query " in calls
    assert "192.168.100.89" not in result.stdout
    assert "public-cert" not in result.stdout
    installed = list(state.glob("*-no_backup_live-link_live-link.properties"))
    assert len(installed) == 2
    assert all("schema_version=1" in path.read_text(encoding="utf-8") for path in installed)
    assert all("network_topology=private_lan" in path.read_text(encoding="utf-8") for path in installed)

    wifi_direct_result = subprocess.run(
        [
            str(repository / "scripts/android-live-link-pair"),
            "--rokid-serial",
            "rokid-test",
            "--poco-serial",
            "poco-test",
            "--network-topology",
            "wifi-direct-required",
        ],
        cwd=repository,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )

    assert wifi_direct_result.returncode == 0, wifi_direct_result.stderr
    calls = adb_log.read_text(encoding="utf-8")
    assert calls.count(" android.permission.ACCESS_COARSE_LOCATION ") == 2
    assert calls.count(" android.permission.ACCESS_FINE_LOCATION ") == 2
    assert all(
        "network_topology=wifi_direct_required" in path.read_text(encoding="utf-8")
        for path in installed
    )


def test_wifi_direct_pairing_does_not_require_a_static_address(tmp_path: Path) -> None:
    repository = Path(__file__).resolve().parents[1]
    script = (repository / "scripts/android-live-link-pair").read_text(encoding="utf-8")

    assert '--network-topology private-lan|wifi-direct-required' in script
    assert 'network_topology="wifi_direct_required"' in script
    assert 'poco_address="192.168.49.1"' in script
    assert 'android.permission.ACCESS_COARSE_LOCATION' in script
    assert 'android.permission.ACCESS_FINE_LOCATION' in script
    assert 'android.permission.NEARBY_WIFI_DEVICES' in script
