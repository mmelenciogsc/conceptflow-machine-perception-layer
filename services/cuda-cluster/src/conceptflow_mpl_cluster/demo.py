# SPDX-License-Identifier: MIT OR Apache-2.0
"""Deterministic synthetic end-to-end validation over a real gRPC boundary."""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import time

import grpc
from google.protobuf import duration_pb2

from conceptflow.mpl.v1 import perception_pb2 as pb
from conceptflow.mpl.v1 import perception_pb2_grpc as pb_grpc
from conceptflow_mpl_host.correlation import ResultCorrelator
from conceptflow_mpl_host.latency import LatencyTracker
from conceptflow_mpl_host.pipeline import HostPipeline
from conceptflow_mpl_host.preprocessing import FramePreprocessor
from conceptflow_mpl_host.renderer import InspectableCueRenderer
from conceptflow_mpl_host.routing import RouteContext
from conceptflow_mpl_host.scheduler import CueScheduler, Verbosity

from .config import ClusterConfig, RuntimeProfile
from .device import ComputeDevice
from .pool import WorkerPool
from .service import create_grpc_server
from .worker import DeterministicMockWorker


class InvariantFailure(RuntimeError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise InvariantFailure(message)


def synthetic_frame(
    frame_id: int,
    *,
    capture_ns: int,
    deadline_ms: int = 100,
    session_id: str = "synthetic-session",
) -> pb.FramePayload:
    data = bytes(((frame_id + offset) % 251 for offset in range(48)))
    return pb.FramePayload(
        request_id=f"request-{frame_id}",
        session_id=session_id,
        stream_id="synthetic-glasses-camera",
        frame_id=frame_id,
        capture_monotonic_timestamp_ns=capture_ns,
        image=pb.ImageDescriptor(
            width=4,
            height=4,
            row_stride_bytes=12,
            encoding=pb.IMAGE_ENCODING_RGB8,
            media_type="application/x-conceptflow-rgb8",
            payload_bytes=len(data),
            sha256=hashlib.sha256(data).digest(),
        ),
        frame_data=data,
        processing_deadline=duration_pb2.Duration(
            seconds=deadline_ms // 1000,
            nanos=(deadline_ms % 1000) * 1_000_000,
        ),
        synthetic=True,
    )


def _logger() -> logging.Logger:
    logger = logging.getLogger("conceptflow.mpl.demo")
    logger.handlers.clear()
    logger.addHandler(logging.NullHandler())
    logger.setLevel(logging.CRITICAL)
    logger.propagate = False
    return logger


async def run_demo() -> dict[str, object]:
    base_ns = time.monotonic_ns() - 1_000_000_000
    worker = DeterministicMockWorker(
        worker_id="synthetic-demo-worker",
        device=ComputeDevice("cpu", "0", "deterministic demo CPU"),
        delay_by_frame_id={1001: 0.2, 1002: 0.2, **{frame_id: 0.08 for frame_id in range(1100, 1120)}},
        failing_frame_ids=frozenset({1003}),
    )
    config = ClusterConfig(
        profile=RuntimeProfile.TEST,
        bind_host="127.0.0.1",
        bind_port=0,
        insecure=True,
        max_receive_bytes=1_048_576,
        max_send_bytes=1_048_576,
        max_frame_bytes=65_536,
        queue_capacity=1,
        runner_count=1,
        worker_timeout_ms=60,
        worker_failure_threshold=3,
        device_preference="cpu",
    )
    pool = WorkerPool(
        [worker],
        queue_capacity=config.queue_capacity,
        runner_count=config.runner_count,
        timeout_ms=config.worker_timeout_ms,
        failure_threshold=config.worker_failure_threshold,
        shutdown_timeout_ms=config.shutdown_timeout_ms,
    )
    await pool.start()
    server, port = create_grpc_server(config, pool, _logger())
    await server.start()
    target = f"127.0.0.1:{port}"
    events: dict[str, bool | int] = {}
    channel: grpc.aio.Channel | None = None
    try:
        channel = grpc.aio.insecure_channel(target)
        stub = pb_grpc.PerceptionServiceStub(channel)
        negotiation = pb.NegotiateRequest(
            client_instance_id="synthetic-glasses",
            supported_versions=[pb.ProtocolVersion(major=1, minor=0, patch=0)],
            capabilities=pb.CapabilitySet(
                image_encodings=[pb.IMAGE_ENCODING_RGB8],
                cue_modalities=[pb.CUE_MODALITY_EARCON, pb.CUE_MODALITY_SPEECH, pb.CUE_MODALITY_HAPTIC],
                max_width=4,
                max_height=4,
                max_frame_bytes=65_536,
                supports_cancellation=True,
                supports_supersession=True,
            ),
            requested_qos=pb.QualityOfService(
                max_in_flight=1,
                target_frames_per_second=60,
                result_deadline=duration_pb2.Duration(nanos=60_000_000),
                allow_frame_drop=True,
                max_cues_per_result=4,
            ),
        )
        first_negotiation = await stub.Negotiate(negotiation)
        _require(first_negotiation.selected_version.major == 1, "initial negotiation failed")
        await channel.close()
        channel = grpc.aio.insecure_channel(target)
        stub = pb_grpc.PerceptionServiceStub(channel)
        second_negotiation = await stub.Negotiate(negotiation)
        _require(
            second_negotiation.selected_version.major == 1
            and second_negotiation.identity.session_id != first_negotiation.identity.session_id,
            "reconnect did not establish a fresh ephemeral identity",
        )
        events["reconnect"] = True
        active_session_id = second_negotiation.identity.session_id

        cancellation_call = stub.ProcessFrame(
            synthetic_frame(1001, capture_ns=base_ns + 1001, session_id=active_session_id),
            timeout=1.0,
        )
        await asyncio.sleep(0.01)
        cancellation_call.cancel()
        try:
            await cancellation_call
        except asyncio.CancelledError:
            events["cancellation"] = True
        await asyncio.sleep(0.01)
        _require(bool(events.get("cancellation", False)), "client cancellation was not observed")
        _require(1001 in worker.cancelled_frame_ids, "worker cancellation was not propagated")

        timeout_result = await stub.ProcessFrame(
            synthetic_frame(1002, capture_ns=base_ns + 1002, deadline_ms=10, session_id=active_session_id),
            timeout=1.0,
        )
        _require(timeout_result.error.code == pb.ERROR_CODE_DEADLINE_EXCEEDED, "timeout did not propagate")
        events["timeout"] = True

        failure_result = await stub.ProcessFrame(
            synthetic_frame(1003, capture_ns=base_ns + 1003, session_id=active_session_id),
            timeout=1.0,
        )
        _require(failure_result.error.code == pb.ERROR_CODE_WORKER_UNAVAILABLE, "worker failure did not propagate")
        events["error_propagation"] = True

        stale_frame = synthetic_frame(1004, capture_ns=base_ns + 1004, session_id=active_session_id)
        stale_result = await stub.ProcessFrame(stale_frame, timeout=1.0)
        stale_correlator = ResultCorrelator(max_result_age_ms=10)
        stale_correlator.register(stale_frame, now_ns=base_ns)
        stale_decision = stale_correlator.accept(stale_result, now_ns=base_ns + 20_000_000)
        _require(not stale_decision.accepted, "stale result was accepted")
        events["stale_rejection"] = True

        pressure_calls = [
            stub.ProcessFrame(
                synthetic_frame(frame_id, capture_ns=base_ns + frame_id, session_id=active_session_id),
                timeout=1.0,
            )
            for frame_id in range(1100, 1120)
        ]
        pressure_results = await asyncio.gather(*pressure_calls)
        overload_count = sum(result.error.code == pb.ERROR_CODE_OVERLOADED for result in pressure_results)
        _require(overload_count > 0, "bounded queue did not report overload")
        events["backpressure"] = overload_count

        latency = LatencyTracker()
        scheduler = CueScheduler(capacity=128, cooldown_ms=0, verbosity=Verbosity.STANDARD)
        pipeline = HostPipeline(
            preprocessor=FramePreprocessor(max_frame_bytes=config.max_frame_bytes),
            correlator=ResultCorrelator(max_pending=128, max_result_age_ms=2_000),
            scheduler=scheduler,
            renderer=InspectableCueRenderer(),
            latency=latency,
            cluster_processor=stub.ProcessFrame,
        )
        rendered: str | None = None
        for frame_id in range(2_000, 2_100):
            await asyncio.sleep(1 / second_negotiation.accepted_qos.target_frames_per_second)
            capture_ns = time.monotonic_ns() - 1_000_000
            outcome = await pipeline.process(
                synthetic_frame(frame_id, capture_ns=capture_ns, session_id=active_session_id),
                route_context=RouteContext(local_available=False, cluster_available=True),
            )
            _require(outcome.correlation.accepted, "successful result failed correlation")
            _require(outcome.result.error.code == pb.ERROR_CODE_UNSPECIFIED, "successful flow returned an error")
            if outcome.rendered is not None:
                rendered = outcome.rendered
        _require(rendered is not None and '"assistive_only":true' in rendered, "cue was not rendered")
        assert rendered is not None
        latency_summary = latency.summary()
        _require(
            all("p99_ms" in stage for stage in latency_summary.values()),
            "p99 was not emitted after sufficient samples",
        )
        events["full_slice"] = True
        health = await stub.Health(pb.HealthRequest(include_workers=True))
        _require(health.status == pb.SERVING_STATUS_SERVING, "service did not recover to serving")
        return {
            "events": events,
            "health": pb.ServingStatus.Name(health.status),
            "latency_ms": latency_summary,
            "rendered_cue": json.loads(rendered),
            "synthetic": True,
        }
    finally:
        if channel is not None:
            await channel.close()
        await server.stop(grace=0.2)
        await pool.close()


def main() -> int:
    try:
        report = asyncio.run(run_demo())
    except Exception as error:
        print(json.dumps({"error": str(error), "ok": False}, sort_keys=True))
        return 1
    print(json.dumps({"ok": True, **report}, sort_keys=True, separators=(",", ":")))
    return 0
