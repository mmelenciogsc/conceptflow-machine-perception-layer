// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.io.Closeable
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.conceptflow.mpl.v1.LiveLinkControl
import org.conceptflow.mpl.v1.LiveLinkEnvelope
import org.conceptflow.mpl.v1.LiveTransportLane

internal fun buildPinnedTls(config: LiveLinkPrivateConfig): PinnedMutualTls {
    val identity = AndroidKeystoreTlsIdentity().ensure(config.identityAlias)
    return PinnedMutualTls.create(
        identity.keyManagers,
        listOf(PeerCertificateProvisioning.exactPublicKeyPin(config.peerCertificate)),
    )
}

internal fun openClientLane(
    tls: PinnedMutualTls,
    config: LiveLinkPrivateConfig,
    address: java.net.InetAddress,
    port: Int,
    lane: LiveTransportLane,
    onConnectedSocket: ((Closeable) -> Unit)? = null,
): AuthenticatedTlsLane {
    val raw = Socket()
    return try {
        raw.connect(InetSocketAddress(address, port), config.connectTimeoutMs)
        onConnectedSocket?.invoke(raw)
        raw.soTimeout = config.socketReadTimeoutMs
        raw.keepAlive = true
        tls.openClientLane(raw, address.hostAddress ?: "", port, lane).also {
            it.socket.soTimeout = config.socketReadTimeoutMs
        }
    } catch (error: Exception) {
        runCatching { raw.close() }
        throw error
    }
}

internal fun openServerSocket(
    tls: PinnedMutualTls,
    config: LiveLinkPrivateConfig,
    address: java.net.InetAddress,
    port: Int,
): SSLServerSocket = tls.createUnboundServerSocket().apply {
    reuseAddress = true
    bind(InetSocketAddress(address, port), 1)
    soTimeout = ACCEPT_POLL_TIMEOUT_MS
}

internal fun acceptServerLane(
    tls: PinnedMutualTls,
    server: SSLServerSocket,
    lane: LiveTransportLane,
    running: AtomicBoolean,
    socketReadTimeoutMs: Int,
    admissionCheck: (() -> Unit)? = null,
    onAcceptedSocket: ((Closeable) -> Unit)? = null,
): AuthenticatedTlsLane {
    while (running.get()) {
        admissionCheck?.invoke()
        try {
            val socket = server.accept() as SSLSocket
            onAcceptedSocket?.invoke(socket)
            socket.soTimeout = socketReadTimeoutMs
            socket.keepAlive = true
            return tls.openServerLane(socket, lane)
        } catch (_: SocketTimeoutException) {
            // Periodic wake-up is required so close/stop does not strand the accept thread.
            admissionCheck?.invoke()
        }
    }
    throw InterruptedException("live-link endpoint stopped")
}

internal fun writeTracked(
    lane: AuthenticatedTlsLane,
    envelope: LiveLinkEnvelope,
    metrics: SanitizedTransportMetrics,
) {
    lane.write(envelope)
    metrics.recordSent(lane.lane, envelope.serializedSize)
}

internal fun readTracked(
    lane: AuthenticatedTlsLane,
    metrics: SanitizedTransportMetrics,
): LiveLinkEnvelope? = lane.readOrNull()?.also { metrics.recordReceived(lane.lane, it.serializedSize) }

internal enum class PeriodicClockInboundKind {
    EXPECTED_CLOCK_RESPONSE,
    KEEPALIVE_RESPONSE,
    IMU_BATCH,
    REMOTE_CLOSE_REQUEST,
    LOCAL_CLOSE_ACKNOWLEDGEMENT,
}

/**
 * Remembers only the small number of periodic probes whose bounded response window expired.
 *
 * Realtime IMU traffic can delay an otherwise valid response beyond the initiator's timeout.
 * Authentication, session binding, lane sequencing and message-size validation have already run
 * before this tracker is consulted. Exact probe/timestamp correlation prevents an unsolicited
 * clock response from being silently accepted, and one-shot removal prevents replay.
 */
internal class LatePeriodicClockResponseWindow(
    private val maximumEntries: Int = LiveControlMessages.CLOCK_PROBES,
) {
    private val expectedInitiatorSendByProbe = LinkedHashMap<Long, Long>()

    init {
        require(maximumEntries > 0)
    }

    @Synchronized
    fun recordTimedOut(probeId: Long, initiatorSendNs: Long) {
        require(probeId > 0L && initiatorSendNs > 0L)
        expectedInitiatorSendByProbe[probeId] = initiatorSendNs
        while (expectedInitiatorSendByProbe.size > maximumEntries) {
            expectedInitiatorSendByProbe.remove(expectedInitiatorSendByProbe.keys.first())
        }
    }

    @Synchronized
    fun accept(control: LiveLinkControl): Boolean {
        if (control.payloadCase != LiveLinkControl.PayloadCase.CLOCK_SYNC_RESPONSE) return false
        val response = control.clockSyncResponse
        val expectedSendNs = expectedInitiatorSendByProbe[response.probeId] ?: return false
        if (response.initiatorSendMonotonicNs != expectedSendNs ||
            response.responderReceiveMonotonicNs <= 0L ||
            response.responderSendMonotonicNs < response.responderReceiveMonotonicNs
        ) {
            return false
        }
        expectedInitiatorSendByProbe.remove(response.probeId)
        return true
    }
}

