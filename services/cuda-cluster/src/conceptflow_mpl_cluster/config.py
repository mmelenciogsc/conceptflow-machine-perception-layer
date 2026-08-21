# SPDX-License-Identifier: MIT OR Apache-2.0
"""Typed environment configuration with secure production defaults."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
import ipaddress
import os
from pathlib import Path
from typing import Mapping


class ConfigError(ValueError):
    pass


class RuntimeProfile(StrEnum):
    DEVELOPMENT = "development"
    TEST = "test"
    PRODUCTION = "production"


def _boolean(value: str, *, name: str) -> bool:
    normalized = value.strip().casefold()
    if normalized in {"1", "true", "yes", "on"}:
        return True
    if normalized in {"0", "false", "no", "off"}:
        return False
    raise ConfigError(f"{name} must be a boolean")


def _integer(value: str, *, name: str, minimum: int, maximum: int) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise ConfigError(f"{name} must be an integer") from exc
    if not minimum <= parsed <= maximum:
        raise ConfigError(f"{name} must be between {minimum} and {maximum}")
    return parsed


def _loopback(host: str) -> bool:
    if host.casefold() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


@dataclass(frozen=True, slots=True)
class ClusterConfig:
    profile: RuntimeProfile = RuntimeProfile.DEVELOPMENT
    bind_host: str = "127.0.0.1"
    bind_port: int = 50051
    insecure: bool = True
    tls_certificate_file: Path | None = None
    tls_private_key_file: Path | None = None
    max_receive_bytes: int = 4 * 1024 * 1024
    max_send_bytes: int = 4 * 1024 * 1024
    max_frame_bytes: int = 2 * 1024 * 1024
    queue_capacity: int = 8
    runner_count: int = 1
    worker_timeout_ms: int = 500
    shutdown_timeout_ms: int = 2_000
    worker_failure_threshold: int = 2
    device_preference: str = "auto"
    allow_cpu_fallback: bool = True

    def __post_init__(self) -> None:
        if not self.bind_host:
            raise ConfigError("bind_host is required")
        if not 0 <= self.bind_port <= 65535:
            raise ConfigError("bind_port must be between 0 and 65535")
        numeric_limits = {
            "max_receive_bytes": self.max_receive_bytes,
            "max_send_bytes": self.max_send_bytes,
            "max_frame_bytes": self.max_frame_bytes,
            "queue_capacity": self.queue_capacity,
            "runner_count": self.runner_count,
            "worker_timeout_ms": self.worker_timeout_ms,
            "shutdown_timeout_ms": self.shutdown_timeout_ms,
            "worker_failure_threshold": self.worker_failure_threshold,
        }
        if any(value <= 0 for value in numeric_limits.values()):
            raise ConfigError("message, queue, runner, timeout, and failure limits must be positive")
        if self.profile == RuntimeProfile.PRODUCTION and self.insecure:
            raise ConfigError("production profile rejects insecure binding")
        if self.insecure and not _loopback(self.bind_host):
            raise ConfigError("insecure binding is restricted to loopback")
        if not self.insecure and (self.tls_certificate_file is None or self.tls_private_key_file is None):
            raise ConfigError("TLS certificate and private key files are required")
        if self.max_frame_bytes > self.max_receive_bytes:
            raise ConfigError("max_frame_bytes cannot exceed max_receive_bytes")
        if self.device_preference not in {"auto", "cuda", "cpu"}:
            raise ConfigError("device_preference must be auto, cuda, or cpu")

    @property
    def bind_target(self) -> str:
        try:
            address = ipaddress.ip_address(self.bind_host)
        except ValueError:
            host = self.bind_host
        else:
            host = f"[{self.bind_host}]" if address.version == 6 else self.bind_host
        return f"{host}:{self.bind_port}"

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> "ClusterConfig":
        env = os.environ if environ is None else environ
        try:
            profile = RuntimeProfile(env.get("MPL_PROFILE", "development").strip().casefold())
        except ValueError as exc:
            raise ConfigError("MPL_PROFILE must be development, test, or production") from exc
        certificate = env.get("MPL_TLS_CERTIFICATE_FILE", "").strip()
        private_key = env.get("MPL_TLS_PRIVATE_KEY_FILE", "").strip()
        return cls(
            profile=profile,
            bind_host=env.get("MPL_BIND_HOST", "127.0.0.1").strip(),
            bind_port=_integer(env.get("MPL_BIND_PORT", "50051"), name="MPL_BIND_PORT", minimum=0, maximum=65535),
            insecure=_boolean(env.get("MPL_INSECURE", "true"), name="MPL_INSECURE"),
            tls_certificate_file=Path(certificate) if certificate else None,
            tls_private_key_file=Path(private_key) if private_key else None,
            max_receive_bytes=_integer(
                env.get("MPL_MAX_RECEIVE_BYTES", str(4 * 1024 * 1024)),
                name="MPL_MAX_RECEIVE_BYTES",
                minimum=1024,
                maximum=128 * 1024 * 1024,
            ),
            max_send_bytes=_integer(
                env.get("MPL_MAX_SEND_BYTES", str(4 * 1024 * 1024)),
                name="MPL_MAX_SEND_BYTES",
                minimum=1024,
                maximum=128 * 1024 * 1024,
            ),
            max_frame_bytes=_integer(
                env.get("MPL_MAX_FRAME_BYTES", str(2 * 1024 * 1024)),
                name="MPL_MAX_FRAME_BYTES",
                minimum=1,
                maximum=128 * 1024 * 1024,
            ),
            queue_capacity=_integer(
                env.get("MPL_QUEUE_CAPACITY", "8"),
                name="MPL_QUEUE_CAPACITY",
                minimum=1,
                maximum=4096,
            ),
            runner_count=_integer(
                env.get("MPL_RUNNER_COUNT", "1"),
                name="MPL_RUNNER_COUNT",
                minimum=1,
                maximum=128,
            ),
            worker_timeout_ms=_integer(
                env.get("MPL_WORKER_TIMEOUT_MS", "500"),
                name="MPL_WORKER_TIMEOUT_MS",
                minimum=1,
                maximum=120_000,
            ),
            shutdown_timeout_ms=_integer(
                env.get("MPL_SHUTDOWN_TIMEOUT_MS", "2000"),
                name="MPL_SHUTDOWN_TIMEOUT_MS",
                minimum=1,
                maximum=120_000,
            ),
            worker_failure_threshold=_integer(
                env.get("MPL_WORKER_FAILURE_THRESHOLD", "2"),
                name="MPL_WORKER_FAILURE_THRESHOLD",
                minimum=1,
                maximum=100,
            ),
            device_preference=env.get("MPL_DEVICE", "auto").strip().casefold(),
            allow_cpu_fallback=_boolean(
                env.get("MPL_ALLOW_CPU_FALLBACK", "true"),
                name="MPL_ALLOW_CPU_FALLBACK",
            ),
        )
