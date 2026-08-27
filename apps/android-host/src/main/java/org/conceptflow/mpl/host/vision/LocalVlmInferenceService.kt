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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
    private val pending = AtomicReference<InferenceRequest?>(null)
    private val workGate = GenerationScopedVlmWorkGate()
    private val prewarmWaiters = ConcurrentHashMap<Long, Messenger>()
    private val prewarmJob = AtomicReference<Job?>(null)
    private val drainJob = AtomicReference<Job?>(null)
    private var handlerThread: HandlerThread? = null
    private var messenger: Messenger? = null
    private var engine: GenieXEnvironmentEngine? = null
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

    override fun onDestroy() {
        cancelAll("service_destroyed", notify = false)
        scope.cancel()
        runCatching { engine?.close() }
        engine = null
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
        if (message.what == LocalVlmIpc.REQUEST_PREWARM) {
            val requestId = message.data?.getLong(LocalVlmIpc.KEY_REQUEST_ID, 0L) ?: 0L
            val replyTo = message.replyTo
            if (requestId <= 0L || replyTo == null) return true
            prewarm(requestId, replyTo)
            return true
        }
        if (message.what != LocalVlmIpc.REQUEST_CLASSIFY) return false
        val request = decodeRequest(message) ?: return true
        pending.getAndSet(request)?.let { superseded ->
            superseded.image.delete()
            replyFailure(superseded, "superseded_before_execution")
        }
        drain()
        return true
    }

    private fun prewarm(requestId: Long, replyTo: Messenger) {
        prewarmWaiters[requestId] = replyTo
        val generation = workGate.begin(LocalVlmWorkLane.PREWARM) ?: return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val response = try {
                val runtime = engine ?: GenieXEnvironmentEngine(applicationContext).also { engine = it }
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
                        val monitor = monitorQnnPriority(owner)
                        try {
                            acquired.handle.use { runtime.prewarm(warmupImage) }
                        } finally {
                            monitor.cancel()
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
                runCatching { engine?.close() }
                engine = null
                PrewarmResponse(LocalVlmIpc.RESPONSE_PREWARM_FAILED)
            }
            if (workGate.finish(LocalVlmWorkLane.PREWARM, generation)) {
                prewarmJob.set(null)
                completePrewarm(response.type, response.reason)
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
            runCatching {
                waitingReply.send(Message.obtain(null, response).apply {
                    data = Bundle().apply {
                        putLong(LocalVlmIpc.KEY_REQUEST_ID, waitingRequestId)
                        reason?.let { putString(LocalVlmIpc.KEY_FAILURE, it) }
                    }
                })
            }
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

    private fun drain() {
        val generation = workGate.begin(LocalVlmWorkLane.DRAIN) ?: return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    val request = pending.getAndSet(null) ?: break
                    process(request)
                }
            } finally {
                if (workGate.finish(LocalVlmWorkLane.DRAIN, generation)) {
                    drainJob.set(null)
                    if (pending.get() != null) drain()
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
            validateImage(request)
            val runtime = engine ?: GenieXEnvironmentEngine(applicationContext).also { engine = it }
            val startedNanos = SystemClock.elapsedRealtimeNanos()
            val owner = currentCoroutineContext()[Job] ?: error("VLM request job unavailable")
            val acquisition = htpExecutionLease.tryAcquire(
                HtpLeaseWorkload.VLM,
                VLM_LEASE_ACQUISITION_TIMEOUT_MILLIS,
            ) { !owner.isActive }
            val acquired = acquisition as? HtpLeaseAcquisition.Acquired
            if (acquired == null) {
                val refusal = acquisition as HtpLeaseAcquisition.Refused
                replyDeferred(request, refusal.reason.name.lowercase(), refusal.waitNanos)
                return
            }
            val monitor = monitorQnnPriority(owner)
            val label = try {
                acquired.handle.use { runtime.classify(request.image) }
            } finally {
                monitor.cancel()
            }
            Log.i(
                LOG_TAG,
                "classified frame=${request.frameId} label=${label.name} " +
                    "serviceMs=${(SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000L}",
            )
            reply(
                request,
                LocalVlmIpc.RESPONSE_CLASSIFIED,
                Bundle().apply {
                    putString(LocalVlmIpc.KEY_LABEL, label.name)
                    putLong(LocalVlmIpc.KEY_COMPLETED_NANOS, SystemClock.elapsedRealtimeNanos())
                },
            )
        } catch (error: QnnPriorityCancellation) {
            replyDeferred(request, "qnn_priority_requested")
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "local VLM execution failed: ${error.javaClass.simpleName}")
            runCatching { engine?.close() }
            engine = null
            replyFailure(request, "vlm_inference_failed")
        } finally {
            request.image.delete()
        }
    }

    private fun monitorQnnPriority(owner: Job): Job = scope.launch {
        while (isActive) {
            delay(QNN_PRIORITY_POLL_MILLIS)
            if (htpExecutionLease.qnnPriorityRequested()) {
                owner.cancel(QnnPriorityCancellation())
                return@launch
            }
        }
    }

    private fun cancelAll(reason: String, notify: Boolean) {
        workGate.cancelAll()
        val queued = pending.getAndSet(null)
        if (queued != null) {
            queued.image.delete()
            if (notify) replyDeferred(queued, reason)
        }
        prewarmWaiters.clear()
        prewarmJob.getAndSet(null)?.cancel(CancellationException(reason))
        drainJob.getAndSet(null)?.cancel(CancellationException(reason))
    }

    private fun decodeRequest(message: Message): InferenceRequest? {
        val data = message.data ?: return null
        val replyTo = message.replyTo ?: return null
        if (data.getString(LocalVlmIpc.KEY_TASK) != LocalVlmTaskKind.SCENE_ENVIRONMENT_CLASSIFICATION_V1.name) {
            return null
        }
        val requestId = data.getLong(LocalVlmIpc.KEY_REQUEST_ID, 0L)
        val frameId = data.getLong(LocalVlmIpc.KEY_FRAME_ID, 0L)
        val captureNanos = data.getLong(LocalVlmIpc.KEY_CAPTURE_NANOS, -1L)
        val imagePath = data.getString(LocalVlmIpc.KEY_IMAGE_PATH) ?: return null
        val digest = data.getString(LocalVlmIpc.KEY_IMAGE_SHA256) ?: return null
        if (requestId <= 0L || frameId <= 0L || captureNanos < 0L || !SHA256.matches(digest)) return null
        return InferenceRequest(requestId, frameId, captureNanos, File(imagePath), digest, replyTo)
    }

    private fun validateImage(request: InferenceRequest) {
        val inbox = inboxDirectory().canonicalFile
        val image = request.image.canonicalFile
        require(image.parentFile == inbox && IMAGE_NAME.matches(image.name))
        require(image.isFile && image.length() in MIN_JPEG_BYTES..MAX_JPEG_BYTES)
        require(FileInputStream(image).use { it.read() == 0xff && it.read() == 0xd8 })
        require(sha256(image) == request.sha256)
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
        runCatching { request.replyTo.send(Message.obtain(null, type).apply { data = payload }) }
    }

    private fun cleanupInbox() {
        val inbox = inboxDirectory()
        if (inbox.exists()) {
            inbox.listFiles()?.filter { it.isFile && IMAGE_NAME.matches(it.name) }?.forEach(File::delete)
        }
        File(cacheDir, PREWARM_IMAGE_NAME).delete()
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
    )

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
        const val QNN_PRIORITY_POLL_MILLIS = 20L
        val SHA256 = Regex("[a-f0-9]{64}")
        val IMAGE_NAME = Regex("frame-[1-9][0-9]{0,18}\\.jpg")
    }
}

