// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.transport.LiveLinkDisconnectReason
import org.conceptflow.mpl.transport.LiveLinkCloseEvidence
import org.conceptflow.mpl.transport.LiveLinkDiagnosticCode
import org.conceptflow.mpl.transport.LiveLinkSession
import org.conceptflow.mpl.transport.NegotiatedLiveLease
import org.conceptflow.mpl.transport.RokidLiveLinkClient
import org.conceptflow.mpl.transport.RokidLiveLinkObserver
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.conceptflow.mpl.v1.SensorStreamKind
import java.util.concurrent.atomic.AtomicLong

/** Narrow seam around the transport client so the sensor lifecycle can be tested without sockets. */
interface RokidLiveTransport : AutoCloseable {
    fun start(observer: RokidLiveLinkObserver)
    fun offerCameraFrame(chunks: List<SensorStreamEnvelope>): Boolean
    fun offerImu(batch: SensorStreamEnvelope): Boolean
    fun closeEvidence(): LiveLinkCloseEvidence = LiveLinkCloseEvidence()
    fun closeAsync(onClosed: (LiveLinkCloseEvidence) -> Unit)
}

class DefaultRokidLiveTransport(private val client: RokidLiveLinkClient) : RokidLiveTransport {
    override fun start(observer: RokidLiveLinkObserver) = client.start(observer)
    override fun offerCameraFrame(chunks: List<SensorStreamEnvelope>): Boolean = client.offerCameraFrame(chunks)
    override fun offerImu(batch: SensorStreamEnvelope): Boolean = client.offerImu(batch)
    override fun closeEvidence(): LiveLinkCloseEvidence = client.closeEvidenceSnapshot()
    override fun closeAsync(onClosed: (LiveLinkCloseEvidence) -> Unit) = client.closeAsync(onClosed)
    override fun close() = client.close()
}

enum class LiveLinkCaptureState {
    STOPPED,
    CONNECTING,
    STREAMING,
}

enum class LiveLinkCaptureStopReason {
    USER_REQUESTED,
    TIME_LIMIT_REACHED,
    LEASE_EXPIRED,
    RETRY_LIMIT_REACHED,
    TRANSPORT_STOPPED,
    REMOTE_COMPLETED,
    SOURCE_FAILURE,
    CLIENT_START_FAILURE,
    SERVICE_DESTROYED,
}

data class LiveLinkCaptureSnapshot(
    val state: LiveLinkCaptureState,
    val stopReason: LiveLinkCaptureStopReason?,
    val lastDisconnectReason: LiveLinkDisconnectReason?,
    val lastDiagnosticCode: LiveLinkDiagnosticCode?,
    val sessionsReady: Long,
    val disconnects: Long,
    val producerStarts: Long,
    val cameraFramesObserved: Long,
    val cameraFramesQueued: Long,
    val cameraFramesDropped: Long,
    val cameraChunksQueued: Long,
    val imuSamplesObserved: Long,
    val imuBatchesQueued: Long,
    val imuSamplesQueued: Long,
    val imuBatchesDropped: Long,
    val closeEvidence: LiveLinkCloseEvidence,
)

/**
 * Owns the camera/IMU producers for one bounded authenticated live-link run.
 *
 * Lifecycle callbacks are dispatched onto [dispatch], while payload callbacks use a generation
 * token so late producer emissions cannot enter a replacement session.
 */
