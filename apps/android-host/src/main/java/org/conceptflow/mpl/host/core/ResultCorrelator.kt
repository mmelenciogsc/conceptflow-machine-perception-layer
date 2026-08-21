// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.core

import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.PerceptionResult
import java.util.LinkedHashMap

enum class CorrelationRejection {
    UNKNOWN_REQUEST,
    SESSION_MISMATCH,
    STREAM_MISMATCH,
    FRAME_MISMATCH,
    CAPTURE_TIMESTAMP_MISMATCH,
    CANCELLED,
    STALE,
    OUT_OF_ORDER,
}

sealed interface CorrelationResult {
    data class Accepted(val result: PerceptionResult) : CorrelationResult
    data class Rejected(val reason: CorrelationRejection) : CorrelationResult
}

private data class PendingFrame(
    val sessionId: String,
    val streamId: String,
    val frameId: Long,
    val captureMonotonicTimestampNs: Long,
    val deadlineNanos: Long,
)

private data class StreamKey(
    val sessionId: String,
    val streamId: String,
)

class ResultCorrelator(
    private val clock: HostClock,
    private val maximumPending: Int = 8,
    private val defaultTtlMillis: Long = 1_500L,
    private val maximumAcceptedStreamHistory: Int = maximumPending * 2,
) {
    private val pending = LinkedHashMap<String, PendingFrame>()
    private val cancelled = LinkedHashSet<String>()
    private val latestAcceptedFrameIds = LinkedHashMap<StreamKey, Long>(16, 0.75f, true)

    init {
        require(maximumPending in 1..128)
        require(defaultTtlMillis in 1L..60_000L)
        require(maximumAcceptedStreamHistory in maximumPending..1_024)
    }

    @Synchronized
    fun register(frame: FramePayload, ttlMillis: Long = defaultTtlMillis): String? {
        require(ttlMillis in 1L..60_000L)
        require(frame.requestId !in pending) { "requestId is already pending" }
        var evicted: String? = null
        if (pending.size == maximumPending) {
            evicted = pending.entries.first().key
            pending.remove(evicted)
        }
        pending[frame.requestId] = PendingFrame(
            sessionId = frame.sessionId,
            streamId = frame.streamId,
            frameId = frame.frameId,
            captureMonotonicTimestampNs = frame.captureMonotonicTimestampNs,
            deadlineNanos = clock.nowNanos() + ttlMillis * 1_000_000L,
        )
        cancelled.remove(frame.requestId)
        return evicted
    }

    @Synchronized
    fun cancel(requestId: String): Boolean {
        val removed = pending.remove(requestId) != null
        if (removed) {
            cancelled += requestId
            while (cancelled.size > maximumPending * 2) cancelled.remove(cancelled.first())
        }
        return removed
    }

    @Synchronized
    fun correlate(result: PerceptionResult): CorrelationResult {
        if (result.requestId in cancelled) return CorrelationResult.Rejected(CorrelationRejection.CANCELLED)
        val expected = pending.remove(result.requestId)
            ?: return CorrelationResult.Rejected(CorrelationRejection.UNKNOWN_REQUEST)
        val streamKey = StreamKey(expected.sessionId, expected.streamId)
        val rejection = when {
            result.sessionId != expected.sessionId -> CorrelationRejection.SESSION_MISMATCH
            result.streamId != expected.streamId -> CorrelationRejection.STREAM_MISMATCH
            result.frameId != expected.frameId -> CorrelationRejection.FRAME_MISMATCH
            result.captureMonotonicTimestampNs != expected.captureMonotonicTimestampNs ->
                CorrelationRejection.CAPTURE_TIMESTAMP_MISMATCH
            clock.nowNanos() >= expected.deadlineNanos -> CorrelationRejection.STALE
            result.frameId <= (latestAcceptedFrameIds[streamKey] ?: Long.MIN_VALUE) -> CorrelationRejection.OUT_OF_ORDER
            else -> null
        }
        if (rejection != null) {
            return CorrelationResult.Rejected(rejection)
        }
        if (streamKey !in latestAcceptedFrameIds && latestAcceptedFrameIds.size == maximumAcceptedStreamHistory) {
            val streamsWithPendingFrames = pending.values
                .mapTo(mutableSetOf()) { StreamKey(it.sessionId, it.streamId) }
            val evicted = latestAcceptedFrameIds.keys.first { it !in streamsWithPendingFrames }
            latestAcceptedFrameIds.remove(evicted)
        }
        latestAcceptedFrameIds[streamKey] = result.frameId
        return CorrelationResult.Accepted(result)
    }

    @Synchronized
    fun pendingCount(): Int = pending.size

    @Synchronized
    fun acceptedStreamHistoryCount(): Int = latestAcceptedFrameIds.size
}
