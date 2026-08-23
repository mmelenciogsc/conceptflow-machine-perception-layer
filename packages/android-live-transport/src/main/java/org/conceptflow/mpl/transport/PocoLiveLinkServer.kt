// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.io.Closeable
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket
import org.conceptflow.mpl.v1.LiveLinkControl
import org.conceptflow.mpl.v1.LiveLinkEnvelope
import org.conceptflow.mpl.v1.LiveTransportLane
import org.conceptflow.mpl.v1.LiveTransportPeerRole

/** Poco-side two-port mutual-TLS receiver. One authenticated glasses session is active at a time. */
class PocoLiveLinkServer(
    private val config: LiveLinkPrivateConfig,
    private val tls: PinnedMutualTls,
    private val clock: MonotonicTimeSource = AndroidMonotonicTimeSource,
    private val secureRandom: SecureRandom = SecureRandom(),
) : Closeable {
    private val running = AtomicBoolean(false)
    private val draining = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "mpl-live-host").apply { isDaemon = true }
    }
    private val metrics = SanitizedTransportMetrics()
    private val metricAccounting = EndpointMetricAccounting(metrics)
    private val activeAttempt = ActiveConnectionAttempt()
    private val activeShutdown = AtomicReference<ServerShutdownContext?>()
    private val shutdownWorker = BoundedEndpointShutdownWorker<Unit>()
    private var shutdownCompletion: CompletableFuture<Unit>? = null
    @Volatile
    private var realtimeServer: SSLServerSocket? = null
    @Volatile
    private var cameraServer: SSLServerSocket? = null

    init {
        require(config.role == LiveLinkEndpointRole.POCO_HOST) { "Poco server requires host configuration" }
    }

    fun start(observer: PocoLiveLinkObserver) {
        check(!disposed.get()) { "Poco live-link server is disposed" }
        check(running.compareAndSet(false, true)) { "Poco live-link server is already running" }
        try {
            realtimeServer = openServerSocket(tls, config, config.realtimePort)
            cameraServer = openServerSocket(tls, config, config.cameraPort)
        } catch (error: Exception) {
            metricAccounting.failure(error)
            running.set(false)
            closeListeners()
            throw error
        }
        executor.execute { serve(observer) }
    }

    fun metricsSnapshot(): TransportMetricsSnapshot = metrics.snapshot()

    private fun serve(observer: PocoLiveLinkObserver) {
        while (running.get() && !draining.get()) {
            val attempt = ConnectionAttemptResources()
            activeAttempt.activate(attempt)
            val termination = ConnectionTermination()
            val state = LiveConnectionState(metrics)
            var cameraAdmissionFuture: Future<AuthenticatedTlsLane>? = null
            var cameraReaderFuture: Future<*>? = null
            var cameraAdmissionCompletion: WorkerCompletion? = null
            var cameraReaderCompletion: WorkerCompletion? = null
            var activeLeaseDeadline: MonotonicLeaseDeadline? = null
            var shutdownContext: ServerShutdownContext? = null
            var notified = false
            try {
                val realtime = attempt.own(
                    acceptServerLane(
                        tls,
                        requireNotNull(realtimeServer),
                        LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                        running,
                        config.socketReadTimeoutMs,
                        onAcceptedSocket = { attempt.own(it) },
                    ),
                )
                realtime.socket.soTimeout = config.socketReadTimeoutMs.coerceAtMost(IO_POLL_TIMEOUT_MS)
                val handshake = establishRealtime(realtime, state)
                activeLeaseDeadline = handshake.leaseDeadline
                val admissionGate = RealtimeAdmissionGate(handshake.cameraAdmission)
                val admissionCompletion = WorkerCompletion()
                cameraAdmissionCompletion = admissionCompletion
                cameraAdmissionFuture = executor.submit<AuthenticatedTlsLane> {
                    admissionCompletion.begin()
                    try {
                        attempt.own(
                            acceptServerLane(
                                tls,
                                requireNotNull(cameraServer),
                                LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA,
                                running,
                                config.socketReadTimeoutMs,
                                admissionCheck = { admissionGate.requireCameraAdmission(clock.nowNs()) },
                                onAcceptedSocket = { attempt.own(it) },
                            ),
                        ).also { admissionGate.requireCameraAdmission(clock.nowNs()) }
                    } finally {
                        admissionCompletion.finish()
                    }
                }
                val camera = awaitCameraAdmission(
                    realtime,
                    state,
                    admissionGate,
                    cameraAdmissionFuture,
                )
                establishCamera(camera, state, handshake.binding)
                metricAccounting.established()
                val closeState = GracefulSessionCloseState(handshake.binding)
                shutdownContext = ServerShutdownContext(
                    handshake.binding,
                    realtime,
                    state,
                    closeState,
                )
                check(activeShutdown.compareAndSet(null, shutdownContext)) {
                    "a live-link host shutdown context is already active"
                }
                observer.onSessionReady(
                    LiveLinkSession(handshake.binding, handshake.clockEstimate, handshake.lease),
                )
                notified = true
                camera.socket.soTimeout = config.socketReadTimeoutMs.coerceAtMost(IO_POLL_TIMEOUT_MS)
                val readerCompletion = WorkerCompletion()
                cameraReaderCompletion = readerCompletion
                cameraReaderFuture = executor.submit {
                    readerCompletion.begin()
                    try {
                        receiveCamera(camera, state, handshake.leaseDeadline, closeState, observer)
                    } catch (error: Throwable) {
                        if (!awaitAuthenticatedCameraLaneClosure(
                                error,
                                closeState,
                                CAMERA_CLOSE_AUTHENTICATION_GRACE_MS,
                            )
                        ) {
                            termination.record(error, LiveLinkFailureLane.CAMERA)
                            attempt.close()
                            throw error
                        }
                    } finally {
                        readerCompletion.finish()
                    }
                }
                receiveRealtime(
                    realtime,
                    state,
                    handshake.binding,
                    handshake.leaseDeadline,
                    closeState,
                    observer,
                )
                cameraReaderFuture.get()
                if (closeState.hasAuthenticatedRemoteClose()) throw RemoteSessionCompletedException()
            } catch (error: Exception) {
                val root = unwrap(error)
                val authenticatedRemoteClose = shutdownContext?.closeState?.hasAuthenticatedRemoteClose() == true
                val fallbackLane = when (root) {
                    is RemoteSessionCompletedException,
                    is InterruptedException,
                    is LeaseExpiredException,
                    -> LiveLinkFailureLane.NONE
                    else -> LiveLinkFailureLane.REALTIME_CONTROL
                }
                val effective = termination.resolve(
                    root,
                    activeLeaseDeadline,
                    clock.nowNs(),
                    fallbackLane,
                    authenticatedRemoteClose,
                )
                metrics.recordHostClose(authenticatedRemoteClose, termination.failureLane())
                observer.onCloseEvidence(metrics.snapshot().closeEvidence)
                if (effective !is RemoteSessionCompletedException) {
                    metricAccounting.failure(effective)
                    observer.onDiagnostic(classifyDiagnostic(effective))
                }
                if (running.get() || notified) observer.onDisconnected(classifyDisconnect(effective))
                if (effective is RemoteSessionCompletedException) {
                    running.set(false)
                    closeListeners()
                }
            } finally {
                cameraAdmissionFuture?.cancel(true)
                cameraReaderFuture?.cancel(true)
                attempt.close()
                cameraAdmissionFuture?.let { future ->
                    cameraAdmissionCompletion?.await(future, WORKER_JOIN_TIMEOUT_MS)
                }
                cameraReaderFuture?.let { future ->
                    cameraReaderCompletion?.await(future, WORKER_JOIN_TIMEOUT_MS)
                }
                shutdownContext?.let { activeShutdown.compareAndSet(it, null) }
                activeAttempt.release(attempt)
                state.disconnect()
            }
        }
    }

    /** Watches the authenticated realtime socket while the independent camera lane is admitted. */
    private fun awaitCameraAdmission(
        realtime: AuthenticatedTlsLane,
        state: LiveConnectionState,
        gate: RealtimeAdmissionGate,
        future: Future<AuthenticatedTlsLane>,
    ): AuthenticatedTlsLane {
        try {
            while (running.get()) {
                if (future.isDone) return future.get()
                val read = try {
                    AdmissionRead(readTracked(realtime, metrics), timedOut = false)
                } catch (_: SocketTimeoutException) {
                    AdmissionRead(null, timedOut = true)
                }
                if (!read.timedOut && read.envelope == null) {
                    throw java.io.EOFException("realtime lane closed before camera admission")
                }
                if (read.envelope != null) {
                    state.accept(read.envelope)
                    throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
                }
            }
            throw InterruptedException("live-link endpoint stopped")
        } catch (error: Exception) {
            gate.markRealtimeClosed()
            future.cancel(true)
            throw error
        }
    }

    private fun establishRealtime(
        lane: AuthenticatedTlsLane,
        state: LiveConnectionState,
    ): ServerHandshake {
        val helloEnvelope = readTracked(lane, metrics)
            ?: throw java.io.EOFException("realtime lane closed during hello")
        val hello = helloEnvelope.control.takeIf {
            helloEnvelope.lane == LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL &&
                helloEnvelope.laneSequenceId == 1L &&
                helloEnvelope.payloadCase == LiveLinkEnvelope.PayloadCase.CONTROL &&
                it.payloadCase == LiveLinkControl.PayloadCase.HELLO &&
                it.hello.peerRole == LiveTransportPeerRole.LIVE_TRANSPORT_PEER_ROLE_GLASSES &&
                it.hello.protocolVersion.major == 1
        } ?: throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
        val binding = LiveSessionBinding(
            helloEnvelope.sessionId,
            helloEnvelope.leaseId,
            hello.hello.connectionNonce.toByteArray(),
        )
        state.reconnect(binding, freshTicketKey(secureRandom), clock.nowNs())
        acceptInbound(state, helloEnvelope)
        val output = LiveEnvelopeFactory(binding, state, clock)
        val ticketIssuedNs = clock.nowNs()
        val ticketLifetimeNs = TimeUnit.MILLISECONDS.toNanos(config.cameraTicketLifetimeMs.toLong())
        val ticket = state.issueCameraTicket(
            ticketIssuedNs,
            ticketLifetimeNs,
        )
        writeTracked(
            lane,
            output.control(
                LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                LiveControlMessages.ticketGrant(ticket, config.cameraTicketLifetimeMs),
            ),
            metrics,
        )

        val leaseEnvelope = readTracked(lane, metrics)
            ?: throw java.io.EOFException("realtime lane closed during lease negotiation")
        acceptInbound(state, leaseEnvelope)
        require(leaseEnvelope.control.payloadCase == LiveLinkControl.PayloadCase.LEASE_REQUEST) {
            "expected a stream lease request"
        }
        val grant = LiveControlMessages.leaseGrant(leaseEnvelope.control.leaseRequest)
        writeTracked(
            lane,
            output.control(LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL, grant),
            metrics,
        )

        state.beginClockRound()
        repeat(LiveControlMessages.CLOCK_PROBES) { index ->
            val probeId = index + 1L
            val t0 = clock.nowNs()
            writeTracked(
                lane,
                output.control(
                    LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                    LiveControlMessages.clockRequest(probeId, t0),
                ),
                metrics,
            )
            val responseEnvelope = readTracked(lane, metrics)
                ?: throw java.io.EOFException("realtime lane closed during clock synchronization")
            val t3 = acceptInbound(state, responseEnvelope)
            val response = responseEnvelope.control.takeIf {
                it.payloadCase == LiveLinkControl.PayloadCase.CLOCK_SYNC_RESPONSE &&
                    it.clockSyncResponse.probeId == probeId &&
                    it.clockSyncResponse.initiatorSendMonotonicNs == t0
            }?.clockSyncResponse ?: throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
            state.addClockProbe(
                FourTimestampClockProbe(
                    probeId,
                    t0,
                    response.responderReceiveMonotonicNs,
                    response.responderSendMonotonicNs,
                    t3,
                ),
            )
        }
        val leaseDeadline = MonotonicLeaseDeadline.fromDurationMillis(
            clock.nowNs(),
            grant.leaseGrant.grantedDurationMs,
        )
        return ServerHandshake(
            binding,
            state.commitClockRound(clock.nowNs()),
            leaseDeadline,
            grant.leaseGrant.toNegotiatedLease(leaseDeadline),
            CameraLaneAdmissionWindow(Math.addExact(ticketIssuedNs, ticketLifetimeNs)),
        )
    }

    private fun establishCamera(
        lane: AuthenticatedTlsLane,
        state: LiveConnectionState,
        binding: LiveSessionBinding,
    ) {
        val request = readTracked(lane, metrics)
            ?: throw java.io.EOFException("camera lane closed during authentication")
        state.acceptCameraLaneOpenAtomic(request, clock)
        val output = LiveEnvelopeFactory(binding, state, clock)
        writeTracked(
            lane,
            output.control(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA, LiveControlMessages.laneOpenAccepted()),
            metrics,
        )
    }

    private fun receiveRealtime(
        lane: AuthenticatedTlsLane,
        state: LiveConnectionState,
        binding: LiveSessionBinding,
        leaseDeadline: MonotonicLeaseDeadline,
        closeState: GracefulSessionCloseState,
        observer: PocoLiveLinkObserver,
    ) {
        val resync = ClockResyncSchedule().also { it.arm(clock.nowNs()) }
        var nextPeriodicProbeId = PERIODIC_CLOCK_PROBE_ID_START
        while (running.get()) {
            leaseDeadline.requireActive(clock.nowNs())
            if (resync.isDue(clock.nowNs())) {
                val resynchronizedProbeId = performPeriodicClockSync(
                    lane,
                    state,
                    binding,
                    closeState,
                    observer,
                    nextPeriodicProbeId,
                )
                if (resynchronizedProbeId == null) return
                nextPeriodicProbeId = resynchronizedProbeId
                resync.markCompleted(clock.nowNs())
            }
            serviceHostKeepalive(lane, state, binding)
            val envelope = try {
                readTracked(lane, metrics)
            } catch (_: SocketTimeoutException) {
                serviceHostKeepalive(lane, state, binding)
                continue
            } ?: throw java.io.EOFException("realtime lane closed")
            val now = acceptInbound(state, envelope)
            when (envelope.payloadCase) {
                LiveLinkEnvelope.PayloadCase.SENSOR -> {
                    require(envelope.sensor.hasImuBatch() && !envelope.sensor.hasMicrophoneChunk()) {
                        "realtime lane accepts only IMU sensor batches"
                    }
                    observer.onSensor(LiveSensorTimestampNormalizer.normalize(envelope, state, now))
                }
                LiveLinkEnvelope.PayloadCase.CONTROL -> {
                    if (closeState.acceptAcknowledgement(envelope.control)) return
                    if (acknowledgeLeaseCloseIfPresent(lane, state, binding, envelope, closeState)) {
                        return
                    }
                    if (envelope.control.payloadCase != LiveLinkControl.PayloadCase.KEEPALIVE ||
                        !envelope.control.keepalive.response
                    ) {
                        throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
                    }
                    // The matching nonce was validated and cleared atomically with admission.
                }
                else -> throw LaneProtocolException(LaneProtocolFailure.PAYLOAD_LANE_MISMATCH)
            }
        }
    }

    /** Refreshes offset and drift evidence without discarding interleaved bounded IMU batches. */
    private fun performPeriodicClockSync(
        lane: AuthenticatedTlsLane,
        state: LiveConnectionState,
        binding: LiveSessionBinding,
        closeState: GracefulSessionCloseState,
        observer: PocoLiveLinkObserver,
        firstProbeId: Long,
    ): Long? {
        val output = LiveEnvelopeFactory(binding, state, clock)
        state.beginClockRound()
        var probeId = firstProbeId
        repeat(LiveControlMessages.CLOCK_PROBES) {
            val t0 = clock.nowNs()
            writeTracked(
                lane,
                output.control(
                    LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                    LiveControlMessages.clockRequest(probeId, t0),
                ),
                metrics,
            )
            while (true) {
                val responseEnvelope = readTracked(lane, metrics)
                    ?: throw java.io.EOFException("realtime lane closed during clock resynchronization")
                val t3 = acceptInbound(state, responseEnvelope)
                when (classifyPeriodicClockInbound(responseEnvelope, binding, probeId, t0)) {
                    PeriodicClockInboundKind.REMOTE_CLOSE_REQUEST -> {
                        check(acknowledgeLeaseCloseIfPresent(
                            lane,
                            state,
                            binding,
                            responseEnvelope,
                            closeState,
                        ))
                        return null
                    }
                    PeriodicClockInboundKind.LOCAL_CLOSE_ACKNOWLEDGEMENT -> {
                        if (!closeState.acceptAcknowledgement(responseEnvelope.control)) {
                            throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
                        }
                        return null
                    }
                    PeriodicClockInboundKind.IMU_BATCH -> {
                        observer.onSensor(LiveSensorTimestampNormalizer.normalize(responseEnvelope, state, t3))
                    }
                    PeriodicClockInboundKind.KEEPALIVE_RESPONSE -> {
                        // The matching nonce was validated and cleared atomically with admission.
                        Unit
                    }
                    PeriodicClockInboundKind.EXPECTED_CLOCK_RESPONSE -> {
                        val response = responseEnvelope.control.clockSyncResponse
                        state.addClockProbe(
                            FourTimestampClockProbe(
                                probeId,
                                t0,
                                response.responderReceiveMonotonicNs,
                                response.responderSendMonotonicNs,
                                t3,
                            ),
                        )
                        break
                    }
                }
            }
            probeId = Math.addExact(probeId, 1L)
        }
        state.commitClockRound(clock.nowNs())
        return probeId
    }

    private fun receiveCamera(
        lane: AuthenticatedTlsLane,
        state: LiveConnectionState,
        leaseDeadline: MonotonicLeaseDeadline,
        closeState: GracefulSessionCloseState,
        observer: PocoLiveLinkObserver,
    ) {
        while (running.get()) {
            leaseDeadline.requireActive(clock.nowNs())
            val envelope = try {
                readTracked(lane, metrics)
            } catch (_: SocketTimeoutException) {
                continue
            } ?: if (closeState.hasAuthenticatedCompletion()) {
                return
            } else {
                throw CameraLaneClosedException()
            }
            val now = acceptInbound(state, envelope)
            require(envelope.hasSensor() && envelope.sensor.hasCameraChunk() &&
                !envelope.sensor.hasMicrophoneChunk()
            ) { "camera lane accepts only camera chunks" }
            observer.onSensor(LiveSensorTimestampNormalizer.normalize(envelope, state, now))
        }
    }

    private fun acknowledgeLeaseCloseIfPresent(
        lane: AuthenticatedTlsLane,
        state: LiveConnectionState,
        binding: LiveSessionBinding,
        envelope: LiveLinkEnvelope,
        closeState: GracefulSessionCloseState,
    ): Boolean {
        if (!envelope.hasControl() || !LiveControlMessages.isLeaseClose(envelope.control, binding)) return false
        if (!closeState.acceptRemoteCloseRequest(envelope.control)) {
            throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
        }
        synchronized(lane) {
            writeTracked(
                lane,
                LiveEnvelopeFactory(binding, state, clock).control(
                    LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                    LiveControlMessages.leaseCloseAcknowledged(binding),
                ),
                metrics,
            )
        }
        return true
    }

    private fun serviceHostKeepalive(
        lane: AuthenticatedTlsLane,
        state: LiveConnectionState,
        binding: LiveSessionBinding,
    ) {
        val decision = state.pollLivenessAtomic(clock, reserveKeepalive = true)
        when (decision.status) {
            LivenessStatus.KEEPALIVE_DUE -> {
                val nonce = checkNotNull(decision.keepaliveNonce) {
                    "due host keepalive must reserve a nonce"
                }
                writeTracked(
                    lane,
                    LiveEnvelopeFactory(binding, state, clock).control(
                        LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                        LiveControlMessages.keepalive(nonce, decision.sampledAtNs, response = false),
                    ),
                    metrics,
                )
            }
            LivenessStatus.TIMED_OUT -> throw SocketTimeoutException("live-link liveness timeout")
            else -> Unit
        }
    }

    private fun acceptInbound(
        state: LiveConnectionState,
        envelope: LiveLinkEnvelope,
    ): Long = state.acceptInboundAtomic(envelope, clock)

    private data class ServerHandshake(
        val binding: LiveSessionBinding,
        val clockEstimate: ClockOffsetEstimate,
        val leaseDeadline: MonotonicLeaseDeadline,
        val lease: NegotiatedLiveLease,
        val cameraAdmission: CameraLaneAdmissionWindow,
    )

    private data class AdmissionRead(val envelope: LiveLinkEnvelope?, val timedOut: Boolean)

    private data class ServerShutdownContext(
        val binding: LiveSessionBinding,
        val realtime: AuthenticatedTlsLane,
        val connectionState: LiveConnectionState,
        val closeState: GracefulSessionCloseState,
    )

    private fun unwrap(error: Throwable): Throwable =
        if (error is java.util.concurrent.ExecutionException && error.cause != null) error.cause!! else error

    private fun closeListeners() {
        runCatching { cameraServer?.close() }
        runCatching { realtimeServer?.close() }
        cameraServer = null
        realtimeServer = null
    }

    /** Returns immediately; authenticated close and teardown execute away from the caller thread. */
    @Synchronized
    fun closeAsync(onClosed: () -> Unit) {
        var completion = shutdownCompletion
        if (completion == null) {
            check(disposed.compareAndSet(false, true)) { "server disposal state is inconsistent" }
            draining.set(true)
            val shutdown = activeShutdown.get()
            completion = shutdownWorker.execute(
                timeoutMs = CLOSE_OPERATION_TIMEOUT_MS,
                onTimeout = {
                    forceCloseResources()
                    Unit
                },
                operation = {
                    completeClose(shutdown)
                    Unit
                },
            )
            shutdownCompletion = completion
        }
        completion.whenComplete { _, _ -> onClosed() }
    }

    private fun completeClose(shutdown: ServerShutdownContext?) {
        try {
            if (shutdown != null && !shutdown.closeState.hasAuthenticatedRemoteClose()) {
                shutdown.closeState.beginDrain()
                runCatching {
                    check(shutdown.closeState.beginCloseRequest()) { "graceful host close request already sent" }
                    synchronized(shutdown.realtime) {
                        writeTracked(
                            shutdown.realtime,
                            LiveEnvelopeFactory(shutdown.binding, shutdown.connectionState, clock).control(
                                LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                                LiveControlMessages.leaseClose(shutdown.binding),
                            ),
                            metrics,
                        )
                    }
                    shutdown.closeState.awaitAcknowledgement(CLOSE_ACK_TIMEOUT_MS)
                }
            }
        } finally {
            forceCloseResources()
        }
    }

    private fun forceCloseResources() {
        running.set(false)
        activeAttempt.closeCurrent()
        closeListeners()
        executor.shutdownNow()
        executor.awaitTerminationPreservingInterrupt(2, TimeUnit.SECONDS)
    }

    override fun close() = closeAsync {}

    companion object {
        private const val WORKER_JOIN_TIMEOUT_MS = 1_000L
        private const val CLOSE_ACK_TIMEOUT_MS = 500L
        private const val CLOSE_OPERATION_TIMEOUT_MS = 4_000L
        private const val CAMERA_CLOSE_AUTHENTICATION_GRACE_MS = 2_000L
        private const val PERIODIC_CLOCK_PROBE_ID_START = 10_000L

        fun fromConfig(config: LiveLinkPrivateConfig): PocoLiveLinkServer =
            PocoLiveLinkServer(config, buildPinnedTls(config))
    }
}
