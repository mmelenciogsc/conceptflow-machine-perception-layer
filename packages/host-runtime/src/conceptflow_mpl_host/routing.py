# SPDX-License-Identifier: MIT OR Apache-2.0
"""Pure local-versus-cluster route selection."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum


class RouteTarget(StrEnum):
    LOCAL = "local"
    CLUSTER = "cluster"
    DROP = "drop"


@dataclass(frozen=True, slots=True)
class RouteContext:
    local_available: bool
    cluster_available: bool
    cluster_allowed: bool = True
    privacy_sensitive: bool = False
    latency_budget_ms: int = 250
    local_estimated_ms: int = 80
    cluster_estimated_ms: int = 120
    prefer_cluster_quality: bool = True


@dataclass(frozen=True, slots=True)
class RouteDecision:
    target: RouteTarget
    reason: str


def choose_route(context: RouteContext) -> RouteDecision:
    """Choose a route without I/O or mutation; fail closed when none is viable."""
    if context.latency_budget_ms <= 0:
        return RouteDecision(RouteTarget.DROP, "invalid latency budget")
    local_viable = context.local_available and context.local_estimated_ms <= context.latency_budget_ms
    cluster_viable = (
        context.cluster_available
        and context.cluster_allowed
        and context.cluster_estimated_ms <= context.latency_budget_ms
    )
    if context.privacy_sensitive:
        if local_viable:
            return RouteDecision(RouteTarget.LOCAL, "privacy-sensitive frame kept local")
        return RouteDecision(RouteTarget.DROP, "privacy policy forbids the available route")
    if context.prefer_cluster_quality and cluster_viable:
        return RouteDecision(RouteTarget.CLUSTER, "cluster quality preferred within deadline")
    if local_viable:
        return RouteDecision(RouteTarget.LOCAL, "local route meets deadline")
    if cluster_viable:
        return RouteDecision(RouteTarget.CLUSTER, "cluster is the only route within deadline")
    return RouteDecision(RouteTarget.DROP, "no available route meets policy and deadline")
