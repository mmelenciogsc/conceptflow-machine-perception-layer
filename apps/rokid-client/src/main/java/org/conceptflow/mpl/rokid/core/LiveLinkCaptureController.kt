// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.transport.LiveLinkDisconnectReason
import org.conceptflow.mpl.transport.LiveLinkCloseEvidence
import org.conceptflow.mpl.transport.LiveLinkDiagnosticCode
import org.conceptflow.mpl.transport.LiveLinkSession
import org.conceptflow.mpl.transport.MicrophoneGestureDispatch
import org.conceptflow.mpl.transport.MicrophoneLeaseAuthorization
import org.conceptflow.mpl.transport.MicrophoneGestureResult
import org.conceptflow.mpl.transport.NegotiatedLiveLease
import org.conceptflow.mpl.transport.RokidLiveLinkClient
import org.conceptflow.mpl.transport.RokidLiveLinkObserver
import org.conceptflow.mpl.transport.RokidGestureDispatch
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.conceptflow.mpl.v1.SensorStreamKind
import org.conceptflow.mpl.v1.MicrophoneControlOperation
import org.conceptflow.mpl.v1.RokidGestureOperation
import org.conceptflow.mpl.v1.RokidNodeCommand
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Narrow seam around the transport client so the sensor lifecycle can be tested without sockets. */
interface RokidLiveTransport : AutoCloseable {
    fun start(observer: RokidLiveLinkObserver)
    fun offerCameraFrame(chunks: List<SensorStreamEnvelope>): Boolean
    fun offerImu(batch: SensorStreamEnvelope): Boolean
    fun offerMicrophone(chunk: SensorStreamEnvelope): Boolean
    fun offerTouch(event: SensorStreamEnvelope): Boolean = false
    fun requestMicrophoneFromUserGesture(): MicrophoneGestureDispatch =
        MicrophoneGestureDispatch.NO_AUTHENTICATED_SESSION
    fun stopMicrophoneFromUserGesture(): MicrophoneGestureDispatch =
        MicrophoneGestureDispatch.NO_AUTHENTICATED_SESSION
    fun requestRokidGesture(
        operation: RokidGestureOperation,
        observedMonotonicNs: Long,
    ): RokidGestureDispatch = RokidGestureDispatch.TRANSPORT_STOPPED
    fun closeEvidence(): LiveLinkCloseEvidence = LiveLinkCloseEvidence()
    fun closeAsync(onClosed: (LiveLinkCloseEvidence) -> Unit)
}

class DefaultRokidLiveTransport(private val client: RokidLiveLinkClient) : RokidLiveTransport {
    override fun start(observer: RokidLiveLinkObserver) = client.start(observer)
    override fun offerCameraFrame(chunks: List<SensorStreamEnvelope>): Boolean = client.offerCameraFrame(chunks)
    override fun offerImu(batch: SensorStreamEnvelope): Boolean = client.offerImu(batch)
    override fun offerMicrophone(chunk: SensorStreamEnvelope): Boolean = client.offerMicrophone(chunk)
    override fun offerTouch(event: SensorStreamEnvelope): Boolean = client.offerTouch(event)
    override fun requestMicrophoneFromUserGesture(): MicrophoneGestureDispatch =
        client.requestMicrophoneFromUserGesture()
    override fun stopMicrophoneFromUserGesture(): MicrophoneGestureDispatch =
        client.stopMicrophoneFromUserGesture()
    override fun requestRokidGesture(
        operation: RokidGestureOperation,
        observedMonotonicNs: Long,
    ): RokidGestureDispatch = client.requestRokidGesture(operation, observedMonotonicNs)
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
    RENDEZVOUS_TIMEOUT,
    TIME_LIMIT_REACHED,
    LEASE_EXPIRED,
    RETRY_LIMIT_REACHED,
    TRANSPORT_STOPPED,
    REMOTE_COMPLETED,
    SOURCE_FAILURE,
    CLIENT_START_FAILURE,
    SERVICE_DESTROYED,
}

enum class LiveMicrophoneCaptureState {
    STARTED,
    STOPPED,
    REJECTED_PERMISSION,
    REJECTED_STATE,
    SOURCE_FAILURE,
}

data class LiveLinkCaptureSnapshot(
    val state: LiveLinkCaptureState,
    val stopReason: LiveLinkCaptureStopReason?,
    val lastDisconnectReason: LiveLinkDisconnectReason?,
    val lastDiagnosticCode: LiveLinkDiagnosticCode?,
    val sessionsReady: Long,
    val disconnects: Long,
    val producerStarts: Long,
    val cameraSourceRestarts: Long,
    val lastCameraSourceDiagnostic: CameraSourceDiagnostic?,
    val cameraFramesObserved: Long,
    val cameraFramesQueued: Long,
    val cameraFramesDropped: Long,
    val cameraChunksQueued: Long,
    val imuSamplesObserved: Long,
    val imuBatchesQueued: Long,
    val imuSamplesQueued: Long,
    val imuBatchesDropped: Long,
    val microphoneStarts: Long,
    val microphoneChunksObserved: Long,
    val microphoneChunksQueued: Long,
    val microphoneChunksDropped: Long,
    val touchEventsObserved: Long,
    val touchEventsQueued: Long,
    val touchEventsOverflowed: Long,
    val cameraSourceTiming: LiveCameraSourceTimingSnapshot,
    val cameraTransform: CameraTransformSnapshot,
    val legacySpoolMetrics: LegacySpoolMetricsSnapshot?,
    val closeEvidence: LiveLinkCloseEvidence,
    val activeDeadlineNanos: Long?,
)

