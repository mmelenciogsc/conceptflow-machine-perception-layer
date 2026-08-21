# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import base64
from pathlib import Path

from conceptflow.mpl.v1 import perception_pb2 as pb
from conceptflow_mpl_protocol.validation import validate_descriptor, validate_generated


def test_descriptor_contract_and_generated_files_are_current() -> None:
    validate_descriptor()
    validate_generated()


def test_language_options_and_rpc_surface() -> None:
    descriptor = pb.DESCRIPTOR
    options = descriptor.GetOptions()
    assert options.java_multiple_files is True
    assert options.java_package == "org.conceptflow.mpl.v1"
    assert options.java_outer_classname == "PerceptionProto"
    assert options.csharp_namespace == "ConceptFlow.Mpl.V1"
    service = descriptor.services_by_name["PerceptionService"]
    assert [method.name for method in service.methods] == ["Negotiate", "ProcessFrame", "Health"]
    assert all("webrtc" not in method.name.casefold() for method in service.methods)


def test_complete_cue_serialization_round_trip(cue_factory) -> None:
    cue = cue_factory(urgency=pb.URGENCY_CRITICAL, priority=100)
    cue.cancel.cue_ids.append("obsolete")
    cue.cancel.reason = "context changed"
    cue.supersede.cue_ids.append("older")
    cue.supersede.reason = "newer frame"
    encoded = cue.SerializeToString(deterministic=True)
    decoded = pb.PerceptionCue.FromString(encoded)
    assert decoded == cue
    assert decoded.HasField("earcon")
    assert decoded.HasField("speech")
    assert decoded.HasField("haptic")
    assert decoded.HasField("cancel")
    assert decoded.HasField("supersede")
    assert decoded.HasField("provenance")


def test_frame_serialization_preserves_monotonic_identity(frame_factory) -> None:
    frame = frame_factory(frame_id=42, capture_ns=900_000_000)
    frame.intrinsics.CopyFrom(
        pb.CameraIntrinsics(
            focal_x_pixels=500.0,
            focal_y_pixels=501.0,
            principal_x_pixels=2.0,
            principal_y_pixels=2.0,
            distortion_coefficients=[0.1, -0.01],
            calibrated_width=4,
            calibrated_height=4,
        )
    )
    frame.pose.CopyFrom(
        pb.Pose(
            reference_frame=pb.COORDINATE_FRAME_LOCAL_WORLD,
            translation_meters=pb.Vector3(x=1.0, y=2.0, z=3.0),
            rotation=pb.Quaternion(w=1.0),
            monotonic_timestamp_ns=899_000_000,
        )
    )
    decoded = pb.FramePayload.FromString(frame.SerializeToString(deterministic=True))
    assert decoded.frame_id == 42
    assert decoded.capture_monotonic_timestamp_ns == 900_000_000
    assert decoded.frame_data == frame.frame_data
    assert decoded.intrinsics.focal_x_pixels == 500.0
    assert decoded.pose.reference_frame == pb.COORDINATE_FRAME_LOCAL_WORLD


def test_canonical_cross_language_vectors_round_trip_byte_exactly() -> None:
    vector_path = Path(__file__).parent / "fixtures" / "protocol_vectors.properties"
    vectors = {
        key: value
        for line in vector_path.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#")
        for key, value in [line.split("=", 1)]
    }
    frame_bytes = base64.b64decode(vectors["frame_payload_base64"], validate=True)
    result_bytes = base64.b64decode(vectors["perception_result_base64"], validate=True)

    frame = pb.FramePayload.FromString(frame_bytes)
    result = pb.PerceptionResult.FromString(result_bytes)

    assert vectors["schema_version"] == "1"
    assert frame.request_id == "interop-request-1"
    assert frame.image.encoding == pb.IMAGE_ENCODING_PNG
    assert frame.frame_data.startswith(b"\x89PNG\r\n\x1a\n")
    assert result.request_id == frame.request_id
    assert result.frame_id == frame.frame_id
    assert result.cues[0].speech.text == "Object ahead"
    assert frame.SerializeToString(deterministic=True) == frame_bytes
    assert result.SerializeToString(deterministic=True) == result_bytes