/**
 * Classifies only records legal while a periodic clock probe is outstanding. Authentication,
 * binding, lane and sequence validation remains the caller's responsibility and runs first.
 */
internal fun classifyPeriodicClockInbound(
    envelope: LiveLinkEnvelope,
    binding: LiveSessionBinding,
    expectedProbeId: Long,
    expectedInitiatorSendNs: Long,
): PeriodicClockInboundKind = when (envelope.payloadCase) {
    LiveLinkEnvelope.PayloadCase.SENSOR -> {
        if (!envelope.sensor.hasImuBatch() || envelope.sensor.hasMicrophoneChunk()) {
            throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
        }
        PeriodicClockInboundKind.IMU_BATCH
    }
    LiveLinkEnvelope.PayloadCase.CONTROL -> when {
        LiveControlMessages.isLeaseClose(envelope.control, binding) ->
            PeriodicClockInboundKind.REMOTE_CLOSE_REQUEST
        LiveControlMessages.isLeaseCloseAcknowledgement(envelope.control, binding) ->
            PeriodicClockInboundKind.LOCAL_CLOSE_ACKNOWLEDGEMENT
        envelope.control.payloadCase == LiveLinkControl.PayloadCase.KEEPALIVE &&
            envelope.control.keepalive.response -> PeriodicClockInboundKind.KEEPALIVE_RESPONSE
        envelope.control.payloadCase == LiveLinkControl.PayloadCase.CLOCK_SYNC_RESPONSE &&
            envelope.control.clockSyncResponse.probeId == expectedProbeId &&
            envelope.control.clockSyncResponse.initiatorSendMonotonicNs == expectedInitiatorSendNs ->
            PeriodicClockInboundKind.EXPECTED_CLOCK_RESPONSE
        else -> throw LaneProtocolException(LaneProtocolFailure.MALFORMED_CONTROL)
    }
    else -> throw LaneProtocolException(LaneProtocolFailure.PAYLOAD_LANE_MISMATCH)
}

internal fun freshTicketKey(secureRandom: SecureRandom): SecretKeySpec =
    SecretKeySpec(ByteArray(32).also(secureRandom::nextBytes), "HmacSHA256")

/** Owns resources for exactly one connection attempt; late workers can only close their own attempt. */
internal class ConnectionAttemptResources : Closeable {
    private val resources = ArrayList<Closeable>(2)
    private var closed = false

    @Synchronized
    fun <T : Closeable> own(resource: T): T {
        if (closed) {
            runCatching { resource.close() }
            throw InterruptedException("live-link connection attempt already closed")
        }
        resources += resource
        return resource
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        resources.asReversed().forEach { runCatching { it.close() } }
        resources.clear()
    }
}

/** Allows endpoint close to reach the current attempt without transferring resource ownership. */
internal class ActiveConnectionAttempt {
    private val current = AtomicReference<ConnectionAttemptResources?>()

    fun activate(attempt: ConnectionAttemptResources) {
        check(current.compareAndSet(null, attempt)) { "a live-link connection attempt is already active" }
    }

    fun release(attempt: ConnectionAttemptResources) {
        current.compareAndSet(attempt, null)
    }

    fun closeCurrent() {
        current.getAndSet(null)?.close()
    }
}

/** Separates Future cancellation state from actual worker-thread completion. */
internal class WorkerCompletion {
    private val started = AtomicBoolean(false)
    private val finished = CountDownLatch(1)

    fun begin() {
        started.set(true)
    }

    fun finish() {
        finished.countDown()
    }

    fun await(future: Future<*>, timeoutMs: Long): Boolean {
        if (!started.get() && future.isDone) return true
        return try {
            finished.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            // Endpoint shutdown interrupts the owning executor. Cleanup waits must therefore
            // terminate promptly without escaping the endpoint worker as an uncaught exception.
            // Restore the flag so callers and executor code retain the cancellation signal.
            Thread.currentThread().interrupt()
            false
        }
    }
}

