#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

ROKID_SERIAL=""

select_rokid_target() {
    local requested_serial="${1:-}"
    local -a authorized_devices=()

    require_command adb "Android platform-tools (adb) are required"
    if [[ -n "$requested_serial" ]]; then
        ROKID_SERIAL="$requested_serial"
    else
        mapfile -t authorized_devices < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
        if ((${#authorized_devices[@]} != 1)); then
            fail "found ${#authorized_devices[@]} authorized devices; pass --serial for the Rokid glasses"
        fi
        ROKID_SERIAL="${authorized_devices[0]}"
    fi

    local state
    state="$(adb -s "$ROKID_SERIAL" get-state 2>/dev/null || true)"
    [[ "$state" == "device" ]] || fail "the selected ADB target is not in the authorized device state"
}

verify_rokid_target() {
    [[ -n "$ROKID_SERIAL" ]] || fail "select_rokid_target must run before verify_rokid_target"

    local model product device manufacturer sdk identity
    model="$(adb -s "$ROKID_SERIAL" shell getprop ro.product.model | tr -d '\r')"
    product="$(adb -s "$ROKID_SERIAL" shell getprop ro.product.name | tr -d '\r')"
    device="$(adb -s "$ROKID_SERIAL" shell getprop ro.product.device | tr -d '\r')"
    manufacturer="$(adb -s "$ROKID_SERIAL" shell getprop ro.product.manufacturer | tr -d '\r')"
    sdk="$(adb -s "$ROKID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
    identity="${manufacturer} ${model} ${product} ${device}"

    if [[ ! "${identity,,}" =~ rokid|rg_glasses|(^|[[:space:]])glasses($|[[:space:]]) ]]; then
        fail "the selected target does not identify as Rokid glasses (manufacturer='$manufacturer', model='$model', product='$product', device='$device')"
    fi

    printf 'Rokid target: manufacturer=%s model=%s product=%s device=%s api=%s\n' \
        "$manufacturer" "$model" "$product" "$device" "$sdk"
}
