# SPDX-License-Identifier: MIT OR Apache-2.0
"""Inspectable cue rendering that never includes image bytes."""

from __future__ import annotations

import json

from conceptflow.mpl.v1 import perception_pb2 as pb


class InspectableCueRenderer:
    def render(self, cue: pb.PerceptionCue) -> str:
        modalities: list[str] = []
        if cue.HasField("earcon"):
            modalities.append("earcon")
        if cue.HasField("speech"):
            modalities.append("speech")
        if cue.HasField("haptic"):
            modalities.append("haptic")
        payload = {
            "assistive_only": True,
            "category": pb.CueCategory.Name(cue.category),
            "confidence": round(cue.confidence, 3),
            "cue_id": cue.cue_id,
            "description": cue.description,
            "direction": pb.Direction.Name(cue.direction),
            "distance_meters": round(cue.distance_meters, 3),
            "frame_id": cue.frame_id,
            "modalities": modalities,
            "priority": cue.priority,
            "ttl_ms": cue.ttl_ms,
            "urgency": pb.Urgency.Name(cue.urgency),
        }
        return json.dumps(payload, sort_keys=True, separators=(",", ":"))
