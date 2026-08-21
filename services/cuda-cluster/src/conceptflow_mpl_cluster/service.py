# SPDX-License-Identifier: MIT OR Apache-2.0
"""Asynchronous gRPC implementation for the v1 perception contract."""

from __future__ import annotations

import asyncio
from collections import OrderedDict
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
import logging
import math
import secrets
import time
import uuid
from typing import Callable, cast

import grpc
from google.protobuf import duration_pb2, timestamp_pb2

from conceptflow.mpl.v1 import perception_pb2 as pb
from conceptflow.mpl.v1 import perception_pb2_grpc as pb_grpc
from conceptflow_mpl_host.preprocessing import FramePreprocessor, FrameValidationError, identifier_is_valid

from .config import ClusterConfig
from .errors import ClusterError
from .image_decode import BoundedImageDecoder
from .logging import event, opaque_label
from .pool import WorkerPool


SERVER_VERSION = pb.ProtocolVersion(major=1, minor=0, patch=0)
_SERVER_IMAGE_ENCODINGS = (
    pb.IMAGE_ENCODING_RGB8,
    pb.IMAGE_ENCODING_GRAY8,
    pb.IMAGE_ENCODING_JPEG,
    pb.IMAGE_ENCODING_PNG,
)
_SERVER_CUE_MODALITIES = (
    pb.CUE_MODALITY_EARCON,
    pb.CUE_MODALITY_SPEECH,
    pb.CUE_MODALITY_HAPTIC,
)
_MAX_WIDTH = 4096
_MAX_HEIGHT = 4096
_MAX_FRAMES_PER_SECOND = 60
_MAX_CUES_PER_RESULT = 8
_DEFAULT_SESSION_TTL = timedelta(minutes=10)
_DEFAULT_MAX_SESSIONS = 1024
_DEFAULT_STREAM_HISTORY = 128
_DEFAULT_REQUEST_HISTORY = 4_096


def _timestamp(moment: datetime) -> timestamp_pb2.Timestamp:
    value = timestamp_pb2.Timestamp()
    value.FromDatetime(moment)
    return value


@dataclass(slots=True)
class _SessionState:
    session_id: str
    expires_at: datetime
    image_encodings: frozenset[int]
    cue_modalities: frozenset[int]
    supports_cancellation: bool
    supports_supersession: bool
    max_width: int
    max_height: int
    max_frame_bytes: int
    max_in_flight: int
    target_frames_per_second: int
    result_deadline_seconds: float
    max_cues_per_result: int
    rate_tokens: float
    rate_updated_at: float
    in_flight: int = 0
    stream_history: OrderedDict[str, tuple[int, int]] = field(default_factory=OrderedDict)
    request_history: OrderedDict[str, None] = field(default_factory=OrderedDict)