/** Coordinates the authenticated, bounded end of one client session. */
internal class GracefulSessionCloseState(private val binding: LiveSessionBinding) {
    private val draining = AtomicBoolean(false)
    private val closeRequestSent = AtomicBoolean(false)
    private val remoteCloseAccepted = AtomicBoolean(false)
    private val authenticatedCompletion = AtomicBoolean(false)
    private val acknowledged = CountDownLatch(1)
    private val completionObserved = CountDownLatch(1)

    fun beginDrain(): Boolean = draining.compareAndSet(false, true)

    fun beginCloseRequest(): Boolean =
        draining.get() && closeRequestSent.compareAndSet(false, true)

    fun acceptAcknowledgement(control: LiveLinkControl): Boolean {
        if (!closeRequestSent.get() || !LiveControlMessages.isLeaseCloseAcknowledgement(control, binding)) {
            return false
        }
        markAuthenticatedCompletion()
        acknowledged.countDown()
        return true
    }

    /** Accepts exactly one authenticated close request for this state's session and lease. */
    fun acceptRemoteCloseRequest(control: LiveLinkControl): Boolean {
        if (!LiveControlMessages.isLeaseClose(control, binding) ||
            !remoteCloseAccepted.compareAndSet(false, true)
        ) {
            return false
        }
        markAuthenticatedCompletion()
        return true
    }

    fun hasAuthenticatedRemoteClose(): Boolean = remoteCloseAccepted.get()

    fun hasAuthenticatedCompletion(): Boolean = authenticatedCompletion.get()

