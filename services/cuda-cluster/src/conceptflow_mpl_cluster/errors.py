# SPDX-License-Identifier: MIT OR Apache-2.0
"""Typed cluster errors mapped to protocol error codes."""

from __future__ import annotations

from dataclasses import dataclass

from conceptflow.mpl.v1 import perception_pb2 as pb


@dataclass(slots=True)
class ClusterError(RuntimeError):
    code: int
    detail: str
    retryable: bool = False
    retry_after_ms: int = 0

    def __str__(self) -> str:
        return self.detail


def overloaded() -> ClusterError:
    return ClusterError(pb.ERROR_CODE_OVERLOADED, "worker queue is full", True, 25)
