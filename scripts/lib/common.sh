#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

SCRIPT_DIRECTORY="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
REPOSITORY_ROOT="$(CDPATH= cd -- "$SCRIPT_DIRECTORY/.." && pwd)"
readonly SCRIPT_DIRECTORY REPOSITORY_ROOT

fail() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "$2"
}

venv_python() {
    if [[ -x "$REPOSITORY_ROOT/.venv/bin/python" ]]; then
        printf '%s\n' "$REPOSITORY_ROOT/.venv/bin/python"
        return
    fi
    if [[ -x "$REPOSITORY_ROOT/.venv/Scripts/python.exe" ]]; then
        printf '%s\n' "$REPOSITORY_ROOT/.venv/Scripts/python.exe"
        return
    fi
    fail "repository virtual environment is missing; run ./scripts/bootstrap first"
}

find_python312() {
    local candidate
    for candidate in python3.12 python3 python; do
        if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys; raise SystemExit(sys.version_info[:2] != (3, 12))'; then
            command -v "$candidate"
            return
        fi
    done
    fail "Python 3.12 is required; install it outside this script and retry"
}

run_gradle() {
    require_command java "Java 17 and an Android SDK are required for Android tasks"
    if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" && ! -f "$REPOSITORY_ROOT/local.properties" ]]; then
        local sdk_candidate
        for sdk_candidate in \
            "${HOME:-}/Android/Sdk" \
            "${HOME:-}/Library/Android/sdk" \
            "${LOCALAPPDATA:-}/Android/Sdk"; do
            if [[ -d "$sdk_candidate" ]]; then
                export ANDROID_HOME="$sdk_candidate"
                break
            fi
        done
    fi
    if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" && ! -f "$REPOSITORY_ROOT/local.properties" ]]; then
        fail "set ANDROID_HOME/ANDROID_SDK_ROOT or provide Gradle local.properties for an installed Android SDK"
    fi
    "$REPOSITORY_ROOT/gradlew" --no-daemon --dependency-verification strict "$@"
}

native_build_directory() {
    printf '%s\n' "$REPOSITORY_ROOT/services/cuda-cluster/native/build-infra"
}
