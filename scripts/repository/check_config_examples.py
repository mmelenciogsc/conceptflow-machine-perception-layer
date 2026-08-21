#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Validate documented environment examples against the runtime loader."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[2]
CONFIG_PATH = ROOT / "services/cuda-cluster/src/conceptflow_mpl_cluster/config.py"
SPEC = importlib.util.spec_from_file_location("conceptflow_mpl_config", CONFIG_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"could not load runtime configuration module: {CONFIG_PATH}")
CONFIG_MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CONFIG_MODULE
SPEC.loader.exec_module(CONFIG_MODULE)
ClusterConfig = CONFIG_MODULE.ClusterConfig
RuntimeProfile = CONFIG_MODULE.RuntimeProfile


ALLOWED_KEYS = {
    "MPL_PROFILE",
    "MPL_BIND_HOST",
    "MPL_BIND_PORT",
    "MPL_INSECURE",
    "MPL_TLS_CERTIFICATE_FILE",
    "MPL_TLS_PRIVATE_KEY_FILE",
    "MPL_MAX_RECEIVE_BYTES",
    "MPL_MAX_SEND_BYTES",
    "MPL_MAX_FRAME_BYTES",
    "MPL_QUEUE_CAPACITY",
    "MPL_RUNNER_COUNT",
    "MPL_WORKER_TIMEOUT_MS",
    "MPL_WORKER_FAILURE_THRESHOLD",
    "MPL_SHUTDOWN_TIMEOUT_MS",
    "MPL_DEVICE",
    "MPL_ALLOW_CPU_FALLBACK",
}
EXPECTED = {
    "development.env.example": RuntimeProfile.DEVELOPMENT,
    "test.env.example": RuntimeProfile.TEST,
    "production.env.example": RuntimeProfile.PRODUCTION,
}


def parse(path: Path) -> tuple[dict[str, str], str]:
    values: dict[str, str] = {}
    text = path.read_text(encoding="utf-8")
    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"{path}:{line_number}: expected KEY=VALUE")
        key, value = line.split("=", 1)
        if key not in ALLOWED_KEYS:
            raise ValueError(f"{path}:{line_number}: unsupported runtime key {key}")
        if key in values:
            raise ValueError(f"{path}:{line_number}: duplicate key {key}")
        values[key] = value
    return values, text


def main() -> int:
    for filename, profile in EXPECTED.items():
        path = ROOT / "config" / filename
        values, text = parse(path)
        config = ClusterConfig.from_env(values)
        if config.profile != profile:
            raise AssertionError(f"{path}: expected profile {profile.value}")
        if "retention: disabled" not in text.casefold():
            raise AssertionError(f"{path}: must document retention: disabled")
        if profile in {RuntimeProfile.DEVELOPMENT, RuntimeProfile.TEST}:
            if not config.insecure or config.bind_host not in {"127.0.0.1", "::1", "localhost"}:
                raise AssertionError(f"{path}: insecure development/test examples must use loopback")
        if profile == RuntimeProfile.PRODUCTION:
            if config.insecure or config.tls_certificate_file is None or config.tls_private_key_file is None:
                raise AssertionError(f"{path}: production must configure TLS")
    print("configuration examples match ClusterConfig and safe profile policy")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
