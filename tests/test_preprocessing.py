# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import pytest

from conceptflow.mpl.v1 import perception_pb2 as pb
from conceptflow_mpl_host.preprocessing import FramePreprocessor, FrameSequenceValidator, FrameValidationError


def test_valid_frame_passes(frame_factory) -> None:
    frame = frame_factory()
    assert FramePreprocessor(max_frame_bytes=1024).validate(frame) is frame


def test_valid_minimal_png_and_jpeg_pass(encoded_frame_factory, encoded_image) -> None:
    encoding, media_type, data = encoded_image
    frame = encoded_frame_factory(encoding, media_type, data)

    assert FramePreprocessor(max_frame_bytes=2_048).validate(frame) is frame


@pytest.mark.parametrize(
    ("mutate", "code"),
    [
        (lambda frame: setattr(frame, "request_id", ""), pb.ERROR_CODE_INVALID_ARGUMENT),
        (lambda frame: setattr(frame, "frame_id", 0), pb.ERROR_CODE_INVALID_ARGUMENT),
        (lambda frame: setattr(frame.image, "width", 0), pb.ERROR_CODE_INVALID_ARGUMENT),
        (lambda frame: setattr(frame.image, "encoding", pb.IMAGE_ENCODING_UNSPECIFIED), pb.ERROR_CODE_INVALID_ARGUMENT),
        (lambda frame: setattr(frame.image, "media_type", "application/octet-stream"), pb.ERROR_CODE_INVALID_ARGUMENT),
        (lambda frame: setattr(frame.image, "payload_bytes", 1), pb.ERROR_CODE_INVALID_ARGUMENT),
        (lambda frame: setattr(frame.image, "row_stride_bytes", 3), pb.ERROR_CODE_INVALID_ARGUMENT),
        (lambda frame: setattr(frame.image, "sha256", b"x" * 32), pb.ERROR_CODE_INVALID_ARGUMENT),
    ],
)
def test_malformed_frames_are_rejected(frame_factory, mutate, code: int) -> None:
    frame = frame_factory()
    mutate(frame)
    with pytest.raises(FrameValidationError) as captured:
        FramePreprocessor(max_frame_bytes=1024).validate(frame)
    assert captured.value.code == code


def test_oversize_payload_is_rejected(frame_factory) -> None:
    frame = frame_factory(data=b"x" * 48)
    with pytest.raises(FrameValidationError) as captured:
        FramePreprocessor(max_frame_bytes=47).validate(frame)
    assert captured.value.code == pb.ERROR_CODE_OVERSIZE


def test_oversize_dimensions_are_rejected(frame_factory) -> None:
    frame = frame_factory()
    frame.image.width = 5
    with pytest.raises(FrameValidationError) as captured:
        FramePreprocessor(max_frame_bytes=1024, max_width=4).validate(frame)
    assert captured.value.code == pb.ERROR_CODE_OVERSIZE


def test_monotonic_sequence_accepts_progress_and_rejects_rewind(frame_factory) -> None:
    sequence = FrameSequenceValidator()
    sequence.validate(frame_factory(frame_id=1, capture_ns=100))
    sequence.validate(frame_factory(frame_id=2, capture_ns=200))
    with pytest.raises(FrameValidationError) as captured:
        sequence.validate(frame_factory(frame_id=2, capture_ns=300, request_id="duplicate"))
    assert captured.value.code == pb.ERROR_CODE_STALE


def test_sequences_are_independent_per_stream(frame_factory) -> None:
    sequence = FrameSequenceValidator()
    first = frame_factory(frame_id=5, capture_ns=500)
    other = frame_factory(frame_id=1, capture_ns=100, request_id="other")
    other.stream_id = "other-camera"
    sequence.validate(first)
    sequence.validate(other)


@pytest.mark.parametrize("bad_identifier", ["person@example.com", "/tmp/camera", "camera\nforged"])
@pytest.mark.parametrize("field", ["request_id", "session_id", "stream_id"])
def test_caller_identifiers_are_tightly_validated(frame_factory, field: str, bad_identifier: str) -> None:
    frame = frame_factory()
    setattr(frame, field, bad_identifier)

    with pytest.raises(FrameValidationError, match="canonical opaque format"):
        FramePreprocessor(max_frame_bytes=1_024).validate(frame)


@pytest.mark.parametrize(
    ("encoding", "media_type"),
    [
        (pb.IMAGE_ENCODING_RGB8, "application/x-conceptflow-gray8"),
        (pb.IMAGE_ENCODING_GRAY8, "application/x-conceptflow-rgb8"),
        (pb.IMAGE_ENCODING_JPEG, "image/png"),
        (pb.IMAGE_ENCODING_PNG, "image/jpeg"),
        (pb.IMAGE_ENCODING_PNG, "image/png; charset=binary"),
    ],
)
def test_encoding_media_type_matrix_is_exact(frame_factory, encoding: int, media_type: str) -> None:
    frame = frame_factory()
    frame.image.encoding = encoding
    frame.image.media_type = media_type

    with pytest.raises(FrameValidationError, match="media type"):
        FramePreprocessor(max_frame_bytes=1_024).validate(frame)


