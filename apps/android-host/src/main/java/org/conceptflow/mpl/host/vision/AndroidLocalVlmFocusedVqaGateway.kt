// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.conceptflow.mpl.host.focus.FocusedVqaCorrelation
import org.conceptflow.mpl.host.focus.FocusedVqaGateway
import org.conceptflow.mpl.host.focus.FocusedVqaRequest

enum class FocusedVqaPhase { ADMITTED, FRAME_READY, SUBMITTED, TERMINAL }

/** Deliberately excludes image, track, question, and answer content. */
data class FocusedVqaPhaseTelemetry(
    val requestId: Long,
    val phase: FocusedVqaPhase,
    val elapsedMillis: Long,
    val outcome: LocalVlmFocusedObjectFailure? = null,
)

interface LocalFocusedVqaClient {
    /** Must synchronously consume or copy [frame]; its JPEG buffer is wiped when this call returns. */
    fun submitFocusedObjectVqa(
        request: LocalVlmFocusedObjectRequest,
        frame: EncodedJpegFrame,
        callback: LocalVlmFocusedObjectCallback,
    ): LocalVlmSubmissionResult

    fun cancelFocusedObjectVqa(correlation: LocalVlmFocusedObjectCorrelation): Boolean
}

/**
 * Non-blocking integration between Spatial Focus and the isolated local VLM.
 *
 * The caller performs only validation plus one bounded queue offer. Exact frame lookup, optional
 * object crop, and JPEG encoding execute on the single worker. One active request is enforced both
 * here and by [AndroidLocalVlmEnvironmentClient].
 */
