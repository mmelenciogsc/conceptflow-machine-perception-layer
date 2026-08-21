# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import json
import logging
from pathlib import Path
import subprocess

import pytest

from conceptflow_mpl_cluster.config import ClusterConfig, ConfigError, RuntimeProfile
from conceptflow_mpl_cluster.device import discover_devices
from conceptflow_mpl_cluster.logging import REDACTED, RedactedJsonFormatter, opaque_label, redact


def test_development_allows_loopback_insecure() -> None:
    config = ClusterConfig.from_env({"MPL_PROFILE": "development", "MPL_BIND_PORT": "0"})
    assert config.insecure
    assert config.bind_host == "127.0.0.1"


def test_bind_target_brackets_ipv6_literals() -> None:
    loopback = ClusterConfig(bind_host="::1", bind_port=50051)
    wildcard = ClusterConfig(
        bind_host="::",
        bind_port=50052,
        insecure=False,
        tls_certificate_file=Path("certificate.pem"),
        tls_private_key_file=Path("private-key.pem"),
    )

    assert loopback.bind_target == "[::1]:50051"
    assert wildcard.bind_target == "[::]:50052"


def test_production_rejects_insecure_bind() -> None:
    with pytest.raises(ConfigError, match="production"):
        ClusterConfig(profile=RuntimeProfile.PRODUCTION, insecure=True)


def test_non_loopback_rejects_insecure_bind() -> None:
    with pytest.raises(ConfigError, match="loopback"):
        ClusterConfig(bind_host="0.0.0.0", insecure=True)


def test_secure_configuration_requires_both_tls_files() -> None:
    with pytest.raises(ConfigError, match="certificate"):
        ClusterConfig(insecure=False)


def test_invalid_environment_value_is_rejected() -> None:
    with pytest.raises(ConfigError, match="MPL_QUEUE_CAPACITY"):
        ClusterConfig.from_env({"MPL_QUEUE_CAPACITY": "zero"})


def test_shutdown_timeout_is_configurable_and_positive() -> None:
    config = ClusterConfig.from_env({"MPL_SHUTDOWN_TIMEOUT_MS": "37"})
    assert config.shutdown_timeout_ms == 37
    with pytest.raises(ConfigError, match="MPL_SHUTDOWN_TIMEOUT_MS"):
        ClusterConfig.from_env({"MPL_SHUTDOWN_TIMEOUT_MS": "0"})


def test_cpu_preference_does_not_probe_cuda() -> None:
    devices = discover_devices("cpu", runner=lambda *args, **kwargs: pytest.fail("runner called"))
    assert devices[0].kind == "cpu"


def test_cuda_discovery_parses_and_filters(monkeypatch) -> None:
    monkeypatch.setattr("conceptflow_mpl_cluster.device.shutil.which", lambda name: "/usr/bin/nvidia-smi")

    def runner(*args, **kwargs):
        return subprocess.CompletedProcess(
            args[0],
            0,
            stdout="0, GPU-AAA, First GPU\n1, GPU-BBB, Second GPU\n",
            stderr="",
        )

    devices = discover_devices("cuda", environ={"CUDA_VISIBLE_DEVICES": "1"}, runner=runner)
    assert [(device.identifier, device.name) for device in devices] == [("GPU-BBB", "Second GPU")]


def test_cuda_failure_falls_back_or_raises(monkeypatch) -> None:
    monkeypatch.setattr("conceptflow_mpl_cluster.device.shutil.which", lambda name: None)
    assert discover_devices("auto", allow_cpu_fallback=True)[0].kind == "cpu"
    with pytest.raises(RuntimeError, match="no usable CUDA"):
        discover_devices("cuda", allow_cpu_fallback=False)


def test_recursive_redaction_covers_credentials_and_frame_content() -> None:
    value = redact(
        {
            "authorization": "Bearer value",
            "nested": {"frame_data": b"pixels", "safe": "ok"},
            "binary": b"abc",
        }
    )
    assert value["authorization"] == REDACTED
    assert value["nested"]["frame_data"] == REDACTED
    assert value["nested"]["safe"] == "ok"
    assert value["binary"] == "<bytes:3>"


def test_json_formatter_redacts_extra_fields() -> None:
    record = logging.LogRecord("test", logging.INFO, __file__, 1, "event token=value", (), None)
    record.event_fields = {"password": "value", "frame_data": b"pixels", "request_id": "r1"}
    payload = json.loads(RedactedJsonFormatter().format(record))
    assert payload["password"] == REDACTED
    assert payload["frame_data"] == REDACTED
    assert payload["request_id"] == opaque_label("r1")
    assert "value" not in payload["message"]


@pytest.mark.parametrize("identifier", ["person@example.com", "/private/camera", "request\nforged"])
def test_opaque_identifier_labels_never_expose_caller_values(identifier: str) -> None:
    label = opaque_label(identifier)

    assert label == opaque_label(identifier)
    assert label.startswith("id-")
    assert len(label) == 15
    assert identifier not in label
