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
import javax.net.ssl.SSLServerSocket
import org.conceptflow.mpl.v1.LiveLinkControl
import org.conceptflow.mpl.v1.LiveLinkEnvelope
import org.conceptflow.mpl.v1.LiveTransportLane
import org.conceptflow.mpl.v1.LiveTransportPeerRole
import org.conceptflow.mpl.v1.MicrophoneControlOperation
import org.conceptflow.mpl.v1.RokidNodeCommandOperation

/** Poco-side two-port mutual-TLS receiver. One authenticated glasses session is active at a time. */
class PocoLiveLinkServer(
    private val config: LiveLinkPrivateConfig,
    private val tls: PinnedMutualTls,
    private val clock: MonotonicTimeSource = AndroidMonotonicTimeSource,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val acceptSequentialSessions: Boolean = false,
    private val endpointResolver: LiveLinkEndpointResolver = StaticLiveLinkEndpointResolver(config.address),
) : Closeable {
    private val running = AtomicBoolean(false)
    private val draining = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "mpl-live-host").apply { isDaemon = true }
    }
    private val metrics = SanitizedTransportMetrics()
    private val metricAccounting = EndpointMetricAccounting(metrics)
    private val cameraFallbackPolicy = CameraTransportFallbackPolicy(config.cameraTransport)
    private val activeAttempt = ActiveConnectionAttempt()
    private val activeShutdown = AtomicReference<ServerShutdownContext?>()
    private val activeMicrophoneControl = AtomicReference<ServerMicrophoneControl?>()
    private val activeNodeControl = AtomicReference<ServerRokidNodeControl?>()
    private val nodeCommandIds = AtomicLong(0L)
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
            val bindAddress = endpointResolver.awaitAddress(ENDPOINT_RESOLUTION_TIMEOUT_MS)
            realtimeServer = openServerSocket(tls, config, bindAddress, config.realtimePort)
            cameraServer = openServerSocket(tls, config, bindAddress, config.cameraPort)
        } catch (error: Exception) {
            metricAccounting.failure(error)
            running.set(false)
            closeListeners()
            throw error
        }
        executor.execute { serve(observer) }
    }

    fun metricsSnapshot(): TransportMetricsSnapshot = metrics.snapshot()

    fun cameraTransportFallbackSnapshot(): CameraTransportFallbackSnapshot =
        cameraFallbackPolicy.snapshot()

    /** Demotes future leases to I420 and interrupts only the current attempt so it can renegotiate. */
    fun requestCameraTransportFallbackToI420(): CameraTransportFallbackDispatch {
        val dispatch = cameraFallbackPolicy.requestI420Demotion()
        if (dispatch == CameraTransportFallbackDispatch.DEMOTED_RECONNECT_REQUIRED) {
            // Close the attempt synchronously while it is still the failing attempt. Deferring an
            // unqualified close could race the serve loop and accidentally close its replacement.
            activeAttempt.closeCurrent()
        }
        return dispatch
    }

    fun requestMicrophone(
        durationMillis: Int = MAXIMUM_MICROPHONE_LEASE_MILLIS,
    ): MicrophoneRequestDispatch {
        require(durationMillis in 1..MAXIMUM_MICROPHONE_LEASE_MILLIS)
        val control = activeMicrophoneControl.get() ?: return MicrophoneRequestDispatch.NO_AUTHENTICATED_SESSION
        val dispatch = reserveMicrophoneRequest(control, durationMillis, originatingIntentId = 0L)
        if (dispatch != MicrophoneRequestDispatch.REQUESTED) return dispatch
        control.observer.onMicrophoneLeaseState(LiveMicrophoneLeaseState.REQUESTED, durationMillis)
        executor.execute {
            try {
                writeReservedMicrophoneRequest(control, durationMillis, originatingIntentId = 0L)
            } catch (_: Throwable) {
                control.cancelUnwrittenRequest(originatingIntentId = 0L)
                control.observer.onMicrophoneLeaseState(LiveMicrophoneLeaseState.REJECTED, 0)
            }
        }
        return MicrophoneRequestDispatch.REQUESTED
    }

    fun requestRokidCommand(operation: RokidNodeCommandOperation): RokidNodeCommandDispatch {
        require(operation in HOST_INITIATED_NODE_COMMANDS)
        val control = activeNodeControl.get()
            ?: return RokidNodeCommandDispatch.NO_AUTHENTICATED_SESSION
        if (!running.get() || draining.get() || control.leaseDeadline.isExpired(clock.nowNs())) {
            return RokidNodeCommandDispatch.NO_AUTHENTICATED_SESSION
        }
        val command = LiveControlMessages.rokidNodeCommand(
            control.binding,
            nodeCommandIds.updateAndGet { previous -> Math.addExact(previous, 1L) },
            originatingGestureId = 0L,
            issuedMonotonicNs = clock.nowNs(),
            operation = operation,
        ).rokidNodeCommand
        control.commandTracker.record(command)
        executor.execute {
            runCatching { writeRokidNodeCommand(control, command) }
                .onFailure { control.commandTracker.discard(command.commandId) }
        }
        return RokidNodeCommandDispatch.REQUESTED
    }

    private fun writeRokidNodeCommand(
        control: ServerRokidNodeControl,
        command: org.conceptflow.mpl.v1.RokidNodeCommand,
    ) {
        synchronized(control) {
            check(activeNodeControl.get() === control && running.get() && !draining.get())
            control.leaseDeadline.requireActive(clock.nowNs())
            control.realtime.withWriteLock {
                writeTracked(
                    control.realtime,
                    LiveEnvelopeFactory(control.binding, control.state, clock).control(
                        LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                        LiveLinkControl.newBuilder().setRokidNodeCommand(command).build(),
                    ),
                    metrics,
                )
            }
        }
    }

    private fun reserveMicrophoneRequest(
        control: ServerMicrophoneControl,
        durationMillis: Int,
        originatingIntentId: Long,
    ): MicrophoneRequestDispatch {
        val nowNs = clock.nowNs()
        synchronized(control) {
            if (control.hasPendingRequest() || control.isAuthorized(nowNs) ||
                control.hasCancelledResponse(originatingIntentId)
            ) {
                return MicrophoneRequestDispatch.ALREADY_PENDING_OR_ACTIVE
            }
            if (activeMicrophoneControl.get() !== control || !running.get() || draining.get() ||
                control.leaseDeadline.isExpired(nowNs)
            ) {
                return MicrophoneRequestDispatch.NO_AUTHENTICATED_SESSION
            }
            control.reserve(durationMillis, originatingIntentId)
        }
        return MicrophoneRequestDispatch.REQUESTED
    }

    private fun writeReservedMicrophoneRequest(
        control: ServerMicrophoneControl,
        durationMillis: Int,
        originatingIntentId: Long,
    ) {
        synchronized(control) {
            if (!control.isReserved(durationMillis, originatingIntentId)) return
            if (activeMicrophoneControl.get() !== control || !running.get() || draining.get()) {
                control.cancelUnwrittenRequest(originatingIntentId)
                return
            }
            control.leaseDeadline.requireActive(clock.nowNs())
            control.realtime.withWriteLock {
                writeTracked(
                    control.realtime,
                    LiveEnvelopeFactory(control.binding, control.state, clock).control(
                        LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                        LiveControlMessages.microphoneLeaseRequest(
                            control.binding,
                            durationMillis,
                            originatingIntentId,
                        ),
                    ),
                    metrics,
                )
            }
            control.markRequestWritten(originatingIntentId)
        }
    }

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
            var microphoneControl: ServerMicrophoneControl? = null
            var nodeControl: ServerRokidNodeControl? = null
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
                microphoneControl = ServerMicrophoneControl(
                    handshake.binding,
                    realtime,
                    state,
                    handshake.leaseDeadline,
                    observer,
                )
                check(activeMicrophoneControl.compareAndSet(null, microphoneControl)) {
                    "a live-link microphone control context is already active"
                }
                nodeControl = ServerRokidNodeControl(
                    handshake.binding,
                    realtime,
                    state,
                    handshake.leaseDeadline,
                    observer,
                )
                check(activeNodeControl.compareAndSet(null, nodeControl)) {
                    "a Rokid Node control context is already active"
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
                    requireNotNull(microphoneControl),
                    requireNotNull(nodeControl),
                    handshake.supportsDiagnosticSpool,
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
                    logFailureOrigin(effective)
                    metricAccounting.failure(effective)
                    observer.onDiagnostic(classifyDiagnostic(effective))
                }
                // A rejected or half-open pre-authentication socket is not a live session
                // disconnect. Reporting it as one lets any unauthenticated LAN probe terminate a
                // persistent Android Node through its fail-closed session policy. Keep the
                // listener alive, retain the diagnostic, and notify lifecycle consumers only
                // when this connection attempt had reached onSessionReady.
                if (shouldNotifySessionDisconnect(notified)) {
                    observer.onDisconnected(classifyDisconnect(effective))
                }
                if (effective is RemoteSessionCompletedException && !acceptSequentialSessions) {
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
                microphoneControl?.let { activeMicrophoneControl.compareAndSet(it, null) }
                nodeControl?.let { activeNodeControl.compareAndSet(it, null) }
                activeAttempt.release(attempt)
                state.disconnect()
            }
        }
    }

    /**
     * Records only a fixed diagnostic and source location. Exception messages can contain peer or
     * session material, so they are deliberately excluded from this hardware-validation trace.
     */
    private fun logFailureOrigin(error: Throwable) {
        val diagnostic = classifyDiagnostic(error)
        val root = generateSequence(error) { it.cause }.take(8).last()
        val origin = root.stackTrace.firstOrNull { it.className.startsWith("org.conceptflow.mpl") }
        Log.e(
            DIAGNOSTIC_TAG,
            "state=transport_failure diagnostic=${diagnostic.name.lowercase()} " +
                "exception=${root.javaClass.simpleName} " +
                "origin=${origin?.className ?: "unknown"}:${origin?.lineNumber ?: -1}",
        )
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
        var peerSupportsDiagnosticSpool = false
        var peerSupportsAvcIntra = false
        if (hello.hello.protocolVersion.minor >= 1) {
            val capabilitiesEnvelope = readTracked(lane, metrics)
                ?: throw java.io.EOFException("realtime lane closed during capability negotiation")
            acceptInbound(state, capabilitiesEnvelope)
            val peerCapabilities = runCatching {
                LiveControlMessages.requireCompatibleCapabilities(
                    capabilitiesEnvelope.control,
                    LiveTransportPeerRole.LIVE_TRANSPORT_PEER_ROLE_GLASSES,
                )
            }.getOrElse { throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL) }
            peerSupportsDiagnosticSpool = peerCapabilities.supportsDiagnosticSpool
            peerSupportsAvcIntra = peerCapabilities.cameraEncodingsList.contains(
                org.conceptflow.mpl.v1.ImageEncoding.IMAGE_ENCODING_AVC_ANNEX_B_INTRA,
            )
            writeTracked(
                lane,
                output.control(
                    LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                        LiveControlMessages.capabilities(
                            LiveTransportPeerRole.LIVE_TRANSPORT_PEER_ROLE_HOST,
                            supportsDiagnosticSpool = true,
                            supportsAvcIntra = cameraFallbackPolicy.allowsAvcIntra(),
                    ),
                ),
                metrics,
            )
        }
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
        val grant = LiveControlMessages.leaseGrant(
            leaseEnvelope.control.leaseRequest,
            allowAvcIntra = cameraFallbackPolicy.allowsAvcIntra() && peerSupportsAvcIntra,
        )
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
            peerSupportsDiagnosticSpool,
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
        microphoneControl: ServerMicrophoneControl,
        nodeControl: ServerRokidNodeControl,
        supportsDiagnosticSpool: Boolean,
    ) {
        val resync = ClockResyncSchedule().also { it.arm(clock.nowNs()) }
        val spoolPull = if (supportsDiagnosticSpool) {
            HostSpoolPullCoordinator(
                deliver = { pulled -> deliverPulledSpoolRecord(pulled, state, binding, observer) },
            )
        } else {
            null
        }
        val lateClockResponses = LatePeriodicClockResponseWindow()
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
                    microphoneControl,
                    nodeControl,
                    spoolPull,
                    lateClockResponses,
                )
                if (resynchronizedProbeId == null) return
                nextPeriodicProbeId = resynchronizedProbeId
                resync.markCompleted(clock.nowNs())
            }
            serviceHostKeepalive(lane, state, binding)
            spoolPull?.nextControl(clock.nowNs())?.let { request ->
                lane.withWriteLock {
                    writeTracked(
                        lane,
                        LiveEnvelopeFactory(binding, state, clock).control(
                            LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                            request,
                        ),
                        metrics,
                    )
                }
            }
            val envelope = try {
                readTracked(lane, metrics)
            } catch (_: SocketTimeoutException) {
                serviceHostKeepalive(lane, state, binding)
                continue
            } ?: throw java.io.EOFException("realtime lane closed")
            val now = acceptInbound(state, envelope)
            when (envelope.payloadCase) {
                LiveLinkEnvelope.PayloadCase.SENSOR -> {
                    if (envelope.sensor.hasImuBatch()) {
                        observer.onSensor(LiveSensorTimestampNormalizer.normalize(envelope, state, now))
                    } else if (envelope.sensor.hasMicrophoneChunk()) {
                        // A bounded chunk already in flight at STOP or expiry is discarded. A
                        // microphone denial must never tear down the independent camera/IMU flow.
                        if (microphoneControl.isAuthorized(now)) {
                            observer.onSensor(LiveSensorTimestampNormalizer.normalize(envelope, state, now))
                        }
                    } else if (envelope.sensor.hasTouchEvent()) {
                        observer.onSensor(LiveSensorTimestampNormalizer.normalize(envelope, state, now))
                    } else {
                        throw LaneProtocolException(LaneProtocolFailure.PAYLOAD_LANE_MISMATCH)
                    }
                }
                LiveLinkEnvelope.PayloadCase.CONTROL -> {
                    if (acceptPeerTelemetry(envelope, observer)) continue
                    if (spoolPull?.accept(envelope.control) == true) continue
                    if (acceptMicrophoneControlIntent(
                            lane,
                            state,
                            envelope,
                            binding,
                            microphoneControl,
                        )
                    ) {
                        continue
                    }
                    if (acceptMicrophoneGrant(envelope, binding, microphoneControl, observer, now)) continue
                    if (acceptRokidNodeControl(lane, state, envelope, nodeControl, now)) continue
                    if (lateClockResponses.accept(envelope.control)) {
                        Log.d(DIAGNOSTIC_TAG, "state=periodic_clock response=discarded_late_correlated")
                        continue
                    }
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
        microphoneControl: ServerMicrophoneControl,
        nodeControl: ServerRokidNodeControl,
        spoolPull: HostSpoolPullCoordinator?,
        lateClockResponses: LatePeriodicClockResponseWindow,
    ): Long? {
        val output = LiveEnvelopeFactory(binding, state, clock)
        state.beginClockRound()
        var probeId = firstProbeId
        repeat(LiveControlMessages.CLOCK_PROBES) {
            val t0 = clock.nowNs()
            val responseDeadlineNs = Math.addExact(
                t0,
                TimeUnit.MILLISECONDS.toNanos(config.socketReadTimeoutMs.toLong()),
            )
            lane.withWriteLock {
                writeTracked(
                    lane,
                    output.control(
                        LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                        LiveControlMessages.clockRequest(probeId, t0),
                    ),
                    metrics,
                )
            }
            Log.d(DIAGNOSTIC_TAG, "state=periodic_clock request=written")
            while (true) {
                val responseEnvelope = try {
                    readTracked(lane, metrics)
                } catch (_: SocketTimeoutException) {
                    // Established sockets use a short polling timeout so endpoint shutdown stays
                    // responsive. A single empty poll is not a failed clock probe; continue until
                    // the configured response deadline while still servicing keepalives.
                    val nowNs = clock.nowNs()
                    if (nowNs >= responseDeadlineNs) {
                        lateClockResponses.recordTimedOut(probeId, t0)
                        Log.d(DIAGNOSTIC_TAG, "state=periodic_clock round=retained_previous reason=response_timeout")
                        return Math.addExact(probeId, 1L)
                    }
                    serviceHostKeepalive(lane, state, binding)
                    continue
                } ?: throw java.io.EOFException("realtime lane closed during clock resynchronization")
                val t3 = acceptInbound(state, responseEnvelope)
                if (acceptPeerTelemetry(responseEnvelope, observer)) continue
                if (responseEnvelope.hasControl() && spoolPull?.accept(responseEnvelope.control) == true) continue
                if (acceptMicrophoneControlIntent(
                        lane,
                        state,
                        responseEnvelope,
                        binding,
                        microphoneControl,
                    )
                ) {
                    continue
                }
                if (acceptMicrophoneGrant(responseEnvelope, binding, microphoneControl, observer, t3)) continue
                if (acceptRokidNodeControl(lane, state, responseEnvelope, nodeControl, t3)) continue
                if (responseEnvelope.hasControl() && lateClockResponses.accept(responseEnvelope.control)) {
                    Log.d(DIAGNOSTIC_TAG, "state=periodic_clock response=discarded_late_correlated")
                    continue
                }
                if (responseEnvelope.hasSensor() && responseEnvelope.sensor.hasMicrophoneChunk()) {
                    if (microphoneControl.isAuthorized(t3)) {
                        observer.onSensor(LiveSensorTimestampNormalizer.normalize(responseEnvelope, state, t3))
                    }
                    continue
                }
                if (responseEnvelope.hasSensor() && responseEnvelope.sensor.hasTouchEvent()) {
                    observer.onSensor(LiveSensorTimestampNormalizer.normalize(responseEnvelope, state, t3))
                    continue
                }
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
                        Log.d(DIAGNOSTIC_TAG, "state=periodic_clock response=received")
                        val response = responseEnvelope.control.clockSyncResponse
                        try {
                            state.addClockProbe(
                                FourTimestampClockProbe(
                                    probeId,
                                    t0,
                                    response.responderReceiveMonotonicNs,
                                    response.responderSendMonotonicNs,
                                    t3,
                                ),
                            )
                        } catch (error: ClockSyncException) {
                            if (!error.failure.isRecoverablePeriodicResyncFailure()) throw error
                            Log.d(
                                DIAGNOSTIC_TAG,
                                "state=periodic_clock round=retained_previous reason=${error.failure.name.lowercase()}",
                            )
                            return Math.addExact(probeId, 1L)
                        }
                        break
                    }
                }
            }
            probeId = Math.addExact(probeId, 1L)
        }
        try {
            state.commitClockRound(clock.nowNs())
        } catch (error: ClockSyncException) {
            if (!error.failure.isRecoverablePeriodicResyncFailure()) throw error
            Log.d(
                DIAGNOSTIC_TAG,
                "state=periodic_clock round=retained_previous reason=${error.failure.name.lowercase()}",
            )
        }
        return probeId
    }

    private fun acceptPeerTelemetry(
        envelope: LiveLinkEnvelope,
        observer: PocoLiveLinkObserver,
    ): Boolean {
        if (!envelope.hasControl() ||
            envelope.control.payloadCase != LiveLinkControl.PayloadCase.TELEMETRY
        ) return false
        val telemetry = envelope.control.telemetry
        if (telemetry.sampledMonotonicTimestampNs > envelope.sentMonotonicTimestampNs) {
            throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
        }
        observer.onPeerTelemetry(telemetry)
        return true
    }

    private fun deliverPulledSpoolRecord(
        pulled: PulledSpoolRecord,
        state: LiveConnectionState,
        binding: LiveSessionBinding,
        observer: PocoLiveLinkObserver,
    ): Boolean = runCatching {
        val lane = if (pulled.record.kind == org.conceptflow.mpl.v1.SpoolRecordKind.SPOOL_RECORD_KIND_CAMERA) {
            LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA
        } else {
            LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL
        }
        pulledRecordToSensors(pulled, binding).forEach { sensor ->
            val envelope = LiveLinkEnvelope.newBuilder()
                .setSessionId(binding.sessionId)
                .setLeaseId(binding.leaseId)
                .setLane(lane)
                .setLaneSequenceId(1L)
                .setSentMonotonicTimestampNs(pulled.record.createdMonotonicTimestampNs)
                .setSensor(sensor)
                .build()
            observer.onSensor(LiveSensorTimestampNormalizer.normalize(envelope, state, clock.nowNs()))
        }
        true
    }.getOrDefault(false)

    private fun acceptMicrophoneControlIntent(
        lane: AuthenticatedTlsLane,
        state: LiveConnectionState,
        envelope: LiveLinkEnvelope,
        binding: LiveSessionBinding,
        microphoneControl: ServerMicrophoneControl,
    ): Boolean {
        if (!envelope.hasControl() ||
            envelope.control.payloadCase != LiveLinkControl.PayloadCase.MICROPHONE_CONTROL_INTENT
        ) {
            return false
        }
        val intent = envelope.control.microphoneControlIntent
        var rejection = microphoneControl.intentGuard.validateStructure(
            intent,
            envelope.sentMonotonicTimestampNs,
        )
        if (rejection == null) {
            val normalized = runCatching {
                state.normalize(RemoteClockStream.MICROPHONE_INTENT_CREATED, intent.createdMonotonicTimestampNs)
            }.getOrNull()
            rejection = if (normalized == null) {
                MicrophoneIntentRejection.MALFORMED
            } else {
                microphoneControl.intentGuard.acceptFresh(
                    intent,
                    normalized.hostMonotonicNs,
                    clock.nowNs(),
                    normalized.uncertaintyNs,
                )
            }
        }
        if (rejection == null) {
            rejection = when (intent.operation) {
                MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_START -> {
                    val dispatch = reserveMicrophoneRequest(
                        microphoneControl,
                        intent.requestedDurationMs,
                        intent.intentId,
                    )
                    when (dispatch) {
                        MicrophoneRequestDispatch.REQUESTED -> {
                            microphoneControl.observer.onMicrophoneLeaseState(
                                LiveMicrophoneLeaseState.REQUESTED,
                                intent.requestedDurationMs,
                            )
                            try {
                                writeReservedMicrophoneRequest(
                                    microphoneControl,
                                    intent.requestedDurationMs,
                                    intent.intentId,
                                )
                                null
                            } catch (_: Throwable) {
                                microphoneControl.cancelUnwrittenRequest(intent.intentId)
                                microphoneControl.observer.onMicrophoneLeaseState(
                                    LiveMicrophoneLeaseState.REJECTED,
                                    0,
                                )
                                MicrophoneIntentRejection.UNAVAILABLE
                            }
                        }
                        MicrophoneRequestDispatch.ALREADY_PENDING_OR_ACTIVE -> null
                        MicrophoneRequestDispatch.NO_AUTHENTICATED_SESSION ->
                            MicrophoneIntentRejection.UNAVAILABLE
                    }
                }
                MicrophoneControlOperation.MICROPHONE_CONTROL_OPERATION_STOP -> {
                    microphoneControl.revoke()
                    microphoneControl.observer.onMicrophoneLeaseState(LiveMicrophoneLeaseState.COMPLETE, 0)
                    null
                }
                else -> MicrophoneIntentRejection.MALFORMED
            }
        }
        lane.withWriteLock {
            writeTracked(
                lane,
                LiveEnvelopeFactory(binding, state, clock).control(
                    LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                    LiveControlMessages.microphoneControlResult(binding, intent, rejection),
                ),
                metrics,
            )
        }
        return true
    }

    private fun acceptRokidNodeControl(
        lane: AuthenticatedTlsLane,
        state: LiveConnectionState,
        envelope: LiveLinkEnvelope,
        control: ServerRokidNodeControl,
        receiveNs: Long,
    ): Boolean {
        if (!envelope.hasControl()) return false
        when (envelope.control.payloadCase) {
            LiveLinkControl.PayloadCase.ROKID_GESTURE_INTENT -> {
                val intent = envelope.control.rokidGestureIntent
                var rejection = control.gestureGuard.validateStructure(
                    intent,
                    envelope.sentMonotonicTimestampNs,
                )
                if (rejection == null) {
                    val normalized = runCatching {
                        state.normalize(
                            RemoteClockStream.ROKID_GESTURE_OBSERVED,
                            intent.observedMonotonicTimestampNs,
                        )
                    }.getOrNull()
                    rejection = if (normalized == null) {
                        RokidNodeControlRejection.MALFORMED
                    } else {
                        control.gestureGuard.acceptFresh(
                            intent,
                            normalized.hostMonotonicNs,
                            receiveNs,
                            normalized.uncertaintyNs,
                        )
                    }
                }
                if (rejection != null) return true
                control.observer.onRokidGesture(intent.operation)
                val operation = RokidGestureCommandPolicy.commandFor(intent.operation) ?: return true
                val command = LiveControlMessages.rokidNodeCommand(
                    control.binding,
                    nodeCommandIds.updateAndGet { previous -> Math.addExact(previous, 1L) },
                    intent.gestureId,
                    clock.nowNs(),
                    operation,
                ).rokidNodeCommand
                control.commandTracker.record(command)
                lane.withWriteLock {
                    writeTracked(
                        lane,
                        LiveEnvelopeFactory(control.binding, state, clock).control(
                            LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                            LiveLinkControl.newBuilder().setRokidNodeCommand(command).build(),
                        ),
                        metrics,
                    )
                }
                return true
            }
            LiveLinkControl.PayloadCase.ROKID_NODE_COMMAND_RESULT -> {
                val result = control.commandTracker.accept(envelope.control.rokidNodeCommandResult)
                    ?: throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
                control.observer.onRokidNodeCommandResult(result)
                return true
            }
            else -> return false
        }
    }

    private fun acceptMicrophoneGrant(
        envelope: LiveLinkEnvelope,
        binding: LiveSessionBinding,
        microphoneControl: ServerMicrophoneControl,
        observer: PocoLiveLinkObserver,
        nowNs: Long,
    ): Boolean {
        if (!envelope.hasControl() ||
            !LiveControlMessages.isMicrophoneGrantResponse(envelope.control, binding)
        ) {
            return false
        }
        val responseOrigin = envelope.control.leaseGrant.originatingMicrophoneIntentId
        val disposition = microphoneControl.acceptResponse(responseOrigin)
        if (disposition == MicrophoneResponseDisposition.UNRELATED) {
            throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
        }
        if (disposition == MicrophoneResponseDisposition.CANCELLED) return true
        val accepted = LiveControlMessages.microphoneGrantAccepted(
            envelope.control,
            binding,
            responseOrigin,
        )
        if (accepted) {
            val duration = envelope.control.leaseGrant.grantedDurationMs
            microphoneControl.setAuthorizedUntil(
                minOf(
                    microphoneControl.leaseDeadline.expiresAtNs,
                    Math.addExact(nowNs, TimeUnit.MILLISECONDS.toNanos(duration.toLong())),
                ),
            )
        } else {
            microphoneControl.setAuthorizedUntil(0L)
        }
        observer.onMicrophoneLeaseState(
            if (accepted) LiveMicrophoneLeaseState.ACTIVE else LiveMicrophoneLeaseState.REJECTED,
            if (accepted) envelope.control.leaseGrant.grantedDurationMs else 0,
        )
        return true
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
        lane.withWriteLock {
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
                lane.withWriteLock {
                    writeTracked(
                        lane,
                        LiveEnvelopeFactory(binding, state, clock).control(
                            LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL,
                            LiveControlMessages.keepalive(nonce, decision.sampledAtNs, response = false),
                        ),
                        metrics,
                    )
                }
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
        val supportsDiagnosticSpool: Boolean,
    )

    private class ServerMicrophoneControl(
        val binding: LiveSessionBinding,
        val realtime: AuthenticatedTlsLane,
        val state: LiveConnectionState,
        val leaseDeadline: MonotonicLeaseDeadline,
        val observer: PocoLiveLinkObserver,
    ) {
        val intentGuard = MicrophoneIntentGuard(binding)
        private val leaseGate = HostMicrophoneLeaseGate(leaseDeadline)

        fun hasPendingRequest(): Boolean = leaseGate.hasPendingRequest()

        fun hasCancelledResponse(originatingIntentId: Long): Boolean =
            leaseGate.hasCancelledResponse(originatingIntentId)

        fun reserve(durationMillis: Int, originatingIntentId: Long) {
            leaseGate.reserve(durationMillis, originatingIntentId)
        }

        fun isReserved(durationMillis: Int, originatingIntentId: Long): Boolean =
            leaseGate.isReserved(durationMillis, originatingIntentId)

        fun markRequestWritten(originatingIntentId: Long) {
            leaseGate.markRequestWritten(originatingIntentId)
        }

        fun cancelUnwrittenRequest(originatingIntentId: Long) {
            leaseGate.cancelUnwrittenRequest(originatingIntentId)
        }

        fun revoke() {
            leaseGate.revoke()
        }

        fun acceptResponse(originatingIntentId: Long): MicrophoneResponseDisposition =
            leaseGate.acceptResponse(originatingIntentId)

        fun setAuthorizedUntil(deadlineNs: Long) {
            leaseGate.setAuthorizedUntil(deadlineNs)
        }

        fun isAuthorized(nowNs: Long): Boolean = leaseGate.isAuthorized(nowNs)
    }

    private class ServerRokidNodeControl(
        val binding: LiveSessionBinding,
        val realtime: AuthenticatedTlsLane,
        val state: LiveConnectionState,
        val leaseDeadline: MonotonicLeaseDeadline,
        val observer: PocoLiveLinkObserver,
    ) {
        val gestureGuard = HostRokidGestureGuard(binding)
        val commandTracker = HostRokidNodeCommandTracker()
    }

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
        endpointResolver.closeIfOwned()
        executor.shutdownNow()
        executor.awaitTerminationPreservingInterrupt(2, TimeUnit.SECONDS)
    }

    override fun close() = closeAsync {}

    companion object {
        private const val DIAGNOSTIC_TAG = "ConceptFlowLiveLink"
        private const val WORKER_JOIN_TIMEOUT_MS = 1_000L
        private const val CLOSE_ACK_TIMEOUT_MS = 500L
        private const val CLOSE_OPERATION_TIMEOUT_MS = 4_000L
        private const val CAMERA_CLOSE_AUTHENTICATION_GRACE_MS = 2_000L
        private const val ENDPOINT_RESOLUTION_TIMEOUT_MS = 180_000L
        private const val PERIODIC_CLOCK_PROBE_ID_START = 10_000L
        private val HOST_INITIATED_NODE_COMMANDS = setOf(
            RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_ACTIVATE_NODE,
            RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_SLEEP_NODE,
            RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_PLAY_BRAND_SEQUENCE,
        )

        fun fromConfig(
            config: LiveLinkPrivateConfig,
            acceptSequentialSessions: Boolean = false,
            endpointResolver: LiveLinkEndpointResolver = StaticLiveLinkEndpointResolver(config.address),
        ): PocoLiveLinkServer = PocoLiveLinkServer(
            config,
            buildPinnedTls(config),
            acceptSequentialSessions = acceptSequentialSessions,
            endpointResolver = endpointResolver,
        )
    }
}

internal fun shouldNotifySessionDisconnect(sessionWasReady: Boolean): Boolean = sessionWasReady
