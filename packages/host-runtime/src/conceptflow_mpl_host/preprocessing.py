# SPDX-License-Identifier: MIT OR Apache-2.0
"""Pure frame validation before any local or cluster routing."""

from __future__ import annotations

from collections import OrderedDict
from dataclasses import dataclass
import hashlib
import re
import zlib

from conceptflow.mpl.v1 import perception_pb2 as pb


_IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,63}\Z")
_PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
_PNG_MAX_CHUNKS = 4_096
_JPEG_MAX_HEADER_BYTES = 64 * 1024
_JPEG_MAX_HEADER_MARKERS = 512
_JPEG_SUPPORTED_SOF = frozenset({0xC0, 0xC1, 0xC2})
_JPEG_UNSUPPORTED_SOF = frozenset({0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF})

IMAGE_MEDIA_TYPES: dict[int, str] = {
    pb.IMAGE_ENCODING_RGB8: "application/x-conceptflow-rgb8",
    pb.IMAGE_ENCODING_GRAY8: "application/x-conceptflow-gray8",
    pb.IMAGE_ENCODING_JPEG: "image/jpeg",
    pb.IMAGE_ENCODING_PNG: "image/png",
}


@dataclass(slots=True)
class FrameValidationError(ValueError):
    code: int
    detail: str

    def __str__(self) -> str:
        return self.detail


def identifier_is_valid(value: str) -> bool:
    """Return whether *value* is a bounded opaque protocol identifier."""

    return _IDENTIFIER.fullmatch(value) is not None


class FrameSequenceValidator:
    """Enforce monotonic IDs and timestamps per stream with bounded history."""

    def __init__(self, *, max_streams: int = 1_024) -> None:
        if max_streams <= 0:
            raise ValueError("max_streams must be positive")
        self._max_streams = max_streams
        self._last: OrderedDict[tuple[str, str], tuple[int, int]] = OrderedDict()

    @property
    def stream_count(self) -> int:
        return len(self._last)

    def validate(self, frame: pb.FramePayload) -> None:
        key = (frame.session_id, frame.stream_id)
        previous = self._last.get(key)
        current = (frame.frame_id, frame.capture_monotonic_timestamp_ns)
        if previous is not None:
            if current[0] <= previous[0]:
                raise FrameValidationError(
                    pb.ERROR_CODE_STALE,
                    "frame_id must increase monotonically within a stream",
                )
            if current[1] <= previous[1]:
                raise FrameValidationError(
                    pb.ERROR_CODE_STALE,
                    "capture timestamp must increase monotonically within a stream",
                )
            self._last.move_to_end(key)
        elif len(self._last) >= self._max_streams:
            self._last.popitem(last=False)
        self._last[key] = current

    def reset_stream(self, session_id: str, stream_id: str) -> bool:
        """Forget one stream, returning whether history existed."""

        return self._last.pop((session_id, stream_id), None) is not None

    def reset(self) -> None:
        """Forget all stream history, for an explicit session lifecycle reset."""

        self._last.clear()


def _invalid_image(detail: str) -> FrameValidationError:
    return FrameValidationError(pb.ERROR_CODE_INVALID_ARGUMENT, detail)


