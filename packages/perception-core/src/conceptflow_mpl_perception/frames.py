# SPDX-License-Identifier: MIT OR Apache-2.0
"""Timestamp-aware BODY, HEAD, SENSOR, and WORLD frame graph."""

from __future__ import annotations

from dataclasses import dataclass

from .model import CoordinateFrame, Quaternion, StaleTransformError, TimedTransform, Vec3


def _compose(parent_from_middle: TimedTransform, middle_from_child: TimedTransform) -> TimedTransform:
    if parent_from_middle.child != middle_from_child.parent:
        raise ValueError("transform edges are not composable")
    return TimedTransform(
        parent=parent_from_middle.parent,
        child=middle_from_child.child,
        translation=parent_from_middle.transform_point_child_to_parent(middle_from_child.translation),
        rotation=(parent_from_middle.rotation * middle_from_child.rotation).normalized(),
        timestamp_ns=min(parent_from_middle.timestamp_ns, middle_from_child.timestamp_ns),
    )


@dataclass(slots=True)
class FrameGraph:
    """Fixed topology WORLD <- BODY <- HEAD <- SENSOR.

    Body and head transforms are deliberately separate: changing head pose cannot
    rotate the calibrated torso envelope.
    """

    world_from_body: TimedTransform
    body_from_head: TimedTransform
    head_from_sensor: TimedTransform

    def __post_init__(self) -> None:
        self._validate_edge(self.world_from_body, CoordinateFrame.WORLD, CoordinateFrame.BODY)
        self._validate_edge(self.body_from_head, CoordinateFrame.BODY, CoordinateFrame.HEAD)
        self._validate_edge(self.head_from_sensor, CoordinateFrame.HEAD, CoordinateFrame.SENSOR)

    @staticmethod
    def _validate_edge(edge: TimedTransform, parent: CoordinateFrame, child: CoordinateFrame) -> None:
        if edge.parent != parent or edge.child != child:
            raise ValueError(f"expected {parent.value}<-{child.value} transform")

    @classmethod
    def identity(cls, timestamp_ns: int = 0) -> FrameGraph:
        zero = Vec3(0.0, 0.0, 0.0)
        identity = Quaternion.identity()
        return cls(
            TimedTransform(CoordinateFrame.WORLD, CoordinateFrame.BODY, zero, identity, timestamp_ns),
            TimedTransform(CoordinateFrame.BODY, CoordinateFrame.HEAD, zero, identity, timestamp_ns),
            TimedTransform(CoordinateFrame.HEAD, CoordinateFrame.SENSOR, zero, identity, timestamp_ns),
        )

    def update(self, transform: TimedTransform) -> None:
        """Replace one edge, rejecting clock rollback for that edge."""
        edge_name: str
        expected: tuple[CoordinateFrame, CoordinateFrame]
        if (transform.parent, transform.child) == (CoordinateFrame.WORLD, CoordinateFrame.BODY):
            edge_name, expected = "world_from_body", (CoordinateFrame.WORLD, CoordinateFrame.BODY)
        elif (transform.parent, transform.child) == (CoordinateFrame.BODY, CoordinateFrame.HEAD):
            edge_name, expected = "body_from_head", (CoordinateFrame.BODY, CoordinateFrame.HEAD)
        elif (transform.parent, transform.child) == (CoordinateFrame.HEAD, CoordinateFrame.SENSOR):
            edge_name, expected = "head_from_sensor", (CoordinateFrame.HEAD, CoordinateFrame.SENSOR)
        else:
            raise ValueError("transform is not an edge in WORLD<-BODY<-HEAD<-SENSOR")
        self._validate_edge(transform, *expected)
        previous = getattr(self, edge_name)
        if transform.timestamp_ns < previous.timestamp_ns:
            raise ValueError("transform timestamp must be monotonic per edge")
        setattr(self, edge_name, transform)

    def _checked(self, edge: TimedTransform, at_ns: int, max_age_ns: int) -> TimedTransform:
        if at_ns < 0 or max_age_ns < 0:
            raise ValueError("timestamps and maximum age must be nonnegative")
        age = at_ns - edge.timestamp_ns
        if age < 0 or age > max_age_ns:
            raise StaleTransformError(
                f"{edge.parent.value}<-{edge.child.value} pose age {age}ns outside [0,{max_age_ns}]"
            )
        return edge

    def world_from(self, frame: CoordinateFrame, at_ns: int, max_age_ns: int) -> TimedTransform | None:
        if frame == CoordinateFrame.WORLD:
            return None
        body = self._checked(self.world_from_body, at_ns, max_age_ns)
        if frame == CoordinateFrame.BODY:
            return body
        head = self._checked(self.body_from_head, at_ns, max_age_ns)
        world_head = _compose(body, head)
        if frame == CoordinateFrame.HEAD:
            return world_head
        sensor = self._checked(self.head_from_sensor, at_ns, max_age_ns)
        return _compose(world_head, sensor)

    def transform_point(
        self,
        point: Vec3,
        source: CoordinateFrame,
        target: CoordinateFrame,
        at_ns: int,
        max_age_ns: int,
    ) -> Vec3:
        if source == target:
            return point
        world_from_source = self.world_from(source, at_ns, max_age_ns)
        point_world = point if world_from_source is None else world_from_source.transform_point_child_to_parent(point)
        world_from_target = self.world_from(target, at_ns, max_age_ns)
        return (
            point_world
            if world_from_target is None
            else world_from_target.inverse().transform_point_child_to_parent(point_world)
        )

    def transform_vector(
        self,
        vector: Vec3,
        source: CoordinateFrame,
        target: CoordinateFrame,
        at_ns: int,
        max_age_ns: int,
    ) -> Vec3:
        if source == target:
            return vector
        world_from_source = self.world_from(source, at_ns, max_age_ns)
        vector_world = (
            vector if world_from_source is None else world_from_source.transform_vector_child_to_parent(vector)
        )
        world_from_target = self.world_from(target, at_ns, max_age_ns)
        return (
            vector_world
            if world_from_target is None
            else world_from_target.inverse().transform_vector_child_to_parent(vector_world)
        )
