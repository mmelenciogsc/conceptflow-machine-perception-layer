# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

import asyncio
import base64
from datetime import datetime, timedelta, timezone
import logging
from pathlib import Path
import re
import struct
from unittest.mock import create_autospec
import zlib

import grpc
from google.protobuf import duration_pb2
import pytest

from conceptflow.mpl.v1 import perception_pb2 as pb
from conceptflow.mpl.v1 import perception_pb2_grpc as pb_grpc
from conceptflow_mpl_cluster.config import ClusterConfig, RuntimeProfile
from conceptflow_mpl_cluster.device import ComputeDevice
from conceptflow_mpl_cluster.image_decode import BoundedImageDecoder
from conceptflow_mpl_cluster.pool import WorkerPool
from conceptflow_mpl_cluster.service import PerceptionService, create_grpc_server
from conceptflow_mpl_cluster.worker import DeterministicMockWorker


class _Context:
    def time_remaining(self) -> float | None:
        return None


class _Aborted(RuntimeError):
    pass


class _AbortContext(_Context):
    def __init__(self) -> None:
        self.code = None
        self.details = ""

    async def abort(self, code, details: str) -> None:
        self.code = code
        self.details = details
        raise _Aborted(details)


class _ControlledPool:
    def __init__(self, *, blocked: bool = False) -> None:
        self.entered = asyncio.Event()
        self.release = asyncio.Event()
        self.blocked = blocked
        self.timeout_seconds: list[float] = []

    async def submit(self, frame: pb.FramePayload, *, timeout_seconds: float) -> pb.PerceptionResult:
        self.timeout_seconds.append(timeout_seconds)
        self.entered.set()
        if self.blocked:
            await self.release.wait()
        cues = []
        for index in range(3):
            cues.append(
                pb.PerceptionCue(
                    cue_id=f"cue-{index}",
                    frame_id=frame.frame_id,
                    created_monotonic_timestamp_ns=frame.capture_monotonic_timestamp_ns,
                    ttl_ms=1_000,
                    description=f"cue {index}",
                    confidence=1.0,
                    earcon=pb.Earcon(earcon_id="object"),
                    speech=pb.Speech(text=f"cue {index}"),
                    haptic=pb.Haptic(pattern=pb.HAPTIC_PATTERN_PULSE),
                )
            )
        return pb.PerceptionResult(
            result_id=f"result-{frame.request_id}",
            request_id=frame.request_id,
            session_id=frame.session_id,
            stream_id=frame.stream_id,
            frame_id=frame.frame_id,
            capture_monotonic_timestamp_ns=frame.capture_monotonic_timestamp_ns,
            cues=cues,
        )


def _logger() -> logging.Logger:
    logger = logging.getLogger("test.grpc.unit")
    logger.handlers.clear()
    logger.addHandler(logging.NullHandler())
    return logger


async def _negotiate(service_client, *, max_cues: int = 2) -> pb.NegotiateResponse:
    return await service_client.Negotiate(
        pb.NegotiateRequest(
            client_instance_id="client",
            supported_versions=[pb.ProtocolVersion(major=1)],
            capabilities=pb.CapabilitySet(
                image_encodings=[
                    pb.IMAGE_ENCODING_RGB8,
                    pb.IMAGE_ENCODING_GRAY8,
                    pb.IMAGE_ENCODING_JPEG,
                    pb.IMAGE_ENCODING_PNG,
                ],
                cue_modalities=[pb.CUE_MODALITY_EARCON, pb.CUE_MODALITY_SPEECH, pb.CUE_MODALITY_HAPTIC],
                max_width=4,
                max_height=4,
                max_frame_bytes=2_048,
            ),
            requested_qos=pb.QualityOfService(
                max_in_flight=1,
                target_frames_per_second=30,
                max_cues_per_result=max_cues,
            ),
        )
    )


