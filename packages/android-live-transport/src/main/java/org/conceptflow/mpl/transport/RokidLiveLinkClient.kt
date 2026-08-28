// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import android.util.Log
import java.io.Closeable
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.conceptflow.mpl.v1.LiveLinkControl
import org.conceptflow.mpl.v1.LiveLinkEnvelope
import org.conceptflow.mpl.v1.LiveTransportLane
import org.conceptflow.mpl.v1.LiveTransportPeerRole
import org.conceptflow.mpl.v1.MicrophoneControlOperation
import org.conceptflow.mpl.v1.RokidGestureOperation
import org.conceptflow.mpl.v1.SensorStreamEnvelope

/** Direct private-WLAN glasses client. Camera and realtime/control use independent TLS sockets. */
class RokidLiveLinkClient(
    private val config: LiveLinkPrivateConfig,
    private val tls: PinnedMutualTls,
    private val clock: MonotonicTimeSource = AndroidMonotonicTimeSource,
    private val bindingFactory: () -> LiveSessionBinding = { EphemeralBindingFactory().create() },
    private val queues: LiveOutboundQueues = LiveOutboundQueues(),
    private val spoolProvider: RokidSpoolProvider = EmptyRokidSpoolProvider,
    private val endpointResolver: LiveLinkEndpointResolver = StaticLiveLinkEndpointResolver(config.address),
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
    private val activeGestureBinding = AtomicReference<LiveSessionBinding?>()
    private val pendingGestureControl = AtomicReference<LiveLinkControl?>()
    private val gestureOutbox = RokidGestureOutbox()
    private val microphoneIntentIds = AtomicLong(0L)
    private val nodeGestureIds = AtomicLong(0L)
    private val cameraGateTelemetry = AtomicReference(LiveCameraGateTelemetry())
    private val microphoneIntentTracker = GlassesMicrophoneIntentTracker()
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

    /** Updates aggregate gate observations without touching capture or queue behavior. */
    fun updateCameraGateTelemetry(snapshot: LiveCameraGateTelemetry) {
        cameraGateTelemetry.set(snapshot)
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

    fun offerMicrophone(chunk: SensorStreamEnvelope): Boolean {
        if (!connected.get() || draining.get()) return false
        val before = queues.snapshot().droppedMicrophoneChunks
        val accepted = queues.offerMicrophone(chunk)
        val snapshot = queues.snapshot()
        if (snapshot.droppedMicrophoneChunks > before) {
            metrics.recordDropped(
                LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                snapshot.droppedMicrophoneChunks - before,
            )
        }
        metrics.recordQueueDepth(
            LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
            snapshot.pendingImuBatches + snapshot.pendingMicrophoneChunks + snapshot.pendingTouchEvents,
        )
        return accepted
    }

    fun offerTouch(event: SensorStreamEnvelope): Boolean {
        if (!connected.get() || draining.get()) return false
        val accepted = queues.offerTouch(event)
        val snapshot = queues.snapshot()
        if (!accepted) {
            metrics.recordDropped(LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL, 1L)
        }
        metrics.recordQueueDepth(
            LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
            snapshot.pendingImuBatches + snapshot.pendingMicrophoneChunks + snapshot.pendingTouchEvents,
        )
        return accepted
    }

    fun requestMicrophoneFromUserGesture(): MicrophoneGestureDispatch =
        queueMicrophoneGesture(MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START)

    fun stopMicrophoneFromUserGesture(): MicrophoneGestureDispatch =
        queueMicrophoneGesture(MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_STOP)

    fun requestRokidGesture(
        operation: RokidGestureOperation,
        observedMonotonicNs: Long = clock.nowNs(),
    ): RokidGestureDispatch {
        require(RokidGestureCommandPolicy.commandFor(operation) != null)
        require(observedMonotonicNs > 0L)
        if (!running.get() || draining.get()) return RokidGestureDispatch.TRANSPORT_STOPPED
        val gestureId = nodeGestureIds.updateAndGet { previous -> Math.addExact(previous, 1L) }
        gestureOutbox.replace(PendingRokidGesture(gestureId, observedMonotonicNs, operation))
        return RokidGestureDispatch.QUEUED
    }

    private fun queueMicrophoneGesture(operation: MicrophoneControlOperation): MicrophoneGestureDispatch {
        val binding = activeGestureBinding.get()
            ?: return MicrophoneGestureDispatch.NO_AUTHENTICATED_SESSION
        if (!connected.get() || draining.get()) return MicrophoneGestureDispatch.NO_AUTHENTICATED_SESSION
        val intentId = microphoneIntentIds.updateAndGet { previous -> Math.addExact(previous, 1L) }
        val control = LiveControlMessages.microphoneControlIntent(binding, intentId, clock.nowNs(), operation)
        microphoneIntentTracker.record(intentId, operation)
        pendingGestureControl.set(control)
        return MicrophoneGestureDispatch.QUEUED
    }

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
                val endpointAddress = endpointResolver.awaitAddress(ENDPOINT_RESOLUTION_TIMEOUT_MS)
                val binding = bindingFactory()
                val realtime = attempt.own(
                    openClientLane(
                        tls,
                        config,
                        endpointAddress,
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
                        endpointAddress,
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
                activeGestureBinding.set(binding)
                connected.set(true)
                observer.onSessionReady(LiveLinkSession(binding, clockEstimate = null, handshake.lease))
                notified = true
                retryDelayMs = MINIMUM_RETRY_MS
                receiveRealtime(
                    realtime,
                    state,
                    binding,
                    handshake.leaseDeadline,
                    closeState,
                    observer,
                    GlassesRokidNodeCommandGuard(binding),
                )
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
                activeGestureBinding.set(null)
                pendingGestureControl.set(null)
                microphoneIntentTracker.resetConnection()
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
        writeTracked(
            lane,
            output.control(
                LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                LiveControlMessages.capabilities(
                    LiveTransportPeerRole.LIVE_TRANSPORT_PEER_ROLE_GLASSES,
                    supportsDiagnosticSpool = spoolProvider !== EmptyRokidSpoolProvider,
                ),
            ),
            metrics,
        )
        val capabilitiesEnvelope = receiveAccepted(lane, state)
        runCatching {
            LiveControlMessages.requireCompatibleCapabilities(
                capabilitiesEnvelope.control,
                LiveTransportPeerRole.LIVE_TRANSPORT_PEER_ROLE_HOST,
            )
        }.getOrElse { throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL) }
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
                it.leaseGrant.isAcceptedOpenGrant(binding)
        }?.leaseGrant ?: throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)

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
        observer: RokidLiveLinkObserver,
        nodeCommandGuard: GlassesRokidNodeCommandGuard,
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
                    lane.withWriteLock {
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
                    Log.d(DIAGNOSTIC_TAG, "state=periodic_clock request=received")
                    val sendNs = clock.nowNs()
                    lane.withWriteLock {
                        writeTracked(
                            lane,
                            output.control(
                                LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                                LiveControlMessages.clockResponse(request, now, sendNs),
                            ),
                            metrics,
                        )
                    }
                    Log.d(DIAGNOSTIC_TAG, "state=periodic_clock response=written")
                }
                LiveLinkControl.PayloadCase.LEASE_GRANT -> {
                    if (!closeState.acceptAcknowledgement(envelope.control)) {
                        throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
                    }
                    return
                }
                LiveLinkControl.PayloadCase.MICROPHONE_CONTROL_RESULT -> {
                    if (!LiveControlMessages.isMicrophoneControlResult(envelope.control, binding)) {
                        throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
                    }
                    microphoneIntentTracker.acceptResult(envelope.control.microphoneControlResult)?.let {
                        observer.onMicrophoneGestureResult(it)
                    }
                }
                LiveLinkControl.PayloadCase.ROKID_NODE_COMMAND -> {
                    val command = envelope.control.rokidNodeCommand
                    val accepted = nodeCommandGuard.accept(command, envelope.sentMonotonicTimestampNs) == null &&
                        runCatching { observer.onRokidNodeCommand(command) }.getOrDefault(false)
                    lane.withWriteLock {
                        writeTracked(
                            lane,
                            output.control(
                                LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                                LiveControlMessages.rokidNodeCommandResult(binding, command, accepted),
                            ),
                            metrics,
                        )
                    }
                }
                LiveLinkControl.PayloadCase.SPOOL_MANIFEST_POLL -> {
                    val poll = envelope.control.spoolManifestPoll
                    if (poll.maxRecords !in 1..HostSpoolPullCoordinator.MAXIMUM_PAGE_RECORDS) {
                        throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
                    }
                    val snapshot = spoolProvider.manifest(poll.maxRecords)
                    lane.withWriteLock {
                        writeTracked(
                            lane,
                            output.control(
                                LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                                LiveLinkControl.newBuilder().setSpoolManifestSnapshot(snapshot).build(),
                            ),
                            metrics,
                        )
                    }
                }
                LiveLinkControl.PayloadCase.SPOOL_ARTIFACT_REQUEST -> {
                    val request = envelope.control.spoolArtifactRequest
                    if (request.recordId.isBlank() || request.offset < 0L ||
                        request.maxBytes !in 1..HostSpoolPullCoordinator.ARTIFACT_CHUNK_BYTES
                    ) {
                        throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
                    }
                    val chunk = spoolProvider.artifact(request.recordId, request.offset, request.maxBytes)
                    lane.withWriteLock {
                        writeTracked(
                            lane,
                            output.control(
                                LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                                LiveLinkControl.newBuilder().setSpoolArtifactChunk(chunk).build(),
                            ),
                            metrics,
                        )
                    }
                }
                LiveLinkControl.PayloadCase.SPOOL_RECORDS_ACK -> {
                    val acknowledgement = envelope.control.spoolRecordsAck
                    if (acknowledgement.recordIdsCount !in 1..HostSpoolPullCoordinator.MAXIMUM_PAGE_RECORDS) {
                        throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
                    }
                    spoolProvider.acknowledge(
                        acknowledgement.manifestRevision,
                        acknowledgement.recordIdsList,
                    )
                }
                LiveLinkControl.PayloadCase.LEASE_REQUEST -> {
                    if (LiveControlMessages.isMicrophoneLeaseRequest(envelope.control, binding)) {
                        val request = envelope.control.leaseRequest
                        val nowNs = clock.nowNs()
                        val requestedDeadline = MonotonicLeaseDeadline.fromDurationMillis(
                            nowNs,
                            request.requestedDurationMs,
                        ).expiresAtNs
                        val authorization = MicrophoneLeaseAuthorization(
                            binding.sessionId,
                            binding.leaseId,
                            request.requestedDurationMs,
                            minOf(requestedDeadline, leaseDeadline.expiresAtNs),
                        )
                        // A local permission/source preflight failure is an explicit rejection;
                        // it must not tear down the already authenticated camera/IMU session.
                        val accepted = microphoneIntentTracker.permitsLease(
                            request.originatingMicrophoneIntentId,
                        ) && runCatching {
                            observer.mayGrantMicrophoneLease(authorization)
                        }.getOrDefault(false)
                        lane.withWriteLock {
                            writeTracked(
                                lane,
                                output.control(
                                    LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                                    LiveControlMessages.microphoneLeaseGrant(request, accepted),
                                ),
                                metrics,
                            )
                        }
                        if (accepted) runCatching {
                            observer.onMicrophoneLeaseGranted(authorization)
                        }
                    } else {
                        if (!closeState.acceptRemoteCloseRequest(envelope.control)) {
                            throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
                        }
                        lane.withWriteLock {
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
        var nextTelemetryNs = clock.nowNs()
        while (running.get() && !draining.get()) {
            val nowNs = clock.nowNs()
            leaseDeadline.requireActive(nowNs)
            if (nowNs >= nextTelemetryNs) {
                lane.withWriteLock {
                    writeTracked(
                        lane,
                        output.control(
                            LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                            LiveControlMessages.telemetry(
                                nowNs,
                                queues.snapshot(),
                                metrics.snapshot(),
                                cameraGateTelemetry.get(),
                            ),
                        ),
                        metrics,
                    )
                }
                nextTelemetryNs = Math.addExact(nowNs, TELEMETRY_INTERVAL_NS)
                continue
            }
            val nodeGesture = gestureOutbox.peekFresh(clock.nowNs())
            if (nodeGesture != null) {
                lane.withWriteLock {
                    writeTracked(
                        lane,
                        output.control(
                            LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                            LiveControlMessages.rokidGestureIntent(
                                binding,
                                nodeGesture.gestureId,
                                nodeGesture.observedMonotonicNs,
                                nodeGesture.operation,
                            ),
                        ),
                        metrics,
                    )
                }
                gestureOutbox.acknowledgeWritten(nodeGesture)
                continue
            }
            val gestureControl = pendingGestureControl.getAndSet(null)
            if (gestureControl != null) {
                lane.withWriteLock {
                    writeTracked(
                        lane,
                        output.control(LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL, gestureControl),
                        metrics,
                    )
                }
                continue
            }
            val sensor = queues.awaitRealtime(IMU_QUEUE_POLL_TIMEOUT_MS) ?: continue
            if (draining.get()) break
            leaseDeadline.requireActive(clock.nowNs())
            lane.withWriteLock {
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
                    shutdown.realtime.withWriteLock {
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
        endpointResolver.closeIfOwned()
        executor.shutdownNow()
        executor.awaitTerminationPreservingInterrupt(2, TimeUnit.SECONDS)
    }

    /** Non-blocking: the bounded authenticated shutdown always runs on the dedicated worker. */
    override fun close() = closeAsync {}

    companion object {
        private const val DIAGNOSTIC_TAG = "ConceptFlowLiveLink"
        private const val MINIMUM_RETRY_MS = 250L
        private const val MAXIMUM_RETRY_MS = 4_000L
        private const val ENDPOINT_RESOLUTION_TIMEOUT_MS = 180_000L
        private val TELEMETRY_INTERVAL_NS = TimeUnit.SECONDS.toNanos(1)
        private const val IMU_QUEUE_POLL_TIMEOUT_MS = 20L
        private const val WORKER_JOIN_TIMEOUT_MS = 1_000L
        private const val DRAIN_TIMEOUT_MS = 5_000L
        private const val CLOSE_ACK_TIMEOUT_MS = 2_000L
        private const val CLOSE_OPERATION_TIMEOUT_MS = 10_000L

        fun fromConfig(
            config: LiveLinkPrivateConfig,
            spoolProvider: RokidSpoolProvider = EmptyRokidSpoolProvider,
            endpointResolver: LiveLinkEndpointResolver = StaticLiveLinkEndpointResolver(config.address),
        ): RokidLiveLinkClient =
            RokidLiveLinkClient(
                config,
                buildPinnedTls(config),
                spoolProvider = spoolProvider,
                endpointResolver = endpointResolver,
            )
    }
}