@pytest.mark.parametrize("encoding", [pb.IMAGE_ENCODING_RGB8, pb.IMAGE_ENCODING_GRAY8])
def test_raw_encoding_byte_and_stride_validation_is_exact(frame_factory, encoding: int) -> None:
    channels = 3 if encoding == pb.IMAGE_ENCODING_RGB8 else 1
    frame = frame_factory(data=b"x" * (4 * channels * 4))
    frame.image.encoding = encoding
    frame.image.media_type = (
        "application/x-conceptflow-rgb8" if encoding == pb.IMAGE_ENCODING_RGB8 else "application/x-conceptflow-gray8"
    )
    frame.image.row_stride_bytes = 4 * channels
    FramePreprocessor(max_frame_bytes=1_024).validate(frame)

    frame.image.row_stride_bytes += 1
    with pytest.raises(FrameValidationError, match="byte count"):
        FramePreprocessor(max_frame_bytes=1_024).validate(frame)


@pytest.mark.parametrize("cut", [1, 8, 24, 32, -1])
def test_truncated_png_is_rejected(encoded_frame_factory, png_image, cut: int) -> None:
    encoding, media_type, data = png_image
    truncated = data[:cut]
    frame = encoded_frame_factory(encoding, media_type, truncated)

    with pytest.raises(FrameValidationError, match="PNG structure"):
        FramePreprocessor(max_frame_bytes=2_048).validate(frame)


def test_png_ihdr_crc_and_dimension_mismatch_are_rejected(encoded_frame_factory, png_image) -> None:
    encoding, media_type, data = png_image
    corrupt = bytearray(data)
    corrupt[29] ^= 0x01
    bad_crc = encoded_frame_factory(encoding, media_type, bytes(corrupt))
    with pytest.raises(FrameValidationError, match="PNG structure"):
        FramePreprocessor(max_frame_bytes=2_048).validate(bad_crc)

    mismatch = encoded_frame_factory(encoding, media_type, data, width=2)
    with pytest.raises(FrameValidationError, match="dimensions"):
        FramePreprocessor(max_frame_bytes=2_048).validate(mismatch)


@pytest.mark.parametrize("cut", [1, 2, 20, -1])
def test_truncated_jpeg_is_rejected(encoded_frame_factory, jpeg_image, cut: int) -> None:
    encoding, media_type, data = jpeg_image
    truncated = data[:cut]
    frame = encoded_frame_factory(encoding, media_type, truncated)

    with pytest.raises(FrameValidationError, match="JPEG structure"):
        FramePreprocessor(max_frame_bytes=2_048).validate(frame)


def test_jpeg_dimension_mismatch_and_excessive_header_scan_are_rejected(encoded_frame_factory, jpeg_image) -> None:
    encoding, media_type, data = jpeg_image
    mismatch = encoded_frame_factory(encoding, media_type, data, height=2)
    with pytest.raises(FrameValidationError, match="dimensions"):
        FramePreprocessor(max_frame_bytes=100_000).validate(mismatch)

    excessive = b"\xff\xd8" + (b"\xff\xe0\x00\x02" * 17_000) + b"\xff\xd9"
    frame = encoded_frame_factory(encoding, media_type, excessive)
    with pytest.raises(FrameValidationError, match="scan limits"):
        FramePreprocessor(max_frame_bytes=100_000).validate(frame)


def test_jpeg_without_scan_data_is_rejected(encoded_frame_factory) -> None:
    header_only = b"\xff\xd8\xff\xc0\x00\x0b\x08\x00\x01\x00\x01\x01\x01\x11\x00\xff\xd9"
    frame = encoded_frame_factory(pb.IMAGE_ENCODING_JPEG, "image/jpeg", header_only)

    with pytest.raises(FrameValidationError, match="JPEG structure"):
        FramePreprocessor(max_frame_bytes=1_024).validate(frame)


def test_sequence_history_is_bounded_and_explicitly_resettable(frame_factory) -> None:
    sequence = FrameSequenceValidator(max_streams=2)
    first = frame_factory()
    first.stream_id = "one"
    second = frame_factory(request_id="second")
    second.stream_id = "two"
    third = frame_factory(request_id="third")
    third.stream_id = "three"
    sequence.validate(first)
    sequence.validate(second)
    sequence.validate(third)

    assert sequence.stream_count == 2
    assert not sequence.reset_stream(first.session_id, first.stream_id)
    assert sequence.reset_stream(second.session_id, second.stream_id)
    assert sequence.stream_count == 1
    sequence.reset()
    assert sequence.stream_count == 0