@pytest.fixture
async def service_client():
    config = ClusterConfig(
        profile=RuntimeProfile.TEST,
        bind_port=0,
        max_receive_bytes=4096,
        max_send_bytes=4096,
        max_frame_bytes=2_048,
        queue_capacity=2,
        worker_timeout_ms=100,
        device_preference="cpu",
    )
    worker = DeterministicMockWorker("worker", ComputeDevice("cpu", "0", "CPU"))
    pool = WorkerPool([worker], queue_capacity=2, runner_count=1, timeout_ms=100, failure_threshold=2)
    await pool.start()
    logger = logging.getLogger("test.grpc")
    logger.handlers.clear()
    logger.addHandler(logging.NullHandler())
    server, port = create_grpc_server(config, pool, logger)
    await server.start()
    channel = grpc.aio.insecure_channel(
        f"127.0.0.1:{port}",
        options=(("grpc.max_receive_message_length", 4096), ("grpc.max_send_message_length", 4096)),
    )
    try:
        yield pb_grpc.PerceptionServiceStub(channel)
    finally:
        await channel.close()
        await server.stop(grace=0.1)
        await pool.close()


@pytest.mark.asyncio
async def test_negotiation_and_health(service_client) -> None:
    negotiation = await service_client.Negotiate(
        pb.NegotiateRequest(
            client_instance_id="client",
            supported_versions=[pb.ProtocolVersion(major=1)],
            requested_qos=pb.QualityOfService(
                max_in_flight=8,
                target_frames_per_second=120,
                allow_frame_drop=True,
            ),
        )
    )
    assert negotiation.selected_version.major == 1
    assert negotiation.identity.session_id
    assert len(negotiation.identity.nonce) == 16
    assert negotiation.accepted_qos.max_in_flight == 2
    assert negotiation.accepted_qos.target_frames_per_second == 60
    assert not negotiation.accepted_qos.allow_frame_drop
    assert pb.IMAGE_ENCODING_PNG in negotiation.capabilities.image_encodings
    health = await service_client.Health(pb.HealthRequest(include_workers=True))
    assert health.status == pb.SERVING_STATUS_SERVING
    assert health.workers[0].healthy


@pytest.mark.asyncio
async def test_incompatible_negotiation_returns_protocol_error(service_client) -> None:
    result = await service_client.Negotiate(
        pb.NegotiateRequest(
            client_instance_id="client",
            supported_versions=[pb.ProtocolVersion(major=2)],
        )
    )
    assert result.error.code == pb.ERROR_CODE_UNSUPPORTED_VERSION


@pytest.mark.asyncio
async def test_malformed_frame_error_propagates(service_client) -> None:
    result = await service_client.ProcessFrame(pb.FramePayload(request_id="bad"))
    assert result.error.code == pb.ERROR_CODE_INVALID_ARGUMENT
    assert result.error.correlation_id.startswith("id-")
    assert result.error.correlation_id != "bad"


@pytest.mark.asyncio
async def test_unknown_session_is_rejected_before_compressed_image_decode(
    encoded_frame_factory,
    png_image,
) -> None:
    decoder = create_autospec(BoundedImageDecoder, instance=True)
    service = PerceptionService(
        ClusterConfig(profile=RuntimeProfile.TEST),
        _ControlledPool(),
        _logger(),
        image_decoder=decoder,
    )
    encoding, media_type, data = png_image
    frame = encoded_frame_factory(
        encoding,
        media_type,
        data,
        session_id="unknown-session",
    )

    result = await service.ProcessFrame(frame, _Context())

    assert result.error.code == pb.ERROR_CODE_STALE
    decoder.validate.assert_not_called()


@pytest.mark.asyncio
async def test_oversize_frame_error_propagates(service_client, frame_factory) -> None:
    negotiation = await _negotiate(service_client)
    frame = frame_factory(data=b"x" * 65)
    frame.session_id = negotiation.identity.session_id
    frame.image.width = 65
    frame.image.height = 1
    frame.image.row_stride_bytes = 65
    frame.image.encoding = pb.IMAGE_ENCODING_GRAY8
    frame.image.media_type = "application/x-conceptflow-gray8"
    result = await service_client.ProcessFrame(frame)
    assert result.error.code == pb.ERROR_CODE_OVERSIZE


@pytest.mark.asyncio
async def test_successful_service_round_trip(service_client, frame_factory) -> None:
    negotiation = await _negotiate(service_client)
    frame = frame_factory()
    frame.session_id = negotiation.identity.session_id
    result = await service_client.ProcessFrame(frame)
    assert result.request_id == frame.request_id
    assert result.observations[0].description == "synthetic object"
    assert result.provenance.synthetic


