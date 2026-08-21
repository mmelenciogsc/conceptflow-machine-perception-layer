# SPDX-License-Identifier: MIT OR Apache-2.0
"""Model-neutral provider contracts and separately governed model profiles."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Protocol, runtime_checkable

from .geometry import MetricGeometryObservation


@runtime_checkable
class DepthProvider(Protocol):
    def depth(self, frame_id: int, payload: bytes, timestamp_ns: int) -> object: ...


@runtime_checkable
class MetricGeometryProvider(Protocol):
    def geometry(self, frame_id: int, timestamp_ns: int) -> list[MetricGeometryObservation]: ...


@runtime_checkable
class PoseProvider(Protocol):
    def pose(self, timestamp_ns: int) -> object: ...


@runtime_checkable
class SemanticSegmenter(Protocol):
    def segment(self, frame_id: int, payload: bytes, prompts: tuple[str, ...]) -> object: ...


OpenVocabularySegmenter = SemanticSegmenter


@runtime_checkable
class ObjectTracker(Protocol):
    def update(self, observations: object, timestamp_ns: int) -> object: ...


@runtime_checkable
class SceneDescriber(Protocol):
    def describe(self, request: object) -> str: ...


class DepthEnvironment(str, Enum):
    INDOOR = "indoor"
    OUTDOOR = "outdoor"


DEPTH_ANYTHING_V2_METRIC_MODELS: dict[DepthEnvironment, str] = {
    DepthEnvironment.INDOOR: "depth-anything/Depth-Anything-V2-Metric-Indoor-Large-hf",
    DepthEnvironment.OUTDOOR: "depth-anything/Depth-Anything-V2-Metric-Outdoor-Large-hf",
}


@dataclass(frozen=True, slots=True)
class DepthAnythingExternalConfig:
    """Configuration only; weights and inference dependencies stay outside core."""

    environment: DepthEnvironment
    endpoint: str
    timeout_ms: int = 180

    def __post_init__(self) -> None:
        if not (self.endpoint.startswith("https://") or self.endpoint.startswith("http://127.0.0.1:")):
            raise ValueError("depth endpoint must use HTTPS or an explicit loopback address")
        if not 10 <= self.timeout_ms <= 5_000:
            raise ValueError("depth timeout must be within [10, 5000] milliseconds")

    @property
    def model_id(self) -> str:
        return DEPTH_ANYTHING_V2_METRIC_MODELS[self.environment]
