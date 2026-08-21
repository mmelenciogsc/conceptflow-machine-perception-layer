# SPDX-License-Identifier: MIT OR Apache-2.0
"""Validate the checked-in protocol descriptor and generated artifacts."""

from __future__ import annotations

import filecmp
from pathlib import Path
import sys
import tempfile

from google.protobuf.descriptor import FieldDescriptor

from conceptflow.mpl.v1 import perception_pb2
from conceptflow_mpl_protocol.generate import PROTO_RELATIVE, SOURCE_ROOT, generate


EXPECTED_METHODS = ("Negotiate", "ProcessFrame", "Health")
REQUIRED_CUE_FIELDS = (
    "cue_id",
    "frame_id",
    "created_monotonic_timestamp_ns",
    "ttl_ms",
    "category",
    "description",
    "confidence",
    "priority",
    "coordinate_frame",
    "azimuth_degrees",
    "elevation_degrees",
    "distance_meters",
    "direction",
    "urgency",
    "earcon",
    "speech",
    "haptic",
    "cancel",
    "supersede",
    "provenance",
)


def validate_descriptor() -> None:
    descriptor = perception_pb2.DESCRIPTOR
    if descriptor.package != "conceptflow.mpl.v1":
        raise AssertionError(f"unexpected protobuf package: {descriptor.package}")
    service = descriptor.services_by_name.get("PerceptionService")
    if service is None:
        raise AssertionError("PerceptionService is missing")
    methods = tuple(method.name for method in service.methods)
    if methods != EXPECTED_METHODS:
        raise AssertionError(f"unexpected RPC surface: {methods}")
    cue = descriptor.message_types_by_name["PerceptionCue"]
    cue_fields = tuple(field.name for field in cue.fields)
    if cue_fields != REQUIRED_CUE_FIELDS:
        raise AssertionError(f"PerceptionCue fields differ: {cue_fields}")
    frame_data = descriptor.message_types_by_name["FramePayload"].fields_by_name["frame_data"]
    if frame_data.type != FieldDescriptor.TYPE_BYTES:
        raise AssertionError("FramePayload.frame_data must be bytes")


def validate_generated() -> None:
    with tempfile.TemporaryDirectory(prefix="conceptflow-mpl-proto-") as temp:
        regenerated = generate(Path(temp))
        checked_dir = SOURCE_ROOT / PROTO_RELATIVE.parent
        for candidate in regenerated:
            checked = checked_dir / candidate.name
            if not checked.exists() or not filecmp.cmp(candidate, checked, shallow=False):
                raise AssertionError(f"generated artifact is stale: {checked}")


def main() -> int:
    validate_descriptor()
    validate_generated()
    print("protocol descriptor and generated artifacts are valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