@pytest.mark.asyncio
async def test_png_service_round_trip(service_client, encoded_frame_factory, png_image) -> None:
    encoding, media_type, data = png_image
    negotiation = await _negotiate(service_client)
    frame = encoded_frame_factory(encoding, media_type, data, session_id=negotiation.identity.session_id)

    result = await service_client.ProcessFrame(frame)

    assert result.error.code == pb.ERROR_CODE_UNSPECIFIED
    assert result.request_id == frame.request_id


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("relative_path", "constant_name", "encoding", "media_type"),
    [
        (
            "packages/android-protocol/src/main/java/org/conceptflow/mpl/protocol/SyntheticImageFixtures.java",
            "ONE_PIXEL_JPEG_BASE64",
            pb.IMAGE_ENCODING_JPEG,
            "image/jpeg",
        ),
        (
            "apps/desktop-relay/src/ConceptFlow.Mpl.DesktopRelay.Core/SyntheticCaptureAssets.cs",
            "OnePixelPngBase64",
            pb.IMAGE_ENCODING_PNG,
            "image/png",
        ),
    ],
)
async def test_shipped_client_fixture_decodes_through_real_grpc_boundary(
    service_client,
    encoded_frame_factory,
    relative_path: str,
    constant_name: str,
    encoding: int,
    media_type: str,
) -> None:
    source = (Path(__file__).resolve().parents[1] / relative_path).read_text(encoding="utf-8")
    match = re.search(rf"\b{constant_name}\s*=\s*\"([^\"]+)\"", source)
    assert match is not None, f"canonical fixture constant {constant_name} is missing"
    data = base64.b64decode(match.group(1), validate=True)
    negotiation = await _negotiate(service_client)
    frame = encoded_frame_factory(
        encoding,
        media_type,
        data,
        request_id=f"interop-{constant_name.lower()}",
        session_id=negotiation.identity.session_id,
    )

    result = await service_client.ProcessFrame(frame)

    assert result.error.code == pb.ERROR_CODE_UNSPECIFIED
    assert result.request_id == frame.request_id


@pytest.mark.asyncio
async def test_transport_message_limit_rejects_large_serialized_request(service_client, frame_factory) -> None:
    frame = frame_factory(data=b"x" * 5_000)
    frame.image.width = 5_000
    frame.image.height = 1
    frame.image.row_stride_bytes = 5_000
    frame.image.encoding = pb.IMAGE_ENCODING_GRAY8
    with pytest.raises(grpc.aio.AioRpcError) as captured:
        await service_client.ProcessFrame(frame)
    assert captured.value.code() == grpc.StatusCode.RESOURCE_EXHAUSTED


@pytest.mark.asyncio
async def test_unknown_session_is_rejected_before_worker_submission(frame_factory) -> None:
    pool = _ControlledPool()
    service = PerceptionService(ClusterConfig(profile=RuntimeProfile.TEST), pool, _logger())

    result = await service.ProcessFrame(frame_factory(), _Context())

    assert result.error.code == pb.ERROR_CODE_STALE
    assert not pool.timeout_seconds


@pytest.mark.asyncio
async def test_session_capacity_rejects_churn_without_evicting_unexpired_sessions(frame_factory) -> None:
    now = [datetime(2026, 8, 21, tzinfo=timezone.utc)]
    identities = iter(("session-1", "session-2", "session-3"))
    pool = _ControlledPool()
    service = PerceptionService(
        ClusterConfig(profile=RuntimeProfile.TEST),
        pool,
        _logger(),
        identity_factory=lambda: next(identities),
        clock=lambda: now[0],
        session_ttl=timedelta(seconds=1),
        max_sessions=2,
    )
    request = pb.NegotiateRequest(
        client_instance_id="sensitive-client", supported_versions=[pb.ProtocolVersion(major=1)]
    )
    first = await service.Negotiate(request, _Context())
    await service.Negotiate(request, _Context())
    context = _AbortContext()
    with pytest.raises(_Aborted, match="capacity"):
        await service.Negotiate(request, context)

    assert service.session_count == 2
    assert context.code == grpc.StatusCode.RESOURCE_EXHAUSTED
    retained_frame = frame_factory()
    retained_frame.session_id = first.identity.session_id
    retained = await service.ProcessFrame(retained_frame, _Context())
    assert retained.error.code == pb.ERROR_CODE_UNSPECIFIED
    retained_state = service._sessions[first.identity.session_id]
    assert not hasattr(retained_state, "client_instance_id")
    assert not hasattr(retained_state, "nonce")
    assert not hasattr(retained_state, "frame_data")

    now[0] += timedelta(seconds=1)
    expired_frame = frame_factory(frame_id=2)
    expired_frame.session_id = first.identity.session_id
    expired = await service.ProcessFrame(expired_frame, _Context())
    assert expired.error.code == pb.ERROR_CODE_STALE
    assert service.session_count == 0


