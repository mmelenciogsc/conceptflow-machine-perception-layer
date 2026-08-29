#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Enforce repository layout, licensing, and safe-example policy."""

from __future__ import annotations

import re
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
SPDX = "SPDX-License-Identifier: MIT OR Apache-2.0"
REQUIRED = (
    ".editorconfig",
    ".gitattributes",
    ".gitignore",
    ".github/PULL_REQUEST_TEMPLATE.md",
    ".github/ISSUE_TEMPLATE/bug_report.yml",
    ".github/ISSUE_TEMPLATE/feature_request.yml",
    ".github/ISSUE_TEMPLATE/config.yml",
    ".github/dependabot.yml",
    ".github/workflows/ci.yml",
    ".github/workflows/hardware-validation.yml",
    "CODE_OF_CONDUCT.md",
    "CONTRIBUTING.md",
    "LICENSE",
    "LICENSE-APACHE",
    "LICENSE-MIT",
    "SECURITY.md",
    "THIRD_PARTY_NOTICES.md",
    "config/development.env.example",
    "config/test.env.example",
    "config/production.env.example",
    "scripts/bootstrap",
    "scripts/build",
    "scripts/test",
    "scripts/lint",
    "scripts/format",
    "scripts/demo",
    "scripts/benchmark",
    "scripts/fmod-lab",
    "scripts/perception-benchmark",
    "scripts/perception-calibration",
    "scripts/perception-demo",
    "scripts/perception-training",
    "scripts/rokid-install",
    "scripts/rokid-control",
    "scripts/lib/rokid-adb.sh",
    "scripts/repository/secret_scan.py",
)
SOURCE_SUFFIXES = {".py", ".sh", ".kt", ".kts", ".java", ".cs", ".cpp", ".hpp", ".proto"}
SOURCE_NAMES = {"CMakeLists.txt", "Makefile"}
SCRIPT_NAMES = {
    "android-model-install",
    "android-model-prepare",
    "android-qnn-build",
    "android-qnn-depth-benchmark",
    "android-qnn-depth-build",
    "benchmark",
    "bootstrap",
    "build",
    "demo",
    "format",
    "fmod-lab",
    "generate",
    "lint",
    "perception-benchmark",
    "perception-calibration",
    "perception-demo",
    "perception-training",
    "rokid-install",
    "rokid-control",
    "test",
    "unity-hrtf-calibration",
}
FORBIDDEN_SUFFIXES = {
    ".aab",
    ".apk",
    ".ckpt",
    ".cubin",
    ".env",
    ".fatbin",
    ".gguf",
    ".jks",
    ".key",
    ".keystore",
    ".onnx",
    ".p12",
    ".pem",
    ".pt",
    ".pth",
    ".safetensors",
    ".whl",
}
FORBIDDEN_PARTS = {
    ".gradle",
    ".idea",
    ".mypy_cache",
    ".pytest_cache",
    ".ruff_cache",
    ".venv",
    "TestResults",
    "__pycache__",
    "benchmark-results",
    "checkpoints",
    "datasets",
    "models",
    "weights",
}
PLACEHOLDER_CREDENTIAL = re.compile(
    r"(?i)\b(api[_-]?key|authorization|credential|password|private[_-]?key|secret|token)\b"
    r"\s*[:=]\s*[\"']?(changeme|change-me|placeholder|replace-me|replace_me|your[_-][a-z0-9_-]+)\b"
)
ABSOLUTE_USER_PATH = re.compile(r"(?:/home/[^/\s]+/|/Users/[^/\s]+/|[A-Za-z]:\\Users\\[^\\\s]+\\)")
ANDROID_NAMESPACE = "{http://schemas.android.com/apk/res/android}"
ROKID_BUILD_FILE = Path("apps/rokid-client/build.gradle.kts")
ROKID_MANIFEST = Path("apps/rokid-client/src/main/AndroidManifest.xml")
ROKID_INSTALL_SCRIPT = Path("scripts/rokid-install")
ROKID_COMMAND_ACTIVITY = Path("apps/rokid-client/src/main/java/org/conceptflow/mpl/rokid/RokidCommandActivity.kt")
ROKID_RENDEZVOUS_WAKE_LEASE = Path(
    "apps/rokid-client/src/main/java/org/conceptflow/mpl/rokid/hardware/BoundedRendezvousWakeLease.kt"
)
ANDROID_HOST_MAIN_ACTIVITY = Path("apps/android-host/src/main/java/org/conceptflow/mpl/host/MainActivity.kt")
ANDROID_HOST_DEBUG_MANIFEST = Path("apps/android-host/src/debug/AndroidManifest.xml")
HARDWARE_WORKFLOW = Path(".github/workflows/hardware-validation.yml")