private class QnnPriorityCancellation : CancellationException("qnn_priority_requested")

private class GenieXEnvironmentEngine(
    context: android.content.Context,
    private val artifactVerifier: LocalVlmArtifactVerifier = LocalVlmArtifactVerifier(),
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val lock = Mutex()
    private var wrapper: VlmWrapper? = null

    suspend fun classify(image: File): LocalVlmEnvironmentLabel = lock.withLock {
        val runtime = wrapper ?: open().also { wrapper = it }
        generate(runtime, image)
    }

    /** Executes the same vision/token path as a real request using a generated, non-user image. */
    suspend fun prewarm(image: File) = lock.withLock {
        val runtime = wrapper ?: open().also { wrapper = it }
        generate(runtime, image)
    }

    private suspend fun generate(runtime: VlmWrapper, image: File): LocalVlmEnvironmentLabel {
        val message = VlmChatMessage(
            "user",
            listOf(
                VlmContent("image", image.absolutePath),
                VlmContent("text", LocalVlmModelProfile.ENVIRONMENT_PROMPT),
            ),
        )
        val template = runtime.applyChatTemplate(arrayOf(message), null, false).getOrThrow()
        val generation = runtime.injectMediaPathsToConfig(
            arrayOf(message),
            GenerationConfig(
                maxTokens = MAX_OUTPUT_TOKENS,
                samplerConfig = SamplerConfig(
                    temperature = 0.0f,
                    topP = 1.0f,
                    topK = 1,
                    seed = DETERMINISTIC_SEED,
                    grammarString = LocalVlmEnvironmentOutputParser.GRAMMAR,
                ),
            ),
        )
        val output = StringBuilder()
        return try {
            withTimeout(INFERENCE_TIMEOUT_MILLIS) {
                runtime.generateStreamFlow(template.formattedText, generation).collect { event ->
                    when (event) {
                        is LlmStreamResult.Token -> {
                            require(output.length + event.text.length <= MAX_OUTPUT_CHARACTERS)
                            output.append(event.text)
                        }
                        is LlmStreamResult.Completed -> Unit
                        is LlmStreamResult.Error -> throw event.throwable
                    }
                }
            }
            LocalVlmEnvironmentOutputParser.parse(output.toString())
                ?: error("VLM returned an invalid environment label")
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { runtime.stopStream() } }
            runtime.destroy()
            wrapper = null
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
                        nBatch = 1,
                        nUBatch = 1,
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
        const val MAX_OUTPUT_TOKENS = 6
        const val MAX_OUTPUT_CHARACTERS = 32
        const val DETERMINISTIC_SEED = 2_603
        const val INFERENCE_TIMEOUT_MILLIS = 8_000L
        const val MAX_INITIALIZATION_ERROR_CHARACTERS = 256
    }
}
