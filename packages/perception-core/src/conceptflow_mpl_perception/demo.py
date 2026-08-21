# SPDX-License-Identifier: MIT OR Apache-2.0
"""Accessible textual synthetic demonstration of all three perception slices."""

from __future__ import annotations

import json

from .audio import InspectableAudioFallback
from .frames import FrameGraph
from .geometry import DepthSource, MetricGeometryObservation
from .model import CoordinateFrame, Quaternion, TimedTransform, Vec3
from .move import MotionSample
from .pipeline import PerceptionEngine
from .semantic import MaskDepthPoint, SegmentedObservation


def _frames(timestamp_ns: int) -> FrameGraph:
    return FrameGraph(
        TimedTransform(
            CoordinateFrame.WORLD, CoordinateFrame.BODY, Vec3(0.0, 0.0, 0.0), Quaternion.identity(), timestamp_ns
        ),
        TimedTransform(
            CoordinateFrame.BODY, CoordinateFrame.HEAD, Vec3(0.0, 1.6, 0.0), Quaternion.identity(), timestamp_ns
        ),
        TimedTransform(
            CoordinateFrame.HEAD, CoordinateFrame.SENSOR, Vec3(0.0, 0.03, 0.08), Quaternion.identity(), timestamp_ns
        ),
    )


def run_demo() -> dict[str, object]:
    now_ns = 10_000_000_000
    frames = _frames(now_ns)
    engine = PerceptionEngine()
    wall = [
        MetricGeometryObservation(
            "synthetic-depth",
            "wall-left",
            f"wall-{index}",
            Vec3(-0.55, 0.8 + index * 0.25, 0.10),
            now_ns,
            0.96,
            DepthSource.SYNTHETIC,
            0.01,
            Vec3(1.0, 0.0, 0.0),
        )
        for index in range(4)
    ]
    geometry = engine.process_geometry(wall, frames, MotionSample(now_ns, 0.8, 0.0, 30.0, 0.0), now_ns)
    semantic = engine.process_semantic(
        SegmentedObservation(
            "person-1",
            "person",
            0.91,
            now_ns,
            (
                MaskDepthPoint(80, 60, Vec3(-0.25, -0.3, 1.0)),
                MaskDepthPoint(96, 60, Vec3(0.0, -0.1, 1.3)),
                MaskDepthPoint(112, 60, Vec3(0.25, 0.1, 2.0)),
            ),
            Vec3(0.0, 0.0, -0.5),
        ),
        frames,
        now_ns,
    )
    scene = engine.scene_description(now_ns, on_demand=True)
    scheduled = engine.dispatch(now_ns)
    return {
        "assistive_position": "supplemental awareness; not a mobility or safety authority",
        "audio": [json.loads(InspectableAudioFallback.render(item)) for item in geometry.audio],
        "geometry_cues": len(geometry.cues),
        "haptic_cues": len(geometry.haptics),
        "map_morph_move": engine.trace_counters(),
        "scene_request": None if scene is None else scene.summary_context,
        "scheduled": [item.cue_id for item in scheduled],
        "scheduler": engine.scheduler.trace(),
        "semantic_icon": None if semantic.icon is None else semantic.icon.asset_key,
    }


def main() -> int:
    print(json.dumps(run_demo(), indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