def _png_dimensions(data: bytes) -> tuple[int, int]:
    if len(data) < 33 or not data.startswith(_PNG_SIGNATURE):
        raise _invalid_image("PNG structure is invalid or truncated")
    if int.from_bytes(data[8:12], "big") != 13 or data[12:16] != b"IHDR":
        raise _invalid_image("PNG structure is invalid or truncated")
    ihdr = data[16:29]
    expected_crc = int.from_bytes(data[29:33], "big")
    if zlib.crc32(b"IHDR" + ihdr) & 0xFFFFFFFF != expected_crc:
        raise _invalid_image("PNG structure is invalid or truncated")
    width = int.from_bytes(ihdr[0:4], "big")
    height = int.from_bytes(ihdr[4:8], "big")
    bit_depth, color_type, compression, filter_method, interlace = ihdr[8:13]
    valid_depths = {
        0: {1, 2, 4, 8, 16},
        2: {8, 16},
        3: {1, 2, 4, 8},
        4: {8, 16},
        6: {8, 16},
    }
    if (
        width == 0
        or height == 0
        or bit_depth not in valid_depths.get(color_type, set())
        or compression != 0
        or filter_method != 0
        or interlace not in {0, 1}
    ):
        raise _invalid_image("PNG structure is invalid or truncated")

    offset = 33
    chunks = 1
    saw_data = False
    while offset < len(data):
        chunks += 1
        if chunks > _PNG_MAX_CHUNKS or len(data) - offset < 12:
            raise _invalid_image("PNG structure is invalid or truncated")
        chunk_length = int.from_bytes(data[offset : offset + 4], "big")
        chunk_end = offset + 12 + chunk_length
        if chunk_end > len(data):
            raise _invalid_image("PNG structure is invalid or truncated")
        chunk_type = data[offset + 4 : offset + 8]
        chunk_data_end = offset + 8 + chunk_length
        chunk_data = data[offset + 8 : chunk_data_end]
        chunk_crc = int.from_bytes(data[chunk_data_end:chunk_end], "big")
        if zlib.crc32(chunk_type + chunk_data) & 0xFFFFFFFF != chunk_crc:
            raise _invalid_image("PNG structure is invalid or truncated")
        if chunk_type == b"IHDR":
            raise _invalid_image("PNG structure is invalid or truncated")
        if chunk_type == b"IDAT":
            saw_data = True
        if chunk_type == b"IEND":
            if chunk_length != 0 or not saw_data or chunk_end != len(data):
                raise _invalid_image("PNG structure is invalid or truncated")
            return width, height
        offset = chunk_end
    raise _invalid_image("PNG structure is invalid or truncated")


def _jpeg_dimensions(data: bytes) -> tuple[int, int]:
    if len(data) < 8 or data[:2] != b"\xff\xd8" or data[-2:] != b"\xff\xd9":
        raise _invalid_image("JPEG structure is invalid or truncated")
    offset = 2
    marker_count = 0
    dimensions: tuple[int, int] | None = None
    saw_scan = False
    in_entropy_data = False
    while offset < len(data):
        if in_entropy_data:
            marker_offset = data.find(b"\xff", offset)
            if marker_offset < 0 or marker_offset + 1 >= len(data):
                raise _invalid_image("JPEG structure is invalid or truncated")
            offset = marker_offset + 1
            while offset < len(data) and data[offset] == 0xFF:
                offset += 1
            if offset >= len(data):
                raise _invalid_image("JPEG structure is invalid or truncated")
            marker = data[offset]
            offset += 1
            if marker == 0x00 or 0xD0 <= marker <= 0xD7:
                continue
            in_entropy_data = False
        else:
            if dimensions is None and offset > _JPEG_MAX_HEADER_BYTES:
                raise _invalid_image("JPEG header is invalid or exceeds scan limits")
            if data[offset] != 0xFF:
                raise _invalid_image("JPEG header is invalid or exceeds scan limits")
            while offset < len(data) and data[offset] == 0xFF:
                offset += 1
            if offset >= len(data):
                raise _invalid_image("JPEG structure is invalid or truncated")
            marker = data[offset]
            offset += 1
        marker_count += 1
        if marker_count > _JPEG_MAX_HEADER_MARKERS:
            raise _invalid_image("JPEG header is invalid or exceeds scan limits")
        if marker == 0x00 or marker == 0xD8:
            raise _invalid_image("JPEG header is invalid or exceeds scan limits")
        if marker == 0xD9:
            if dimensions is None or not saw_scan or offset != len(data):
                raise _invalid_image("JPEG structure is invalid or truncated")
            return dimensions
        if marker == 0x01 or 0xD0 <= marker <= 0xD7:
            continue
        if offset + 2 > len(data):
            raise _invalid_image("JPEG structure is invalid or truncated")
        segment_length = int.from_bytes(data[offset : offset + 2], "big")
        if segment_length < 2 or offset + segment_length > len(data):
            raise _invalid_image("JPEG structure is invalid or truncated")
        if marker in _JPEG_UNSUPPORTED_SOF:
            raise _invalid_image("JPEG frame type is unsupported")
        if marker in _JPEG_SUPPORTED_SOF:
            if dimensions is not None or segment_length < 8:
                raise _invalid_image("JPEG structure is invalid or truncated")
            precision = data[offset + 2]
            height = int.from_bytes(data[offset + 3 : offset + 5], "big")
            width = int.from_bytes(data[offset + 5 : offset + 7], "big")
            components = data[offset + 7]
            if (
                width == 0
                or height == 0
                or precision not in {8, 12}
                or components not in {1, 3, 4}
                or segment_length != 8 + 3 * components
            ):
                raise _invalid_image("JPEG structure is invalid or truncated")
            dimensions = (width, height)
        if marker == 0xDA:
            if dimensions is None or segment_length < 6:
                raise _invalid_image("JPEG does not contain a supported frame header")
            scan_components = data[offset + 2]
            if scan_components == 0 or segment_length != 6 + 2 * scan_components:
                raise _invalid_image("JPEG structure is invalid or truncated")
            saw_scan = True
            in_entropy_data = True
        offset += segment_length
    raise _invalid_image("JPEG structure is invalid or truncated")