def repository_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    return sorted(Path(item.decode()) for item in result.stdout.split(b"\0") if item)


def is_fixture(path: Path) -> bool:
    return "fixtures" in path.parts and ("tests" in path.parts or "test" in path.parts)


def needs_spdx(path: Path) -> bool:
    if path.as_posix() in {"gradlew", "gradlew.bat"}:
        return False
    if path.suffix in SOURCE_SUFFIXES or path.name in SOURCE_NAMES:
        return True
    return len(path.parts) == 2 and path.parts[0] == "scripts" and path.name in SCRIPT_NAMES


def read_text(path: Path) -> str | None:
    try:
        return (ROOT / path).read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return None


def check_rokid_workflow_component() -> list[str]:
    failures: list[str] = []
    build_text = read_text(ROKID_BUILD_FILE) or ""
    workflow_text = read_text(HARDWARE_WORKFLOW) or ""
    install_text = read_text(ROKID_INSTALL_SCRIPT) or ""
    namespace_match = re.search(r'(?m)^\s*namespace\s*=\s*"([^"]+)"\s*$', build_text)
    application_id_match = re.search(r'(?m)^\s*applicationId\s*=\s*"([^"]+)"\s*$', build_text)
    install_package_match = re.search(r'(?m)^readonly PACKAGE_NAME="([^"]+)"$', install_text)
    control_text = read_text(Path("scripts/rokid-control")) or ""
    command_activity_text = read_text(ROKID_COMMAND_ACTIVITY) or ""
    runtime_service_text = (
        read_text(Path("apps/rokid-client/src/main/java/org/conceptflow/mpl/rokid/RokidRuntimeService.kt")) or ""
    )
    wake_lease_text = read_text(ROKID_RENDEZVOUS_WAKE_LEASE) or ""
    control_package_match = re.search(r'(?m)^readonly PACKAGE_NAME="([^"]+)"$', control_text)
    control_activity_match = re.search(r'(?m)^readonly COMMAND_ACTIVITY_NAME="([^"]+)"$', control_text)

    try:
        manifest = ET.parse(ROOT / ROKID_MANIFEST).getroot()
    except (ET.ParseError, OSError) as error:
        return [f"cannot parse Rokid manifest: {error}"]
    launcher_activities: list[str] = []
    for activity in manifest.findall("./application/activity"):
        for intent_filter in activity.findall("intent-filter"):
            actions = {item.get(f"{ANDROID_NAMESPACE}name") for item in intent_filter.findall("action")}
            categories = {item.get(f"{ANDROID_NAMESPACE}name") for item in intent_filter.findall("category")}
            if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in categories:
                name = activity.get(f"{ANDROID_NAMESPACE}name")
                if name:
                    launcher_activities.append(name)

    services = manifest.findall("./application/service")
    runtime_services = [
        service
        for service in services
        if (service.get(f"{ANDROID_NAMESPACE}name") or "").endswith("RokidRuntimeService")
    ]
    command_activities = [
        activity
        for activity in manifest.findall("./application/activity")
        if (activity.get(f"{ANDROID_NAMESPACE}name") or "").endswith("RokidCommandActivity")
    ]

    if namespace_match is None:
        failures.append(f"cannot determine Android namespace from {ROKID_BUILD_FILE}")
    if application_id_match is None:
        failures.append(f"cannot determine applicationId from {ROKID_BUILD_FILE}")
    if launcher_activities:
        failures.append(f"non-display Rokid client must not declare a MAIN/LAUNCHER activity: {ROKID_MANIFEST}")
    if len(runtime_services) != 1:
        failures.append(f"expected exactly one RokidRuntimeService in {ROKID_MANIFEST}")
    if len(command_activities) != 1:
        failures.append(f"expected exactly one RokidCommandActivity in {ROKID_MANIFEST}")
    if "./scripts/rokid-install --no-build" not in workflow_text:
        failures.append(f"Rokid workflow does not invoke the direct-sideload helper: {HARDWARE_WORKFLOW}")
    if install_package_match is None:
        failures.append(f"cannot determine install package from {ROKID_INSTALL_SCRIPT}")
    for gesture_install_token in (
        "--enable-gesture-controls",
        '"$SCRIPT_DIRECTORY/rokid-accessibility-control" --serial "$ROKID_SERIAL" enable',
        '"$SCRIPT_DIRECTORY/rokid-accessibility-control" --serial "$ROKID_SERIAL" commands-enable',
    ):
        if gesture_install_token not in install_text:
            failures.append(f"Rokid installer lacks explicit gesture provisioning token: {gesture_install_token}")
    if control_package_match is None or control_activity_match is None:
        failures.append("cannot determine the nonvisual runtime component from scripts/rokid-control")
    if (
        namespace_match is None
        or application_id_match is None
        or install_package_match is None
        or control_package_match is None
        or control_activity_match is None
        or len(runtime_services) != 1
        or len(command_activities) != 1
    ):
        return failures

    namespace = namespace_match.group(1)
    application_id = application_id_match.group(1)
    runtime_service = runtime_services[0]
    command_activity = command_activities[0]
    activity_name = command_activity.get(f"{ANDROID_NAMESPACE}name") or ""
    if activity_name.startswith("."):
        activity_name = f"{namespace}{activity_name}"
    elif "." not in activity_name:
        activity_name = f"{namespace}.{activity_name}"
    expected_component = f"{application_id}/{activity_name}"
    actual_component = f"{control_package_match.group(1)}/{control_activity_match.group(1)}"
    if actual_component != expected_component:
        failures.append(f"Rokid workflow component mismatch: expected {expected_component}, found {actual_component}")
    if install_package_match.group(1) != application_id:
        failures.append(
            f"Rokid installer package mismatch: expected {application_id}, found {install_package_match.group(1)}"
        )
    if runtime_service.get(f"{ANDROID_NAMESPACE}exported") != "false":
        failures.append("RokidRuntimeService must remain private in the main/release manifest")
    if runtime_service.findall("intent-filter"):
        failures.append("RokidRuntimeService main/release manifest must not declare intent filters")
    if runtime_service.get(f"{ANDROID_NAMESPACE}permission") is not None:
        failures.append("RokidRuntimeService main/release manifest must not declare a component permission")
    permissions = {permission.get(f"{ANDROID_NAMESPACE}name") for permission in manifest.findall("uses-permission")}
    if "android.permission.WAKE_LOCK" not in permissions:
        failures.append("Rokid bounded pre-authentication wake lease requires WAKE_LOCK")
    if "android.permission.SCHEDULE_EXACT_ALARM" in permissions:
        failures.append("Rokid in-process cooldown scheduling must not retain exact-alarm special access")
    if command_activity.get(f"{ANDROID_NAMESPACE}exported") != "true":
        failures.append("RokidCommandActivity must be exported for explicit authorized ADB control")
    if command_activity.get(f"{ANDROID_NAMESPACE}permission") != "android.permission.DUMP":
        failures.append("RokidCommandActivity must require the shell-held android.permission.DUMP permission")
    if command_activity.get(f"{ANDROID_NAMESPACE}theme") != "@style/Theme.ConceptFlow.Nonvisual":
        failures.append("RokidCommandActivity must retain the nonvisual compatibility theme")
    for forbidden_key_hook in ("dispatchKeyEvent", "onKeyDown", "onKeyUp", "onKeyLongPress", "onKeyMultiple"):
        if forbidden_key_hook in command_activity_text:
            failures.append(
                f"RokidCommandActivity must not intercept native Rokid controls: found {forbidden_key_hook}"
            )
    for legacy_path in (
        "apps/rokid-client/src/main/java/org/conceptflow/mpl/rokid/WornTouchAndroidKeyMap.kt",
        "apps/rokid-client/src/main/java/org/conceptflow/mpl/rokid/core/WornTouchLongPressRecognizer.kt",
    ):
        if (ROOT / legacy_path).exists():
            failures.append(f"native Rokid control interception must remain removed: {legacy_path}")
    polling_method = re.search(
        r"private fun updateLiveLinkPolling\(.*?\n    }\n\n    private fun initializeLiveIdentity",
        runtime_service_text,
        re.DOTALL,
    )
    if polling_method is None:
        failures.append("cannot inspect Rokid live-link polling implementation")
    elif "rendezvousDeadline" in polling_method.group(0) or "preAuthenticationDeadline" in polling_method.group(0):
        failures.append("generic live-link polling must not cancel the pre-authentication deadline")
    idempotent_arm = re.search(
        r"IdleControlArmDecision\.ALREADY_ARMED -> \{"
        r"[^}]*return START_STICKY\s*}",
        runtime_service_text,
        re.DOTALL,
    )
    collision_rejection = re.search(
        r"IdleControlArmDecision\.REJECT_LIVE_COLLISION -> \{"
        r"[^}]*stopSelfResult\(startId\)[^}]*return START_NOT_STICKY\s*}",
        runtime_service_text,
        re.DOTALL,
    )
    if idempotent_arm is None:
        failures.append("an already-armed Rokid standby must remain idempotently START_STICKY")
    if collision_rejection is None:
        failures.append("Rokid idle-arm collision must satisfy the foreground-service start request")
    armed_method = re.search(
        r"fun isIdleControlArmed\(\): Boolean =.*?\n\s*fun completeIdleControlEnable",
        runtime_service_text,
        re.DOTALL,
    )
    if armed_method is None or "IdleControlPolicy.isArmed" not in armed_method.group(0):
        failures.append("Rokid binder armed state must use the tested idle-control policy")
    elif "liveLinkRun" in armed_method.group(0) or "rendezvousRetry" in armed_method.group(0):
        failures.append("Rokid armed state must survive sensor-off rendezvous cooldown")
    for required_wakeup_token in (
        "mainHandler.postDelayed(retry, delayMillis)",
        "retry_scheduler=in_process_foreground",
        "MAXIMUM_WAKE_LEASE_MILLIS",
    ):
        if required_wakeup_token not in runtime_service_text:
            failures.append(f"Rokid cable-free rendezvous lacks {required_wakeup_token}")
    if "PendingIntent.getForegroundService" in runtime_service_text:
        legacy_cancel = re.search(
            r"private fun cancelLegacyRendezvousAlarm\(\).*?\n    }",
            runtime_service_text,
            re.DOTALL,
        )
        if legacy_cancel is None or "FLAG_NO_CREATE" not in legacy_cancel.group(0):
            failures.append("Rokid cooldown must not re-enter through a background foreground-service request")
        elif "PendingIntent.getForegroundService" in (
            runtime_service_text[: legacy_cancel.start()] + runtime_service_text[legacy_cancel.end() :]
        ):
            failures.append("Rokid background foreground-service PendingIntent is allowed only for legacy cancellation")
    if (
        "PowerManager.PARTIAL_WAKE_LOCK" not in wake_lease_text
        or "wakeLock.acquire(timeoutMillis)" not in wake_lease_text
    ):
        failures.append("Rokid pre-authentication wake lease must be partial and timeout-bounded")
    return failures


