// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import javax.crypto.SecretKey
import org.conceptflow.mpl.v1.LiveLinkControl
import org.conceptflow.mpl.v1.LiveLinkEnvelope
import org.conceptflow.mpl.v1.LiveTransportLane

/** One state-locked liveness observation, optionally including a reserved outbound keepalive. */
internal data class LiveLivenessDecision(
    val status: LivenessStatus,
    val sampledAtNs: Long,
    val keepaliveNonce: Long? = null,
) {
    init {
        require(sampledAtNs >= 0L) { "sampledAtNs must be non-negative" }
        require(keepaliveNonce == null || status == LivenessStatus.KEEPALIVE_DUE) {
            "keepalive nonce requires KEEPALIVE_DUE status"
        }
    }
}

/** Owns all state that must be discarded at a live-link reconnect boundary. */
class LiveConnectionState(
    val metrics: SanitizedTransportMetrics = SanitizedTransportMetrics(),
    private val clockSynchronizer: MinRttClockSynchronizer = MinRttClockSynchronizer(),
    private val clockNormalizer: RemoteMonotonicClockNormalizer = RemoteMonotonicClockNormalizer(),
    private val liveness: LivenessMonitor = LivenessMonitor(),
) {
    private var sequenceGuard: PerLaneSequenceGuard? = null
    private val sequenceAllocator = PerLaneSequenceAllocator()
    private var ticketAuthority: CameraLaneTicketAuthority? = null
    private var currentBinding: LiveSessionBinding? = null
    private var cameraLaneAuthenticated = false
    private var livenessTimeoutRecorded = false
    private var generation = 0L

    @Synchronized
    fun reconnect(binding: LiveSessionBinding, ticketKey: SecretKey, nowNs: Long) {
        require(nowNs >= 0) { "nowNs must be non-negative" }
        val isReconnect = generation > 0
        clearConnectionState()
        sequenceGuard = PerLaneSequenceGuard(binding)
        ticketAuthority = CameraLaneTicketAuthority(ticketKey)
        currentBinding = binding
        liveness.connect(nowNs)
        generation = Math.addExact(generation, 1L)
        if (isReconnect) metrics.recordReconnect()
    }

    @Synchronized
    fun reconnectWithoutTicketAuthority(binding: LiveSessionBinding, nowNs: Long) {
        require(nowNs >= 0) { "nowNs must be non-negative" }
        val isReconnect = generation > 0
        clearConnectionState()
        sequenceGuard = PerLaneSequenceGuard(binding)
        currentBinding = binding
        liveness.connect(nowNs)
        generation = Math.addExact(generation, 1L)
        if (isReconnect) metrics.recordReconnect()
    }

    @Synchronized
    fun disconnect() {
        if (sequenceGuard != null) metrics.recordDisconnect()
        clearConnectionState()
    }

    @Synchronized
    fun accept(envelope: LiveLinkEnvelope) {
        val guard = sequenceGuard ?: throw IllegalStateException("link is disconnected")
        try {
            if (envelope.lane == LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA) {
                if (envelope.control.payloadCase == LiveLinkControl.PayloadCase.LANE_OPEN_REQUEST ||
                    (envelope.payloadCase == LiveLinkEnvelope.PayloadCase.SENSOR && !cameraLaneAuthenticated)
                ) {
                    throw LaneProtocolException(LaneProtocolFailure.CAMERA_LANE_UNAUTHENTICATED)
                }
            }
            guard.accept(envelope)
            if (envelope.lane == LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA &&
                envelope.control.payloadCase == LiveLinkControl.PayloadCase.LANE_OPEN_RESPONSE &&
                envelope.control.laneOpenResponse.accepted
            ) {
                cameraLaneAuthenticated = true
            }
        } catch (error: LaneProtocolException) {
            if (envelope.lane == LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL ||
                envelope.lane == LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA
            ) {
                metrics.recordRejected(envelope.lane)
            }
            throw error
        }
    }

    /**
     * Validates an inbound record and records its receive time as one state transaction.
     * The clock is deliberately sampled while holding this object's monitor: camera and realtime
     * readers cannot otherwise commit an older, pre-lock timestamp after a newer lane has updated
     * liveness. The returned value is the authoritative receive timestamp for normalization.
     */
    @Synchronized
    internal fun acceptInboundAtomic(
        envelope: LiveLinkEnvelope,
        clock: MonotonicTimeSource,
    ): Long {
        accept(envelope)
        val receiveNs = clock.nowNs()
        if (envelope.payloadCase == LiveLinkEnvelope.PayloadCase.CONTROL &&
            envelope.control.payloadCase == LiveLinkControl.PayloadCase.KEEPALIVE &&
            envelope.control.keepalive.response
        ) {
            // Validate and clear the outstanding nonce in the same transaction as sequence
            // admission and receive-time sampling. A camera reader cannot advance liveness
            // between these operations and make this timestamp appear to move backwards.
            liveness.onKeepaliveResponse(envelope.control.keepalive.nonce, receiveNs)
        } else {
            liveness.onInbound(receiveNs)
        }
        return receiveNs
    }

    /** Host-side atomic admission for the first camera-lane record. Failure is terminal for the connection. */
    @Synchronized
    fun acceptCameraLaneOpen(envelope: LiveLinkEnvelope, nowNs: Long) {
        val guard = sequenceGuard ?: throw IllegalStateException("link is disconnected")
        if (envelope.lane != LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA ||
            envelope.payloadCase != LiveLinkEnvelope.PayloadCase.CONTROL ||
            envelope.control.payloadCase != LiveLinkControl.PayloadCase.LANE_OPEN_REQUEST
        ) {
            metrics.recordRejected(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA)
            throw LaneProtocolException(LaneProtocolFailure.CAMERA_LANE_UNAUTHENTICATED)
        }
        val ticket = envelope.control.laneOpenRequest.laneTicket.toByteArray()
        try {
            requireTicketAuthority().consume(ticket, requireBinding(), nowNs)
            guard.accept(envelope)
            cameraLaneAuthenticated = true
        } catch (error: CameraTicketException) {
            metrics.recordAuthenticationFailure()
            throw error
        } catch (error: LaneProtocolException) {
            metrics.recordRejected(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA)
            throw error
        }
    }

    /** Atomically samples arrival time, consumes the ticket, advances sequence, and updates liveness. */
    @Synchronized
    internal fun acceptCameraLaneOpenAtomic(
        envelope: LiveLinkEnvelope,
        clock: MonotonicTimeSource,
    ): Long {
        val receiveNs = clock.nowNs()
        acceptCameraLaneOpen(envelope, receiveNs)
        liveness.onInbound(receiveNs)
        return receiveNs
    }

    @Synchronized
    fun takeNextSequence(lane: LiveTransportLane): Long {
        if (sequenceGuard == null) throw IllegalStateException("link is disconnected")
        return sequenceAllocator.take(lane)
    }

    @Synchronized
    fun issueCameraTicket(nowNs: Long, lifetimeNs: Long): ByteArray {
        val binding = requireBinding()
        return requireTicketAuthority().issue(binding, nowNs, lifetimeNs)
    }

    @Synchronized
    internal fun consumeCameraTicket(ticket: ByteArray, nowNs: Long) {
        val binding = requireBinding()
        try {
            requireTicketAuthority().consume(ticket, binding, nowNs)
        } catch (error: CameraTicketException) {
            metrics.recordAuthenticationFailure()
            throw error
        }
    }

    @Synchronized
    fun beginClockRound() = clockSynchronizer.beginRound()

    @Synchronized
    fun addClockProbe(probe: FourTimestampClockProbe): ClockOffsetSample = clockSynchronizer.add(probe)

    @Synchronized
    fun commitClockRound(installedHostMonotonicNs: Long = 0L): ClockOffsetEstimate =
        clockSynchronizer.commitBest().also { clockNormalizer.install(it, installedHostMonotonicNs) }

    @Synchronized
    fun normalize(stream: RemoteClockStream, remoteNs: Long): NormalizedMonotonicTimestamp =
        clockNormalizer.normalize(stream, remoteNs)

    @Synchronized
    fun currentClockEstimate(): ClockOffsetEstimate? = clockSynchronizer.currentEstimate()

    @Synchronized
    fun pollLiveness(nowNs: Long): LivenessStatus = liveness.poll(nowNs).also {
        if (it == LivenessStatus.TIMED_OUT && !livenessTimeoutRecorded) {
            metrics.recordLivenessTimeout()
            livenessTimeoutRecorded = true
        }
    }

    /**
     * Samples time and evaluates liveness while holding the connection-state monitor. When
     * requested, a due keepalive nonce is reserved with that exact sample before another lane can
     * advance inbound time. Callers then serialize the returned immutable decision outside the
     * lock without re-reading or mutating liveness state.
     */
    @Synchronized
    internal fun pollLivenessAtomic(
        clock: MonotonicTimeSource,
        reserveKeepalive: Boolean,
    ): LiveLivenessDecision {
        val sampledAtNs = clock.nowNs()
        val status = pollLiveness(sampledAtNs)
        val nonce = if (reserveKeepalive && status == LivenessStatus.KEEPALIVE_DUE) {
            liveness.markKeepaliveSent(sampledAtNs)
        } else {
            null
        }
        return LiveLivenessDecision(status, sampledAtNs, nonce)
    }

    @Synchronized
    fun generation(): Long = generation

    @Synchronized
    fun isConnected(): Boolean = sequenceGuard != null

    override fun toString(): String = "LiveConnectionState(connected=${isConnected()},generation=${generation()})"

    private fun requireBinding(): LiveSessionBinding {
        if (sequenceGuard == null) throw IllegalStateException("link is disconnected")
        return currentBinding ?: throw IllegalStateException("link is disconnected")
    }

    private fun requireTicketAuthority(): CameraLaneTicketAuthority =
        ticketAuthority ?: throw IllegalStateException("link is disconnected")

    private fun clearConnectionState() {
        sequenceGuard?.clear()
        sequenceAllocator.reset()
        ticketAuthority?.reset()
        sequenceGuard = null
        ticketAuthority = null
        currentBinding = null
        cameraLaneAuthenticated = false
        livenessTimeoutRecorded = false
        clockSynchronizer.reset()
        clockNormalizer.reset()
        liveness.reset()
    }
}
