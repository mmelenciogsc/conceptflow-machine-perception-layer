// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import org.conceptflow.mpl.v1.LiveTransportLane

data class LaneMetricsSnapshot(
    val sentMessages: Long,
    val sentBytes: Long,
    val receivedMessages: Long,
    val receivedBytes: Long,
    val droppedMessages: Long,
    val rejectedMessages: Long,
    val queueHighWater: Long,
)

data class TransportMetricsSnapshot(
    val realtimeControl: LaneMetricsSnapshot,
    val camera: LaneMetricsSnapshot,
    val authenticationFailures: Long,
    val framingFailures: Long,
    val livenessTimeouts: Long,
    val reconnects: Long,
    val disconnects: Long,
    val closeEvidence: LiveLinkCloseEvidence,
)

/** Aggregate-only metrics. No method accepts payloads, addresses, identities, or secrets. */
class SanitizedTransportMetrics {
    private val realtime = LaneCounters()
    private val camera = LaneCounters()
    private val authenticationFailures = AtomicLong()
    private val framingFailures = AtomicLong()
    private val livenessTimeouts = AtomicLong()
    private val reconnects = AtomicLong()
    private val disconnects = AtomicLong()
    private val closeEvidence = AtomicReference(LiveLinkCloseEvidence())

    fun recordSent(lane: LiveTransportLane, bytes: Int) = counters(lane).recordSent(bytes)
    fun recordReceived(lane: LiveTransportLane, bytes: Int) = counters(lane).recordReceived(bytes)
    fun recordDropped(lane: LiveTransportLane, count: Long = 1L) = counters(lane).addDropped(count)
    fun recordRejected(lane: LiveTransportLane, count: Long = 1L) = counters(lane).addRejected(count)
    fun recordQueueDepth(lane: LiveTransportLane, depth: Int) = counters(lane).recordQueueDepth(depth)
    fun recordAuthenticationFailure() = authenticationFailures.incrementAndGet()
    fun recordFramingFailure() = framingFailures.incrementAndGet()
    fun recordLivenessTimeout() = livenessTimeouts.incrementAndGet()
    fun recordReconnect() = reconnects.incrementAndGet()
    fun recordDisconnect() = disconnects.incrementAndGet()
    internal fun recordClientClose(outcome: InitiatedSessionCloseOutcome) {
        closeEvidence.set(
            LiveLinkCloseEvidence(
                clientCloseAttempted = true,
                clientCloseRequestWritten = outcome.closeRequestWritten,
                clientWritersDrained = outcome.writersDrained,
                clientAcknowledgementReceived = outcome.acknowledgementReceived,
                clientRequestFailure = outcome.requestFailure,
            ),
        )
    }

    internal fun recordHostClose(authenticatedCloseSeen: Boolean, failureLane: LiveLinkFailureLane) {
        closeEvidence.set(
            LiveLinkCloseEvidence(
                hostAuthenticatedCloseSeen = authenticatedCloseSeen,
                hostFailureLane = failureLane,
            ),
        )
    }

    fun snapshot(): TransportMetricsSnapshot = TransportMetricsSnapshot(
        realtimeControl = realtime.snapshot(),
        camera = camera.snapshot(),
        authenticationFailures = authenticationFailures.get(),
        framingFailures = framingFailures.get(),
        livenessTimeouts = livenessTimeouts.get(),
        reconnects = reconnects.get(),
        disconnects = disconnects.get(),
        closeEvidence = closeEvidence.get(),
    )

    private fun counters(lane: LiveTransportLane): LaneCounters = when (lane) {
        LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL -> realtime
        LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA -> camera
        else -> throw IllegalArgumentException("unsupported transport lane")
    }

    private class LaneCounters {
        private val sentMessages = AtomicLong()
        private val sentBytes = AtomicLong()
        private val receivedMessages = AtomicLong()
        private val receivedBytes = AtomicLong()
        private val droppedMessages = AtomicLong()
        private val rejectedMessages = AtomicLong()
        private val queueHighWater = AtomicLong()

        fun recordSent(bytes: Int) {
            require(bytes >= 0) { "bytes must be non-negative" }
            sentMessages.incrementAndGet()
            sentBytes.addAndGet(bytes.toLong())
        }

        fun recordReceived(bytes: Int) {
            require(bytes >= 0) { "bytes must be non-negative" }
            receivedMessages.incrementAndGet()
            receivedBytes.addAndGet(bytes.toLong())
        }

        fun addDropped(count: Long) {
            require(count >= 0) { "count must be non-negative" }
            droppedMessages.addAndGet(count)
        }

        fun addRejected(count: Long) {
            require(count >= 0) { "count must be non-negative" }
            rejectedMessages.addAndGet(count)
        }

        fun recordQueueDepth(depth: Int) {
            require(depth >= 0) { "depth must be non-negative" }
            var observed = queueHighWater.get()
            while (depth.toLong() > observed && !queueHighWater.compareAndSet(observed, depth.toLong())) {
                observed = queueHighWater.get()
            }
        }

        fun snapshot(): LaneMetricsSnapshot = LaneMetricsSnapshot(
            sentMessages.get(),
            sentBytes.get(),
            receivedMessages.get(),
            receivedBytes.get(),
            droppedMessages.get(),
            rejectedMessages.get(),
            queueHighWater.get(),
        )
    }
}
