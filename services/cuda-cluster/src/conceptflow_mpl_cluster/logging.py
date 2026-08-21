# SPDX-License-Identifier: MIT OR Apache-2.0
"""Redacted structured JSON logging without raw frame content."""

from __future__ import annotations

import json
import hashlib
import logging
import re
from typing import Any, Mapping


_SENSITIVE_PARTS = (
    "authorization",
    "credential",
    "frame_data",
    "image_data",
    "nonce",
    "password",
    "private_key",
    "raw_frame",
    "secret",
    "token",
)
_IDENTIFIER_KEYS = frozenset({"client_instance_id", "correlation_id", "request_id", "session_id", "stream_id"})
REDACTED = "[REDACTED]"
_MESSAGE_SECRET = re.compile(
    r"(?i)\b(authorization|credential|nonce|password|private_key|secret|token)\s*[=:]\s*([^\s,;]+)"
)


def _sensitive(key: str) -> bool:
    normalized = key.casefold()
    return any(part in normalized for part in _SENSITIVE_PARTS)


def opaque_label(identifier: str) -> str:
    """Return a deterministic short label without retaining the identifier."""

    return f"id-{hashlib.sha256(identifier.encode('utf-8')).hexdigest()[:12]}"


def redact(value: Any, *, key: str = "") -> Any:
    if key and _sensitive(key):
        return REDACTED
    if key.casefold() in _IDENTIFIER_KEYS:
        return opaque_label(str(value))
    if isinstance(value, Mapping):
        return {str(item_key): redact(item_value, key=str(item_key)) for item_key, item_value in value.items()}
    if isinstance(value, (list, tuple)):
        return [redact(item) for item in value]
    if isinstance(value, bytes):
        return f"<bytes:{len(value)}>"
    return value


class RedactedJsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        message = _MESSAGE_SECRET.sub(lambda match: f"{match.group(1)}={REDACTED}", record.getMessage())
        payload: dict[str, Any] = {
            "level": record.levelname,
            "logger": record.name,
            "message": message,
        }
        fields = getattr(record, "event_fields", None)
        if isinstance(fields, Mapping):
            payload.update(redact(fields))
        return json.dumps(payload, sort_keys=True, separators=(",", ":"), default=str)


def configure_json_logging(level: int = logging.INFO) -> logging.Logger:
    logger = logging.getLogger("conceptflow.mpl.cluster")
    logger.setLevel(level)
    logger.handlers.clear()
    handler = logging.StreamHandler()
    handler.setFormatter(RedactedJsonFormatter())
    logger.addHandler(handler)
    logger.propagate = False
    return logger


def event(logger: logging.Logger, name: str, **fields: Any) -> None:
    logger.info(name, extra={"event_fields": fields})
