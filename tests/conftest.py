# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import base64
import hashlib
import time

from google.protobuf import duration_pb2
import pytest

from conceptflow.mpl.v1 import perception_pb2 as pb


_ONE_PIXEL_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)
_ONE_PIXEL_JPEG = base64.b64decode(
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIs"
    "IxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIy"
    "MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAA"
    "AAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAk"
    "M2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKT"
    "lJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QA"
    "HwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdh"
    "cRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hp"
    "anN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk"
    "5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwBKKKKAP//Z"
)


@pytest.fixture
def frame_factory():
    def make(
        frame_id: int = 1,
        *,
        request_id: str | None = None,
        capture_ns: int | None = None,
        data: bytes | None = None,
        deadline_ms: int = 100,
    ) -> pb.FramePayload:
        payload = bytes(range(48)) if data is None else data
        return pb.FramePayload(
            request_id=request_id or f"request-{frame_id}",
            session_id="session",
            stream_id="camera",
            frame_id=frame_id,
            capture_monotonic_timestamp_ns=capture_ns or time.monotonic_ns() - 1_000_000,
            image=pb.ImageDescriptor(
                width=4,
                height=4,
                row_stride_bytes=12,
                encoding=pb.IMAGE_ENCODING_RGB8,
                media_type="application/x-conceptflow-rgb8",
                payload_bytes=len(payload),
                sha256=hashlib.sha256(payload).digest(),
            ),
            frame_data=payload,
            processing_deadline=duration_pb2.Duration(
                seconds=deadline_ms // 1000,
                nanos=(deadline_ms % 1000) * 1_000_000,
            ),
            synthetic=True,
        )

    return make


@pytest.fixture(
    params=[
        (pb.IMAGE_ENCODING_PNG, "image/png", _ONE_PIXEL_PNG),
        (pb.IMAGE_ENCODING_JPEG, "image/jpeg", _ONE_PIXEL_JPEG),
    ]
)
def encoded_image(request):
    return request.param


@pytest.fixture
def png_image():
    return pb.IMAGE_ENCODING_PNG, "image/png", _ONE_PIXEL_PNG


@pytest.fixture
def jpeg_image():
    return pb.IMAGE_ENCODING_JPEG, "image/jpeg", _ONE_PIXEL_JPEG


@pytest.fixture
def encoded_frame_factory():
    def make(
        encoding: int,
        media_type: str,
        data: bytes,
        *,
        request_id: str = "encoded-request",
        session_id: str = "session",
        stream_id: str = "camera",
        frame_id: int = 1,
        capture_ns: int | None = None,
        width: int = 1,
        height: int = 1,
    ) -> pb.FramePayload:
        return pb.FramePayload(
            request_id=request_id,
            session_id=session_id,
            stream_id=stream_id,
            frame_id=frame_id,
            capture_monotonic_timestamp_ns=capture_ns or time.monotonic_ns() - 1_000_000,
            image=pb.ImageDescriptor(
                width=width,
                height=height,
                row_stride_bytes=0,
                encoding=encoding,
                media_type=media_type,
                payload_bytes=len(data),
                sha256=hashlib.sha256(data).digest(),
            ),
            frame_data=data,
            processing_deadline=duration_pb2.Duration(nanos=100_000_000),
            synthetic=True,
        )

    return make


@pytest.fixture
def cue_factory():
    def make(
        cue_id: str = "cue-1",
        *,
        now_ns: int = 1_000_000_000,
        ttl_ms: int = 1_000,
        priority: int = 50,
        urgency: int = pb.URGENCY_NORMAL,
        description: str = "door ahead",
    ) -> pb.PerceptionCue:
        return pb.PerceptionCue(
            cue_id=cue_id,
            frame_id=1,
            created_monotonic_timestamp_ns=now_ns,
            ttl_ms=ttl_ms,
            category=pb.CUE_CATEGORY_OBJECT,
            description=description,
            confidence=0.9,
            priority=priority,
            coordinate_frame=pb.COORDINATE_FRAME_CAMERA_OPTICAL,
            azimuth_degrees=0.0,
            elevation_degrees=0.0,
            distance_meters=2.0,
            direction=pb.DIRECTION_AHEAD,
            urgency=urgency,
            earcon=pb.Earcon(earcon_id="object", gain=0.5, pitch=1.0, spatialized=True),
            speech=pb.Speech(text=description, language_tag="en"),
            haptic=pb.Haptic(pattern=pb.HAPTIC_PATTERN_PULSE, intensity=0.4, duration_ms=60),
            provenance=pb.Provenance(component="test", component_version="1", synthetic=True),
        )

    return make