class LiveLinkCaptureController(
    private val clock: MonotonicClock,
    private val frameSources: FrameSourceStateController,
    private val transport: RokidLiveTransport,
    private val frameSourceFactory: (NegotiatedLiveLease) -> FrameSource,
    private val poseSourceFactory: () -> PoseSource,
    private val dispatch: ((() -> Unit) -> Unit) = { it() },
    private val runDurationMillis: Long = DEFAULT_RUN_DURATION_MILLIS,
    private val maximumDisconnects: Int = DEFAULT_MAXIMUM_DISCONNECTS,
    private val onStatus: (LiveLinkCaptureSnapshot) -> Unit = {},
    private val onTerminal: (LiveLinkCaptureSnapshot) -> Unit = {},
) : AutoCloseable {
    @Volatile private var state = LiveLinkCaptureState.STOPPED
    @Volatile private var generation = 0L
    private var deadlineNanos = 0L
    private var stopReason: LiveLinkCaptureStopReason? = null
    private var lastDisconnectReason: LiveLinkDisconnectReason? = null
    private var lastDiagnosticCode: LiveLinkDiagnosticCode? = null
    private var closeEvidence = LiveLinkCloseEvidence()
    @Volatile private var activeLease: ActiveStreamLease? = null
    private var activeFrameSource: FrameSource? = null
    private var activePoseSource: PoseSource? = null
    @Volatile private var imuGate: ImuTransmissionGate? = null
    private var terminalReported = false
    private val packetizer = SensorStreamPacketizer(clock)

    private val sessionsReady = AtomicLong()
    private val disconnects = AtomicLong()
    private val producerStarts = AtomicLong()
    private val cameraFramesObserved = AtomicLong()
    private val cameraFramesQueued = AtomicLong()
    private val cameraFramesDropped = AtomicLong()
    private val cameraChunksQueued = AtomicLong()
    private val imuSamplesObserved = AtomicLong()
    private val imuBatchesQueued = AtomicLong()
    private val imuSamplesQueued = AtomicLong()
    private val imuBatchesDropped = AtomicLong()

    init {
        require(runDurationMillis in 1_000L..MAXIMUM_RUN_DURATION_MILLIS)
        require(maximumDisconnects in 1..MAXIMUM_DISCONNECTS_BOUND)
    }

    fun start(): Boolean {
        check(state == LiveLinkCaptureState.STOPPED && !terminalReported) {
            "live-link capture controller cannot be restarted"
        }
        val now = clock.nowNanos()
        deadlineNanos = saturatingNanosAfter(now, runDurationMillis)
        state = LiveLinkCaptureState.CONNECTING
        publishStatus()
        return try {
            transport.start(observer)
            true
        } catch (_: Throwable) {
            terminate(LiveLinkCaptureStopReason.CLIENT_START_FAILURE)
            false
        }
    }

    /** Called by the service's 20 ms cadence; it also enforces the total run bound. */
    fun poll() {
        if (state == LiveLinkCaptureState.STOPPED) return
        val now = clock.nowNanos()
        if (now >= deadlineNanos) {
            terminate(LiveLinkCaptureStopReason.TIME_LIMIT_REACHED)
            return
        }
        if (state != LiveLinkCaptureState.STREAMING) return
        if (now >= (activeLease?.expiresAtNanos ?: 0L)) {
            terminate(LiveLinkCaptureStopReason.LEASE_EXPIRED)
            return
        }
        val token = generation
        imuGate?.poll(now)?.let { offerImu(token, it) }
    }

    fun snapshot(): LiveLinkCaptureSnapshot = LiveLinkCaptureSnapshot(
        state = state,
        stopReason = stopReason,
        lastDisconnectReason = lastDisconnectReason,
        lastDiagnosticCode = lastDiagnosticCode,
        sessionsReady = sessionsReady.get(),
        disconnects = disconnects.get(),
        producerStarts = producerStarts.get(),
        cameraFramesObserved = cameraFramesObserved.get(),
        cameraFramesQueued = cameraFramesQueued.get(),
        cameraFramesDropped = cameraFramesDropped.get(),
        cameraChunksQueued = cameraChunksQueued.get(),
        imuSamplesObserved = imuSamplesObserved.get(),
        imuBatchesQueued = imuBatchesQueued.get(),
        imuSamplesQueued = imuSamplesQueued.get(),
        imuBatchesDropped = imuBatchesDropped.get(),
        closeEvidence = closeEvidence,
    )

    fun stop(reason: LiveLinkCaptureStopReason = LiveLinkCaptureStopReason.USER_REQUESTED) {
        terminate(reason)
    }

    override fun close() = stop(LiveLinkCaptureStopReason.SERVICE_DESTROYED)

    private val observer = object : RokidLiveLinkObserver {
        override fun onSessionReady(session: LiveLinkSession) {
            dispatch { handleSessionReady(session) }
        }

        override fun onDisconnected(reason: LiveLinkDisconnectReason) {
            dispatch { handleDisconnected(reason) }
        }

        override fun onDiagnostic(code: LiveLinkDiagnosticCode) {
            dispatch {
                if (state != LiveLinkCaptureState.STOPPED) {
                    lastDiagnosticCode = code
                    publishStatus()
                }
            }
        }
    }

    private fun handleSessionReady(session: LiveLinkSession) {
        if (state == LiveLinkCaptureState.STOPPED) return
        val now = clock.nowNanos()
        if (now >= deadlineNanos) {
            terminate(LiveLinkCaptureStopReason.TIME_LIMIT_REACHED)
            return
        }
        val effectiveLeaseDeadline = minOf(deadlineNanos, session.lease.expiresAtMonotonicNs)
        if (now >= effectiveLeaseDeadline) {
            terminate(LiveLinkCaptureStopReason.LEASE_EXPIRED)
            return
        }

        invalidateAndStopProducers()
        val token = generation
        val lease = ActiveStreamLease(
            leaseId = session.binding.leaseId,
            peer = AUTHENTICATED_LIVE_LINK_PEER,
            sessionId = session.binding.sessionId,
            streams = LIVE_STREAMS,
            openedAtNanos = now,
            expiresAtNanos = effectiveLeaseDeadline,
            microphoneExpiresAtNanos = null,
        )
        val frameSource = try {
            frameSourceFactory(session.lease)
        } catch (_: Throwable) {
            terminate(LiveLinkCaptureStopReason.SOURCE_FAILURE)
            return
        }
        val poseSource = try {
            poseSourceFactory()
        } catch (_: Throwable) {
            runCatching { frameSource.close() }
            terminate(LiveLinkCaptureStopReason.SOURCE_FAILURE)
            return
        }
        if (!frameSources.attach(frameSource)) {
            runCatching { frameSource.close() }
            runCatching { poseSource.close() }
            terminate(LiveLinkCaptureStopReason.SOURCE_FAILURE)
            return
        }

        activeLease = lease
        activeFrameSource = frameSource
        activePoseSource = poseSource
        imuGate = try {
            ImuTransmissionGate(
                ImuTransmissionConfig(
                    maxBatchDelayNanos = session.lease.imuMaximumBatchDelayMs * NANOS_PER_MILLISECOND,
                    maxSilenceNanos = session.lease.imuMaximumSilenceMs * NANOS_PER_MILLISECOND,
                ),
            )
        } catch (_: IllegalArgumentException) {
            terminate(LiveLinkCaptureStopReason.SOURCE_FAILURE)
            return
        }
        state = LiveLinkCaptureState.STREAMING
        sessionsReady.incrementAndGet()
        try {
            frameSource.start(frameListener(frameSource, token))
            poseSource.start { sample -> onImuSample(token, sample) }
            check(frameSource.isRunning && poseSource.isRunning)
        } catch (_: Throwable) {
            terminate(LiveLinkCaptureStopReason.SOURCE_FAILURE)
            return
        }
        producerStarts.incrementAndGet()
        publishStatus()
    }

    private fun frameListener(source: FrameSource, token: Long): FrameSource.Listener =
        object : FrameSource.Listener {
            override fun onFrame(frame: FramePayload) {
                if (!isCurrentStream(token) || !frameSources.isCurrent(source)) return
                cameraFramesObserved.incrementAndGet()
                val lease = activeLease ?: return
                val chunks = runCatching { packetizer.camera(lease, frame) }
                    .getOrElse { emptyList() }
                if (chunks.isEmpty()) {
                    cameraFramesDropped.incrementAndGet()
                    return
                }
                // Packetization can overlap a main-thread disconnect/stop. Re-check immediately
                // before handing bytes to the transport so an invalidated producer cannot enqueue.
                if (!isCurrentStream(token) || !frameSources.isCurrent(source)) return
                val accepted = runCatching { transport.offerCameraFrame(chunks) }.getOrDefault(false)
                if (accepted) {
                    cameraFramesQueued.incrementAndGet()
                    cameraChunksQueued.addAndGet(chunks.size.toLong())
                } else {
                    cameraFramesDropped.incrementAndGet()
                    dispatch { handleOutboundUnavailable(token) }
                }
            }

            override fun onError(message: String) {
                dispatch {
                    if (isCurrentStream(token)) terminate(LiveLinkCaptureStopReason.SOURCE_FAILURE)
                }
            }
        }

    private fun onImuSample(token: Long, sample: ImuSample) {
        if (!isCurrentStream(token)) return
        imuSamplesObserved.incrementAndGet()
        imuGate?.offer(sample)?.let { offerImu(token, it) }
    }

    private fun offerImu(token: Long, batch: ImuTransmissionBatch) {
        if (!isCurrentStream(token)) return
        val lease = activeLease ?: return
        val envelope = packetizer.imu(lease, batch)
        if (envelope == null) {
            imuBatchesDropped.incrementAndGet()
            return
        }
        val accepted = runCatching { transport.offerImu(envelope) }.getOrDefault(false)
        if (accepted) {
            imuBatchesQueued.incrementAndGet()
            imuSamplesQueued.addAndGet(batch.samples.size.toLong())
        } else {
            imuBatchesDropped.incrementAndGet()
            dispatch { handleOutboundUnavailable(token) }
        }
    }

    private fun handleOutboundUnavailable(token: Long) {
        if (!isCurrentStream(token)) return
        state = LiveLinkCaptureState.CONNECTING
        invalidateAndStopProducers()
        publishStatus()
    }

    private fun handleDisconnected(reason: LiveLinkDisconnectReason) {
        if (state == LiveLinkCaptureState.STOPPED) return
        lastDisconnectReason = reason
        state = LiveLinkCaptureState.CONNECTING
        invalidateAndStopProducers()
        if (reason == LiveLinkDisconnectReason.LEASE_EXPIRED) {
            terminate(LiveLinkCaptureStopReason.LEASE_EXPIRED)
        } else if (reason == LiveLinkDisconnectReason.STOPPED) {
            terminate(LiveLinkCaptureStopReason.TRANSPORT_STOPPED)
        } else if (reason == LiveLinkDisconnectReason.REMOTE_COMPLETED) {
            terminate(LiveLinkCaptureStopReason.REMOTE_COMPLETED)
        } else {
            val count = disconnects.incrementAndGet()
            if (count >= maximumDisconnects) {
                terminate(LiveLinkCaptureStopReason.RETRY_LIMIT_REACHED)
            } else {
                publishStatus()
            }
        }
    }

    private fun isCurrentStream(token: Long): Boolean =
        state == LiveLinkCaptureState.STREAMING && generation == token

    private fun invalidateAndStopProducers() {
        generation += 1L
        val frameSource = activeFrameSource
        activeFrameSource = null
        if (frameSource != null) runCatching { frameSources.stopIfCurrent(frameSource) }
        activePoseSource?.let { runCatching { it.close() } }
        activePoseSource = null
        activeLease = null
        imuGate = null
    }

    private fun terminate(reason: LiveLinkCaptureStopReason) {
        if (state == LiveLinkCaptureState.STOPPED) return
        state = LiveLinkCaptureState.STOPPED
        stopReason = reason
        invalidateAndStopProducers()
        val closeScheduled = runCatching {
            transport.closeAsync { evidence -> dispatch { finishTermination(evidence) } }
        }.isSuccess
        if (!closeScheduled) finishTermination(LiveLinkCloseEvidence())
    }

    private fun finishTermination(evidence: LiveLinkCloseEvidence) {
        if (terminalReported) return
        closeEvidence = evidence
        val finalSnapshot = snapshot()
        terminalReported = true
        onStatus(finalSnapshot)
        onTerminal(finalSnapshot)
    }

    private fun publishStatus() = onStatus(snapshot())

    private fun saturatingNanosAfter(nowNanos: Long, durationMillis: Long): Long {
        require(nowNanos >= 0L)
        val durationNanos = durationMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - nowNanos < durationNanos) Long.MAX_VALUE else nowNanos + durationNanos
    }

    companion object {
        const val DEFAULT_RUN_DURATION_MILLIS = 30_000L
        const val DEFAULT_MAXIMUM_DISCONNECTS = 6
        private const val MAXIMUM_RUN_DURATION_MILLIS = 60_000L
        private const val MAXIMUM_DISCONNECTS_BOUND = 32
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val AUTHENTICATED_LIVE_LINK_PEER = AuthenticatedStreamPeer("authenticated-live-link-peer")
        private val LIVE_STREAMS = setOf(
            SensorStreamKind.SENSOR_STREAM_KIND_CAMERA,
            SensorStreamKind.SENSOR_STREAM_KIND_IMU,
        )
    }
}