data class LiveTimingDistributionSnapshot(
    val samples: Long = 0L,
    val p50Nanos: Long = 0L,
    val p95Nanos: Long = 0L,
    val p99Nanos: Long = 0L,
    val maximumNanos: Long = 0L,
)

data class LiveCapturePipelineSnapshot(
    val requestsSubmitted: Long = 0L,
    val opportunitiesBackpressured: Long = 0L,
    val requestsSuperseded: Long = 0L,
    val imagesWithoutExactRequestMatch: Long = 0L,
    val captureFailures: Long = 0L,
    val lateCallbacks: Long = 0L,
    val outstandingRequests: Int = 0,
    val maximumOutstandingRequests: Int = 0,
)

data class LiveCameraSourceTimingSnapshot(
    val requestToImage: LiveTimingDistributionSnapshot = LiveTimingDistributionSnapshot(),
    val imageAcquisition: LiveTimingDistributionSnapshot = LiveTimingDistributionSnapshot(),
    val gateAndResizeProcessor: LiveTimingDistributionSnapshot = LiveTimingDistributionSnapshot(),
    val listenerPath: LiveTimingDistributionSnapshot = LiveTimingDistributionSnapshot(),
    val pipeline: LiveCapturePipelineSnapshot = LiveCapturePipelineSnapshot(),
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
    private val sensorSpool: RokidSensorSpool? = null,
    private val cameraTransformerFactory: (() -> CameraFrameTransformer)? = null,
    private val frameSourceFactory: (NegotiatedLiveLease, MonotonicFrameSequence) -> FrameSource,
    private val poseSourceFactory: () -> PoseSource,
    private val audioSourceFactory: (() -> AudioInputSource)? = null,
    private val microphonePermissionAvailable: () -> Boolean = { false },
    private val beforeProducerStart: (LiveLinkSession) -> Boolean = { true },
    private val beforeMicrophoneStart: () -> Boolean = { true },
    private val dispatch: ((() -> Unit) -> Unit) = { it() },
    private val runDurationMillis: Long = DEFAULT_RUN_DURATION_MILLIS,
    private val maximumPreAuthenticationDisconnects: Int = DEFAULT_MAXIMUM_PRE_AUTHENTICATION_DISCONNECTS,
    private val maximumDisconnects: Int = DEFAULT_MAXIMUM_DISCONNECTS,
    private val maximumCameraRestartAttempts: Int = DEFAULT_MAXIMUM_CAMERA_RESTART_ATTEMPTS,
    private val cameraRestartDelayMillis: Long = DEFAULT_CAMERA_RESTART_DELAY_MILLIS,
    private val onStatus: (LiveLinkCaptureSnapshot) -> Unit = {},
    private val onMicrophoneState: (LiveMicrophoneCaptureState) -> Unit = {},
    private val onRokidNodeCommand: (RokidNodeCommand) -> Boolean = { false },
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
    @Volatile private var activeMicrophoneSource: AudioInputSource? = null
    @Volatile private var activeCameraTransformer: CameraFrameTransformer? = null
    @Volatile private var lastCameraTransformSnapshot = CameraTransformSnapshot(0, 0, 0, 0)
    private val microphoneDeadlineExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mpl-microphone-deadline").apply { isDaemon = true }
    }
    private var microphoneDeadlineTask: ScheduledFuture<*>? = null
    private var cameraRecoveryTask: ScheduledFuture<*>? = null
    private var cameraRecoveryAttempts = 0
    @Volatile private var lastCameraSourceDiagnostic: CameraSourceDiagnostic? = null
    @Volatile private var imuGate: ImuTransmissionGate? = null
    private var terminalReported = false
    private val packetizer = SensorStreamPacketizer(clock)

    private val sessionsReady = AtomicLong()
    private val disconnects = AtomicLong()
    private val producerStarts = AtomicLong()
    private val cameraSourceRestarts = AtomicLong()
    private val cameraSourceGeneration = AtomicLong()
    private val cameraFramesObserved = AtomicLong()
    private val cameraFramesQueued = AtomicLong()
    private val cameraFramesDropped = AtomicLong()
    private val cameraChunksQueued = AtomicLong()
    private val imuSamplesObserved = AtomicLong()
    private val imuBatchesQueued = AtomicLong()
    private val imuSamplesQueued = AtomicLong()
    private val imuBatchesDropped = AtomicLong()
    private val microphoneStarts = AtomicLong()
    private val microphoneChunksObserved = AtomicLong()
    private val microphoneChunksQueued = AtomicLong()
    private val microphoneChunksDropped = AtomicLong()
    private val touchEventIds = AtomicLong()
    private val touchEventsObserved = AtomicLong()
    private val touchEventsQueued = AtomicLong()
    private val touchEventsOverflowed = AtomicLong()
    private val cameraSourceMetrics = LiveCameraSourceMetrics()
    private val cameraFrameSequence = MonotonicFrameSequence()

    init {
        require(runDurationMillis in 1_000L..MAXIMUM_RUN_DURATION_MILLIS)
        require(maximumPreAuthenticationDisconnects in 1..MAXIMUM_DISCONNECTS_BOUND)
        require(maximumDisconnects in 1..MAXIMUM_DISCONNECTS_BOUND)
        require(maximumCameraRestartAttempts in 1..MAXIMUM_CAMERA_RESTART_ATTEMPTS_BOUND)
        require(cameraRestartDelayMillis in 0L..MAXIMUM_CAMERA_RESTART_DELAY_MILLIS)
    }

    fun start(): Boolean {
        check(state == LiveLinkCaptureState.STOPPED && !terminalReported) {
            "live-link capture controller cannot be restarted"
        }
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
        if (deadlineNanos != NO_DEADLINE && now >= deadlineNanos) {
            terminate(LiveLinkCaptureStopReason.TIME_LIMIT_REACHED)
            return
        }
        if (state != LiveLinkCaptureState.STREAMING) return
        if (now >= (activeLease?.expiresAtNanos ?: 0L)) {
            terminate(LiveLinkCaptureStopReason.LEASE_EXPIRED)
            return
        }
        val microphoneExpiry = activeLease?.microphoneExpiresAtNanos
        if (microphoneExpiry != null && now >= microphoneExpiry) stopMicrophone(LiveMicrophoneCaptureState.STOPPED)
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
        cameraSourceRestarts = cameraSourceRestarts.get(),
        lastCameraSourceDiagnostic = lastCameraSourceDiagnostic,
        cameraFramesObserved = cameraFramesObserved.get(),
        cameraFramesQueued = cameraFramesQueued.get(),
        cameraFramesDropped = cameraFramesDropped.get(),
        cameraChunksQueued = cameraChunksQueued.get(),
        imuSamplesObserved = imuSamplesObserved.get(),
        imuBatchesQueued = imuBatchesQueued.get(),
        imuSamplesQueued = imuSamplesQueued.get(),
        imuBatchesDropped = imuBatchesDropped.get(),
        microphoneStarts = microphoneStarts.get(),
        microphoneChunksObserved = microphoneChunksObserved.get(),
        microphoneChunksQueued = microphoneChunksQueued.get(),
        microphoneChunksDropped = microphoneChunksDropped.get(),
        touchEventsObserved = touchEventsObserved.get(),
        touchEventsQueued = touchEventsQueued.get(),
        touchEventsOverflowed = touchEventsOverflowed.get(),
        cameraSourceTiming = cameraSourceMetrics.snapshot(),
        cameraTransform = activeCameraTransformer?.snapshot() ?: lastCameraTransformSnapshot,
        legacySpoolMetrics = sensorSpool?.metricsSnapshot(),
        closeEvidence = closeEvidence,
        activeDeadlineNanos = deadlineNanos.takeUnless { it == NO_DEADLINE },
    )

    fun stop(reason: LiveLinkCaptureStopReason = LiveLinkCaptureStopReason.USER_REQUESTED) {
        terminate(reason)
    }

    /** Requests host authorization; capture still starts only after the authenticated sublease. */
    fun requestMicrophoneFromUserGesture(): MicrophoneGestureDispatch =
        if (state == LiveLinkCaptureState.STREAMING) {
            transport.requestMicrophoneFromUserGesture()
        } else {
            MicrophoneGestureDispatch.NO_AUTHENTICATED_SESSION
        }

    /** Privacy-first and idempotent: local capture stops before the network acknowledgement. */
    @Synchronized
    fun stopMicrophoneFromUserGesture(): MicrophoneGestureDispatch {
        stopMicrophone(LiveMicrophoneCaptureState.STOPPED)
        return if (state == LiveLinkCaptureState.STREAMING) {
            transport.stopMicrophoneFromUserGesture()
        } else {
            MicrophoneGestureDispatch.NO_AUTHENTICATED_SESSION
        }
    }

    fun requestRokidGesture(
        operation: RokidGestureOperation,
        observedMonotonicNs: Long,
    ): RokidGestureDispatch = transport.requestRokidGesture(operation, observedMonotonicNs)

    /** Publishes an already allowlisted raw touch event without changing gesture recognition. */
    fun offerTouchEvent(event: RokidInputEvent, observedMonotonicTimestampNs: Long): Boolean {
        touchEventsObserved.incrementAndGet()
        if (state != LiveLinkCaptureState.STREAMING) return false
        val lease = activeLease ?: return false
        val eventId = touchEventIds.updateAndGet { previous -> Math.addExact(previous, 1L) }
        val envelope = packetizer.touch(lease, eventId, observedMonotonicTimestampNs, event)
        val accepted = envelope != null && runCatching { transport.offerTouch(envelope) }.getOrDefault(false)
        if (accepted) touchEventsQueued.incrementAndGet() else touchEventsOverflowed.incrementAndGet()
        return accepted
    }

    /** Publishes a complete YodaOS-recognized gesture without fabricating raw DOWN/UP events. */
    fun offerSystemTouchEvent(event: RokidSystemTouchEvent): Boolean {
        touchEventsObserved.incrementAndGet()
        if (state != LiveLinkCaptureState.STREAMING) return false
        val lease = activeLease ?: return false
        val eventId = touchEventIds.updateAndGet { previous -> Math.addExact(previous, 1L) }
        val envelope = packetizer.systemTouch(lease, eventId, event)
        val accepted = envelope != null && runCatching { transport.offerTouch(envelope) }.getOrDefault(false)
        if (accepted) touchEventsQueued.incrementAndGet() else touchEventsOverflowed.incrementAndGet()
        return accepted
    }

    override fun close() = stop(LiveLinkCaptureStopReason.SERVICE_DESTROYED)

    private val observer = object : RokidLiveLinkObserver {
        override fun onSessionReady(session: LiveLinkSession) {
            dispatch { handleSessionReady(session) }
        }

        override fun onDisconnected(reason: LiveLinkDisconnectReason) {
            dispatch { handleDisconnected(reason) }
        }

        override fun mayGrantMicrophoneLease(authorization: MicrophoneLeaseAuthorization): Boolean =
            mayGrantMicrophone(authorization)

        override fun onMicrophoneLeaseGranted(authorization: MicrophoneLeaseAuthorization) {
            dispatch { startMicrophone(authorization) }
        }

        override fun onMicrophoneGestureResult(result: MicrophoneGestureResult) {
            dispatch {
                when {
                    result.operation == MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_STOP ->
                        stopMicrophone(LiveMicrophoneCaptureState.STOPPED)
                    !result.accepted -> onMicrophoneState(LiveMicrophoneCaptureState.REJECTED_STATE)
                }
            }
        }

        override fun onRokidNodeCommand(command: RokidNodeCommand): Boolean =
            runCatching { this@LiveLinkCaptureController.onRokidNodeCommand(command) }
                .getOrDefault(false)

        override fun onDiagnostic(code: LiveLinkDiagnosticCode) {
            dispatch {
                if (state != LiveLinkCaptureState.STOPPED) {
                    lastDiagnosticCode = code
                    publishStatus()
                }
            }
        }
    }

    @Synchronized
    private fun mayGrantMicrophone(authorization: MicrophoneLeaseAuthorization): Boolean {
        val lease = activeLease
        val nowNanos = clock.nowNanos()
        val bindingMatches = lease != null && lease.sessionId == authorization.sessionId &&
            lease.leaseId == authorization.leaseId
        return when {
            !microphonePermissionAvailable() -> false.also {
                dispatch { onMicrophoneState(LiveMicrophoneCaptureState.REJECTED_PERMISSION) }
            }
            state != LiveLinkCaptureState.STREAMING || lease == null || !bindingMatches ||
                lease.expiresAtNanos <= nowNanos || authorization.expiresAtMonotonicNs <= nowNanos ||
                activeMicrophoneSource != null || audioSourceFactory == null -> false.also {
                dispatch { onMicrophoneState(LiveMicrophoneCaptureState.REJECTED_STATE) }
            }
            else -> true
        }
    }

    @Synchronized
    private fun startMicrophone(authorization: MicrophoneLeaseAuthorization) {
        val lease = activeLease
        val nowNanos = clock.nowNanos()
        if (state != LiveLinkCaptureState.STREAMING || lease == null ||
            lease.sessionId != authorization.sessionId || lease.leaseId != authorization.leaseId ||
            lease.expiresAtNanos <= nowNanos || authorization.expiresAtMonotonicNs <= nowNanos ||
            activeMicrophoneSource != null
        ) {
            onMicrophoneState(LiveMicrophoneCaptureState.REJECTED_STATE)
            return
        }
        val microphoneExpiryNanos = minOf(authorization.expiresAtMonotonicNs, lease.expiresAtNanos)
        val microphoneLease = lease.copy(
            streams = lease.streams + SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE,
            microphoneExpiresAtNanos = microphoneExpiryNanos,
        )
        if (!runCatching(beforeMicrophoneStart).getOrDefault(false)) {
            onMicrophoneState(LiveMicrophoneCaptureState.SOURCE_FAILURE)
            return
        }
        val source = try {
            requireNotNull(audioSourceFactory).invoke()
        } catch (_: Throwable) {
            onMicrophoneState(LiveMicrophoneCaptureState.SOURCE_FAILURE)
            return
        }
        activeLease = microphoneLease
        activeMicrophoneSource = source
        try {
            source.start(object : AudioInputSource.Listener {
                override fun onAudioChunk(chunk: PcmAudioChunk) {
                    if (activeMicrophoneSource !== source) return
                    microphoneChunksObserved.incrementAndGet()
                    val lease = activeLease ?: return
                    val spool = sensorSpool
                    val accepted = if (spool != null) {
                        runCatching { spool.storeMicrophone(lease, chunk) }.getOrDefault(false)
                    } else {
                        val packet = packetizer.microphone(lease, chunk)
                        packet != null && runCatching { transport.offerMicrophone(packet) }.getOrDefault(false)
                    }
                    if (accepted) {
                        microphoneChunksQueued.incrementAndGet()
                    } else {
                        microphoneChunksDropped.incrementAndGet()
                    }
                }

                override fun onError(message: String) {
                    dispatch {
                        if (activeMicrophoneSource === source) {
                            stopMicrophone(LiveMicrophoneCaptureState.SOURCE_FAILURE)
                        }
                    }
                }
            })
            check(source.isRunning)
            val remainingNanos = (microphoneExpiryNanos - clock.nowNanos()).coerceAtLeast(0L)
            microphoneDeadlineTask?.cancel(false)
            microphoneDeadlineTask = microphoneDeadlineExecutor.schedule(
                {
                    dispatch {
                        if (activeMicrophoneSource === source) {
                            stopMicrophone(LiveMicrophoneCaptureState.STOPPED)
                        }
                    }
                },
                remainingNanos,
                TimeUnit.NANOSECONDS,
            )
        } catch (_: Throwable) {
            if (activeMicrophoneSource === source) activeMicrophoneSource = null
            runCatching { source.close() }
            activeLease = lease
            onMicrophoneState(LiveMicrophoneCaptureState.SOURCE_FAILURE)
            return
        }
        microphoneStarts.incrementAndGet()
        onMicrophoneState(LiveMicrophoneCaptureState.STARTED)
        publishStatus()
    }

    @Synchronized
    private fun stopMicrophone(result: LiveMicrophoneCaptureState) {
        val source = activeMicrophoneSource ?: return
        activeMicrophoneSource = null
        microphoneDeadlineTask?.cancel(false)
        microphoneDeadlineTask = null
        runCatching { source.close() }
        val lease = activeLease
        activeLease = lease?.copy(
            streams = lease.streams - SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE,
            microphoneExpiresAtNanos = null,
        )
        onMicrophoneState(result)
        publishStatus()
    }

    private fun handleSessionReady(session: LiveLinkSession) {
        if (state == LiveLinkCaptureState.STOPPED) return
        val now = clock.nowNanos()
        if (deadlineNanos == NO_DEADLINE) {
            deadlineNanos = saturatingNanosAfter(now, runDurationMillis)
        }
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
        if (!runCatching { beforeProducerStart(session) }.getOrDefault(false)) {
            terminate(LiveLinkCaptureStopReason.SOURCE_FAILURE)
            return
        }
        val token = generation
        cameraRecoveryAttempts = 0
        lastCameraSourceDiagnostic = null
        val lease = ActiveStreamLease(
            leaseId = session.binding.leaseId,
            peer = AUTHENTICATED_LIVE_LINK_PEER,
            sessionId = session.binding.sessionId,
            streams = LIVE_STREAMS,
            openedAtNanos = now,
            expiresAtNanos = effectiveLeaseDeadline,
            microphoneExpiresAtNanos = null,
        )
        if (!runCatching { sensorSpool?.beginSession(lease.sessionId) }.isSuccess) {
            terminate(LiveLinkCaptureStopReason.SOURCE_FAILURE)
            return
        }
        val frameSource = try {
            frameSourceFactory(session.lease, cameraFrameSequence)
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
        val cameraTransformer = try {
            if (sensorSpool == null) cameraTransformerFactory?.invoke() else null
        } catch (_: Throwable) {
            runCatching { frameSource.close() }
            runCatching { poseSource.close() }
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
        activeCameraTransformer = cameraTransformer
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
            frameSource.start(
                frameListener(
                    frameSource,
                    token,
                    session.lease,
                    cameraSourceGeneration.incrementAndGet(),
                ),
            )
            poseSource.start { sample -> onImuSample(token, sample) }
            check(frameSource.isRunning && poseSource.isRunning)
        } catch (_: Throwable) {
            terminate(LiveLinkCaptureStopReason.SOURCE_FAILURE)
            return
        }
        producerStarts.incrementAndGet()
        publishStatus()
    }

    private fun frameListener(
        source: FrameSource,
        token: Long,
        negotiatedLease: NegotiatedLiveLease,
        pipelineGeneration: Long,
    ): FrameSource.Listener =
        object : FrameSource.Listener {
            override fun onFrame(frame: FramePayload) {
                if (!isCurrentStream(token) || !frameSources.isCurrent(source)) return
                cameraFramesObserved.incrementAndGet()
                val lease = activeLease ?: return
                val spool = sensorSpool
                if (spool != null) {
                    val stored = runCatching { spool.storeCamera(lease, frame) }.getOrDefault(false)
                    if (stored) {
                        cameraFramesQueued.incrementAndGet()
                        cameraChunksQueued.incrementAndGet()
                    } else {
                        cameraFramesDropped.incrementAndGet()
                    }
                    return
                }
                val transformer = activeCameraTransformer
                if (transformer != null) {
                    val accepted = transformer.offer(
                        frame,
                        onReady = { transformed -> publishCamera(token, source, transformed) },
                        onFailure = { cameraFramesDropped.incrementAndGet() },
                    )
                    if (!accepted) cameraFramesDropped.incrementAndGet()
                    return
                }
                publishCamera(token, source, frame)
            }

            override fun onCaptureTiming(event: CaptureTimingEvent) {
                if (!isCurrentStream(token) || !frameSources.isCurrent(source)) return
                cameraSourceMetrics.record(event)
            }

            override fun onCapturePipelineSnapshot(snapshot: CapturePipelineSnapshot) {
                if (!isCurrentStream(token) || !frameSources.isCurrent(source)) return
                cameraSourceMetrics.recordPipeline(pipelineGeneration, snapshot)
            }

            override fun onError(message: String) {
                dispatch {
                    if (isCurrentStream(token) && activeFrameSource === source &&
                        frameSources.isCurrent(source)
                    ) {
                        terminate(LiveLinkCaptureStopReason.SOURCE_FAILURE)
                    }
                }
            }

            override fun onError(message: String, diagnostic: CameraSourceDiagnostic) {
                dispatch {
                    if (isCurrentStream(token) && activeFrameSource === source &&
                        frameSources.isCurrent(source)
                    ) {
                        lastCameraSourceDiagnostic = diagnostic
                        terminate(LiveLinkCaptureStopReason.SOURCE_FAILURE)
                    }
                }
            }

            override fun onRecoverableError(message: String) {
                dispatch { recoverCameraSource(source, token, negotiatedLease) }
            }

            override fun onRecoverableError(message: String, diagnostic: CameraSourceDiagnostic) {
                dispatch {
                    if (isCurrentStream(token) && activeFrameSource === source &&
                        frameSources.isCurrent(source)
                    ) {
                        lastCameraSourceDiagnostic = diagnostic
                        recoverCameraSource(source, token, negotiatedLease)
                    }
                }
            }
        }

    private fun recoverCameraSource(
        failedSource: FrameSource,
        token: Long,
        negotiatedLease: NegotiatedLiveLease,
    ) {
        if (!isCurrentStream(token) || activeFrameSource !== failedSource ||
            !frameSources.isCurrent(failedSource)
        ) {
            return
        }
        activeFrameSource = null
        runCatching { frameSources.stopIfCurrent(failedSource) }
        activeCameraTransformer?.let { transformer ->
            runCatching { lastCameraTransformSnapshot = transformer.snapshot() }
            runCatching { transformer.close() }
        }
        activeCameraTransformer = null
        scheduleCameraRecovery(token, negotiatedLease)
    }

    private fun scheduleCameraRecovery(token: Long, negotiatedLease: NegotiatedLiveLease) {
        if (!isCurrentStream(token) || activeFrameSource != null || cameraRecoveryTask != null) return
        if (cameraRecoveryAttempts >= maximumCameraRestartAttempts) {
            terminate(LiveLinkCaptureStopReason.SOURCE_FAILURE)
            return
        }
        cameraRecoveryAttempts += 1
        val restart = Runnable { dispatch { restartCameraSource(token, negotiatedLease) } }
        if (cameraRestartDelayMillis == 0L) {
            restart.run()
            return
        }
        cameraRecoveryTask = runCatching {
            microphoneDeadlineExecutor.schedule(
                restart,
                cameraRestartDelayMillis,
                TimeUnit.MILLISECONDS,
            )
        }.getOrElse {
            terminate(LiveLinkCaptureStopReason.SOURCE_FAILURE)
            null
        }
    }

    private fun restartCameraSource(token: Long, negotiatedLease: NegotiatedLiveLease) {
        cameraRecoveryTask = null
        if (!isCurrentStream(token) || activeFrameSource != null) return
        val now = clock.nowNanos()
        if (deadlineNanos != NO_DEADLINE && now >= deadlineNanos) {
            terminate(LiveLinkCaptureStopReason.TIME_LIMIT_REACHED)
            return
        }
        val lease = activeLease
        if (lease == null || now >= lease.expiresAtNanos) {
            terminate(LiveLinkCaptureStopReason.LEASE_EXPIRED)
            return
        }
        val replacement = runCatching {
            frameSourceFactory(negotiatedLease, cameraFrameSequence)
        }.getOrElse {
            scheduleCameraRecovery(token, negotiatedLease)
            return
        }
        val transformer = runCatching {
            if (sensorSpool == null) cameraTransformerFactory?.invoke() else null
        }.getOrElse {
            runCatching { replacement.close() }
            scheduleCameraRecovery(token, negotiatedLease)
            return
        }
        if (!frameSources.attach(replacement)) {
            runCatching { replacement.close() }
            runCatching { transformer?.close() }
            scheduleCameraRecovery(token, negotiatedLease)
            return
        }
        activeFrameSource = replacement
        activeCameraTransformer = transformer
        val started = runCatching {
            replacement.start(
                frameListener(
                    replacement,
                    token,
                    negotiatedLease,
                    cameraSourceGeneration.incrementAndGet(),
                ),
            )
            replacement.isRunning
        }.getOrDefault(false)
        if (!started) {
            if (activeFrameSource === replacement) activeFrameSource = null
            runCatching { frameSources.stopIfCurrent(replacement) }
            if (activeCameraTransformer === transformer) activeCameraTransformer = null
            runCatching { transformer?.close() }
            scheduleCameraRecovery(token, negotiatedLease)
            return
        }
        cameraSourceRestarts.incrementAndGet()
        publishStatus()
    }

    private fun publishCamera(token: Long, source: FrameSource, frame: FramePayload) {
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

    private fun onImuSample(token: Long, sample: ImuSample) {
        if (!isCurrentStream(token)) return
        imuSamplesObserved.incrementAndGet()
        imuGate?.offer(sample)?.let { offerImu(token, it) }
    }

    private fun offerImu(token: Long, batch: ImuTransmissionBatch) {
        if (!isCurrentStream(token)) return
        val lease = activeLease ?: return
        val spool = sensorSpool
        if (spool != null) {
            val stored = runCatching { spool.storeImu(lease, batch) }.getOrDefault(false)
            if (stored) {
                imuBatchesQueued.incrementAndGet()
                imuSamplesQueued.addAndGet(batch.samples.size.toLong())
            } else {
                imuBatchesDropped.incrementAndGet()
            }
            return
        }
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
        } else if (reason in FAIL_CLOSED_DISCONNECT_REASONS) {
            terminate(LiveLinkCaptureStopReason.RETRY_LIMIT_REACHED)
        } else {
            val count = disconnects.incrementAndGet()
            val limit = if (sessionsReady.get() == 0L) {
                maximumPreAuthenticationDisconnects
            } else {
                maximumDisconnects
            }
            if (count >= limit) {
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
        cameraRecoveryTask?.cancel(false)
        cameraRecoveryTask = null
        val frameSource = activeFrameSource
        activeFrameSource = null
        if (frameSource != null) runCatching { frameSources.stopIfCurrent(frameSource) }
        activePoseSource?.let { runCatching { it.close() } }
        activePoseSource = null
        activeCameraTransformer?.let { transformer ->
            runCatching { lastCameraTransformSnapshot = transformer.snapshot() }
            runCatching { transformer.close() }
        }
        activeCameraTransformer = null
        if (activeMicrophoneSource != null) stopMicrophone(LiveMicrophoneCaptureState.STOPPED)
        activeLease = null
        imuGate = null
    }

    private fun terminate(reason: LiveLinkCaptureStopReason) {
        if (state == LiveLinkCaptureState.STOPPED) return
        state = LiveLinkCaptureState.STOPPED
        stopReason = reason
        invalidateAndStopProducers()
        microphoneDeadlineExecutor.shutdownNow()
        // Sensor truth is published immediately; terminal completion still waits for bounded
        // transport shutdown evidence below.
        publishStatus()
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
        const val SOAK_RUN_DURATION_MILLIS = 600_000L
        const val DEFAULT_MAXIMUM_PRE_AUTHENTICATION_DISCONNECTS = 1
        const val DEFAULT_MAXIMUM_DISCONNECTS = 6
        const val DEFAULT_MAXIMUM_CAMERA_RESTART_ATTEMPTS = 3
        const val DEFAULT_CAMERA_RESTART_DELAY_MILLIS = 500L
        private const val MAXIMUM_RUN_DURATION_MILLIS = SOAK_RUN_DURATION_MILLIS
        private const val MAXIMUM_DISCONNECTS_BOUND = 32
        private const val MAXIMUM_CAMERA_RESTART_ATTEMPTS_BOUND = 8
        private const val MAXIMUM_CAMERA_RESTART_DELAY_MILLIS = 10_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val NO_DEADLINE = 0L
        private val FAIL_CLOSED_DISCONNECT_REASONS = setOf(
            LiveLinkDisconnectReason.AUTHENTICATION,
            LiveLinkDisconnectReason.CONFIGURATION,
            LiveLinkDisconnectReason.PROTOCOL,
            LiveLinkDisconnectReason.INTERNAL,
        )
        private val AUTHENTICATED_LIVE_LINK_PEER = AuthenticatedStreamPeer("authenticated-live-link-peer")
        private val LIVE_STREAMS = setOf(
            SensorStreamKind.SENSOR_STREAM_KIND_CAMERA,
            SensorStreamKind.SENSOR_STREAM_KIND_IMU,
            SensorStreamKind.SENSOR_STREAM_KIND_TOUCH,
        )
    }
}

private class LiveCameraSourceMetrics {
    private val requestToImage = LiveTimingWindow()
    private val imageAcquisition = LiveTimingWindow()
    private val gateAndResizeProcessor = LiveTimingWindow()
    private val listenerPath = LiveTimingWindow()
    private val pipeline = LiveCapturePipelineAccumulator()

    fun record(event: CaptureTimingEvent) {
        event.requestToImageLatencyNanos?.let(requestToImage::record)
        imageAcquisition.record(event.imageAcquisitionDurationNanos)
        gateAndResizeProcessor.record(event.processorDurationNanos)
        listenerPath.record(event.listenerPathDurationNanos)
    }

    fun recordPipeline(generation: Long, snapshot: CapturePipelineSnapshot) {
        pipeline.record(generation, snapshot)
    }

    fun snapshot(): LiveCameraSourceTimingSnapshot = LiveCameraSourceTimingSnapshot(
        requestToImage = requestToImage.snapshot(),
        imageAcquisition = imageAcquisition.snapshot(),
        gateAndResizeProcessor = gateAndResizeProcessor.snapshot(),
        listenerPath = listenerPath.snapshot(),
        pipeline = pipeline.snapshot(),
    )
}

private class LiveTimingWindow(private val capacity: Int = LIVE_TIMING_WINDOW_CAPACITY) {
    private val samples = LongArray(capacity)
    private var retained = 0
    private var next = 0
    private var total = 0L
    private var maximum = 0L

    init {
        require(capacity in 1..MAXIMUM_LIVE_TIMING_WINDOW_CAPACITY)
    }

    @Synchronized
    fun record(durationNanos: Long) {
        if (durationNanos < 0L) return
        samples[next] = durationNanos
        next = (next + 1) % capacity
        retained = minOf(capacity, retained + 1)
        total += 1L
        maximum = maxOf(maximum, durationNanos)
    }

    @Synchronized
    fun snapshot(): LiveTimingDistributionSnapshot {
        if (retained == 0) return LiveTimingDistributionSnapshot()
        val sorted = samples.copyOf(retained).sortedArray()
        return LiveTimingDistributionSnapshot(
            samples = total,
            p50Nanos = sorted.liveNearestRank(0.50),
            p95Nanos = sorted.liveNearestRank(0.95),
            p99Nanos = sorted.liveNearestRank(0.99),
            maximumNanos = maximum,
        )
    }
}

private class LiveCapturePipelineAccumulator {
    private var activeGeneration: Long? = null
    private var completed = emptyCapturePipelineSnapshot()
    private var active = emptyCapturePipelineSnapshot()

    @Synchronized
    fun record(generation: Long, snapshot: CapturePipelineSnapshot) {
        if (activeGeneration != generation) {
            completed = completed.plusCompleted(active)
            activeGeneration = generation
        }
        active = snapshot
    }

    @Synchronized
    fun snapshot(): LiveCapturePipelineSnapshot {
        val aggregate = completed.plusCompleted(active)
        return LiveCapturePipelineSnapshot(
            requestsSubmitted = aggregate.requestsSubmitted,
            opportunitiesBackpressured = aggregate.opportunitiesBackpressured,
            requestsSuperseded = aggregate.requestsSuperseded,
            imagesWithoutExactRequestMatch = aggregate.imagesWithoutExactRequestMatch,
            captureFailures = aggregate.captureFailures,
            lateCallbacks = aggregate.lateCallbacks,
            outstandingRequests = active.outstandingRequests,
            maximumOutstandingRequests = maxOf(
                completed.maximumOutstandingRequests,
                active.maximumOutstandingRequests,
            ),
        )
    }
}

private fun emptyCapturePipelineSnapshot(): CapturePipelineSnapshot =
    CapturePipelineSnapshot(0, 0, 0, 0, 0, 0, 0, 0)

private fun CapturePipelineSnapshot.plusCompleted(other: CapturePipelineSnapshot): CapturePipelineSnapshot =
    CapturePipelineSnapshot(
        requestsSubmitted = requestsSubmitted + other.requestsSubmitted,
        opportunitiesBackpressured = opportunitiesBackpressured + other.opportunitiesBackpressured,
        requestsSuperseded = requestsSuperseded + other.requestsSuperseded,
        imagesWithoutExactRequestMatch = imagesWithoutExactRequestMatch + other.imagesWithoutExactRequestMatch,
        captureFailures = captureFailures + other.captureFailures,
        lateCallbacks = lateCallbacks + other.lateCallbacks,
        outstandingRequests = 0,
        maximumOutstandingRequests = maxOf(maximumOutstandingRequests, other.maximumOutstandingRequests),
    )

private fun LongArray.liveNearestRank(percentile: Double): Long {
    val rank = kotlin.math.ceil(percentile * size).toInt().coerceIn(1, size)
    return this[rank - 1]
}

private const val LIVE_TIMING_WINDOW_CAPACITY = 256
private const val MAXIMUM_LIVE_TIMING_WINDOW_CAPACITY = 4_096