@pytest.mark.asyncio
async def test_negotiated_frame_deadline_modality_and_cue_limits_are_enforced(frame_factory) -> None:
    pool = _ControlledPool()
    service = PerceptionService(
        ClusterConfig(profile=RuntimeProfile.TEST, worker_timeout_ms=100, max_frame_bytes=2_048),
        pool,
        _logger(),
        identity_factory=lambda: "bounded-session",
    )
    negotiation = await service.Negotiate(
        pb.NegotiateRequest(
            client_instance_id="client",
            supported_versions=[pb.ProtocolVersion(major=1)],
            capabilities=pb.CapabilitySet(
                image_encodings=[pb.IMAGE_ENCODING_RGB8, pb.IMAGE_ENCODING_GRAY8, pb.IMAGE_ENCODING_JPEG],
                cue_modalities=[pb.CUE_MODALITY_SPEECH],
                max_width=8,
                max_height=8,
                max_frame_bytes=48,
            ),
            requested_qos=pb.QualityOfService(
                max_in_flight=1,
                result_deadline=duration_pb2.Duration(nanos=20_000_000),
                allow_frame_drop=True,
                max_cues_per_result=2,
            ),
        ),
        _Context(),
    )
    frame = frame_factory(deadline_ms=80)
    frame.session_id = negotiation.identity.session_id

    result = await service.ProcessFrame(frame, _Context())

    assert not negotiation.accepted_qos.allow_frame_drop
    assert len(result.cues) == 2
    assert len(pool.timeout_seconds) == 1
    assert pool.timeout_seconds[0] == pytest.approx(0.02)
    assert all(cue.HasField("speech") for cue in result.cues)
    assert all(not cue.HasField("earcon") and not cue.HasField("haptic") for cue in result.cues)

    oversize = frame_factory(frame_id=2, data=b"x" * 49)
    oversize.session_id = negotiation.identity.session_id
    oversize.image.width = 7
    oversize.image.height = 7
    oversize.image.row_stride_bytes = 7
    oversize.image.encoding = pb.IMAGE_ENCODING_GRAY8
    oversize.image.media_type = "application/x-conceptflow-gray8"
    rejected = await service.ProcessFrame(oversize, _Context())
    assert rejected.error.code == pb.ERROR_CODE_OVERSIZE
    assert len(pool.timeout_seconds) == 1


