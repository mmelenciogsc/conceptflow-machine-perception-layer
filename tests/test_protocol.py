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


def test_sensor_stream_envelope_round_trip_preserves_lease_and_absolute_imu() -> None:
    reading = pb.ImuReading(
        sequence_id=9,
        pose=pb.Pose(
            reference_frame=pb.COORDINATE_FRAME_HEAD,
            rotation=pb.Quaternion(w=1.0),
            monotonic_timestamp_ns=900,
        ),
        angular_velocity_radians_per_second=pb.Vector3(x=0.25),
        linear_acceleration_meters_per_second_squared=pb.Vector3(y=0.5),
        orientation_accuracy=3,
        angular_velocity_monotonic_timestamp_ns=890,
        linear_acceleration_monotonic_timestamp_ns=895,
    )
    envelope = pb.SensorStreamEnvelope(
        session_id="ephemeral-session",
        lease_id="lease-1",
        sequence_id=4,
        sent_monotonic_timestamp_ns=910,
        imu_batch=pb.ImuBatch(
            lease_id="lease-1",
            batch_id=2,
            created_monotonic_timestamp_ns=905,
            samples=[reading],
        ),
    )

    decoded = pb.SensorStreamEnvelope.FromString(envelope.SerializeToString(deterministic=True))

    assert decoded.WhichOneof("payload") == "imu_batch"
    assert decoded.imu_batch.samples[0] == reading
    assert decoded.imu_batch.samples[0].pose.rotation.w == 1.0


def test_live_link_wrapper_preserves_independent_lane_sequence_and_control() -> None:
    legacy_sensor = pb.SensorStreamEnvelope(
        session_id="ephemeral-session",
        lease_id="lease-1",
        sequence_id=99,
        imu_batch=pb.ImuBatch(lease_id="lease-1", batch_id=3),
    )
    envelope = pb.LiveLinkEnvelope(
        session_id="ephemeral-session",
        lease_id="lease-1",
        lane=pb.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
        lane_sequence_id=1,
        sensor=legacy_sensor,
    )

    decoded = pb.LiveLinkEnvelope.FromString(envelope.SerializeToString(deterministic=True))

    assert decoded.lane_sequence_id == 1
    assert decoded.sensor.sequence_id == 99
    assert decoded.sensor.WhichOneof("payload") == "imu_batch"


def test_spool_manifest_contract_preserves_path_inline_imu_and_hash() -> None:
    record = pb.SpoolRecord(
        record_id="imu-1-100",
        revision=1,
        created_monotonic_timestamp_ns=100,
        kind=pb.SPOOL_RECORD_KIND_IMU,
        imu_batch=pb.ImuBatch(lease_id="lease", batch_id=1, created_monotonic_timestamp_ns=100),
    )
    snapshot = pb.SpoolManifestSnapshot(
        revision=1,
        manifest_json_utf8=b'{"schema_version":1}',
        manifest_sha256=bytes(range(32)),
        records=[record],
    )

    decoded = pb.SpoolManifestSnapshot.FromString(snapshot.SerializeToString(deterministic=True))

    assert decoded.records[0].WhichOneof("metadata") == "imu_batch"
    assert decoded.records[0].relative_path == ""
    assert decoded.manifest_sha256 == bytes(range(32))


def test_camera_intrinsics_do_not_imply_calibration_or_uncertainty_by_default() -> None:
    unknown = pb.CameraIntrinsics(focal_x_pixels=900.0, focal_y_pixels=900.0)
    calibrated = pb.CameraIntrinsics(
        focal_x_pixels=900.0,
        focal_y_pixels=901.0,
        principal_x_pixels=960.0,
        principal_y_pixels=540.0,
        provenance=pb.CAMERA_INTRINSICS_PROVENANCE_CALIBRATED,
        uncertainty=pb.CameraIntrinsicsUncertainty(
            focal_x_stddev_pixels=0.5,
            focal_y_stddev_pixels=0.5,
            principal_x_stddev_pixels=1.0,
            principal_y_stddev_pixels=1.0,
        ),
    )

    assert unknown.provenance == pb.CAMERA_INTRINSICS_PROVENANCE_UNSPECIFIED
    assert not unknown.HasField("uncertainty")
    assert calibrated.provenance == pb.CAMERA_INTRINSICS_PROVENANCE_CALIBRATED
    assert calibrated.uncertainty.principal_x_stddev_pixels == 1.0