class PerceptionService(pb_grpc.PerceptionServiceServicer):
    def __init__(
        self,
        config: ClusterConfig,
        pool: WorkerPool,
        logger: logging.Logger,
        *,
        identity_factory: Callable[[], str] | None = None,
        clock: Callable[[], datetime] | None = None,
        monotonic_clock: Callable[[], float] | None = None,
        session_ttl: timedelta = _DEFAULT_SESSION_TTL,
        max_sessions: int = _DEFAULT_MAX_SESSIONS,
        max_stream_history: int = _DEFAULT_STREAM_HISTORY,
        max_request_history: int = _DEFAULT_REQUEST_HISTORY,
        image_decoder: BoundedImageDecoder | None = None,
    ) -> None:
        if session_ttl <= timedelta(0):
            raise ValueError("session_ttl must be positive")
        if min(max_sessions, max_stream_history, max_request_history) <= 0:
            raise ValueError("session and history limits must be positive")
        self._config = config
        self._pool = pool
        self._logger = logger
        self._identity_factory = identity_factory or (lambda: uuid.uuid4().hex)
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._monotonic_clock = monotonic_clock or time.monotonic
        self._session_ttl = session_ttl
        self._max_sessions = max_sessions
        self._max_stream_history = max_stream_history
        self._max_request_history = max_request_history
        self._sessions: OrderedDict[str, _SessionState] = OrderedDict()
        self._session_lock = asyncio.Lock()
        self._preprocessor = FramePreprocessor(max_frame_bytes=config.max_frame_bytes)
        self._image_decoder = image_decoder or BoundedImageDecoder()

    @property
    def session_count(self) -> int:
        return len(self._sessions)

    @staticmethod
    def _bounded_client_limit(requested: int, server_limit: int) -> int:
        return min(requested, server_limit) if requested > 0 else server_limit

    def _purge_expired_sessions(self, now: datetime) -> None:
        expired = [
            session_id
            for session_id, state in self._sessions.items()
            if state.expires_at <= now and state.in_flight == 0
        ]
        for session_id in expired:
            del self._sessions[session_id]

    @staticmethod
    def _session_frame_error(frame: pb.FramePayload, state: _SessionState) -> ClusterError | None:
        if frame.image.encoding not in state.image_encodings:
            return ClusterError(pb.ERROR_CODE_INVALID_ARGUMENT, "image encoding was not negotiated")
        if frame.image.width > state.max_width or frame.image.height > state.max_height:
            return ClusterError(pb.ERROR_CODE_OVERSIZE, "image dimensions exceed negotiated limits")
        if len(frame.frame_data) > state.max_frame_bytes:
            return ClusterError(pb.ERROR_CODE_OVERSIZE, "frame payload exceeds negotiated limit")
        return None

    def _rate_error(self, state: _SessionState, now: float) -> ClusterError | None:
        elapsed = max(now - state.rate_updated_at, 0.0)
        capacity = float(state.target_frames_per_second)
        state.rate_tokens = min(capacity, state.rate_tokens + elapsed * state.target_frames_per_second)
        state.rate_updated_at = max(now, state.rate_updated_at)
        if state.rate_tokens >= 1.0:
            state.rate_tokens -= 1.0
            return None
        retry_after_ms = max(
            math.ceil((1.0 - state.rate_tokens) / state.target_frames_per_second * 1_000),
            1,
        )
        return ClusterError(
            pb.ERROR_CODE_OVERLOADED,
            "negotiated session frame rate exceeded",
            True,
            retry_after_ms,
        )

    def _record_admission(self, frame: pb.FramePayload, state: _SessionState) -> None:
        if frame.stream_id in state.stream_history:
            state.stream_history.move_to_end(frame.stream_id)
        elif len(state.stream_history) >= self._max_stream_history:
            state.stream_history.popitem(last=False)
        state.stream_history[frame.stream_id] = (frame.frame_id, frame.capture_monotonic_timestamp_ns)
        if len(state.request_history) >= self._max_request_history:
            state.request_history.popitem(last=False)
        state.request_history[frame.request_id] = None

    async def _reserve_frame(self, frame: pb.FramePayload) -> tuple[_SessionState | None, ClusterError | None]:
        now = self._clock()
        monotonic_now = self._monotonic_clock()
        async with self._session_lock:
            state = self._sessions.get(frame.session_id)
            if state is None:
                self._purge_expired_sessions(now)
                return None, ClusterError(pb.ERROR_CODE_STALE, "session is unknown or expired")
            if state.expires_at <= now:
                if state.in_flight == 0:
                    del self._sessions[frame.session_id]
                self._purge_expired_sessions(now)
                return None, ClusterError(pb.ERROR_CODE_STALE, "session is unknown or expired")
            self._purge_expired_sessions(now)
            negotiated_error = self._session_frame_error(frame, state)
            if negotiated_error is not None:
                return None, negotiated_error
            if frame.request_id in state.request_history:
                return None, ClusterError(pb.ERROR_CODE_STALE, "request identifier was already used")
            previous = state.stream_history.get(frame.stream_id)
            if previous is not None and (
                frame.frame_id <= previous[0] or frame.capture_monotonic_timestamp_ns <= previous[1]
            ):
                return None, ClusterError(pb.ERROR_CODE_STALE, "frame sequence is not increasing")
            if state.in_flight >= state.max_in_flight:
                return None, ClusterError(
                    pb.ERROR_CODE_OVERLOADED,
                    "negotiated session in-flight limit reached",
                    True,
                    25,
                )
            rate_error = self._rate_error(state, monotonic_now)
            if rate_error is not None:
                return None, rate_error
            self._record_admission(frame, state)
            state.in_flight += 1
            return state, None

    async def _release_session(self, state: _SessionState) -> None:
        async with self._session_lock:
            state.in_flight = max(state.in_flight - 1, 0)
            if state.in_flight == 0 and state.expires_at <= self._clock():
                if self._sessions.get(state.session_id) is state:
                    del self._sessions[state.session_id]

    @staticmethod
    def _bounded_result(result: pb.PerceptionResult, state: _SessionState) -> pb.PerceptionResult:
        bounded = pb.PerceptionResult()
        bounded.CopyFrom(result)
        del bounded.cues[state.max_cues_per_result :]
        retained_cues: list[pb.PerceptionCue] = []
        for cue in bounded.cues:
            if pb.CUE_MODALITY_EARCON not in state.cue_modalities:
                cue.ClearField("earcon")
            if pb.CUE_MODALITY_SPEECH not in state.cue_modalities:
                cue.ClearField("speech")
            if pb.CUE_MODALITY_HAPTIC not in state.cue_modalities:
                cue.ClearField("haptic")
            if not state.supports_cancellation:
                cue.ClearField("cancel")
            if not state.supports_supersession:
                cue.ClearField("supersede")
            has_delivery = cue.HasField("earcon") or cue.HasField("speech") or cue.HasField("haptic")
            is_control = cue.HasField("cancel") or cue.HasField("supersede")
            if has_delivery or is_control:
                retained_cues.append(cue)
        del bounded.cues[:]
        bounded.cues.extend(retained_cues)
        return bounded

    @staticmethod
    def _negotiation_error(code: int, message: str, *, retryable: bool = False) -> pb.NegotiateResponse:
        return pb.NegotiateResponse(
            error=pb.ErrorStatus(code=cast(pb.ErrorCode, code), message=message, retryable=retryable)
        )

    async def _store_session(
        self,
        state: _SessionState,
        *,
        now: datetime,
        context: grpc.aio.ServicerContext,
    ) -> bool:
        capacity_exhausted = False
        async with self._session_lock:
            self._purge_expired_sessions(now)
            collision = self._sessions.get(state.session_id)
            if collision is not None:
                capacity_exhausted = True
            if not capacity_exhausted and len(self._sessions) >= self._max_sessions:
                capacity_exhausted = True
            if not capacity_exhausted:
                self._sessions[state.session_id] = state
        if capacity_exhausted:
            await context.abort(grpc.StatusCode.RESOURCE_EXHAUSTED, "session capacity is exhausted")
            return False
        return True

    async def Negotiate(
        self,
        request: pb.NegotiateRequest,
        context: grpc.aio.ServicerContext,
    ) -> pb.NegotiateResponse:
        if not identifier_is_valid(request.client_instance_id) or (
            request.identity.session_id and not identifier_is_valid(request.identity.session_id)
        ):
            return self._negotiation_error(
                pb.ERROR_CODE_INVALID_ARGUMENT,
                "client and session identifiers must use the canonical opaque format",
            )
        compatible = [version for version in request.supported_versions if version.major == SERVER_VERSION.major]
        if not compatible:
            return self._negotiation_error(
                pb.ERROR_CODE_UNSUPPORTED_VERSION,
                "no compatible protocol major version",
            )
        selected = max(compatible, key=lambda version: (version.minor, version.patch))
        now = self._clock()
        expiry = now + self._session_ttl
        requested_encodings = set(request.capabilities.image_encodings)
        image_encodings = tuple(
            encoding
            for encoding in _SERVER_IMAGE_ENCODINGS
            if not requested_encodings or encoding in requested_encodings
        )
        requested_modalities = set(request.capabilities.cue_modalities)
        cue_modalities = tuple(
            modality
            for modality in _SERVER_CUE_MODALITIES
            if not requested_modalities or modality in requested_modalities
        )
        if not image_encodings:
            return self._negotiation_error(
                pb.ERROR_CODE_INVALID_ARGUMENT,
                "no compatible image encoding",
            )
        supports_cancellation = request.capabilities.supports_cancellation
        supports_supersession = request.capabilities.supports_supersession
        max_width = self._bounded_client_limit(request.capabilities.max_width, _MAX_WIDTH)
        max_height = self._bounded_client_limit(request.capabilities.max_height, _MAX_HEIGHT)
        max_frame_bytes = self._bounded_client_limit(
            request.capabilities.max_frame_bytes,
            self._config.max_frame_bytes,
        )
        session_id = self._identity_factory()
        if not identifier_is_valid(session_id):
            return self._negotiation_error(pb.ERROR_CODE_INTERNAL, "session identity generation failed")
        identity = pb.EphemeralIdentity(
            session_id=session_id,
            nonce=secrets.token_bytes(16),
            expires_at=_timestamp(expiry),
        )
        max_in_flight = min(max(request.requested_qos.max_in_flight, 1), self._config.queue_capacity)
        target_frames_per_second = min(
            max(request.requested_qos.target_frames_per_second, 1),
            _MAX_FRAMES_PER_SECOND,
        )
        requested_deadline_seconds = request.requested_qos.result_deadline.ToTimedelta().total_seconds()
        configured_deadline_seconds = self._config.worker_timeout_ms / 1000
        result_deadline_seconds = (
            min(requested_deadline_seconds, configured_deadline_seconds)
            if requested_deadline_seconds > 0
            else configured_deadline_seconds
        )
        max_cues_per_result = min(
            max(request.requested_qos.max_cues_per_result, 1),
            _MAX_CUES_PER_RESULT,
        )
        state = _SessionState(
            session_id=identity.session_id,
            expires_at=expiry,
            image_encodings=frozenset(image_encodings),
            cue_modalities=frozenset(cue_modalities),
            supports_cancellation=supports_cancellation,
            supports_supersession=supports_supersession,
            max_width=max_width,
            max_height=max_height,
            max_frame_bytes=max_frame_bytes,
            max_in_flight=max_in_flight,
            target_frames_per_second=target_frames_per_second,
            result_deadline_seconds=result_deadline_seconds,
            max_cues_per_result=max_cues_per_result,
            rate_tokens=float(target_frames_per_second),
            rate_updated_at=self._monotonic_clock(),
        )
        if not await self._store_session(state, now=now, context=context):
            return self._negotiation_error(
                pb.ERROR_CODE_OVERLOADED,
                "session capacity is exhausted",
                retryable=True,
            )
        response = pb.NegotiateResponse(
            selected_version=pb.ProtocolVersion(
                major=SERVER_VERSION.major,
                minor=min(selected.minor, SERVER_VERSION.minor),
                patch=min(selected.patch, SERVER_VERSION.patch),
            ),
            identity=identity,
            capabilities=pb.CapabilitySet(
                image_encodings=image_encodings,
                cue_modalities=cue_modalities,
                max_width=max_width,
                max_height=max_height,
                max_frame_bytes=max_frame_bytes,
                supports_cancellation=supports_cancellation,
                supports_supersession=supports_supersession,
                supports_pose=True,
                supports_intrinsics=True,
            ),
            accepted_qos=pb.QualityOfService(
                max_in_flight=max_in_flight,
                target_frames_per_second=target_frames_per_second,
                result_deadline=duration_pb2.Duration(
                    seconds=int(result_deadline_seconds),
                    nanos=int(result_deadline_seconds % 1 * 1_000_000_000),
                ),
                allow_frame_drop=False,
                max_cues_per_result=max_cues_per_result,
            ),
        )
        event(
            self._logger,
            "negotiated",
            client_label=opaque_label(request.client_instance_id),
            session_label=opaque_label(identity.session_id),
        )
        return response

    @staticmethod
    def _error_result(frame: pb.FramePayload, error: ClusterError | FrameValidationError) -> pb.PerceptionResult:
        retryable = error.retryable if isinstance(error, ClusterError) else False
        retry_after = error.retry_after_ms if isinstance(error, ClusterError) else 0
        request_id = frame.request_id if identifier_is_valid(frame.request_id) else ""
        session_id = frame.session_id if identifier_is_valid(frame.session_id) else ""
        stream_id = frame.stream_id if identifier_is_valid(frame.stream_id) else ""
        return pb.PerceptionResult(
            result_id=f"error-{opaque_label(frame.request_id)}",
            request_id=request_id,
            session_id=session_id,
            stream_id=stream_id,
            frame_id=frame.frame_id,
            capture_monotonic_timestamp_ns=frame.capture_monotonic_timestamp_ns,
            completed_monotonic_timestamp_ns=time.monotonic_ns(),
            error=pb.ErrorStatus(
                code=cast(pb.ErrorCode, error.code),
                message=str(error),
                retryable=retryable,
                retry_after_ms=retry_after,
                correlation_id=opaque_label(request_id) if request_id else "",
            ),
        )

    async def ProcessFrame(
        self,
        request: pb.FramePayload,
        context: grpc.aio.ServicerContext,
    ) -> pb.PerceptionResult:
        if request.ByteSize() > self._config.max_receive_bytes:
            return self._error_result(
                request,
                ClusterError(pb.ERROR_CODE_OVERSIZE, "serialized request exceeds message limit"),
            )
        try:
            self._preprocessor.validate(request)
        except FrameValidationError as error:
            event(
                self._logger,
                "frame_rejected",
                request_label=opaque_label(request.request_id),
                frame_id=request.frame_id,
                reason=str(error),
            )
            return self._error_result(request, error)
        session, session_error = await self._reserve_frame(request)
        if session_error is not None or session is None:
            admission_error = session_error or ClusterError(pb.ERROR_CODE_STALE, "session is unknown or expired")
            event(
                self._logger,
                "frame_rejected",
                request_label=opaque_label(request.request_id),
                frame_id=request.frame_id,
                reason=str(admission_error),
            )
            return self._error_result(request, admission_error)
        try:
            try:
                self._image_decoder.validate(request)
            except FrameValidationError as error:
                event(
                    self._logger,
                    "frame_rejected",
                    request_label=opaque_label(request.request_id),
                    frame_id=request.frame_id,
                    reason=str(error),
                )
                return self._error_result(request, error)
            requested_deadline_seconds = request.processing_deadline.ToTimedelta().total_seconds()
            deadline_seconds = session.result_deadline_seconds
            if requested_deadline_seconds > 0:
                deadline_seconds = min(deadline_seconds, requested_deadline_seconds)
            remaining = context.time_remaining()
            if remaining is not None:
                deadline_seconds = min(deadline_seconds, max(remaining, 0.001))
            try:
                result = await self._pool.submit(request, timeout_seconds=deadline_seconds)
                return self._bounded_result(result, session)
            except asyncio.CancelledError:
                event(
                    self._logger,
                    "request_cancelled",
                    request_label=opaque_label(request.request_id),
                    frame_id=request.frame_id,
                )
                raise
            except ClusterError as error:
                event(
                    self._logger,
                    "worker_error",
                    request_label=opaque_label(request.request_id),
                    frame_id=request.frame_id,
                    code=pb.ErrorCode.Name(error.code),
                )
                return self._error_result(request, error)
        finally:
            await self._release_session(session)

    async def Health(
        self,
        request: pb.HealthRequest,
        context: grpc.aio.ServicerContext,
    ) -> pb.HealthResponse:
        snapshots = self._pool.snapshots()
        healthy_count = sum(snapshot.healthy for snapshot in snapshots)
        if healthy_count == len(snapshots) and self._pool.running:
            status = pb.SERVING_STATUS_SERVING
        elif healthy_count and self._pool.running:
            status = pb.SERVING_STATUS_DEGRADED
        else:
            status = pb.SERVING_STATUS_NOT_SERVING
        workers = []
        if request.include_workers:
            workers = [
                pb.WorkerHealth(
                    worker_id=snapshot.worker_id,
                    device=snapshot.device,
                    healthy=snapshot.healthy,
                    consecutive_failures=snapshot.consecutive_failures,
                    queue_depth=self._pool.queue_depth,
                )
                for snapshot in snapshots
            ]
        return pb.HealthResponse(
            status=status,
            protocol_version=SERVER_VERSION,
            workers=workers,
            queue_depth=self._pool.queue_depth,
            queue_capacity=self._pool.queue_capacity,
        )


def create_grpc_server(
    config: ClusterConfig,
    pool: WorkerPool,
    logger: logging.Logger,
) -> tuple[grpc.aio.Server, int]:
    server = grpc.aio.server(
        options=(
            ("grpc.max_receive_message_length", config.max_receive_bytes),
            ("grpc.max_send_message_length", config.max_send_bytes),
        )
    )
    pb_grpc.add_PerceptionServiceServicer_to_server(PerceptionService(config, pool, logger), server)
    if config.insecure:
        port = server.add_insecure_port(config.bind_target)
    else:
        certificate = config.tls_certificate_file.read_bytes()  # type: ignore[union-attr]
        private_key = config.tls_private_key_file.read_bytes()  # type: ignore[union-attr]
        credentials = grpc.ssl_server_credentials(((private_key, certificate),))
        port = server.add_secure_port(config.bind_target, credentials)
    if port == 0:
        raise RuntimeError("gRPC server could not bind")
    return server, port