def check_android_host_command_surface() -> list[str]:
    failures: list[str] = []
    main_activity = read_text(ANDROID_HOST_MAIN_ACTIVITY) or ""
    for forbidden_action in ("START_LIVE_TEST", "STOP_LIVE_TEST", "handleDebugLiveAction"):
        if forbidden_action in main_activity:
            failures.append(
                f"Android host MainActivity must not accept external debug control: found {forbidden_action}"
            )
    try:
        manifest = ET.parse(ROOT / ANDROID_HOST_DEBUG_MANIFEST).getroot()
    except (ET.ParseError, OSError) as error:
        return failures + [f"cannot parse Android host debug manifest: {error}"]
    receivers = [
        receiver
        for receiver in manifest.findall("./application/receiver")
        if (receiver.get(f"{ANDROID_NAMESPACE}name") or "").endswith("LiveLinkDebugCommandReceiver")
    ]
    if len(receivers) != 1:
        failures.append("expected one debug-only LiveLinkDebugCommandReceiver")
    elif receivers[0].get(f"{ANDROID_NAMESPACE}permission") != "android.permission.DUMP":
        failures.append("LiveLinkDebugCommandReceiver must require android.permission.DUMP")
    return failures


def main() -> int:
    failures: list[str] = []
    files = repository_files()
    file_set = {path.as_posix() for path in files}

    for required in REQUIRED:
        if required not in file_set:
            failures.append(f"missing required repository file: {required}")

    for path in files:
        path_string = path.as_posix()
        if any(part in FORBIDDEN_PARTS for part in path.parts):
            failures.append(f"forbidden generated or local path: {path_string}")
        if path.suffix.casefold() in FORBIDDEN_SUFFIXES and not path_string.endswith(".env.example"):
            failures.append(f"forbidden artifact or sensitive file type: {path_string}")
        if path.name == "gradle-wrapper.jar" and path_string != "gradle/wrapper/gradle-wrapper.jar":
            failures.append(f"unexpected wrapper jar location: {path_string}")
        if is_fixture(path) and (ROOT / path).stat().st_size > 1_048_576:
            failures.append(f"test fixture exceeds 1 MiB: {path_string}")

        text = read_text(path)
        if text is None:
            continue
        if needs_spdx(path) and SPDX not in "\n".join(text.splitlines()[:6]):
            failures.append(f"source file lacks SPDX header: {path_string}")
        if (
            path_string
            not in {
                "scripts/repository/check_policy.py",
                "scripts/repository/secret_scan.py",
            }
            and "tests" not in path.parts
            and "src/test" not in path_string
        ):
            match = PLACEHOLDER_CREDENTIAL.search(text)
            if match:
                failures.append(f"placeholder credential in {path_string}: {match.group(1)}")
            if ABSOLUTE_USER_PATH.search(text):
                failures.append(f"absolute user path in {path_string}")
        if "production" in path.name.casefold() and "example.com" in text.casefold():
            failures.append(f"example.com is not a valid production endpoint: {path_string}")

    for workflow in (".github/workflows/ci.yml", ".github/workflows/hardware-validation.yml"):
        text = read_text(Path(workflow)) or ""
        if not re.search(r"(?m)^permissions:\s*\n\s+contents:\s*read\s*$", text):
            failures.append(f"workflow lacks least-privilege contents: read permission: {workflow}")
        if "continue-on-error" in text:
            failures.append(f"workflow weakens validation with continue-on-error: {workflow}")

    failures.extend(check_rokid_workflow_component())
    failures.extend(check_android_host_command_surface())

    if failures:
        for failure in failures:
            print(f"policy error: {failure}", file=sys.stderr)
        return 1
    print(f"repository policy passed for {len(files)} tracked-like files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
