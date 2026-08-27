// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.v1.FramePayload

data class CameraTransformSnapshot(
    val accepted: Long,
    val completed: Long,
    val replacedBeforeTransform: Long,
    val failed: Long,
    val latency: CameraTransformLatencySnapshot = CameraTransformLatencySnapshot(),
)

data class CameraTransformLatencySnapshot(
    val samples: Long = 0L,
    val p50Nanos: Long = 0L,
    val p95Nanos: Long = 0L,
    val p99Nanos: Long = 0L,
    val maximumNanos: Long = 0L,
)

/** Bounded asynchronous handoff between the validated camera gate and network publication. */
interface CameraFrameTransformer : AutoCloseable {
    fun offer(frame: FramePayload, onReady: (FramePayload) -> Unit, onFailure: () -> Unit): Boolean
    fun snapshot(): CameraTransformSnapshot
}
