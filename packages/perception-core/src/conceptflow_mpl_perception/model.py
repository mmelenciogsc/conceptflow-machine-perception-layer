# SPDX-License-Identifier: MIT OR Apache-2.0
"""Dependency-free vector, quaternion, and timestamped transform primitives."""

from __future__ import annotations

import math
from dataclasses import dataclass
from enum import Enum

EPSILON = 1.0e-9


def clamp01(value: float) -> float:
    """Clamp a finite scalar to the closed unit interval."""
    if not math.isfinite(value):
        raise ValueError("value must be finite")
    return max(0.0, min(1.0, value))


@dataclass(frozen=True, slots=True)
class Vec3:
    x: float
    y: float
    z: float

    def __post_init__(self) -> None:
        if not all(math.isfinite(value) for value in (self.x, self.y, self.z)):
            raise ValueError("vector components must be finite")

    def __add__(self, other: Vec3) -> Vec3:
        return Vec3(self.x + other.x, self.y + other.y, self.z + other.z)

    def __sub__(self, other: Vec3) -> Vec3:
        return Vec3(self.x - other.x, self.y - other.y, self.z - other.z)

    def __neg__(self) -> Vec3:
        return Vec3(-self.x, -self.y, -self.z)

    def __mul__(self, scalar: float) -> Vec3:
        if not math.isfinite(scalar):
            raise ValueError("scalar must be finite")
        return Vec3(self.x * scalar, self.y * scalar, self.z * scalar)

    __rmul__ = __mul__

    def __truediv__(self, scalar: float) -> Vec3:
        if not math.isfinite(scalar) or abs(scalar) <= EPSILON:
            raise ValueError("scalar divisor must be finite and nonzero")
        return self * (1.0 / scalar)

    def dot(self, other: Vec3) -> float:
        return self.x * other.x + self.y * other.y + self.z * other.z

    def cross(self, other: Vec3) -> Vec3:
        return Vec3(
            self.y * other.z - self.z * other.y,
            self.z * other.x - self.x * other.z,
            self.x * other.y - self.y * other.x,
        )

    def squared_norm(self) -> float:
        return self.dot(self)

    def norm(self) -> float:
        return math.sqrt(self.squared_norm())

    def normalized(self, fallback: Vec3 | None = None) -> Vec3:
        length = self.norm()
        if length <= EPSILON:
            if fallback is None:
                raise ValueError("cannot normalize a near-zero vector")
            return fallback.normalized()
        return self / length

    def distance_to(self, other: Vec3) -> float:
        return (self - other).norm()


@dataclass(frozen=True, slots=True)
class Quaternion:
    """Hamilton quaternion with scalar component first."""

    w: float
    x: float
    y: float
    z: float

    def __post_init__(self) -> None:
        if not all(math.isfinite(value) for value in (self.w, self.x, self.y, self.z)):
            raise ValueError("quaternion components must be finite")

    @classmethod
    def identity(cls) -> Quaternion:
        return cls(1.0, 0.0, 0.0, 0.0)

    @classmethod
    def from_axis_angle(cls, axis: Vec3, radians: float) -> Quaternion:
        if not math.isfinite(radians):
            raise ValueError("angle must be finite")
        unit = axis.normalized()
        half = radians * 0.5
        scale = math.sin(half)
        return cls(math.cos(half), unit.x * scale, unit.y * scale, unit.z * scale)

    def squared_norm(self) -> float:
        return self.w * self.w + self.x * self.x + self.y * self.y + self.z * self.z

    def normalized(self) -> Quaternion:
        length = math.sqrt(self.squared_norm())
        if length <= EPSILON:
            raise ValueError("cannot normalize a near-zero quaternion")
        return Quaternion(self.w / length, self.x / length, self.y / length, self.z / length)

    def conjugate(self) -> Quaternion:
        return Quaternion(self.w, -self.x, -self.y, -self.z)

    def __mul__(self, other: Quaternion) -> Quaternion:
        return Quaternion(
            self.w * other.w - self.x * other.x - self.y * other.y - self.z * other.z,
            self.w * other.x + self.x * other.w + self.y * other.z - self.z * other.y,
            self.w * other.y - self.x * other.z + self.y * other.w + self.z * other.x,
            self.w * other.z + self.x * other.y - self.y * other.x + self.z * other.w,
        )

    def rotate(self, vector: Vec3) -> Vec3:
        unit = self.normalized()
        pure = Quaternion(0.0, vector.x, vector.y, vector.z)
        result = unit * pure * unit.conjugate()
        return Vec3(result.x, result.y, result.z)


class CoordinateFrame(str, Enum):
    BODY = "body"
    HEAD = "head"
    SENSOR = "sensor"
    WORLD = "world"


class StaleTransformError(ValueError):
    """A frame transform is unavailable or too old for the observation."""


@dataclass(frozen=True, slots=True)
class TimedTransform:
    """Rigid parent-from-child transform sampled on a monotonic clock."""

    parent: CoordinateFrame
    child: CoordinateFrame
    translation: Vec3
    rotation: Quaternion
    timestamp_ns: int

    def __post_init__(self) -> None:
        if self.parent == self.child:
            raise ValueError("parent and child frames must differ")
        if not isinstance(self.timestamp_ns, int) or self.timestamp_ns < 0:
            raise ValueError("timestamp_ns must be a nonnegative integer")
        object.__setattr__(self, "rotation", self.rotation.normalized())

    def inverse(self) -> TimedTransform:
        inverse_rotation = self.rotation.conjugate()
        inverse_translation = -inverse_rotation.rotate(self.translation)
        return TimedTransform(
            parent=self.child,
            child=self.parent,
            translation=inverse_translation,
            rotation=inverse_rotation,
            timestamp_ns=self.timestamp_ns,
        )

    def transform_point_child_to_parent(self, point: Vec3) -> Vec3:
        return self.rotation.rotate(point) + self.translation

    def transform_vector_child_to_parent(self, vector: Vec3) -> Vec3:
        return self.rotation.rotate(vector)