class AndroidLocalVlmFocusedVqaGateway(
    private val client: LocalFocusedVqaClient,
    private val frameProvider: FocusedVqaFrameProvider,
    private val callback: LocalVlmFocusedObjectCallback,
    private val executor: ExecutorService = newWorker(),
    private val watchdog: ScheduledExecutorService = newWatchdog(),
    private val clockNanos: () -> Long = System::nanoTime,
    private val onTelemetry: (FocusedVqaPhaseTelemetry) -> Unit = {},
) : FocusedVqaGateway, AutoCloseable {
    private val active = AtomicReference<GatewayRequest?>(null)
    private val closed = AtomicBoolean(false)

    override fun submit(request: FocusedVqaRequest): Boolean =
        submitDetailed(request) == LocalVlmSubmissionResult.ACCEPTED

    /** ACCEPTED means admitted to the bounded preparation worker, not that inference completed. */
    fun submitDetailed(request: FocusedVqaRequest): LocalVlmSubmissionResult {
        if (closed.get()) return LocalVlmSubmissionResult.UNAVAILABLE
        val localRequest = request.toLocalVlmRequest() ?: return LocalVlmSubmissionResult.INVALID_REQUEST
        if (!FocusedVqaTiming.hasTimeRemaining(localRequest.deadlineMonotonicTimestampNanos, clockNanos())) {
            return LocalVlmSubmissionResult.INVALID_REQUEST
        }
        val gatewayRequest = GatewayRequest(request.correlation, localRequest)
        if (!active.compareAndSet(null, gatewayRequest)) return LocalVlmSubmissionResult.BUSY
        return try {
            emit(gatewayRequest, FocusedVqaPhase.ADMITTED)
            val delayNanos =
                (localRequest.deadlineMonotonicTimestampNanos - clockNanos()).coerceAtLeast(0L)
            gatewayRequest.watchdog = watchdog.schedule(
                { timeOut(gatewayRequest) },
                delayNanos,
                TimeUnit.NANOSECONDS,
            )
            gatewayRequest.future = executor.submit { dispatch(gatewayRequest, request, localRequest) }
            LocalVlmSubmissionResult.ACCEPTED
        } catch (_: RejectedExecutionException) {
            active.compareAndSet(gatewayRequest, null)
            gatewayRequest.watchdog?.cancel(false)
            LocalVlmSubmissionResult.BUSY
        } catch (_: RuntimeException) {
            active.compareAndSet(gatewayRequest, null)
            gatewayRequest.watchdog?.cancel(false)
            LocalVlmSubmissionResult.UNAVAILABLE
        }
    }

    override fun cancel(correlation: FocusedVqaCorrelation) {
        val current = active.get() ?: return
        if (current.focusCorrelation != correlation || !active.compareAndSet(current, null)) return
        current.watchdog?.cancel(false)
        current.future?.cancel(true)
        if (current.clientSubmitted.get()) client.cancelFocusedObjectVqa(current.localCorrelation)
    }

    fun reset() {
        val current = active.getAndSet(null) ?: return
        current.watchdog?.cancel(false)
        current.future?.cancel(true)
        if (current.clientSubmitted.get()) client.cancelFocusedObjectVqa(current.localCorrelation)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reset()
        executor.shutdownNow()
        watchdog.shutdownNow()
    }

    private fun dispatch(
        gatewayRequest: GatewayRequest,
        focusRequest: FocusedVqaRequest,
        localRequest: LocalVlmFocusedObjectRequest,
    ) {
        if (closed.get() || active.get() !== gatewayRequest) return
        val frame = try {
            frameProvider.prepare(focusRequest)
        } catch (_: RuntimeException) {
            reject(gatewayRequest, LocalVlmFocusedObjectFailure.UNAVAILABLE)
            return
        }
        if (frame == null) {
            reject(gatewayRequest, LocalVlmFocusedObjectFailure.INVALID_REQUEST)
            return
        }
        emit(gatewayRequest, FocusedVqaPhase.FRAME_READY)
        if (closed.get() || active.get() !== gatewayRequest || Thread.currentThread().isInterrupted) {
            frame.jpeg.fill(0)
            return
        }
        val callbackGate = SubmissionCallbackGate { outcome ->
            finishFromClient(gatewayRequest, outcome)
        }
        val result = try {
            client.submitFocusedObjectVqa(localRequest, frame, callbackGate::receive)
        } catch (_: RuntimeException) {
            LocalVlmSubmissionResult.UNAVAILABLE
        } finally {
            // The IPC client has synchronously copied/persisted the explicit request by this point.
            // Do not retain a second in-process JPEG after dispatch. Synchronous client callbacks
            // remain gated until this wipe completes, so terminal observers cannot race cleanup.
            frame.jpeg.fill(0)
        }
        val accepted = result == LocalVlmSubmissionResult.ACCEPTED
        if (accepted) {
            gatewayRequest.clientSubmitted.set(true)
            if (active.get() === gatewayRequest) emit(gatewayRequest, FocusedVqaPhase.SUBMITTED)
        }
        val deferredOutcome = callbackGate.complete(accepted)
        if (accepted) {
            deferredOutcome?.let { finishFromClient(gatewayRequest, it) }
            if ((closed.get() || active.get() !== gatewayRequest) &&
                !gatewayRequest.clientTerminalWon.get()
            ) {
                client.cancelFocusedObjectVqa(gatewayRequest.localCorrelation)
            }
            return
        }
        reject(gatewayRequest, result.toFailure())
    }

    private fun finishFromClient(
        gatewayRequest: GatewayRequest,
        outcome: LocalVlmFocusedObjectOutcome,
    ) {
        if (!active.compareAndSet(gatewayRequest, null)) return
        gatewayRequest.clientTerminalWon.set(true)
        gatewayRequest.watchdog?.cancel(false)
        val delivered = if (outcome.correlation() == gatewayRequest.localCorrelation) {
            outcome
        } else {
            LocalVlmFocusedObjectOutcome.Rejected(
                gatewayRequest.localCorrelation,
                LocalVlmFocusedObjectFailure.STALE_OR_MISMATCHED,
            )
        }
        emit(
            gatewayRequest,
            FocusedVqaPhase.TERMINAL,
            (delivered as? LocalVlmFocusedObjectOutcome.Rejected)?.reason,
        )
        runCatching { callback.onOutcome(delivered) }
    }

    private fun reject(gatewayRequest: GatewayRequest, reason: LocalVlmFocusedObjectFailure) {
        if (!active.compareAndSet(gatewayRequest, null)) return
        gatewayRequest.watchdog?.cancel(false)
        emit(gatewayRequest, FocusedVqaPhase.TERMINAL, reason)
        runCatching {
            callback.onOutcome(LocalVlmFocusedObjectOutcome.Rejected(gatewayRequest.localCorrelation, reason))
        }
    }

    private fun timeOut(gatewayRequest: GatewayRequest) {
        if (!active.compareAndSet(gatewayRequest, null)) return
        gatewayRequest.future?.cancel(true)
        if (gatewayRequest.clientSubmitted.get()) {
            client.cancelFocusedObjectVqa(gatewayRequest.localCorrelation)
        }
        emit(gatewayRequest, FocusedVqaPhase.TERMINAL, LocalVlmFocusedObjectFailure.TIMED_OUT)
        runCatching {
            callback.onOutcome(
                LocalVlmFocusedObjectOutcome.Rejected(
                    gatewayRequest.localCorrelation,
                    LocalVlmFocusedObjectFailure.TIMED_OUT,
                ),
            )
        }
    }

    private fun emit(
        request: GatewayRequest,
        phase: FocusedVqaPhase,
        outcome: LocalVlmFocusedObjectFailure? = null,
    ) {
        val elapsedMillis = ((clockNanos() - request.requestedNanos).coerceAtLeast(0L) / 1_000_000L)
            .coerceAtMost(60_000L)
        runCatching {
            onTelemetry(FocusedVqaPhaseTelemetry(request.localCorrelation.focusRequestId, phase, elapsedMillis, outcome))
        }
    }

    private class GatewayRequest(
        val focusCorrelation: FocusedVqaCorrelation,
        localRequest: LocalVlmFocusedObjectRequest,
    ) {
        val localCorrelation = localRequest.correlation
        val requestedNanos = localRequest.requestedMonotonicTimestampNanos
        @Volatile var future: Future<*>? = null
        @Volatile var watchdog: ScheduledFuture<*>? = null
        val clientSubmitted = AtomicBoolean(false)
        val clientTerminalWon = AtomicBoolean(false)
    }

    /** Holds callbacks until the synchronous submission result and JPEG cleanup are known. */
    private class SubmissionCallbackGate(
        private val deliver: (LocalVlmFocusedObjectOutcome) -> Unit,
    ) {
        private val lock = Any()
        private var submissionAccepted: Boolean? = null
        private var pending: LocalVlmFocusedObjectOutcome? = null

        fun receive(outcome: LocalVlmFocusedObjectOutcome) {
            val immediate = synchronized(lock) {
                when (submissionAccepted) {
                    null -> {
                        if (pending == null) pending = outcome
                        null
                    }
                    true -> outcome
                    false -> null
                }
            }
            immediate?.let(deliver)
        }

        fun complete(accepted: Boolean): LocalVlmFocusedObjectOutcome? = synchronized(lock) {
            check(submissionAccepted == null)
            submissionAccepted = accepted
            val deferred = pending.takeIf { accepted }
            pending = null
            deferred
        }
    }

    private companion object {
        fun newWorker(): ExecutorService = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            { runnable -> Thread(runnable, "mpl-focused-vqa").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )

        fun newWatchdog(): ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "mpl-focused-vqa-watchdog").apply { isDaemon = true }
            }
    }
}

