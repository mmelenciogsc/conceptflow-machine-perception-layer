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
REQUIRED_STREAM_ENVELOPE_PAYLOADS = (
    "camera_chunk",
    "imu_batch",
    "microphone_chunk",
    "lease_grant",
    "error",
    "touch_event",
)
REQUIRED_LIVE_CONTROL_PAYLOADS = (
    "hello",
    "lane_open_request",
    "lane_open_response",
    "clock_sync_request",
    "clock_sync_response",
    "keepalive",
    "lease_request",
    "lease_grant",
    "error",
    "lane_ticket_grant",
    "microphone_control_intent",
    "microphone_control_result",
    "rokid_gesture_intent",
    "rokid_node_command",
    "rokid_node_command_result",
    "spool_manifest_poll",
    "spool_manifest_snapshot",
    "spool_artifact_request",
    "spool_artifact_chunk",
    "spool_records_ack",
    "capabilities",
    "telemetry",
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
    stream_envelope = descriptor.message_types_by_name["SensorStreamEnvelope"]
    payload = stream_envelope.oneofs_by_name.get("payload")
    if payload is None or tuple(field.name for field in payload.fields) != REQUIRED_STREAM_ENVELOPE_PAYLOADS:
        raise AssertionError("SensorStreamEnvelope payload contract differs")
    live_control = descriptor.message_types_by_name["LiveLinkControl"]
    control_payload = live_control.oneofs_by_name.get("payload")
    if (
        control_payload is None
        or tuple(field.name for field in control_payload.fields) != REQUIRED_LIVE_CONTROL_PAYLOADS
    ):
        raise AssertionError("LiveLinkControl payload contract differs")
    microphone_intent = descriptor.message_types_by_name["MicrophoneControlIntent"]
    if tuple(field.name for field in microphone_intent.fields) != (
        "session_id",
        "lease_id",
        "intent_id",
        "created_monotonic_timestamp_ns",
        "operation",
        "user_requested",
        "requested_duration_ms",
    ):
        raise AssertionError("MicrophoneControlIntent fields differ")
    microphone_result = descriptor.message_types_by_name["MicrophoneControlResult"]
    if tuple(field.name for field in microphone_result.fields) != (
        "session_id",
        "lease_id",
        "intent_id",
        "operation",
        "accepted",
        "error",
    ):
        raise AssertionError("MicrophoneControlResult fields differ")
    lease_request = descriptor.message_types_by_name["StreamLeaseRequest"]
    lease_grant = descriptor.message_types_by_name["StreamLeaseGrant"]
    if lease_request.fields_by_name["originating_microphone_intent_id"].number != 12:
        raise AssertionError("StreamLeaseRequest gesture correlation field differs")
    if lease_request.fields_by_name["requested_camera_encoding"].number != 13:
        raise AssertionError("StreamLeaseRequest camera encoding field differs")
    if lease_grant.fields_by_name["originating_microphone_intent_id"].number != 11:
        raise AssertionError("StreamLeaseGrant gesture correlation field differs")
    if lease_grant.fields_by_name["granted_camera_encoding"].number != 12:
        raise AssertionError("StreamLeaseGrant camera encoding field differs")
    encoding = descriptor.enum_types_by_name["ImageEncoding"]
    if encoding.values_by_name["IMAGE_ENCODING_AVC_ANNEX_B_INTRA"].number != 7:
        raise AssertionError("AVC-intra image encoding differs")
    live_envelope = descriptor.message_types_by_name["LiveLinkEnvelope"]
    if tuple(field.name for field in live_envelope.fields) != (
        "session_id",
        "lease_id",
        "lane",
        "lane_sequence_id",
        "sent_monotonic_timestamp_ns",
        "control",
        "sensor",
    ):
        raise AssertionError("LiveLinkEnvelope fields differ")
    intrinsics = descriptor.message_types_by_name["CameraIntrinsics"]
    if "provenance" not in intrinsics.fields_by_name or "uncertainty" not in intrinsics.fields_by_name:
        raise AssertionError("CameraIntrinsics provenance contract is missing")


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
