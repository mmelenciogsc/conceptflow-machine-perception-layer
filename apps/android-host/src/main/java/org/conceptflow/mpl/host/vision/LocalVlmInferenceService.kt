// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Private-process owner of the GenieX VLM. Keeping this service in :local_vlm isolates GenieX's
 * native runtime from the separately pinned QAIRT runtime used by live YOLOE and metric depth.
 */
class LocalVlmInferenceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeRequest = AtomicReference<InferenceRequest?>(null)
    private val workGate = GenerationScopedVlmWorkGate()
    private val prewarmWaiters = ConcurrentHashMap<Long, Messenger>()
    private val prewarmJob = AtomicReference<Job?>(null)
    private val drainJob = AtomicReference<Job?>(null)
    private val nativeExecution = AtomicReference<LocalVlmNativeExecution?>(null)
    private val nativeAbortFuture = AtomicReference<ScheduledFuture<*>?>(null)
    private val nativeAbortLock = Any()
    private val nativeAbortExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mpl-vlm-hard-abort").apply { isDaemon = true }
    }
    private val engineLifecycleLock = Any()
    private val processRetirementScheduled = AtomicBoolean(false)
    private var handlerThread: HandlerThread? = null
    private var messenger: Messenger? = null
    private var engine: GenieXLocalVlmEngine? = null
    private var engineIdleCloseJob: Job? = null
    private var releaseEngineWhenIdle = false
    private lateinit var htpExecutionLease: HtpExecutionLease

    override fun onCreate() {
        super.onCreate()
        htpExecutionLease = HtpExecutionLease(applicationContext, ::logLeaseTelemetry)
        cleanupInbox()
        val thread = HandlerThread("mpl-local-vlm-ipc").also { it.start() }
        handlerThread = thread
        messenger = Messenger(Handler(thread.looper) { message -> handleMessage(message) })
    }

    override fun onBind(intent: Intent?): IBinder? = messenger?.binder

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (LocalVlmRuntimeTuning.shouldReleaseForTrimLevel(level)) {
            releaseEngineForMemoryPressure("trim_$level")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        releaseEngineForMemoryPressure("low_memory")
    }

    override fun onDestroy() {
        cancelAll("service_destroyed", notify = false)
        scope.cancel()
        closeEngineNow()
        if (nativeExecution.get() == null) nativeAbortExecutor.shutdownNow()
        handlerThread?.quitSafely()
        handlerThread = null
        messenger = null
        cleanupInbox()
        super.onDestroy()
    }

    private fun handleMessage(message: Message): Boolean {
        if (message.what == LocalVlmIpc.REQUEST_CANCEL_ALL) {
            cancelAll("client_session_invalidated", notify = false)
            return true
        }
        if (message.what == LocalVlmIpc.REQUEST_CANCEL) {
            cancelRequest(message.data)
            return true
        }
        if (message.what == LocalVlmIpc.REQUEST_PREWARM) {
            val requestId = message.data?.getLong(LocalVlmIpc.KEY_REQUEST_ID, 0L) ?: 0L
            val replyTo = message.replyTo
            if (requestId <= 0L || replyTo == null) return true
            prewarm(requestId, replyTo)
            return true
        }
        if (message.what != LocalVlmIpc.REQUEST_INFER) return false
        val request = decodeRequest(message)
        if (request == null) {
            deleteOwnedImage(message.data?.getString(LocalVlmIpc.KEY_IMAGE_PATH)?.let(::File))
            replyMalformed(message)
            return true
        }
        if (!activeRequest.compareAndSet(null, request)) {
            deleteOwnedImage(request.image)
            replyBusy(request)
            return true
        }
        if (request.task == LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1) {
            logFocusedPhase(request, "admitted")
        }
        startInference(request)
        return true
    }

    private fun prewarm(requestId: Long, replyTo: Messenger) {
        prewarmWaiters[requestId] = replyTo
        val generation = workGate.begin(LocalVlmWorkLane.PREWARM)
        if (generation == null) {
            if (!workGate.isActive(LocalVlmWorkLane.PREWARM)) {
                prewarmWaiters.remove(requestId, replyTo)
                sendPrewarmResponse(requestId, replyTo, LocalVlmIpc.RESPONSE_BUSY, "vlm_busy")
            }
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val response = try {
                val runtime = engineForWork()
                val warmupImage = createWarmupImage()
                try {
                    val owner = currentCoroutineContext()[Job] ?: error("prewarm job unavailable")
                    val acquisition = htpExecutionLease.tryAcquire(
                        HtpLeaseWorkload.VLM,
                        VLM_LEASE_ACQUISITION_TIMEOUT_MILLIS,
                    ) { !owner.isActive }
                    val acquired = acquisition as? HtpLeaseAcquisition.Acquired
                    if (acquired == null) {
                        PrewarmResponse(
                            LocalVlmIpc.RESPONSE_DEFERRED,
                            (acquisition as HtpLeaseAcquisition.Refused).reason.name.lowercase(),
                        )
                    } else {
                        val execution = armNativeExecution(
                            requestId,
                            LocalVlmNativeTask.PREWARM,
                            warmupImage,
                        )
                        val monitor = monitorQnnPriority(owner, execution)
                        try {
                            acquired.handle.use { runtime.prewarm(warmupImage) }
                        } finally {
                            monitor.cancel()
                            disarmNativeExecution(execution)
                        }
                        Log.i(LOG_TAG, "local VLM full-path prewarm complete")
                        PrewarmResponse(LocalVlmIpc.RESPONSE_PREWARMED)
                    }
                } finally {
                    warmupImage.delete()
                }
            } catch (error: QnnPriorityCancellation) {
                Log.i(LOG_TAG, "local VLM prewarm cooperatively deferred for QNN priority")
                PrewarmResponse(LocalVlmIpc.RESPONSE_DEFERRED, "qnn_priority_requested")
            } catch (error: CancellationException) {
                PrewarmResponse(LocalVlmIpc.RESPONSE_DEFERRED, "cancelled")
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "local VLM prewarm failed: ${error.javaClass.simpleName}")
                closeEngineNow()
                PrewarmResponse(LocalVlmIpc.RESPONSE_PREWARM_FAILED)
            }
            if (workGate.finish(LocalVlmWorkLane.PREWARM, generation)) {
                prewarmJob.set(null)
                completePrewarm(response.type, response.reason)
                scheduleEngineIdleClose()
            }
        }
        prewarmJob.set(job)
        if (workGate.isCurrent(LocalVlmWorkLane.PREWARM, generation)) {
            job.start()
        } else {
            prewarmJob.compareAndSet(job, null)
            job.cancel()
        }
    }

    private fun completePrewarm(response: Int, reason: String? = null) {
        val waiters = prewarmWaiters.entries.toList()
        waiters.forEach { (waitingRequestId, waitingReply) ->
            prewarmWaiters.remove(waitingRequestId, waitingReply)
            sendPrewarmResponse(waitingRequestId, waitingReply, response, reason)
        }
    }

    private fun sendPrewarmResponse(
        requestId: Long,
        replyTo: Messenger,
        response: Int,
        reason: String? = null,
    ) {
        runCatching {
            replyTo.send(Message.obtain(null, response).apply {
                data = Bundle().apply {
                    putLong(LocalVlmIpc.KEY_REQUEST_ID, requestId)
                    reason?.let { putString(LocalVlmIpc.KEY_FAILURE, it) }
                }
            })
        }
    }

    private fun createWarmupImage(): File {
        val destination = File(cacheDir, PREWARM_IMAGE_NAME)
        val pixels = IntArray(PREWARM_IMAGE_EDGE * PREWARM_IMAGE_EDGE) { index ->
            val x = index % PREWARM_IMAGE_EDGE
            val y = index / PREWARM_IMAGE_EDGE
            val shade = if ((x / 28 + y / 28) % 2 == 0) 0x2a else 0x4a
            (0xff shl 24) or (shade shl 16) or ((shade + 4) shl 8) or (shade + 8)
        }
        val bitmap = Bitmap.createBitmap(pixels, PREWARM_IMAGE_EDGE, PREWARM_IMAGE_EDGE, Bitmap.Config.ARGB_8888)
        try {
            destination.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, PREWARM_JPEG_QUALITY, output))
            }
        } finally {
            bitmap.recycle()
        }
        check(destination.setReadable(false, false) && destination.setReadable(true, true))
        check(destination.setWritable(false, false) && destination.setWritable(true, true))
        return destination
    }

    private fun startInference(request: InferenceRequest) {
        val generation = workGate.begin(LocalVlmWorkLane.DRAIN)
        if (generation == null) {
            activeRequest.compareAndSet(request, null)
            deleteOwnedImage(request.image)
            replyBusy(request)
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                process(request)
            } finally {
                activeRequest.compareAndSet(request, null)
                if (workGate.finish(LocalVlmWorkLane.DRAIN, generation)) {
                    drainJob.set(null)
                }
            }
        }
        drainJob.set(job)
        if (workGate.isCurrent(LocalVlmWorkLane.DRAIN, generation)) {
            job.start()
        } else {
            drainJob.compareAndSet(job, null)
            job.cancel()
        }
    }

    private suspend fun process(request: InferenceRequest) {
        try {
            val image = validateImage(request)
            val runtime = engineForWork()
            val startedNanos = SystemClock.elapsedRealtimeNanos()
            val owner = currentCoroutineContext()[Job] ?: error("VLM request job unavailable")
            val acquisition = acquireForRequest(request, owner)
            val acquired = acquisition as? HtpLeaseAcquisition.Acquired
            if (acquired == null) {
                val refusal = acquisition as HtpLeaseAcquisition.Refused
                if (activeRequest.get() === request) {
                    if (request.isFocusedExpired(SystemClock.elapsedRealtimeNanos())) {
                        logFocusedPhase(request, "terminal", "timed_out")
                        replyFailure(request, "timed_out")
                    } else {
                        logFocusedPhase(request, "terminal", "lease_deferred")
                        replyDeferred(request, refusal.reason.name.lowercase(), refusal.waitNanos)
                    }
                }
                return
            }
            val focusedTimeoutMillis = if (request.task == LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1) {
                FocusedVqaTiming.remainingGenerationMillis(
                    request.deadlineNanos,
                    SystemClock.elapsedRealtimeNanos(),
                )
            } else {
                null
            }
            if (request.task == LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1 && focusedTimeoutMillis == null) {
                acquired.handle.close()
                logFocusedPhase(request, "terminal", "timed_out")
                replyFailure(request, "timed_out")
                return
            }
            if (request.task == LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1) {
                logFocusedPhase(request, "lease_acquired")
            }
            val execution = armNativeExecution(
                request.requestId,
                request.task.toNativeTask(),
                request.image,
                request.deadlineNanos.takeIf { request.task == LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1 },
            )
            val monitor = monitorQnnPriority(owner, execution)
            val result = try {
                acquired.handle.use {
                    when (request.task) {
                        LocalVlmTaskKind.SCENE_ENVIRONMENT_CLASSIFICATION_V1 ->
                            InferenceResult.Environment(runtime.classify(image))
                        LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1 ->
                            InferenceResult.FocusedAnswer(
                                runtime.answerFocusedObject(
                                    image,
                                    requireNotNull(request.question),
                                    requireNotNull(focusedTimeoutMillis),
                                ),
                            )
                    }
                }
            } finally {
                monitor.cancel()
                disarmNativeExecution(execution)
            }
            if (activeRequest.get() !== request || !owner.isActive) return
            Log.i(
                LOG_TAG,
                "completed task=${request.task.name} " +
                    "serviceMs=${(SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000L}",
            )
            val completedNanos = SystemClock.elapsedRealtimeNanos()
            if (request.task == LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1) {
                if (request.isFocusedExpired(completedNanos)) {
                    logFocusedPhase(request, "terminal", "timed_out", completedNanos)
                    replyFailure(request, "timed_out")
                    return
                }
                logFocusedPhase(request, "terminal", "answered", completedNanos)
            }
            when (result) {
                is InferenceResult.Environment -> reply(
                    request,
                    LocalVlmIpc.RESPONSE_CLASSIFIED,
                    Bundle().apply {
                        putString(LocalVlmIpc.KEY_LABEL, result.label.name)
                        putLong(LocalVlmIpc.KEY_COMPLETED_NANOS, completedNanos)
                    },
                )
                is InferenceResult.FocusedAnswer -> reply(
                    request,
                    LocalVlmIpc.RESPONSE_VQA_ANSWERED,
                    Bundle().apply {
                        putString(LocalVlmIpc.KEY_ANSWER, result.answer)
                        putLong(LocalVlmIpc.KEY_COMPLETED_NANOS, completedNanos)
                    },
                )
            }
        } catch (error: QnnPriorityCancellation) {
            if (activeRequest.get() === request) {
                if (request.task == LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1) {
                    logFocusedPhase(request, "terminal", "qnn_priority")
                }
                replyDeferred(request, "qnn_priority_requested")
            }
            throw error
        } catch (_: LocalVlmInferenceTimeout) {
            Log.i(LOG_TAG, "focused VQA timed out")
            closeEngineNow()
            if (activeRequest.get() === request) {
                logFocusedPhase(request, "terminal", "timed_out")
                replyFailure(request, "timed_out")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "local VLM execution failed: ${error.javaClass.simpleName}")
            closeEngineNow()
            if (activeRequest.get() === request) {
                if (request.task == LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1) {
                    logFocusedPhase(request, "terminal", "inference_failed")
                }
                replyFailure(request, "vlm_inference_failed")
            }
        } finally {
            deleteOwnedImage(request.image)
            // Retain the initialized runtime only for a bounded interaction window. This avoids a
            // second multi-second model load when the user asks about a just-focused object while
            // still releasing the measured multi-gigabyte native allocation before indefinite
            // idle can put pressure on the persistent sensor process.
            scheduleEngineIdleClose()
        }
    }

    private fun engineForWork(): GenieXLocalVlmEngine = synchronized(engineLifecycleLock) {
        engineIdleCloseJob?.cancel()
        engineIdleCloseJob = null
        engine ?: GenieXLocalVlmEngine(applicationContext).also { engine = it }
    }

    private fun scheduleEngineIdleClose() {
        synchronized(engineLifecycleLock) {
            engineIdleCloseJob?.cancel()
            val delayNanos = if (releaseEngineWhenIdle) {
                0L
            } else {
                LocalVlmRuntimeTuning.ENGINE_IDLE_RETENTION_NANOS
            }
            engineIdleCloseJob = scope.launch {
                delay(delayNanos / 1_000_000L)
                val released = synchronized(engineLifecycleLock) {
                    if (activeRequest.get() != null ||
                        workGate.isActive(LocalVlmWorkLane.PREWARM) ||
                        workGate.isActive(LocalVlmWorkLane.DRAIN)
                    ) return@synchronized false
                    runCatching { engine?.close() }
                    engine = null
                    releaseEngineWhenIdle = false
                    engineIdleCloseJob = null
                    Log.i(LOG_TAG, "local VLM idle runtime released")
                    true
                }
                if (released) retireIsolatedProcess("idle_lease_expired")
            }
        }
    }

    private fun releaseEngineForMemoryPressure(reason: String) {
        val released = synchronized(engineLifecycleLock) {
            engineIdleCloseJob?.cancel()
            engineIdleCloseJob = null
            if (activeRequest.get() != null ||
                workGate.isActive(LocalVlmWorkLane.PREWARM) ||
                workGate.isActive(LocalVlmWorkLane.DRAIN)
            ) {
                releaseEngineWhenIdle = true
                Log.i(LOG_TAG, "local VLM memory release deferred reason=$reason")
                return@synchronized false
            }
            runCatching { engine?.close() }
            engine = null
            releaseEngineWhenIdle = false
            Log.i(LOG_TAG, "local VLM runtime released reason=$reason")
            true
        }
        if (released) retireIsolatedProcess(reason)
    }

    private fun retireIsolatedProcess(reason: String) {
        if (!processRetirementScheduled.compareAndSet(false, true)) return
        Log.i(LOG_TAG, "state=runtime_retire reason=$reason")
        nativeAbortExecutor.schedule(
            { android.os.Process.killProcess(android.os.Process.myPid()) },
            PROCESS_RETIRE_LOG_FLUSH_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun closeEngineNow() {
        synchronized(engineLifecycleLock) {
            engineIdleCloseJob?.cancel()
            engineIdleCloseJob = null
            runCatching { engine?.close() }
            engine = null
            releaseEngineWhenIdle = false
        }
    }

    /**
     * A focused request is admitted by the main process before this isolated process runs. A QNN
     * invocation that already owned the cross-process lease can therefore win the first probe by
     * a few hundred milliseconds. Retry only that explicit request, for a hard-bounded interval;
     * background classification remains fail-fast and the QNN-priority monitor may still cancel
     * an acquired VQA when critical geometry needs the HTP.
     */
    private suspend fun acquireForRequest(request: InferenceRequest, owner: Job): HtpLeaseAcquisition {
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        while (true) {
            if (request.isFocusedExpired(SystemClock.elapsedRealtimeNanos())) {
                return HtpLeaseAcquisition.Refused(HtpLeaseRefusalReason.TIMEOUT, 0L)
            }
            val acquisition = htpExecutionLease.tryAcquire(
                HtpLeaseWorkload.VLM,
                VLM_LEASE_ACQUISITION_TIMEOUT_MILLIS,
            ) { !owner.isActive || activeRequest.get() !== request }
            val afterAcquireNanos = SystemClock.elapsedRealtimeNanos()
            val acquired = acquisition as? HtpLeaseAcquisition.Acquired
            if (acquired != null) {
                if (request.isFocusedExpired(afterAcquireNanos)) {
                    acquired.handle.close()
                    return HtpLeaseAcquisition.Refused(HtpLeaseRefusalReason.TIMEOUT, 0L)
                }
                return acquired
            }
            val refusal = acquisition as HtpLeaseAcquisition.Refused
            if (!FocusedVqaTiming.mayRetryLease(
                    request.task,
                    refusal.reason,
                    startedNanos,
                    afterAcquireNanos,
                    request.deadlineNanos,
                )
            ) {
                return refusal
            }
            delay(FOCUSED_VQA_LEASE_RETRY_MILLIS)
        }
    }

    private fun monitorQnnPriority(
        owner: Job,
        execution: LocalVlmNativeExecution,
    ): Job = scope.launch {
        while (isActive) {
            delay(QNN_PRIORITY_POLL_MILLIS)
            if (htpExecutionLease.qnnPriorityRequested()) {
                requestNativeAbort(execution.requestId, "qnn_priority")
                owner.cancel(QnnPriorityCancellation())
                return@launch
            }
        }
    }

    private fun cancelAll(reason: String, notify: Boolean) {
        requestNativeAbort(requestId = null, reason = reason)
        workGate.cancelAll()
        val active = activeRequest.getAndSet(null)
        if (active != null) {
            deleteOwnedImage(active.image)
            if (notify) replyDeferred(active, reason)
        }
        prewarmWaiters.clear()
        prewarmJob.getAndSet(null)?.cancel(CancellationException(reason))
        drainJob.getAndSet(null)?.cancel(CancellationException(reason))
    }

    private fun cancelRequest(data: Bundle?) {
        val request = activeRequest.get() ?: return
        if (!request.matchesCancellation(data)) return
        requestNativeAbort(request.requestId, "focused_request_cancelled")
        workGate.cancelAll()
        if (activeRequest.compareAndSet(request, null)) deleteOwnedImage(request.image)
        drainJob.getAndSet(null)?.cancel(CancellationException("focused_request_cancelled"))
    }

    private fun armNativeExecution(
        requestId: Long,
        task: LocalVlmNativeTask,
        image: File,
        deadlineNanos: Long? = null,
    ): LocalVlmNativeExecution {
        val token = LocalVlmNativeExecution(requestId, task, image)
        check(nativeExecution.compareAndSet(null, token))
        if (deadlineNanos != null) {
            scheduleNativeAbort(
                token,
                LocalVlmNativeAbortPolicy.deadlineDelayNanos(
                    deadlineNanos,
                    SystemClock.elapsedRealtimeNanos(),
                ),
                "deadline",
            )
        }
        return token
    }

    private fun requestNativeAbort(requestId: Long?, reason: String): Boolean {
        val token = nativeExecution.get() ?: return false
        if (requestId != null && token.requestId != requestId) return false
        return scheduleNativeAbort(
            token,
            LocalVlmNativeAbortPolicy.COOPERATIVE_STOP_GRACE_NANOS,
            reason,
        )
    }

    private fun scheduleNativeAbort(
        token: LocalVlmNativeExecution,
        delayNanos: Long,
        reason: String,
    ): Boolean = synchronized(nativeAbortLock) {
        if (nativeExecution.get() !== token) return@synchronized false
        val replacement = runCatching {
            nativeAbortExecutor.schedule(
                { hardAbortNativeExecution(token, reason) },
                delayNanos.coerceAtLeast(0L),
                TimeUnit.NANOSECONDS,
            )
        }.getOrElse {
            hardAbortNativeExecution(token, "scheduler_unavailable")
            return@synchronized true
        }
        nativeAbortFuture.getAndSet(replacement)?.cancel(false)
        true
    }

    private fun disarmNativeExecution(token: LocalVlmNativeExecution) {
        if (!nativeExecution.compareAndSet(token, null)) return
        synchronized(nativeAbortLock) {
            nativeAbortFuture.getAndSet(null)?.cancel(false)
        }
    }

    private fun hardAbortNativeExecution(token: LocalVlmNativeExecution, reason: String) {
        if (!nativeExecution.compareAndSet(token, null)) return
        nativeAbortFuture.set(null)
        deleteOwnedImage(token.image)
        Log.e(
            LOG_TAG,
            "state=native_hard_abort task=${token.task.logName} request=${token.requestId} reason=$reason",
        )
        // This service has its own Android process. Killing it is the only hard bound available
        // when the proprietary native prefill call ignores cooperative cancellation; Android's
        // still-active client binding reconstructs a fresh service without killing Android Node.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun decodeRequest(message: Message): InferenceRequest? {
        val data = message.data ?: return null
        val replyTo = message.replyTo ?: return null
        val task = LocalVlmTaskKind.parse(data.getString(LocalVlmIpc.KEY_TASK)) ?: return null
        val requestId = data.getLong(LocalVlmIpc.KEY_REQUEST_ID, 0L)
        val frameId = data.getLong(LocalVlmIpc.KEY_FRAME_ID, 0L)
        val captureNanos = data.getLong(LocalVlmIpc.KEY_CAPTURE_NANOS, -1L)
        val imagePath = data.getString(LocalVlmIpc.KEY_IMAGE_PATH) ?: return null
        val digest = data.getString(LocalVlmIpc.KEY_IMAGE_SHA256) ?: return null
        if (requestId <= 0L || frameId <= 0L || captureNanos < 0L || !SHA256.matches(digest)) return null
        val correlation = if (task == LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1) {
            runCatching {
                LocalVlmFocusedObjectCorrelation(
                    data.getLong(LocalVlmIpc.KEY_FOCUS_REQUEST_ID, 0L),
                    data.getLong(LocalVlmIpc.KEY_SESSION_GENERATION, 0L),
                    data.getLong(LocalVlmIpc.KEY_SNAPSHOT_ID, 0L),
                    data.getLong(LocalVlmIpc.KEY_FOCUS_GENERATION, 0L),
                    data.getString(LocalVlmIpc.KEY_TRACK_ID).orEmpty(),
                    frameId,
                    captureNanos,
                )
            }.getOrNull() ?: return null
        } else {
            null
        }
        val requestedNanos = if (correlation != null) {
            data.getLong(LocalVlmIpc.KEY_REQUESTED_NANOS, -1L)
        } else {
            -1L
        }
        val deadlineNanos = if (correlation != null) {
            data.getLong(LocalVlmIpc.KEY_DEADLINE_NANOS, -1L)
        } else {
            -1L
        }
        val question = if (correlation != null) {
            LocalVlmFocusedObjectQuestionSanitizer.sanitize(
                data.getString(LocalVlmIpc.KEY_QUESTION).orEmpty(),
            ) ?: return null
        } else {
            null
        }
        if (correlation != null) {
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            if (requestedNanos < captureNanos ||
                nowNanos < requestedNanos ||
                nowNanos - requestedNanos > MAXIMUM_FOCUSED_VQA_REQUEST_AGE_NANOS ||
                deadlineNanos != FocusedVqaTiming.deadlineNanos(requestedNanos) ||
                !FocusedVqaTiming.hasTimeRemaining(deadlineNanos, nowNanos)
            ) return null
        }
        return InferenceRequest(
            requestId, frameId, captureNanos, File(imagePath), digest, replyTo,
            task, correlation, requestedNanos, deadlineNanos, question,
        )
    }

    private fun validateImage(request: InferenceRequest): File {
        val inbox = inboxDirectory().canonicalFile
        val image = request.image.canonicalFile
        require(image.parentFile == inbox && IMAGE_NAME.matches(image.name))
        require(image.isFile && image.length() in MIN_JPEG_BYTES..MAX_JPEG_BYTES)
        require(FileInputStream(image).use { it.read() == 0xff && it.read() == 0xd8 })
        require(sha256(image) == request.sha256)
        return image
    }

    private fun replyFailure(request: InferenceRequest, failure: String) = reply(
        request,
        LocalVlmIpc.RESPONSE_FAILED,
        Bundle().apply { putString(LocalVlmIpc.KEY_FAILURE, failure) },
    )

    private fun replyDeferred(
        request: InferenceRequest,
        reason: String,
        waitNanos: Long = 0L,
        holdNanos: Long = 0L,
    ) = reply(
        request,
        LocalVlmIpc.RESPONSE_DEFERRED,
        Bundle().apply {
            putString(LocalVlmIpc.KEY_FAILURE, reason)
            putLong(LocalVlmIpc.KEY_LEASE_WAIT_NANOS, waitNanos)
            putLong(LocalVlmIpc.KEY_LEASE_HOLD_NANOS, holdNanos)
        },
    )

    private fun replyBusy(request: InferenceRequest) = reply(
        request,
        LocalVlmIpc.RESPONSE_BUSY,
        Bundle().apply { putString(LocalVlmIpc.KEY_FAILURE, "vlm_busy") },
    )

    private fun logLeaseTelemetry(event: HtpLeaseTelemetry) {
        Log.i(
            LOG_TAG,
            "state=htp_lease workload=${event.workload.name.lowercase()} acquired=${event.acquired} " +
                "reason=${event.refusalReason?.name?.lowercase() ?: "released"} " +
                "waitMs=${event.waitNanos / 1_000_000L} holdMs=${event.holdNanos?.div(1_000_000L) ?: 0L}",
        )
    }

    private fun reply(request: InferenceRequest, type: Int, payload: Bundle) {
        payload.putLong(LocalVlmIpc.KEY_REQUEST_ID, request.requestId)
        payload.putLong(LocalVlmIpc.KEY_FRAME_ID, request.frameId)
        payload.putLong(LocalVlmIpc.KEY_CAPTURE_NANOS, request.captureNanos)
        payload.putString(LocalVlmIpc.KEY_TASK, request.task.name)
        request.correlation?.let { correlation ->
            payload.putLong(LocalVlmIpc.KEY_FOCUS_REQUEST_ID, correlation.focusRequestId)
            payload.putLong(LocalVlmIpc.KEY_SESSION_GENERATION, correlation.sessionGeneration)
            payload.putLong(LocalVlmIpc.KEY_SNAPSHOT_ID, correlation.snapshotId)
            payload.putLong(LocalVlmIpc.KEY_FOCUS_GENERATION, correlation.focusGeneration)
            payload.putString(LocalVlmIpc.KEY_TRACK_ID, correlation.stableTrackId)
            payload.putLong(LocalVlmIpc.KEY_REQUESTED_NANOS, request.requestedNanos)
            payload.putLong(LocalVlmIpc.KEY_DEADLINE_NANOS, request.deadlineNanos)
        }
        runCatching { request.replyTo.send(Message.obtain(null, type).apply { data = payload }) }
    }

    private fun replyMalformed(message: Message) {
        val replyTo = message.replyTo ?: return
        val request = message.data ?: Bundle.EMPTY
        runCatching {
            replyTo.send(Message.obtain(null, LocalVlmIpc.RESPONSE_FAILED).apply {
                data = Bundle().apply {
                    putLong(LocalVlmIpc.KEY_REQUEST_ID, request.getLong(LocalVlmIpc.KEY_REQUEST_ID, 0L))
                    putLong(LocalVlmIpc.KEY_FRAME_ID, request.getLong(LocalVlmIpc.KEY_FRAME_ID, 0L))
                    putLong(LocalVlmIpc.KEY_CAPTURE_NANOS, request.getLong(LocalVlmIpc.KEY_CAPTURE_NANOS, -1L))
                    putString(LocalVlmIpc.KEY_TASK, request.getString(LocalVlmIpc.KEY_TASK))
                    putLong(
                        LocalVlmIpc.KEY_FOCUS_REQUEST_ID,
                        request.getLong(LocalVlmIpc.KEY_FOCUS_REQUEST_ID, 0L),
                    )
                    putLong(
                        LocalVlmIpc.KEY_SESSION_GENERATION,
                        request.getLong(LocalVlmIpc.KEY_SESSION_GENERATION, 0L),
                    )
                    putLong(LocalVlmIpc.KEY_SNAPSHOT_ID, request.getLong(LocalVlmIpc.KEY_SNAPSHOT_ID, 0L))
                    putLong(
                        LocalVlmIpc.KEY_FOCUS_GENERATION,
                        request.getLong(LocalVlmIpc.KEY_FOCUS_GENERATION, 0L),
                    )
                    putString(LocalVlmIpc.KEY_TRACK_ID, request.getString(LocalVlmIpc.KEY_TRACK_ID))
                    putLong(
                        LocalVlmIpc.KEY_REQUESTED_NANOS,
                        request.getLong(LocalVlmIpc.KEY_REQUESTED_NANOS, -1L),
                    )
                    putLong(
                        LocalVlmIpc.KEY_DEADLINE_NANOS,
                        request.getLong(LocalVlmIpc.KEY_DEADLINE_NANOS, -1L),
                    )
                    putString(LocalVlmIpc.KEY_FAILURE, "invalid_request")
                }
            })
        }
    }

    private fun cleanupInbox() {
        val inbox = inboxDirectory()
        if (inbox.exists()) {
            inbox.listFiles()
                ?.filter { it.isFile && (IMAGE_NAME.matches(it.name) || TEMP_IMAGE_NAME.matches(it.name)) }
                ?.forEach(File::delete)
        }
        File(cacheDir, PREWARM_IMAGE_NAME).delete()
    }

    private fun deleteOwnedImage(candidate: File?) {
        if (candidate == null) return
        runCatching {
            val image = candidate.canonicalFile
            if (image.parentFile == inboxDirectory().canonicalFile &&
                (IMAGE_NAME.matches(image.name) || TEMP_IMAGE_NAME.matches(image.name))
            ) {
                image.delete()
            }
        }
    }

    private fun inboxDirectory() = File(cacheDir, INBOX_DIRECTORY).apply { mkdirs() }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(64 * 1_024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class InferenceRequest(
        val requestId: Long,
        val frameId: Long,
        val captureNanos: Long,
        val image: File,
        val sha256: String,
        val replyTo: Messenger,
        val task: LocalVlmTaskKind,
        val correlation: LocalVlmFocusedObjectCorrelation?,
        val requestedNanos: Long,
        val deadlineNanos: Long,
        val question: String?,
    ) {
        fun isFocusedExpired(nowNanos: Long): Boolean =
            task == LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1 && nowNanos >= deadlineNanos

        fun matchesCancellation(data: Bundle?): Boolean {
            val expected = correlation ?: return false
            if (data == null) return false
            return data.getLong(LocalVlmIpc.KEY_REQUEST_ID, 0L) == requestId &&
                data.getLong(LocalVlmIpc.KEY_FOCUS_REQUEST_ID, 0L) == expected.focusRequestId &&
                data.getLong(LocalVlmIpc.KEY_SESSION_GENERATION, 0L) == expected.sessionGeneration &&
                data.getLong(LocalVlmIpc.KEY_SNAPSHOT_ID, 0L) == expected.snapshotId &&
                data.getLong(LocalVlmIpc.KEY_FOCUS_GENERATION, 0L) == expected.focusGeneration &&
                data.getString(LocalVlmIpc.KEY_TRACK_ID) == expected.stableTrackId &&
                data.getLong(LocalVlmIpc.KEY_FRAME_ID, 0L) == expected.sourceFrameId &&
                data.getLong(LocalVlmIpc.KEY_CAPTURE_NANOS, -1L) == expected.sourceCaptureTimestampNanos
        }
    }

    private sealed interface InferenceResult {
        data class Environment(val label: LocalVlmEnvironmentLabel) : InferenceResult
        data class FocusedAnswer(val answer: String) : InferenceResult
    }

    private data class PrewarmResponse(val type: Int, val reason: String? = null)

    private companion object {
        const val INBOX_DIRECTORY = "local-vlm-inbox"
        const val MIN_JPEG_BYTES = 4L
        const val MAX_JPEG_BYTES = 4L * 1_024L * 1_024L
        const val LOG_TAG = "MplLocalVlmService"
        const val PREWARM_IMAGE_NAME = "generated-prewarm.jpg"
        const val PREWARM_IMAGE_EDGE = 224
        const val PREWARM_JPEG_QUALITY = 82
        const val VLM_LEASE_ACQUISITION_TIMEOUT_MILLIS = 25L
        const val FOCUSED_VQA_LEASE_RETRY_MILLIS = 25L
        const val QNN_PRIORITY_POLL_MILLIS = 20L
        const val PROCESS_RETIRE_LOG_FLUSH_MILLIS = 100L
        const val MAXIMUM_FOCUSED_VQA_REQUEST_AGE_NANOS = 1_500_000_000L
        val SHA256 = Regex("[a-f0-9]{64}")
        val IMAGE_NAME = Regex("frame-[1-9][0-9]{0,18}\\.jpg")
        val TEMP_IMAGE_NAME = Regex("frame-[1-9][0-9]{0,18}\\.jpg\\.tmp")
    }

    private fun logFocusedPhase(
        request: InferenceRequest,
        phase: String,
        outcome: String = "none",
        nowNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ) {
        val elapsedMillis = ((nowNanos - request.requestedNanos).coerceAtLeast(0L) / 1_000_000L)
            .coerceAtMost(60_000L)
        Log.i(
            LOG_TAG,
            "state=focused_vqa requestId=${request.requestId} phase=$phase " +
                "elapsedMs=$elapsedMillis outcome=$outcome",
        )
    }
}

internal fun shouldRetryFocusedVlmLease(
    task: LocalVlmTaskKind,
    reason: HtpLeaseRefusalReason,
    elapsedNanos: Long,
): Boolean = task == LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1 &&
    FocusedVqaTiming.mayRetryLease(task, reason, 0L, elapsedNanos, Long.MAX_VALUE)

private class QnnPriorityCancellation : CancellationException("qnn_priority_requested")

private class GenieXLocalVlmEngine(
    context: android.content.Context,
    private val artifactVerifier: LocalVlmArtifactVerifier = LocalVlmArtifactVerifier(),
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val lock = Mutex()
    private var wrapper: VlmWrapper? = null

    suspend fun classify(image: File): LocalVlmEnvironmentLabel = lock.withLock {
        val runtime = wrapper ?: open().also { wrapper = it }
        LocalVlmEnvironmentOutputParser.parse(
            generate(
                runtime,
                image,
                LocalVlmModelProfile.ENVIRONMENT_PROMPT,
                ENVIRONMENT_MAX_OUTPUT_TOKENS,
                ENVIRONMENT_MAX_OUTPUT_CHARACTERS,
                LocalVlmEnvironmentOutputParser.GRAMMAR,
                ENVIRONMENT_INFERENCE_TIMEOUT_MILLIS,
            ),
        ) ?: error("VLM returned an invalid environment label")
    }

    suspend fun answerFocusedObject(image: File, question: String, timeoutMillis: Long): String = try {
        require(timeoutMillis in 1L..FOCUSED_VQA_INFERENCE_TIMEOUT_MILLIS)
        withTimeout(timeoutMillis) {
            lock.withLock {
                val sanitized = requireNotNull(LocalVlmFocusedObjectQuestionSanitizer.sanitize(question))
                val runtime = wrapper ?: open().also { wrapper = it }
                LocalVlmFocusedObjectAnswerParser.parse(
                    generate(
                        runtime,
                        image,
                        "$FOCUSED_OBJECT_PROMPT\n$sanitized",
                        FOCUSED_VQA_MAX_OUTPUT_TOKENS,
                        LocalVlmFocusedObjectAnswerParser.MAXIMUM_CHARACTERS,
                        grammar = null,
                        timeoutMillis = timeoutMillis,
                    ),
                ) ?: error("VLM returned an invalid focused-object answer")
            }
        }
    } catch (_: TimeoutCancellationException) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { wrapper?.stopStream() }
            runCatching { wrapper?.destroy() }
            wrapper = null
        }
        throw LocalVlmInferenceTimeout()
    }

    /** Executes the same vision/token path as a real request using a generated, non-user image. */
    suspend fun prewarm(image: File) = lock.withLock {
        val runtime = wrapper ?: open().also { wrapper = it }
        LocalVlmEnvironmentOutputParser.parse(
            generate(
                runtime,
                image,
                LocalVlmModelProfile.ENVIRONMENT_PROMPT,
                ENVIRONMENT_MAX_OUTPUT_TOKENS,
                ENVIRONMENT_MAX_OUTPUT_CHARACTERS,
                LocalVlmEnvironmentOutputParser.GRAMMAR,
                ENVIRONMENT_INFERENCE_TIMEOUT_MILLIS,
            ),
        ) ?: error("VLM prewarm output was invalid")
    }

    private suspend fun generate(
        runtime: VlmWrapper,
        image: File,
        prompt: String,
        maximumOutputTokens: Int,
        maximumOutputCharacters: Int,
        grammar: String?,
        timeoutMillis: Long,
    ): String {
        val message = VlmChatMessage(
            "user",
            listOf(
                VlmContent("image", image.absolutePath),
                VlmContent("text", prompt),
            ),
        )
        val template = runtime.applyChatTemplate(arrayOf(message), null, false).getOrThrow()
        val generation = runtime.injectMediaPathsToConfig(
            arrayOf(message),
            GenerationConfig(
                maxTokens = maximumOutputTokens,
                samplerConfig = SamplerConfig(
                    temperature = 0.0f,
                    topP = 1.0f,
                    topK = 1,
                    seed = DETERMINISTIC_SEED,
                    grammarString = grammar,
                ),
            ),
        )
        val output = StringBuilder()
        return try {
            withTimeout(timeoutMillis) {
                runtime.generateStreamFlow(template.formattedText, generation).collect { event ->
                    when (event) {
                        is LlmStreamResult.Token -> {
                            require(output.length + event.text.length <= maximumOutputCharacters)
                            output.append(event.text)
                        }
                        is LlmStreamResult.Completed -> Unit
                        is LlmStreamResult.Error -> throw event.throwable
                    }
                }
            }
            output.toString()
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { runtime.stopStream() } }
            runtime.destroy()
            wrapper = null
            if (error is TimeoutCancellationException) throw LocalVlmInferenceTimeout()
            throw error
        } finally {
            if (wrapper === runtime) runCatching { runtime.reset() }
        }
    }

    private suspend fun open(): VlmWrapper {
        val directory = File(applicationContext.filesDir, MODEL_DIRECTORY)
        val check = artifactVerifier.inspect(directory)
        check(check.available) { check.reason }
        var initializationFailure: String? = null
        GenieXSdk.getInstance().init(
            applicationContext,
            object : GenieXSdk.InitCallback {
                override fun onSuccess() = Unit
                override fun onFailure(reason: String) {
                    initializationFailure = reason.take(MAX_INITIALIZATION_ERROR_CHARACTERS)
                }
            },
        )
        check(initializationFailure == null) { "geniex_initialization_failed" }
        return VlmWrapper.builder()
            .vlmCreateInput(
                VlmCreateInput(
                    model_path = File(directory, LocalVlmModelProfile.MODEL_FILE).absolutePath,
                    mmproj_path = File(directory, LocalVlmModelProfile.PROJECTOR_FILE).absolutePath,
                    config = ModelConfig(
                        nCtx = CONTEXT_TOKENS,
                        nThreads = WORKER_THREADS,
                        nThreadsBatch = WORKER_THREADS,
                        nBatch = LocalVlmRuntimeTuning.PREFILL_BATCH_TOKENS,
                        nUBatch = LocalVlmRuntimeTuning.PREFILL_BATCH_TOKENS,
                        nSeqMax = 1,
                        nGpuLayers = -1,
                    ),
                    runtime_id = LocalVlmModelProfile.RUNTIME_ID,
                    compute_unit = LocalVlmModelProfile.COMPUTE_UNIT,
                ),
            )
            .dispatcher(Dispatchers.IO)
            .build()
            .getOrThrow()
    }

    override fun close() {
        wrapper?.destroy()
        wrapper = null
    }

    private companion object {
        const val MODEL_DIRECTORY = "local-vlm"
        const val CONTEXT_TOKENS = 4_096
        const val WORKER_THREADS = 4
        const val ENVIRONMENT_MAX_OUTPUT_TOKENS = 6
        const val ENVIRONMENT_MAX_OUTPUT_CHARACTERS = 32
        // A physical Poco run still exceeded the eight-second response budget at 24 tokens.
        // Focused answers are a terse interaction layer, so bound them to eight generated tokens;
        // the hard timeout and cooperative QNN preemption remain authoritative.
        const val FOCUSED_VQA_MAX_OUTPUT_TOKENS = 8
        const val DETERMINISTIC_SEED = 2_603
        const val ENVIRONMENT_INFERENCE_TIMEOUT_MILLIS = 8_000L
        const val FOCUSED_VQA_INFERENCE_TIMEOUT_MILLIS = 8_000L
        const val MAX_INITIALIZATION_ERROR_CHARACTERS = 256
        const val FOCUSED_OBJECT_PROMPT = "Answer only the supplied question about the selected " +
            "visible object. Use visible evidence only; do not infer identity, intent, safety, " +
            "or unseen details. Reply with one brief plain-text sentence of at most 16 words."
    }
}

private class LocalVlmInferenceTimeout : IllegalStateException("vlm_inference_timeout")

private enum class LocalVlmNativeTask(val logName: String) {
    PREWARM("prewarm"),
    ENVIRONMENT("environment"),
    FOCUSED_OBJECT_VQA("focused_object_vqa"),
}

private fun LocalVlmTaskKind.toNativeTask(): LocalVlmNativeTask = when (this) {
    LocalVlmTaskKind.SCENE_ENVIRONMENT_CLASSIFICATION_V1 -> LocalVlmNativeTask.ENVIRONMENT
    LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1 -> LocalVlmNativeTask.FOCUSED_OBJECT_VQA
}

private class LocalVlmNativeExecution(
    val requestId: Long,
    val task: LocalVlmNativeTask,
    val image: File,
)