internal fun FocusedVqaRequest.toLocalVlmRequest(): LocalVlmFocusedObjectRequest? {
    val sanitizedQuestion = LocalVlmFocusedObjectQuestionSanitizer.sanitize(question) ?: return null
    return runCatching {
        LocalVlmFocusedObjectRequest(
            LocalVlmFocusedObjectCorrelation(
                focusRequestId = correlation.requestId,
                sessionGeneration = correlation.sessionGeneration,
                snapshotId = correlation.snapshotId,
                focusGeneration = correlation.focusGeneration,
                stableTrackId = correlation.stableTrackId,
                sourceFrameId = correlation.sourceFrameId,
                sourceCaptureTimestampNanos = sourceCaptureTimestampNanos,
            ),
            requestedMonotonicTimestampNanos = requestedTimestampNanos,
            deadlineMonotonicTimestampNanos = FocusedVqaTiming.deadlineNanos(requestedTimestampNanos),
            question = sanitizedQuestion,
        )
    }.getOrNull()
}

private fun LocalVlmSubmissionResult.toFailure(): LocalVlmFocusedObjectFailure = when (this) {
    LocalVlmSubmissionResult.ACCEPTED -> error("accepted submission is not a failure")
    LocalVlmSubmissionResult.BUSY -> LocalVlmFocusedObjectFailure.BUSY
    LocalVlmSubmissionResult.INVALID_REQUEST -> LocalVlmFocusedObjectFailure.INVALID_REQUEST
    LocalVlmSubmissionResult.UNAVAILABLE -> LocalVlmFocusedObjectFailure.UNAVAILABLE
}

private fun LocalVlmFocusedObjectOutcome.correlation(): LocalVlmFocusedObjectCorrelation = when (this) {
    is LocalVlmFocusedObjectOutcome.Answered -> value.correlation
    is LocalVlmFocusedObjectOutcome.Rejected -> correlation
}
