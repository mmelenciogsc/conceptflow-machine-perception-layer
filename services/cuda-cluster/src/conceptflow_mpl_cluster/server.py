# SPDX-License-Identifier: MIT OR Apache-2.0
"""Command-line entry point for the bounded async cluster service."""

from __future__ import annotations

import asyncio
import sys

from .config import ClusterConfig, ConfigError, RuntimeProfile
from .device import discover_devices
from .logging import configure_json_logging, event
from .pool import WorkerPool
from .service import create_grpc_server
from .worker import DeterministicMockWorker


async def run() -> None:
    config = ClusterConfig.from_env()
    if config.profile == RuntimeProfile.PRODUCTION:
        raise ConfigError("production requires a registered non-synthetic worker implementation")
    logger = configure_json_logging()
    devices = discover_devices(config.device_preference, allow_cpu_fallback=config.allow_cpu_fallback)
    workers = [
        DeterministicMockWorker(worker_id=f"synthetic-{index}", device=device) for index, device in enumerate(devices)
    ]
    pool = WorkerPool(
        workers,
        queue_capacity=config.queue_capacity,
        runner_count=config.runner_count,
        timeout_ms=config.worker_timeout_ms,
        failure_threshold=config.worker_failure_threshold,
        shutdown_timeout_ms=config.shutdown_timeout_ms,
    )
    await pool.start()
    server, port = create_grpc_server(config, pool, logger)
    await server.start()
    event(logger, "server_started", bind_host=config.bind_host, bind_port=port, profile=config.profile.value)
    try:
        await server.wait_for_termination()
    finally:
        await server.stop(grace=config.shutdown_timeout_ms / 1000)
        await pool.close()


def main() -> int:
    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