@pytest.mark.parametrize(
    ("supports_cancellation", "supports_supersession", "expected"),
    [
        (False, False, ["speech"]),
        (True, False, ["speech", "cancel"]),
        (False, True, ["speech", "supersede"]),
        (True, True, ["speech", "cancel", "supersede"]),
    ],
)
def test_bounded_result_intersects_modalities_and_control_capabilities(
    supports_cancellation: bool,
    supports_supersession: bool,
    expected: list[str],
) -> None:
    result = pb.PerceptionResult(
        cues=[
            pb.PerceptionCue(cue_id="earcon-only", earcon=pb.Earcon(earcon_id="object")),
            pb.PerceptionCue(cue_id="speech", speech=pb.Speech(text="Door ahead")),
            pb.PerceptionCue(cue_id="cancel", cancel=pb.CueCancellation(cue_ids=["previous"])),
            pb.PerceptionCue(cue_id="supersede", supersede=pb.CueSupersession(cue_ids=["older"])),
        ]
    )
    state = type(
        "NegotiatedState",
        (),
        {
            "max_cues_per_result": 8,
            "cue_modalities": frozenset({pb.CUE_MODALITY_SPEECH}),
            "supports_cancellation": supports_cancellation,
            "supports_supersession": supports_supersession,
        },
    )()

    bounded = PerceptionService._bounded_result(result, state)

    assert [cue.cue_id for cue in bounded.cues] == expected
    assert bounded.cues[0].HasField("speech")
    assert [cue.cue_id for cue in result.cues] == ["earcon-only", "speech", "cancel", "supersede"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("cancellation", "supersession"),
    [(False, False), (True, False), (False, True), (True, True)],
)
async def test_negotiation_returns_control_capability_intersection(
    cancellation: bool,
    supersession: bool,
) -> None:
    service = PerceptionService(
        ClusterConfig(profile=RuntimeProfile.TEST),
        _ControlledPool(),
        _logger(),
        identity_factory=lambda: f"controls-{int(cancellation)}-{int(supersession)}",
    )
    result = await service.Negotiate(
        pb.NegotiateRequest(
            client_instance_id="client",
            supported_versions=[pb.ProtocolVersion(major=1)],
            capabilities=pb.CapabilitySet(
                supports_cancellation=cancellation,
                supports_supersession=supersession,
            ),
        ),
        _Context(),
    )

    assert result.capabilities.supports_cancellation is cancellation
    assert result.capabilities.supports_supersession is supersession


@pytest.mark.asyncio
async def test_negotiated_in_flight_limit_rejects_concurrent_unary_request(frame_factory) -> None:
    pool = _ControlledPool(blocked=True)
    service = PerceptionService(
        ClusterConfig(profile=RuntimeProfile.TEST),
        pool,
        _logger(),
        identity_factory=lambda: "single-flight-session",
    )
    negotiation = await service.Negotiate(
        pb.NegotiateRequest(
            client_instance_id="client",
            supported_versions=[pb.ProtocolVersion(major=1)],
            requested_qos=pb.QualityOfService(max_in_flight=1),
        ),
        _Context(),
    )
    first_frame = frame_factory(frame_id=1)
    first_frame.session_id = negotiation.identity.session_id
    first = asyncio.create_task(service.ProcessFrame(first_frame, _Context()))
    await pool.entered.wait()
    second_frame = frame_factory(frame_id=2)
    second_frame.session_id = negotiation.identity.session_id

    second = await service.ProcessFrame(second_frame, _Context())

    assert second.error.code == pb.ERROR_CODE_OVERLOADED
    assert second.error.retryable
    assert second.result_id.startswith("error-id-")
    assert len(pool.timeout_seconds) == 1
    pool.release.set()
    assert (await first).error.code == pb.ERROR_CODE_UNSPECIFIED


@pytest.mark.asyncio
@pytest.mark.parametrize("case", ["truncated", "media", "dimensions"])
async def test_invalid_compressed_content_is_rejected_before_worker_submission(
    encoded_frame_factory,
    encoded_image,
    case: str,
) -> None:
    encoding, media_type, data = encoded_image
    pool = _ControlledPool()
    service = PerceptionService(
        ClusterConfig(profile=RuntimeProfile.TEST, max_frame_bytes=2_048),
        pool,
        _logger(),
        identity_factory=lambda: "content-session",
    )
    negotiation = await service.Negotiate(
        pb.NegotiateRequest(
            client_instance_id="client",
            supported_versions=[pb.ProtocolVersion(major=1)],
            capabilities=pb.CapabilitySet(image_encodings=[encoding], max_frame_bytes=2_048),
            requested_qos=pb.QualityOfService(target_frames_per_second=60),
        ),
        _Context(),
    )
    malformed = encoded_frame_factory(
        encoding,
        media_type,
        data,
        session_id=negotiation.identity.session_id,
    )
    if case == "truncated":
        malformed.frame_data = data[:-1]
        malformed.image.payload_bytes = len(malformed.frame_data)
    elif case == "media":
        malformed.image.media_type = "image/jpeg" if encoding == pb.IMAGE_ENCODING_PNG else "image/png"
    else:
        malformed.image.width = 2

    result = await service.ProcessFrame(malformed, _Context())

    assert result.error.code == pb.ERROR_CODE_INVALID_ARGUMENT
    assert not pool.timeout_seconds