@dataclass(frozen=True, slots=True)
class FramePreprocessor:
    max_frame_bytes: int
    max_width: int = 4096
    max_height: int = 4096

    def validate(self, frame: pb.FramePayload) -> pb.FramePayload:
        if not all(identifier_is_valid(value) for value in (frame.request_id, frame.session_id, frame.stream_id)):
            raise FrameValidationError(
                pb.ERROR_CODE_INVALID_ARGUMENT,
                "request, session, and stream identifiers must use the canonical opaque format",
            )
        if frame.frame_id <= 0 or frame.capture_monotonic_timestamp_ns <= 0:
            raise FrameValidationError(
                pb.ERROR_CODE_INVALID_ARGUMENT,
                "frame_id and capture timestamp must be positive",
            )
        image = frame.image
        if image.width <= 0 or image.height <= 0:
            raise FrameValidationError(pb.ERROR_CODE_INVALID_ARGUMENT, "image dimensions must be positive")
        if image.width > self.max_width or image.height > self.max_height:
            raise FrameValidationError(pb.ERROR_CODE_OVERSIZE, "image dimensions exceed configured limits")
        expected_media_type = IMAGE_MEDIA_TYPES.get(image.encoding)
        if expected_media_type is None:
            raise FrameValidationError(pb.ERROR_CODE_INVALID_ARGUMENT, "image encoding is unsupported")
        if image.media_type != expected_media_type:
            raise FrameValidationError(
                pb.ERROR_CODE_INVALID_ARGUMENT,
                "image media type does not match encoding",
            )
        actual_bytes = len(frame.frame_data)
        if actual_bytes == 0:
            raise FrameValidationError(pb.ERROR_CODE_INVALID_ARGUMENT, "frame payload is empty")
        if actual_bytes > self.max_frame_bytes:
            raise FrameValidationError(pb.ERROR_CODE_OVERSIZE, "frame payload exceeds configured limit")
        if image.payload_bytes != actual_bytes:
            raise FrameValidationError(
                pb.ERROR_CODE_INVALID_ARGUMENT,
                "descriptor payload size does not match frame payload",
            )
        channels = {
            pb.IMAGE_ENCODING_RGB8: 3,
            pb.IMAGE_ENCODING_GRAY8: 1,
        }.get(image.encoding)
        if channels is not None:
            minimum_stride = image.width * channels
            if image.row_stride_bytes < minimum_stride:
                raise FrameValidationError(pb.ERROR_CODE_INVALID_ARGUMENT, "row stride is too small")
            if actual_bytes != image.row_stride_bytes * image.height:
                raise FrameValidationError(pb.ERROR_CODE_INVALID_ARGUMENT, "raw image byte count is invalid")
        else:
            if image.row_stride_bytes != 0:
                raise FrameValidationError(
                    pb.ERROR_CODE_INVALID_ARGUMENT,
                    "compressed image row stride must be zero",
                )
            parsed_dimensions = (
                _png_dimensions(frame.frame_data)
                if image.encoding == pb.IMAGE_ENCODING_PNG
                else _jpeg_dimensions(frame.frame_data)
            )
            if parsed_dimensions != (image.width, image.height):
                raise FrameValidationError(
                    pb.ERROR_CODE_INVALID_ARGUMENT,
                    "encoded image dimensions do not match descriptor",
                )
        if image.sha256:
            if len(image.sha256) != 32:
                raise FrameValidationError(pb.ERROR_CODE_INVALID_ARGUMENT, "sha256 must contain 32 bytes")
            if hashlib.sha256(frame.frame_data).digest() != image.sha256:
                raise FrameValidationError(pb.ERROR_CODE_INVALID_ARGUMENT, "frame digest mismatch")
        return frame
