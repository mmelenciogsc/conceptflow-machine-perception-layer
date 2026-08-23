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
import org.conceptflow.mpl.v1.LiveLinkControl
import org.conceptflow.mpl.v1.LiveLinkEnvelope
import org.conceptflow.mpl.v1.LiveTransportLane
import org.conceptflow.mpl.v1.LiveTransportPeerRole
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.conceptflow.mpl.v1.SensorStreamKind

/** Direct private-WLAN glasses client. Camera and realtime/control use independent TLS sockets. */
class RokidLiveLinkClient(
    private val config: LiveLinkPrivateConfig,
    private val tls: PinnedMutualTls,
    private val clock: MonotonicTimeSource = AndroidMonotonicTimeSource,
    private val bindingFactory: () -> LiveSessionBinding = { EphemeralBindingFactory().create() },
    private val queues: LiveOutboundQueues = LiveOutboundQueues(),
) : Closeable {
    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val draining = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "mpl-live-glasses").apply { isDaemon = true }
    }
    private val metrics = SanitizedTransportMetrics()
    private val metricAccounting = EndpointMetricAccounting(metrics)
    private val activeAttempt = ActiveConnectionAttempt()
    private val activeShutdown = AtomicReference<ClientShutdownContext?>()
    private val shutdownWorker = BoundedEndpointShutdownWorker<LiveLinkCloseEvidence>()
    private var shutdownCompletion: CompletableFuture<LiveLinkCloseEvidence>? = null

    init {
        require(config.role == LiveLinkEndpointRole.ROKID_CLIENT) { "Rokid client requires client configuration" }
    }

    fun start(observer: RokidLiveLinkObserver) {
        check(!disposed.get()) { "Rokid live-link client is disposed" }
        check(running.compareAndSet(false, true)) { "Rokid live-link client is already running" }
        executor.execute { connectLoop(observer) }
    }

    fun offerCameraFrame(chunks: List<SensorStreamEnvelope>): Boolean {
        if (!connected.get() || draining.get()) return false
        val before = queues.snapshot().droppedCameraFrames
        queues.offerCameraFrame(chunks)
        val after = queues.snapshot().droppedCameraFrames
        if (after > before) metrics.recordDropped(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA, after - before)
        metrics.recordQueueDepth(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA, 1)
        return true
    }

    fun offerImu(batch: SensorStreamEnvelope): Boolean {
        if (!connected.get() || draining.get()) return false
        val before = queues.snapshot().droppedImuBatches
        queues.offerImu(batch)
        val snapshot = queues.snapshot()
        if (snapshot.droppedImuBatches > before) {
            metrics.recordDropped(
                LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                snapshot.droppedImuBatches - before,
            )
        }
        metrics.recordQueueDepth(
            LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
            snapshot.pendingImuBatches,
        )
        return true
    }

    /** Microphone remains structurally unavailable in this baseline. */
    fun offerMicrophone(@Suppress("UNUSED_PARAMETER") chunk: SensorStreamEnvelope): Boolean = false

    fun metricsSnapshot(): TransportMetricsSnapshot = metrics.snapshot()

    fun closeEvidenceSnapshot(): LiveLinkCloseEvidence = metrics.snapshot().closeEvidence

    private fun connectLoop(observer: RokidLiveLinkObserver) {
        var retryDelayMs = MINIMUM_RETRY_MS
        while (running.get() && !draining.get()) {
            val attempt = ConnectionAttemptResources()
            activeAttempt.activate(attempt)
            val termination = ConnectionTermination()
            val state = LiveConnectionState(metrics)
            var cameraFuture: Future<*>? = null
            var realtimeWriterFuture: Future<*>? = null
            var cameraCompletion: WorkerCompletion? = null
            var realtimeWriterCompletion: WorkerCompletion? = null
            var shutdownContext: ClientShutdownContext? = null
            var activeLeaseDeadline: MonotonicLeaseDeadline? = null
            var notified = false
            try {
                queues.reset()
                val binding = bindingFactory()
                val realtime = attempt.own(
                    openClientLane(
                        tls,
                        config,
                        config.realtimePort,
                        LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                        onConnectedSocket = { attempt.own(it) },
                    ),
                )
                state.reconnectWithoutTicketAuthority(binding, clock.nowNs())
                val handshake = establishRealtime(realtime, state, binding)
                activeLeaseDeadline = handshake.leaseDeadline
                val camera = attempt.own(
                    openClientLane(
                        tls,
                        config,
                        config.cameraPort,
                        LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA,
                        onConnectedSocket = { attempt.own(it) },
                    ),
                )
                establishCamera(camera, state, binding, handshake.ticket)
                metricAccounting.established()
                realtime.socket.soTimeout = config.socketReadTimeoutMs.coerceAtMost(IO_POLL_TIMEOUT_MS)
                camera.socket.soTimeout = config.socketReadTimeoutMs.coerceAtMost(IO_POLL_TIMEOUT_MS)
                val cameraDone = WorkerCompletion()
                cameraCompletion = cameraDone
                cameraFuture = executor.submit {
                    cameraDone.begin()
                    try {
                        sendCamera(camera, state, binding, handshake.leaseDeadline)
                    } catch (error: Throwable) {
                        termination.record(error)
                        attempt.close()
                        throw error
                    } finally {
                        cameraDone.finish()
                    }
                }
                val realtimeDone = WorkerCompletion()
                realtimeWriterCompletion = realtimeDone
                realtimeWriterFuture = executor.submit {
                    realtimeDone.begin()
                    try {
                        sendRealtime(realtime, state, binding, handshake.leaseDeadline)
                    } catch (error: Throwable) {
                        termination.record(error)
                        attempt.close()
                        throw error
                    } finally {
                        realtimeDone.finish()
                    }
                }
                if (!running.get() || draining.get()) throw InterruptedException("live-link endpoint stopped")
                val closeState = GracefulSessionCloseState(binding)
                shutdownContext = ClientShutdownContext(
                    binding,
                    realtime,
                    state,
                    closeState,
                    listOf(cameraDone to cameraFuture, realtimeDone to realtimeWriterFuture),
                )
                check(activeShutdown.compareAndSet(null, shutdownContext)) {
                    "a live-link graceful shutdown context is already active"
                }
                if (!running.get() || draining.get()) throw InterruptedException("live-link endpoint stopped")
                connected.set(true)
                observer.onSessionReady(LiveLinkSession(binding, clockEstimate = null, handshake.lease))
                notified = true
                retryDelayMs = MINIMUM_RETRY_MS
                receiveRealtime(realtime, state, binding, handshake.leaseDeadline, closeState)
                cameraFuture.get()
                realtimeWriterFuture.get()
            } catch (error: Exception) {
                val root = unwrap(error)
                val effective = termination.resolve(root, activeLeaseDeadline, clock.nowNs())
                if (!draining.get()) {
                    if (effective !is RemoteSessionCompletedException) {
                        metricAccounting.failure(effective)
                        observer.onDiagnostic(classifyDiagnostic(effective))
                    }
                    if (running.get() || notified) observer.onDisconnected(classifyDisconnect(effective))
                    if (effective is LeaseExpiredException || effective is RemoteSessionCompletedException) {
                        running.set(false)
                    }
                }
            } finally {
                connected.set(false)
                cameraFuture?.cancel(true)
                realtimeWriterFuture?.cancel(true)
                attempt.close()
                awaitStopped(cameraCompletion, cameraFuture)
                awaitStopped(realtimeWriterCompletion, realtimeWriterFuture)
                shutdownContext?.let { activeShutdown.compareAndSet(it, null) }
                activeAttempt.release(attempt)
                queues.reset()
                state.disconnect()
            }
            if (running.get() && !draining.get()) {
                try {
                    Thread.sleep(retryDelayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAXIMUM_RETRY_MS)
            }
        }
    }

    private fun establishRealtime(
        lane: AuthenticatedTlsLane,
        state: LiveConnectionState,
        binding: LiveSessionBinding,
    ): ClientHandshake {
        val output = LiveEnvelopeFactory(binding, state, clock)
        writeTracked(
            lane,
            output.control(
                LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                LiveControlMessages.hello(
                    LiveTransportPeerRole.LIVE_TRANSPORT_PEER_ROLE_GLASSES,
                    binding.connectionNonce,
                ),
            ),
            metrics,
        )
        val ticketEnvelope = receiveAccepted(lane, state)
        val ticketGrant = ticketEnvelope.control.takeIf {
            it.payloadCase == LiveLinkControl.PayloadCase.LANE_TICKET_GRANT &&
                it.laneTicketGrant.lane == LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA &&
                !it.laneTicketGrant.laneTicket.isEmpty &&
                it.laneTicketGrant.validForMs in 1..30_000
        }?.laneTicketGrant ?: throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)

        writeTracked(
            lane,
            output.control(
                LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                LiveControlMessages.leaseRequest(binding),
            ),
            metrics,
        )
        val grantEnvelope = receiveAccepted(lane, state)
        val grant = grantEnvelope.control.takeIf {
            it.payloadCase == LiveLinkControl.PayloadCase.LEASE_GRANT &&
                it.leaseGrant.sessionId == binding.sessionId &&
                it.leaseGrant.leaseId == binding.leaseId &&
                !it.leaseGrant.hasError() &&
                it.leaseGrant.grantedStreamsList.toSet() == setOf(
                    SensorStreamKind.SENSOR_STREAM_KIND_CAMERA,
                    SensorStreamKind.SENSOR_STREAM_KIND_IMU,
                )
        }?.leaseGrant ?: throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
        require(grant.grantedDurationMs > 0) { "stream lease has no valid duration" }

        repeat(LiveControlMessages.CLOCK_PROBES) { index ->
            val requestEnvelope = receiveAccepted(lane, state)
            val receiveNs = clock.nowNs()
            val request = requestEnvelope.control.takeIf {
                it.payloadCase == LiveLinkControl.PayloadCase.CLOCK_SYNC_REQUEST &&
                    it.clockSyncRequest.probeId == index + 1L
            }?.clockSyncRequest ?: throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
            val sendNs = clock.nowNs()
            writeTracked(
                lane,
                output.control(
                    LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                    LiveControlMessages.clockResponse(request, receiveNs, sendNs),
                ),
                metrics,
            )
        }
        val leaseDeadline = MonotonicLeaseDeadline.fromDurationMillis(clock.nowNs(), grant.grantedDurationMs)
        return ClientHandshake(ticketGrant.laneTicket.toByteArray(), leaseDeadline, grant.toNegotiatedLease(leaseDeadline))
    }

    private fun establishCamera(
        lane: AuthenticatedTlsLane,
        state: LiveConnectionState,
        binding: LiveSessionBinding,
        ticket: ByteArray,
    ) {
        val output = LiveEnvelopeFactory(binding, state, clock)
        writeTracked(
            lane,
            output.control(
                LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA,
                LiveControlMessages.laneOpen(binding, ticket),
            ),
            metrics,
        )
        val response = receiveAccepted(lane, state)
        require(response.control.payloadCase == LiveLinkControl.PayloadCase.LANE_OPEN_RESPONSE &&
            response.control.laneOpenResponse.lane == LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA &&
            response.control.laneOpenResponse.accepted
        ) { "camera lane was rejected" }
    }

    private fun receiveRealtime(
        lane: AuthenticatedTlsLane,
        state: LiveConnectionState,
        binding: LiveSessionBinding,
        leaseDeadline: MonotonicLeaseDeadline,
        closeState: GracefulSessionCloseState,
    ) {
        val output = LiveEnvelopeFactory(binding, state, clock)
        while (running.get()) {
            leaseDeadline.requireActive(clock.nowNs())
            val envelope = try {
                readTracked(lane, metrics)
            } catch (_: SocketTimeoutException) {
                if (state.pollLivenessAtomic(clock, reserveKeepalive = false).status ==
                    LivenessStatus.TIMED_OUT
                ) {
                    throw SocketTimeoutException("live-link liveness timeout")
                }
                continue
            } ?: throw java.io.EOFException("realtime lane closed")
            val now = state.acceptInboundAtomic(envelope, clock)
            when (envelope.control.payloadCase) {
                LiveLinkControl.PayloadCase.KEEPALIVE -> {
                    val keepalive = envelope.control.keepalive
                    if (keepalive.response) throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
                    synchronized(lane) {
                        writeTracked(
                            lane,
                            output.control(
                                LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                                LiveControlMessages.keepalive(keepalive.nonce, now, response = true),
                            ),
                            metrics,
                        )
                    }
                }
                LiveLinkControl.PayloadCase.CLOCK_SYNC_REQUEST -> {
                    val request = envelope.control.clockSyncRequest
                    if (request.probeId <= 0L) {
                        throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
                    }
                    val sendNs = clock.nowNs()
                    synchronized(lane) {
                        writeTracked(
                            lane,
                            output.control(
                                LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                                LiveControlMessages.clockResponse(request, now, sendNs),
                            ),
                            metrics,
                        )
                    }
                }
                LiveLinkControl.PayloadCase.LEASE_GRANT -> {
                    if (!closeState.acceptAcknowledgement(envelope.control)) {
                        throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
                    }
                    return
                }
                LiveLinkControl.PayloadCase.LEASE_REQUEST -> {
                    if (!closeState.acceptRemoteCloseRequest(envelope.control)) {
                        throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
                    }
                    synchronized(lane) {
                        writeTracked(
                            lane,
                            output.control(
                                LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                                LiveControlMessages.leaseCloseAcknowledged(binding),
                            ),
                            metrics,
                        )
                    }
                    throw RemoteSessionCompletedException()
                }
                else -> throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
            }
        }
    }

    private fun sendRealtime(
        lane: AuthenticatedTlsLane,
        state: LiveConnectionState,
        binding: LiveSessionBinding,
        leaseDeadline: MonotonicLeaseDeadline,
    ) {
        val output = LiveEnvelopeFactory(binding, state, clock)
        while (running.get() && !draining.get()) {
            leaseDeadline.requireActive(clock.nowNs())
            val sensor = queues.awaitImu(IMU_QUEUE_POLL_TIMEOUT_MS) ?: continue
            if (draining.get()) break
            leaseDeadline.requireActive(clock.nowNs())
            synchronized(lane) {
                writeTracked(
                    lane,
                    output.sensor(LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL, sensor),
                    metrics,
                )
            }
        }
    }

    private fun sendCamera(
        lane: AuthenticatedTlsLane,
        state: LiveConnectionState,
        binding: LiveSessionBinding,
        leaseDeadline: MonotonicLeaseDeadline,
    ) {
        val output = LiveEnvelopeFactory(binding, state, clock)
        while (running.get() && !draining.get()) {
            leaseDeadline.requireActive(clock.nowNs())
            val frame = queues.awaitCameraFrame(IO_POLL_TIMEOUT_MS.toLong()) ?: continue
            if (draining.get()) break
            leaseDeadline.requireActive(clock.nowNs())
            // Once a frame starts, finish every bounded chunk so the peer never observes a
            // locally initiated shutdown as a truncated protobuf record or partial frame.
            frame.forEach { sensor ->
                leaseDeadline.requireActive(clock.nowNs())
                writeTracked(
                    lane,
                    output.sensor(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA, sensor),
                    metrics,
                )
            }
        }
    }

    private fun receiveAccepted(
        lane: AuthenticatedTlsLane,
        state: LiveConnectionState,
    ): LiveLinkEnvelope {
        val envelope = readTracked(lane, metrics) ?: throw java.io.EOFException("live-link handshake closed")
        state.acceptInboundAtomic(envelope, clock)
        return envelope
    }

    private fun unwrap(error: Throwable): Throwable =
        if (error is java.util.concurrent.ExecutionException && error.cause != null) error.cause!! else error

    private fun awaitStopped(completion: WorkerCompletion?, worker: Future<*>?) {
        if (completion == null || worker == null) return
        runCatching { completion.await(worker, WORKER_JOIN_TIMEOUT_MS) }
    }

    private data class ClientHandshake(
        val ticket: ByteArray,
        val leaseDeadline: MonotonicLeaseDeadline,
        val lease: NegotiatedLiveLease,
    )

    private data class ClientShutdownContext(
        val binding: LiveSessionBinding,
        val realtime: AuthenticatedTlsLane,
        val connectionState: LiveConnectionState,
        val closeState: GracefulSessionCloseState,
        val writers: List<Pair<WorkerCompletion, Future<*>>>,
    )

    @Synchronized
    fun closeAsync(onClosed: (LiveLinkCloseEvidence) -> Unit) {
        var completion = shutdownCompletion
        if (completion == null) {
            check(disposed.compareAndSet(false, true)) { "client disposal state is inconsistent" }
            draining.set(true)
            connected.set(false)
            val shutdown = activeShutdown.get()
            queues.close()
            completion = shutdownWorker.execute(
                timeoutMs = CLOSE_OPERATION_TIMEOUT_MS,
                onTimeout = {
                    metrics.recordClientClose(
                        InitiatedSessionCloseOutcome(
                            closeRequestWritten = false,
                            writersDrained = false,
                            acknowledgementReceived = false,
                            requestFailure = LiveLinkCloseRequestFailure.SHUTDOWN_DEADLINE_EXCEEDED,
                        ),
                    )
                    forceCloseResources()
                    metrics.snapshot().closeEvidence
                },
                operation = { completeClose(shutdown) },
            )
            shutdownCompletion = completion
        }
        completion.whenComplete { evidence, _ ->
            onClosed(evidence ?: metrics.snapshot().closeEvidence)
        }
    }

    private fun completeClose(shutdown: ClientShutdownContext?): LiveLinkCloseEvidence {
        try {
            if (shutdown != null && !shutdown.closeState.hasAuthenticatedRemoteClose()) {
                val outcome = coordinateInitiatedSessionClose(
                    shutdown.closeState,
                    shutdown.writers,
                    DRAIN_TIMEOUT_MS,
                    CLOSE_ACK_TIMEOUT_MS,
                ) {
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
                }
                metrics.recordClientClose(outcome)
            }
        } finally {
            forceCloseResources()
        }
        return metrics.snapshot().closeEvidence
    }

    private fun forceCloseResources() {
        running.set(false)
        activeAttempt.closeCurrent()
        executor.shutdownNow()
        executor.awaitTerminationPreservingInterrupt(2, TimeUnit.SECONDS)
    }

    /** Non-blocking: the bounded authenticated shutdown always runs on the dedicated worker. */
    override fun close() = closeAsync {}

    companion object {
        private const val MINIMUM_RETRY_MS = 250L
        private const val MAXIMUM_RETRY_MS = 4_000L
        private const val IMU_QUEUE_POLL_TIMEOUT_MS = 20L
        private const val WORKER_JOIN_TIMEOUT_MS = 1_000L
        private const val DRAIN_TIMEOUT_MS = 5_000L
        private const val CLOSE_ACK_TIMEOUT_MS = 2_000L
        private const val CLOSE_OPERATION_TIMEOUT_MS = 10_000L

        fun fromConfig(config: LiveLinkPrivateConfig): RokidLiveLinkClient =
            RokidLiveLinkClient(config, buildPinnedTls(config))
    }
}