def _png_chunk(kind: bytes, data: bytes) -> bytes:
    checksum = zlib.crc32(kind + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", checksum)


def _structurally_valid_png(width: int, height: int, compressed: bytes) -> bytes:
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n" + _png_chunk(b"IHDR", header) + _png_chunk(b"IDAT", compressed) + _png_chunk(b"IEND", b"")
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("malformed_kind", ["invalid-zlib", "invalid-jpeg-entropy", "missing-jpeg-tables"])
async def test_decoder_invalid_compressed_content_never_reaches_worker(
    encoded_frame_factory,
    jpeg_image,
    malformed_kind: str,
) -> None:
    _, _, valid_jpeg = jpeg_image
    if malformed_kind == "invalid-zlib":
        encoding = pb.IMAGE_ENCODING_PNG
        media_type = "image/png"
        data = _structurally_valid_png(1, 1, b"not-zlib")
    elif malformed_kind == "invalid-jpeg-entropy":
        encoding = pb.IMAGE_ENCODING_JPEG
        media_type = "image/jpeg"
        data = (
            b"\xff\xd8\xff\xc0\x00\x0b\x08\x00\x01\x00\x01\x01\x01\x11\x00"
            b"\xff\xda\x00\x08\x01\x01\x00\x00\x3f\x00\x00\xff\xd9"
        )
    else:
        encoding = pb.IMAGE_ENCODING_JPEG
        media_type = "image/jpeg"
        data = valid_jpeg
        for marker in (b"\xff\xdb", b"\xff\xc4"):
            while marker in data:
                offset = data.index(marker)
                length = int.from_bytes(data[offset + 2 : offset + 4], "big")
                data = data[:offset] + data[offset + 2 + length :]
    pool = _ControlledPool()
    service = PerceptionService(
        ClusterConfig(profile=RuntimeProfile.TEST, max_frame_bytes=2_048),
        pool,
        _logger(),
        identity_factory=lambda: "decoder-session",
    )
    negotiation = await service.Negotiate(
        pb.NegotiateRequest(
            client_instance_id="client",
            supported_versions=[pb.ProtocolVersion(major=1)],
            capabilities=pb.CapabilitySet(image_encodings=[encoding], max_frame_bytes=2_048),
        ),
        _Context(),
    )
    frame = encoded_frame_factory(
        encoding,
        media_type,
        data,
        session_id=negotiation.identity.session_id,
    )

    result = await service.ProcessFrame(frame, _Context())

    assert result.error.code == pb.ERROR_CODE_INVALID_ARGUMENT
    assert not pool.timeout_seconds


@pytest.mark.asyncio
async def test_declared_decode_bomb_is_rejected_before_worker_submission(encoded_frame_factory) -> None:
    data = _structurally_valid_png(4_097, 1, zlib.compress(b"\x00" * 17))
    frame = encoded_frame_factory(pb.IMAGE_ENCODING_PNG, "image/png", data, width=4_097, height=1)
    pool = _ControlledPool()
    service = PerceptionService(ClusterConfig(profile=RuntimeProfile.TEST), pool, _logger())

    result = await service.ProcessFrame(frame, _Context())

    assert result.error.code == pb.ERROR_CODE_OVERSIZE
    assert not pool.timeout_seconds


@pytest.mark.asyncio
async def test_decoded_resource_budget_is_enforced_before_worker_submission(
    encoded_frame_factory,
    png_image,
) -> None:
    encoding, media_type, data = png_image
    pool = _ControlledPool()
    service = PerceptionService(
        ClusterConfig(profile=RuntimeProfile.TEST),
        pool,
        _logger(),
        image_decoder=BoundedImageDecoder(max_decoded_bytes=1),
    )
    negotiation = await service.Negotiate(
        pb.NegotiateRequest(client_instance_id="client", supported_versions=[pb.ProtocolVersion(major=1)]),
        _Context(),
    )
    frame = encoded_frame_factory(
        encoding,
        media_type,
        data,
        session_id=negotiation.identity.session_id,
    )

    result = await service.ProcessFrame(frame, _Context())

    assert result.error.code == pb.ERROR_CODE_OVERSIZE
    assert not pool.timeout_seconds


@pytest.mark.asyncio
async def test_rate_and_replay_admission_are_enforced_before_shared_pool(frame_factory) -> None:
    monotonic_now = [100.0]
    pool = _ControlledPool()
    service = PerceptionService(
        ClusterConfig(profile=RuntimeProfile.TEST),
        pool,
        _logger(),
        identity_factory=lambda: "rate-session",
        monotonic_clock=lambda: monotonic_now[0],
    )
    negotiation = await service.Negotiate(
        pb.NegotiateRequest(
            client_instance_id="client",
            supported_versions=[pb.ProtocolVersion(major=1)],
            requested_qos=pb.QualityOfService(target_frames_per_second=2, max_in_flight=1),
        ),
        _Context(),
    )

    first = frame_factory(frame_id=1, request_id="first", capture_ns=100)
    first.session_id = negotiation.identity.session_id
    second = frame_factory(frame_id=2, request_id="second", capture_ns=200)
    second.session_id = negotiation.identity.session_id
    third = frame_factory(frame_id=3, request_id="third", capture_ns=300)
    third.session_id = negotiation.identity.session_id
    assert (await service.ProcessFrame(first, _Context())).error.code == pb.ERROR_CODE_UNSPECIFIED
    assert (await service.ProcessFrame(second, _Context())).error.code == pb.ERROR_CODE_UNSPECIFIED

    rate_limited = await service.ProcessFrame(third, _Context())
    assert rate_limited.error.code == pb.ERROR_CODE_OVERLOADED
    assert rate_limited.error.retryable
    assert len(pool.timeout_seconds) == 2

    duplicate_request = frame_factory(frame_id=3, request_id="second", capture_ns=300)
    duplicate_request.session_id = negotiation.identity.session_id
    assert (await service.ProcessFrame(duplicate_request, _Context())).error.code == pb.ERROR_CODE_STALE
    rewind = frame_factory(frame_id=2, request_id="rewind", capture_ns=400)
    rewind.session_id = negotiation.identity.session_id
    assert (await service.ProcessFrame(rewind, _Context())).error.code == pb.ERROR_CODE_STALE
    timestamp_rewind = frame_factory(frame_id=3, request_id="timestamp-rewind", capture_ns=199)
    timestamp_rewind.session_id = negotiation.identity.session_id
    assert (await service.ProcessFrame(timestamp_rewind, _Context())).error.code == pb.ERROR_CODE_STALE

    monotonic_now[0] += 0.5
    assert (await service.ProcessFrame(third, _Context())).error.code == pb.ERROR_CODE_UNSPECIFIED
    assert len(pool.timeout_seconds) == 3


@pytest.mark.asyncio
async def test_rate_buckets_are_independent_between_sessions(frame_factory) -> None:
    monotonic_now = [100.0]
    identities = iter(("session-one", "session-two"))
    pool = _ControlledPool()
    service = PerceptionService(
        ClusterConfig(profile=RuntimeProfile.TEST),
        pool,
        _logger(),
        identity_factory=lambda: next(identities),
        monotonic_clock=lambda: monotonic_now[0],
    )
    request = pb.NegotiateRequest(
        client_instance_id="client",
        supported_versions=[pb.ProtocolVersion(major=1)],
        requested_qos=pb.QualityOfService(target_frames_per_second=1),
    )
    first_session = await service.Negotiate(request, _Context())
    second_session = await service.Negotiate(request, _Context())
    first = frame_factory(request_id="first")
    first.session_id = first_session.identity.session_id
    second = frame_factory(request_id="second")
    second.session_id = second_session.identity.session_id

    assert (await service.ProcessFrame(first, _Context())).error.code == pb.ERROR_CODE_UNSPECIFIED
    assert (await service.ProcessFrame(second, _Context())).error.code == pb.ERROR_CODE_UNSPECIFIED

    limited = frame_factory(frame_id=2, request_id="limited", capture_ns=first.capture_monotonic_timestamp_ns + 1)
    limited.session_id = first_session.identity.session_id
    assert (await service.ProcessFrame(limited, _Context())).error.code == pb.ERROR_CODE_OVERLOADED
    assert len(pool.timeout_seconds) == 2


@pytest.mark.asyncio
async def test_session_histories_are_bounded_deterministically(frame_factory) -> None:
    pool = _ControlledPool()
    service = PerceptionService(
        ClusterConfig(profile=RuntimeProfile.TEST),
        pool,
        _logger(),
        identity_factory=lambda: "history-session",
        max_stream_history=2,
        max_request_history=2,
    )
    negotiation = await service.Negotiate(
        pb.NegotiateRequest(
            client_instance_id="client",
            supported_versions=[pb.ProtocolVersion(major=1)],
            requested_qos=pb.QualityOfService(target_frames_per_second=60),
        ),
        _Context(),
    )
    for index, stream_id in enumerate(("one", "two", "three"), start=1):
        frame = frame_factory(frame_id=index, request_id=f"request-{index}", capture_ns=index)
        frame.session_id = negotiation.identity.session_id
        frame.stream_id = stream_id
        assert (await service.ProcessFrame(frame, _Context())).error.code == pb.ERROR_CODE_UNSPECIFIED

    state = service._sessions[negotiation.identity.session_id]
    assert tuple(state.stream_history) == ("two", "three")
    assert tuple(state.request_history) == ("request-2", "request-3")


@pytest.mark.asyncio
async def test_capacity_never_reclaims_an_in_flight_session(frame_factory) -> None:
    identities = iter(("active-session", "replacement-session"))
    pool = _ControlledPool(blocked=True)
    service = PerceptionService(
        ClusterConfig(profile=RuntimeProfile.TEST),
        pool,
        _logger(),
        identity_factory=lambda: next(identities),
        max_sessions=1,
    )
    request = pb.NegotiateRequest(
        client_instance_id="client",
        supported_versions=[pb.ProtocolVersion(major=1)],
        requested_qos=pb.QualityOfService(target_frames_per_second=60),
    )
    first_negotiation = await service.Negotiate(request, _Context())
    frame = frame_factory()
    frame.session_id = first_negotiation.identity.session_id
    active = asyncio.create_task(service.ProcessFrame(frame, _Context()))
    await pool.entered.wait()
    context = _AbortContext()

    with pytest.raises(_Aborted, match="capacity"):
        await service.Negotiate(request, context)

    assert context.code == grpc.StatusCode.RESOURCE_EXHAUSTED
    assert first_negotiation.identity.session_id in service._sessions
    pool.release.set()
    assert (await active).error.code == pb.ERROR_CODE_UNSPECIFIED


@pytest.mark.asyncio
@pytest.mark.parametrize("bad_client", ["person@example.com", "/tmp/client", "client\nforged"])
async def test_negotiation_rejects_sensitive_or_unsafe_client_identifiers(bad_client: str) -> None:
    service = PerceptionService(ClusterConfig(profile=RuntimeProfile.TEST), _ControlledPool(), _logger())

    result = await service.Negotiate(
        pb.NegotiateRequest(
            client_instance_id=bad_client,
            supported_versions=[pb.ProtocolVersion(major=1)],
        ),
        _Context(),
    )

    assert result.error.code == pb.ERROR_CODE_INVALID_ARGUMENT
    assert bad_client not in result.error.message


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("field", "bad_identifier"),
    [
        ("request_id", "person@example.com"),
        ("session_id", "/private/session"),
        ("stream_id", "stream\nforged"),
    ],
)
async def test_frame_boundary_rejects_unsafe_identifiers_without_logging_or_dispatch(
    frame_factory,
    caplog,
    field: str,
    bad_identifier: str,
) -> None:
    pool = _ControlledPool()
    logger = logging.getLogger(f"test.grpc.privacy.{field}")
    service = PerceptionService(ClusterConfig(profile=RuntimeProfile.TEST), pool, logger)
    frame = frame_factory()
    setattr(frame, field, bad_identifier)

    with caplog.at_level(logging.INFO, logger=logger.name):
        result = await service.ProcessFrame(frame, _Context())

    assert result.error.code == pb.ERROR_CODE_INVALID_ARGUMENT
    assert bad_identifier not in str(result)
    assert bad_identifier not in caplog.text
    assert not pool.timeout_seconds
    fields = caplog.records[-1].event_fields
    assert fields["request_label"].startswith("id-")