    fun awaitAuthenticatedCompletion(timeoutMs: Long): Boolean {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        if (authenticatedCompletion.get()) return true
        return try {
            completionObserved.await(timeoutMs, TimeUnit.MILLISECONDS) && authenticatedCompletion.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun awaitAcknowledgement(timeoutMs: Long): Boolean {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        return try {
            acknowledged.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun awaitWriterDrain(
        workers: List<Pair<WorkerCompletion, Future<*>>>,
        timeoutMs: Long,
    ): Boolean {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        return workers.all { (completion, future) ->
            val remainingNs = deadlineNs - System.nanoTime()
            remainingNs > 0 && completion.await(
                future,
                TimeUnit.NANOSECONDS.toMillis(remainingNs).coerceAtLeast(1L),
            )
        }
    }

    private fun markAuthenticatedCompletion() {
        authenticatedCompletion.set(true)
        completionObserved.countDown()
    }
}

/**
 * Defers only a peer-style camera transport closure for the bounded interval in which its
 * authenticated realtime close can overtake it. Protocol, timeout and arbitrary I/O failures are
 * never reclassified, and elapsed grace without authenticated completion remains a failure.
 */
internal fun awaitAuthenticatedCameraLaneClosure(
    error: Throwable,
    closeState: GracefulSessionCloseState,
    timeoutMs: Long,
): Boolean {
    val peerClosedCameraLane = isAuthenticatedCloseTransportArtifact(error)
    return peerClosedCameraLane && closeState.awaitAuthenticatedCompletion(timeoutMs)
}

/** Narrow transport artifacts that an exact authenticated close may supersede. */
internal fun isAuthenticatedCloseTransportArtifact(error: Throwable): Boolean =
    generateSequence(error) { it.cause }
        .take(MAXIMUM_CAMERA_CLOSE_CAUSE_DEPTH)
        .any { it is CameraLaneClosedException || (it is SocketException && it !is SocketTimeoutException) }

/** Clean frame-boundary EOF on the authenticated camera lane, distinct from truncated framing. */
internal class CameraLaneClosedException : EOFException("camera lane closed")

/** Evidence from a bounded locally initiated close; complete is true only for all three phases. */
internal data class InitiatedSessionCloseOutcome(
    val closeRequestWritten: Boolean,
    val writersDrained: Boolean,
    val acknowledgementReceived: Boolean,
    val requestFailure: LiveLinkCloseRequestFailure = LiveLinkCloseRequestFailure.NONE,
) {
    val complete: Boolean
        get() = closeRequestWritten && writersDrained && acknowledgementReceived
}

/**
 * Writes the authenticated close before waiting on data-lane drain. The realtime-lane write lock
 * orders the request after any in-progress realtime record, while the subsequent writer wait keeps
 * sockets open until a camera frame already being serialized has finished.
 */
internal fun coordinateInitiatedSessionClose(
    closeState: GracefulSessionCloseState,
    writers: List<Pair<WorkerCompletion, Future<*>>>,
    drainTimeoutMs: Long,
    acknowledgementTimeoutMs: Long,
    writeCloseRequest: () -> Unit,
): InitiatedSessionCloseOutcome {
    check(closeState.beginDrain()) { "graceful close drain already started" }
    var requestFailure = LiveLinkCloseRequestFailure.NONE
    val requestWritten = try {
        check(closeState.beginCloseRequest()) { "graceful close request already sent" }
        writeCloseRequest()
        true
    } catch (error: Throwable) {
        requestFailure = classifyCloseRequestFailure(error)
        false
    }
    val writersDrained = closeState.awaitWriterDrain(writers, drainTimeoutMs)
    val acknowledgementReceived = requestWritten &&
        closeState.awaitAcknowledgement(acknowledgementTimeoutMs)
    return InitiatedSessionCloseOutcome(
        requestWritten,
        writersDrained,
        acknowledgementReceived,
        requestFailure,
    )
}

private fun classifyCloseRequestFailure(error: Throwable): LiveLinkCloseRequestFailure = when {
    error.javaClass.name == "android.os.NetworkOnMainThreadException" ->
        LiveLinkCloseRequestFailure.CALLER_THREAD_NETWORK_POLICY
    error is java.io.IOException -> LiveLinkCloseRequestFailure.TRANSPORT_IO
    else -> LiveLinkCloseRequestFailure.INTERNAL
}

/** Executes one shutdown operation off its caller with an independent bounded watchdog. */
internal class BoundedEndpointShutdownWorker<T>(
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { runnable ->
        Thread(runnable, "mpl-live-client-close").apply { isDaemon = true }
    },
) {
    private val started = AtomicBoolean(false)

    fun execute(
        timeoutMs: Long,
        onTimeout: () -> T,
        operation: () -> T,
    ): CompletableFuture<T> {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        check(started.compareAndSet(false, true)) { "shutdown worker is one-shot" }
        val completion = CompletableFuture<T>()
        val worker = AtomicReference<Future<*>?>()
        val watchdog = executor.schedule({
            if (!completion.isDone) {
                try {
                    if (completion.complete(onTimeout())) worker.get()?.cancel(true)
                } catch (error: Throwable) {
                    completion.completeExceptionally(error)
                }
            }
        }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            worker.set(executor.submit {
                try {
                    completion.complete(operation())
                } catch (error: Throwable) {
                    completion.completeExceptionally(error)
                }
            })
        } catch (error: Throwable) {
            watchdog.cancel(false)
            completion.completeExceptionally(error)
        }
        completion.whenComplete { _, _ ->
            watchdog.cancel(false)
            executor.shutdownNow()
        }
        return completion
    }
}

internal fun java.util.concurrent.ExecutorService.awaitTerminationPreservingInterrupt(
    timeout: Long,
    unit: TimeUnit,
): Boolean = try {
    awaitTermination(timeout, unit)
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    false
}

/** Admission is cancelled by actual realtime-reader completion, not Socket.isConnected metadata. */
internal class RealtimeAdmissionGate(private val window: CameraLaneAdmissionWindow) {
    private val realtimeReadable = AtomicBoolean(true)

    fun markRealtimeClosed() {
        realtimeReadable.set(false)
    }

    fun requireCameraAdmission(nowNs: Long) = window.requireOpen(nowNs, realtimeReadable.get())
}

internal fun classifyDisconnect(error: Throwable): LiveLinkDisconnectReason = when (error) {
    is InterruptedException -> LiveLinkDisconnectReason.STOPPED
    is RemoteSessionCompletedException -> LiveLinkDisconnectReason.REMOTE_COMPLETED
    is LeaseExpiredException -> LiveLinkDisconnectReason.LEASE_EXPIRED
    is LaneProtocolException,
    is IllegalArgumentException,
    -> LiveLinkDisconnectReason.PROTOCOL
    is FramingException -> when (error.failure) {
        // An authenticated peer process or radio can disappear between any two bytes. The next
        // mTLS session starts with fresh framing/session state, so truncation is reconnectable.
        FramingFailure.TRUNCATED_PREFIX,
        FramingFailure.TRUNCATED_RECORD,
        -> LiveLinkDisconnectReason.NETWORK
        FramingFailure.INVALID_LENGTH,
        FramingFailure.MALFORMED_PROTOBUF,
        -> LiveLinkDisconnectReason.PROTOCOL
    }
    is CameraTicketException -> LiveLinkDisconnectReason.AUTHENTICATION
    is javax.net.ssl.SSLException,
    is java.security.GeneralSecurityException,
    is SecurityException,
    -> LiveLinkDisconnectReason.AUTHENTICATION
    is SocketTimeoutException -> LiveLinkDisconnectReason.TIMEOUT
    is java.io.IOException -> LiveLinkDisconnectReason.NETWORK
    else -> LiveLinkDisconnectReason.INTERNAL
}

internal class RemoteSessionCompletedException : java.io.EOFException("remote live session completed")

internal const val ACCEPT_POLL_TIMEOUT_MS = 500
internal const val IO_POLL_TIMEOUT_MS = 200
private const val MAXIMUM_CAMERA_CLOSE_CAUSE_DEPTH = 8