def test_microphone_request_is_explicit_and_camera_chunks_are_bounded() -> None:
    request = pb.StreamLeaseRequest(
        request_id="request-1",
        session_id="session-1",
        operation=pb.STREAM_LEASE_OPERATION_OPEN,
        requested_streams=[
            pb.SENSOR_STREAM_KIND_CAMERA,
            pb.SENSOR_STREAM_KIND_IMU,
            pb.SENSOR_STREAM_KIND_MICROPHONE,
        ],
        requested_duration_ms=8_000,
        user_requested_microphone=True,
        camera_relaxed_fps=2,
        camera_motion_fps=5,
        imu_max_batch_delay_ms=20,
        imu_max_silence_ms=1_000,
    )
    chunk = pb.CameraFrameChunk(
        frame_id=1,
        chunk_index=0,
        chunk_count=1,
        total_payload_bytes=4,
        chunk_data=b"test",
    )

    assert request.user_requested_microphone is True
    assert request.camera_relaxed_fps == 2
    assert len(chunk.chunk_data) <= 64 * 1024


def test_microphone_gesture_control_round_trips_with_sublease_correlation() -> None:
    intent = pb.MicrophoneControlIntent(
        session_id="session-1",
        lease_id="lease-1",
        intent_id=7,
        created_monotonic_timestamp_ns=123_000_000,
        operation=pb.MICROPHONE_CONTROL_OPERATION_START,
        user_requested=True,
        requested_duration_ms=10_000,
    )
    envelope = pb.LiveLinkControl(microphone_control_intent=intent)
    parsed = pb.LiveLinkControl.FromString(envelope.SerializeToString(deterministic=True))
    request = pb.StreamLeaseRequest(
        request_id="live-link-microphone-open",
        session_id=intent.session_id,
        lease_id=intent.lease_id,
        operation=pb.STREAM_LEASE_OPERATION_OPEN,
        requested_streams=[pb.SENSOR_STREAM_KIND_MICROPHONE],
        requested_duration_ms=10_000,
        user_requested_microphone=True,
        originating_microphone_intent_id=intent.intent_id,
    )

    assert parsed.microphone_control_intent == intent
    assert request.originating_microphone_intent_id == intent.intent_id


def test_rokid_gesture_command_and_acknowledgement_round_trip() -> None:
    gesture = pb.RokidGestureIntent(
        session_id="session-1",
        lease_id="lease-1",
        gesture_id=17,
        observed_monotonic_timestamp_ns=123_000_000,
        operation=pb.ROKID_GESTURE_OPERATION_ENABLE_NODE,
        user_initiated=True,
    )
    command = pb.RokidNodeCommand(
        session_id=gesture.session_id,
        lease_id=gesture.lease_id,
        command_id=23,
        originating_gesture_id=gesture.gesture_id,
        issued_monotonic_timestamp_ns=456_000_000,
        valid_for_ms=2_000,
        operation=pb.ROKID_NODE_COMMAND_OPERATION_ACTIVATE_NODE,
        user_authorized=True,
    )
    result = pb.RokidNodeCommandResult(
        session_id=command.session_id,
        lease_id=command.lease_id,
        command_id=command.command_id,
        originating_gesture_id=command.originating_gesture_id,
        operation=command.operation,
        accepted_for_execution=True,
    )

    for field, expected in (
        ("rokid_gesture_intent", gesture),
        ("rokid_node_command", command),
        ("rokid_node_command_result", result),
    ):
        envelope = pb.LiveLinkControl(**{field: expected})
        parsed = pb.LiveLinkControl.FromString(envelope.SerializeToString(deterministic=True))
        assert parsed.WhichOneof("payload") == field
        assert getattr(parsed, field) == expected


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
