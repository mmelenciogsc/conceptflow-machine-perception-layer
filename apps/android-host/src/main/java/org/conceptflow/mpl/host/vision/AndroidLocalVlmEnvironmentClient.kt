// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Non-blocking main-process bridge to the process-isolated, HTP-pinned local VLM. */
class AndroidLocalVlmEnvironmentClient(
    context: Context,
    private val clockNanos: () -> Long,
    private val cadence: LocalVlmCadenceGate = LocalVlmCadenceGate(),
    private val sceneChangeGate: LocalVlmSceneChangeGate = LocalVlmSceneChangeGate(),
) : AutoCloseable, LocalFocusedVqaClient {
    private val context = context.applicationContext
    private val requestIds = AtomicLong()
    private val latest = AtomicReference<EnvironmentSignal?>(null)
    private val responseHandler = Handler(Looper.getMainLooper()) { handleResponse(it) }
    private val responseMessenger = Messenger(responseHandler)
    private val focusedCallbacks = LocalVlmFocusedCallbackDispatcher(
        enqueue = { action -> responseHandler.post(action) },
        onCallbackFailure = { error ->
            Log.w(LOG_TAG, "focused VQA callback failed: ${error.javaClass.simpleName}")
        },
    )
    private val reconnectPolicy = LocalVlmReconnectPolicy()
    private val lock = Any()
    private var service: Messenger? = null
    private var bound = false
    private var binding = false
    private var closed = false
    private var prewarmReady = false
    private var prewarmInFlight = false
    private var prewarmRequestId = 0L
    private var prewarmStartedNanos = -1L
    private var nextPrewarmAttemptNanos = 0L
    private var activeRequest: ActiveRequest? = null
    private var latestSceneObservation: SceneObservation? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                handleServiceLoss(unbindBeforeRetry = true)
                return
            }
            var rejectConnection = false
            synchronized(lock) {
                if (closed) {
                    rejectConnection = true
                } else {
                    service = Messenger(binder)
                    binding = false
                    bound = true
                    reconnectPolicy.connected()
                    requestPrewarmLocked(clockNanos())
                }
            }
            if (rejectConnection) unbindQuietly()
            Log.i(LOG_TAG, "local VLM service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Android normally reconnects this still-active binding. A bounded watchdog falls back
            // to an explicit unbind/rebind if no replacement binder arrives.
            handleServiceLoss(unbindBeforeRetry = false)
            Log.w(LOG_TAG, "local VLM service disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            handleServiceLoss(unbindBeforeRetry = true)
        }

        override fun onNullBinding(name: ComponentName?) {
            handleServiceLoss(unbindBeforeRetry = true)
        }
    }

    fun start(): Boolean = bindIfNeeded()

    /** Returns immediately unless this admitted low-rate request needs bounded RGB-to-JPEG encoding. */
    fun offer(frame: EncodedJpegFrame): Boolean {
        val descriptor = runCatching { descriptorFor(frame) }.getOrNull() ?: return false
        return offer(frame.frameId, frame.captureMonotonicTimestampNanos, descriptor) { frame.jpeg }
    }

    fun observe(frame: EncodedJpegFrame): Boolean {
        val descriptor = runCatching { descriptorFor(frame) }.getOrNull() ?: return false
        return observe(frame.frameId, descriptor)
    }

    /** Live P2P camera frames are packed RGB8; encode only after the cadence gate admits a request. */
    fun offer(frame: RawRgbFrame): Boolean {
        val descriptor = runCatching {
            LocalVlmSceneDescriptorExtractor.fromRgb(frame.rgb, frame.width, frame.height, frame.rowStrideBytes)
        }.getOrNull() ?: return false
        return offer(frame.frameId, frame.captureMonotonicTimestampNanos, descriptor) {
            encodeRgbAsJpeg(frame)
        }
    }

    fun observe(frame: RawRgbFrame): Boolean {
        val descriptor = runCatching {
            LocalVlmSceneDescriptorExtractor.fromRgb(frame.rgb, frame.width, frame.height, frame.rowStrideBytes)
        }.getOrNull() ?: return false
        return observe(frame.frameId, descriptor)
    }

    /**
     * Submits one explicit focused-object question. It never runs from frame cadence and never
     * replaces an environment request or another VQA request already using the shared VLM.
     */
    override fun submitFocusedObjectVqa(
        request: LocalVlmFocusedObjectRequest,
        frame: EncodedJpegFrame,
        callback: LocalVlmFocusedObjectCallback,
    ): LocalVlmSubmissionResult = synchronized(lock) {
        val now = clockNanos()
        if (closed || service == null) return LocalVlmSubmissionResult.UNAVAILABLE
        if (!prewarmReady) {
            requestPrewarmLocked(now)
            return if (prewarmInFlight) LocalVlmSubmissionResult.BUSY else LocalVlmSubmissionResult.UNAVAILABLE
        }
        if (activeRequest != null) return LocalVlmSubmissionResult.BUSY
        val correlation = request.correlation
        if (frame.frameId != correlation.sourceFrameId ||
            frame.captureMonotonicTimestampNanos != correlation.sourceCaptureTimestampNanos ||
            now < request.requestedMonotonicTimestampNanos ||
            now - request.requestedMonotonicTimestampNanos > MAXIMUM_FOCUSED_VQA_SUBMISSION_AGE_NANOS ||
            frame.jpeg.size !in MIN_JPEG_BYTES..MAX_JPEG_BYTES
        ) return LocalVlmSubmissionResult.INVALID_REQUEST

        val transportRequestId = requestIds.incrementAndGet()
        val image = runCatching { persist(transportRequestId, frame.jpeg) }.getOrElse {
            return LocalVlmSubmissionResult.UNAVAILABLE
        }
        val active = FocusedActiveRequest(
            transportRequestId,
            correlation.sourceFrameId,
            correlation.sourceCaptureTimestampNanos,
            now,
            image,
            request,
            callback,
        )
        activeRequest = active
        val message = Message.obtain(null, LocalVlmIpc.REQUEST_INFER).apply {
            replyTo = responseMessenger
            data = Bundle().apply {
                putLong(LocalVlmIpc.KEY_REQUEST_ID, transportRequestId)
                putLong(LocalVlmIpc.KEY_FRAME_ID, correlation.sourceFrameId)
                putLong(LocalVlmIpc.KEY_CAPTURE_NANOS, correlation.sourceCaptureTimestampNanos)
                putString(LocalVlmIpc.KEY_IMAGE_PATH, image.absolutePath)
                putString(LocalVlmIpc.KEY_IMAGE_SHA256, sha256(frame.jpeg))
                putString(LocalVlmIpc.KEY_TASK, LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1.name)
                putLong(LocalVlmIpc.KEY_FOCUS_REQUEST_ID, correlation.focusRequestId)
                putLong(LocalVlmIpc.KEY_SESSION_GENERATION, correlation.sessionGeneration)
                putLong(LocalVlmIpc.KEY_SNAPSHOT_ID, correlation.snapshotId)
                putLong(LocalVlmIpc.KEY_FOCUS_GENERATION, correlation.focusGeneration)
                putString(LocalVlmIpc.KEY_TRACK_ID, correlation.stableTrackId)
                putLong(LocalVlmIpc.KEY_REQUESTED_NANOS, request.requestedMonotonicTimestampNanos)
                putString(LocalVlmIpc.KEY_QUESTION, request.question)
            }
        }
        try {
            requireNotNull(service).send(message)
            LocalVlmSubmissionResult.ACCEPTED
        } catch (_: RemoteException) {
            activeRequest = null
            image.delete()
            LocalVlmSubmissionResult.UNAVAILABLE
        }
    }

    /** Cancels only the exactly correlated focused request; stale cancellation is ignored. */
    override fun cancelFocusedObjectVqa(correlation: LocalVlmFocusedObjectCorrelation): Boolean = synchronized(lock) {
        val active = activeRequest as? FocusedActiveRequest ?: return false
        if (active.request.correlation != correlation) return false
        runCatching {
            service?.send(Message.obtain(null, LocalVlmIpc.REQUEST_CANCEL).apply {
                data = Bundle().apply {
                    putLong(LocalVlmIpc.KEY_REQUEST_ID, active.requestId)
                    putLong(LocalVlmIpc.KEY_FOCUS_REQUEST_ID, correlation.focusRequestId)
                    putLong(LocalVlmIpc.KEY_SESSION_GENERATION, correlation.sessionGeneration)
                    putLong(LocalVlmIpc.KEY_SNAPSHOT_ID, correlation.snapshotId)
                    putLong(LocalVlmIpc.KEY_FOCUS_GENERATION, correlation.focusGeneration)
                    putString(LocalVlmIpc.KEY_TRACK_ID, correlation.stableTrackId)
                    putLong(LocalVlmIpc.KEY_FRAME_ID, correlation.sourceFrameId)
                    putLong(LocalVlmIpc.KEY_CAPTURE_NANOS, correlation.sourceCaptureTimestampNanos)
                }
            })
        }
        activeRequest = null
        active.image.delete()
        prewarmReady = false
        nextPrewarmAttemptNanos = clockNanos() + QNN_PRIORITY_RETRY_NANOS
        true
    }

    private fun offer(
        frameId: Long,
        captureNanos: Long,
        descriptor: LocalVlmSceneDescriptor,
        jpegProvider: () -> ByteArray,
    ): Boolean = synchronized(lock) {
        val now = clockNanos()
        if (closed || service == null) return false
        if (!prewarmReady) {
            requestPrewarmLocked(now)
            return false
        }
        val scene = observeLocked(frameId, descriptor, now)
        if (activeRequest != null || !cadence.tryStart(now, scene.significantChange)) return false
        val jpeg = runCatching(jpegProvider).getOrElse {
            cadence.fail(clockNanos())
            return false
        }
        if (jpeg.size !in MIN_JPEG_BYTES..MAX_JPEG_BYTES) {
            cadence.fail(clockNanos())
            return false
        }
        val requestId = requestIds.incrementAndGet()
        val image = runCatching { persist(requestId, jpeg) }.getOrElse {
            cadence.fail(clockNanos())
            return false
        }
        val request = EnvironmentActiveRequest(requestId, frameId, captureNanos, now, image, descriptor)
        activeRequest = request
        val message = Message.obtain(null, LocalVlmIpc.REQUEST_INFER).apply {
            replyTo = responseMessenger
            data = Bundle().apply {
                putLong(LocalVlmIpc.KEY_REQUEST_ID, requestId)
                putLong(LocalVlmIpc.KEY_FRAME_ID, frameId)
                putLong(LocalVlmIpc.KEY_CAPTURE_NANOS, captureNanos)
                putString(LocalVlmIpc.KEY_IMAGE_PATH, image.absolutePath)
                putString(LocalVlmIpc.KEY_IMAGE_SHA256, sha256(jpeg))
                putString(LocalVlmIpc.KEY_TASK, LocalVlmTaskKind.SCENE_ENVIRONMENT_CLASSIFICATION_V1.name)
            }
        }
        try {
            requireNotNull(service).send(message)
            true
        } catch (_: RemoteException) {
            activeRequest = null
            image.delete()
            cadence.fail(clockNanos())
            false
        }
    }

    private fun observe(frameId: Long, descriptor: LocalVlmSceneDescriptor): Boolean = synchronized(lock) {
        if (closed || service == null) return false
        observeLocked(frameId, descriptor, clockNanos())
        true
    }

    private fun observeLocked(
        frameId: Long,
        descriptor: LocalVlmSceneDescriptor,
        nowNanos: Long,
    ): LocalVlmSceneChangeDecision {
        val existing = latestSceneObservation?.takeIf { it.frameId == frameId }
        if (existing != null) return existing.decision
        val scene = sceneChangeGate.observe(descriptor)
        latestSceneObservation = SceneObservation(frameId, scene)
        (activeRequest as? EnvironmentActiveRequest)?.observe(descriptor, sceneChangeGate)
        if (scene.significantChange && cadence.isStable()) {
            latest.set(null)
            cadence.invalidateForSceneChange(nowNanos)
        }
        return scene
    }

    private fun encodeRgbAsJpeg(frame: RawRgbFrame): ByteArray {
        val pixelCount = Math.multiplyExact(frame.width, frame.height)
        val pixels = IntArray(pixelCount)
        var source = 0
        for (index in 0 until pixelCount) {
            val red = frame.rgb[source].toInt() and 0xff
            val green = frame.rgb[source + 1].toInt() and 0xff
            val blue = frame.rgb[source + 2].toInt() and 0xff
            pixels[index] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
            source += 3
        }
        val bitmap = Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val scaled = Bitmap.createScaledBitmap(bitmap, VLM_INPUT_EDGE_PIXELS, VLM_INPUT_EDGE_PIXELS, true)
        return try {
            ByteArrayOutputStream((VLM_INPUT_EDGE_PIXELS * VLM_INPUT_EDGE_PIXELS / 2).coerceAtLeast(1_024)).use { output ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, VLM_JPEG_QUALITY, output))
                output.toByteArray()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    private fun descriptorFor(frame: EncodedJpegFrame): LocalVlmSceneDescriptor {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size))
        return try {
            val samples = ArrayList<Double>(DESCRIPTOR_GRID_EDGE * DESCRIPTOR_GRID_EDGE)
            for (row in 0 until DESCRIPTOR_GRID_EDGE) {
                val y = ((row + 0.5) * bitmap.height / DESCRIPTOR_GRID_EDGE).toInt()
                    .coerceIn(0, bitmap.height - 1)
                for (column in 0 until DESCRIPTOR_GRID_EDGE) {
                    val x = ((column + 0.5) * bitmap.width / DESCRIPTOR_GRID_EDGE).toInt()
                        .coerceIn(0, bitmap.width - 1)
                    val pixel = bitmap.getPixel(x, y)
                    val red = pixel shr 16 and 0xff
                    val green = pixel shr 8 and 0xff
                    val blue = pixel and 0xff
                    samples += (54 * red + 183 * green + 19 * blue) / (255.0 * 256.0)
                }
            }
            LocalVlmSceneDescriptorExtractor.fromLumaSamples(samples)
        } finally {
            bitmap.recycle()
        }
    }

    fun latestFor(frame: VisionFrame): EnvironmentSignal? {
        val signal = latest.get() ?: return null
        val scene = synchronized(lock) {
            latestSceneObservation?.takeIf { it.frameId == frame.frameId }?.decision
        } ?: return null
        if (!scene.baselineMatched || !cadence.isStable()) return null
        if (signal.timestampNanos > frame.captureMonotonicTimestampNanos) return null
        return signal.copy(
            sourceId = STABLE_SCENE_SOURCE_ID,
            timestampNanos = frame.captureMonotonicTimestampNanos,
            originatingFrameId = frame.frameId,
            reliability = (signal.reliability *
                (1.0 - scene.normalizedChangeScore.coerceIn(0.0, 1.0) * MAXIMUM_CARRY_RELIABILITY_REDUCTION))
                .coerceIn(0.0, 1.0),
        )
    }

    /** Snapshot used by the main process to give admitted VLM work a bounded HTP opportunity. */
    fun htpWorkState(): LocalVlmHtpWorkState? = synchronized(lock) {
        activeRequest?.let {
            return LocalVlmHtpWorkState(
                if (it is FocusedActiveRequest) {
                    LocalVlmHtpWorkKind.FOCUSED_OBJECT_VQA
                } else {
                    LocalVlmHtpWorkKind.ENVIRONMENT_CLASSIFICATION
                },
                it.startedNanos,
            )
        }
        if (prewarmInFlight && prewarmStartedNanos >= 0L) {
            LocalVlmHtpWorkState(LocalVlmHtpWorkKind.PREWARM, prewarmStartedNanos)
        } else {
            null
        }
    }

    /**
     * Cancels only current VLM/prewarm work for material QNN demand or bounded-time recovery.
     * Previously confirmed environment evidence is intentionally retained until a replacement is
     * confirmed. The proprietary runtime is still responsible for yielding to cooperative stop.
     */
    fun cancelHtpWorkForQnn(): Boolean = synchronized(lock) {
        val active = activeRequest
        val hadWork = active != null || prewarmInFlight
        if (!hadWork) return false
        runCatching { service?.send(Message.obtain(null, LocalVlmIpc.REQUEST_CANCEL_ALL)) }
        active?.image?.delete()
        activeRequest = null
        when (active) {
            is EnvironmentActiveRequest -> cadence.defer(clockNanos(), QNN_PRIORITY_RETRY_NANOS)
            is FocusedActiveRequest -> notifyFocused(active, LocalVlmFocusedObjectFailure.DEFERRED_FOR_QNN)
            null -> Unit
        }
        prewarmReady = false
        prewarmInFlight = false
        prewarmStartedNanos = -1L
        nextPrewarmAttemptNanos = clockNanos() + QNN_PRIORITY_RETRY_NANOS
        true
    }

    /** Cancels session-owned work and evidence without unbinding the reusable isolated service. */
    fun cancelOutstanding() = synchronized(lock) {
        if (closed) return
        runCatching { service?.send(Message.obtain(null, LocalVlmIpc.REQUEST_CANCEL_ALL)) }
        prewarmReady = false
        prewarmInFlight = false
        prewarmStartedNanos = -1L
        nextPrewarmAttemptNanos = clockNanos() + QNN_PRIORITY_RETRY_NANOS
        activeRequest?.image?.delete()
        activeRequest = null
        cadence.reset()
        sceneChangeGate.reset()
        latestSceneObservation = null
        latest.set(null)
    }

    override fun close() {
        val shouldUnbind = synchronized(lock) {
            if (closed) return
            runCatching { service?.send(Message.obtain(null, LocalVlmIpc.REQUEST_CANCEL_ALL)) }
            closed = true
            reconnectPolicy.close()
            service = null
            prewarmReady = false
            prewarmInFlight = false
            prewarmStartedNanos = -1L
            activeRequest?.image?.delete()
            activeRequest = null
            cadence.reset()
            sceneChangeGate.reset()
            latestSceneObservation = null
            latest.set(null)
            val hadBinding = bound || binding
            bound = false
            binding = false
            hadBinding
        }
        if (shouldUnbind) unbindQuietly()
        cleanupInbox()
    }

    private fun handleResponse(message: Message): Boolean = synchronized(lock) {
        val prewarmTerminal = message.what == LocalVlmIpc.RESPONSE_PREWARMED ||
            message.what == LocalVlmIpc.RESPONSE_PREWARM_FAILED ||
            message.what == LocalVlmIpc.RESPONSE_DEFERRED ||
            message.what == LocalVlmIpc.RESPONSE_BUSY
        if (prewarmInFlight && prewarmTerminal) {
            val requestId = message.data?.getLong(LocalVlmIpc.KEY_REQUEST_ID, 0L) ?: 0L
            if (requestId != prewarmRequestId) return true
            prewarmInFlight = false
            prewarmStartedNanos = -1L
            if (message.what == LocalVlmIpc.RESPONSE_PREWARMED) {
                prewarmReady = true
                Log.i(LOG_TAG, "local VLM prewarm complete")
            } else if (message.what == LocalVlmIpc.RESPONSE_DEFERRED ||
                message.what == LocalVlmIpc.RESPONSE_BUSY
            ) {
                prewarmReady = false
                nextPrewarmAttemptNanos = clockNanos() + QNN_PRIORITY_RETRY_NANOS
                Log.i(LOG_TAG, "local VLM prewarm deferred")
            } else {
                prewarmReady = false
                nextPrewarmAttemptNanos = clockNanos() + PREWARM_RETRY_NANOS
                Log.w(LOG_TAG, "local VLM prewarm failed")
            }
            return true
        }
        if (message.what != LocalVlmIpc.RESPONSE_CLASSIFIED &&
            message.what != LocalVlmIpc.RESPONSE_VQA_ANSWERED &&
            message.what != LocalVlmIpc.RESPONSE_FAILED &&
            message.what != LocalVlmIpc.RESPONSE_DEFERRED &&
            message.what != LocalVlmIpc.RESPONSE_BUSY
        ) {
            return false
        }
        val data = message.data ?: return true
        val request = activeRequest ?: return true
        if (data.getLong(LocalVlmIpc.KEY_REQUEST_ID, 0L) != request.requestId) return true
        activeRequest = null
        request.image.delete()
        val now = clockNanos()
        when (request) {
            is EnvironmentActiveRequest -> handleEnvironmentResponse(request, message.what, data, now)
            is FocusedActiveRequest -> handleFocusedResponse(request, message.what, data, now)
        }
        true
    }

    private fun handleEnvironmentResponse(
        request: EnvironmentActiveRequest,
        response: Int,
        data: Bundle,
        nowNanos: Long,
    ) {
        if (response == LocalVlmIpc.RESPONSE_DEFERRED || response == LocalVlmIpc.RESPONSE_BUSY) {
            cadence.defer(nowNanos, QNN_PRIORITY_RETRY_NANOS)
            Log.i(LOG_TAG, "local VLM environment request deferred")
            return
        }
        if (response == LocalVlmIpc.RESPONSE_FAILED) {
            cadence.fail(nowNanos)
            prewarmReady = false
            requestPrewarmLocked(nowNanos)
            Log.w(LOG_TAG, "local VLM environment request failed")
            return
        }
        if (request.superseded) {
            cadence.cancel()
            latest.set(null)
            Log.i(LOG_TAG, "discarded stale local VLM response for frame=${request.frameId}")
            return
        }
        val frameId = data.getLong(LocalVlmIpc.KEY_FRAME_ID, 0L)
        val captureNanos = data.getLong(LocalVlmIpc.KEY_CAPTURE_NANOS, -1L)
        val completedNanos = data.getLong(LocalVlmIpc.KEY_COMPLETED_NANOS, -1L)
        val task = LocalVlmTaskKind.parse(data.getString(LocalVlmIpc.KEY_TASK))
        val label = data.getString(LocalVlmIpc.KEY_LABEL)
            ?.let { runCatching { LocalVlmEnvironmentLabel.valueOf(it) }.getOrNull() }
        if (response != LocalVlmIpc.RESPONSE_CLASSIFIED ||
            task != LocalVlmTaskKind.SCENE_ENVIRONMENT_CLASSIFICATION_V1 ||
            frameId != request.frameId || captureNanos != request.captureNanos ||
            completedNanos < captureNanos || label == null
        ) {
            cadence.fail(nowNanos)
            return
        }
        val result = LocalVlmEnvironmentResult(
            request.requestId,
            frameId,
            captureNanos,
            completedNanos,
            label,
            LocalVlmModelProfile.MODEL_ID,
            LocalVlmModelProfile.RUNTIME_ID,
            LocalVlmModelProfile.COMPUTE_UNIT,
        )
        val confirmed = cadence.complete(label, nowNanos)
        if (confirmed == label) {
            sceneChangeGate.markClassified(request.descriptor)
            result.toEnvironmentSignal()?.let(latest::set)
        }
        Log.i(
            LOG_TAG,
            "local VLM classified frame=$frameId label=${label.name} " +
                "latencyMs=${(completedNanos - captureNanos) / 1_000_000L}",
        )
    }

    private fun handleFocusedResponse(
        request: FocusedActiveRequest,
        response: Int,
        data: Bundle,
        nowNanos: Long,
    ) {
        val correlation = request.request.correlation
        val returnedCorrelation = decodeFocusedCorrelation(data)
        val task = LocalVlmTaskKind.parse(data.getString(LocalVlmIpc.KEY_TASK))
        val requestedNanos = data.getLong(LocalVlmIpc.KEY_REQUESTED_NANOS, -1L)
        if (!LocalVlmFocusedObjectResponseValidator.matches(
                correlation,
                request.request.requestedMonotonicTimestampNanos,
                task,
                returnedCorrelation,
                requestedNanos,
            )
        ) {
            notifyFocused(request, LocalVlmFocusedObjectFailure.STALE_OR_MISMATCHED)
            return
        }
        when (response) {
            LocalVlmIpc.RESPONSE_BUSY -> {
                notifyFocused(request, LocalVlmFocusedObjectFailure.BUSY)
                return
            }
            LocalVlmIpc.RESPONSE_DEFERRED -> {
                notifyFocused(request, LocalVlmFocusedObjectFailure.DEFERRED_FOR_QNN)
                return
            }
            LocalVlmIpc.RESPONSE_FAILED -> {
                val failure = if (data.getString(LocalVlmIpc.KEY_FAILURE) == "invalid_request") {
                    LocalVlmFocusedObjectFailure.INVALID_REQUEST
                } else {
                    prewarmReady = false
                    requestPrewarmLocked(nowNanos)
                    LocalVlmFocusedObjectFailure.INFERENCE_FAILED
                }
                notifyFocused(request, failure)
                return
            }
        }
        val completedNanos = data.getLong(LocalVlmIpc.KEY_COMPLETED_NANOS, -1L)
        val answer = LocalVlmFocusedObjectResponseValidator.validateAnswer(
            expectedCorrelation = correlation,
            expectedRequestedNanos = request.request.requestedMonotonicTimestampNanos,
            requestStartedNanos = request.startedNanos,
            nowNanos = nowNanos,
            isAnswerResponse = response == LocalVlmIpc.RESPONSE_VQA_ANSWERED,
            returnedTask = task,
            returnedCorrelation = returnedCorrelation,
            returnedRequestedNanos = requestedNanos,
            completedNanos = completedNanos,
            rawAnswer = data.getString(LocalVlmIpc.KEY_ANSWER),
        )
        if (answer == null) {
            notifyFocused(request, LocalVlmFocusedObjectFailure.STALE_OR_MISMATCHED)
            return
        }
        notifyFocused(request, LocalVlmFocusedObjectOutcome.Answered(answer))
    }

    private fun decodeFocusedCorrelation(data: Bundle): LocalVlmFocusedObjectCorrelation? = runCatching {
        LocalVlmFocusedObjectCorrelation(
            data.getLong(LocalVlmIpc.KEY_FOCUS_REQUEST_ID, 0L),
            data.getLong(LocalVlmIpc.KEY_SESSION_GENERATION, 0L),
            data.getLong(LocalVlmIpc.KEY_SNAPSHOT_ID, 0L),
            data.getLong(LocalVlmIpc.KEY_FOCUS_GENERATION, 0L),
            data.getString(LocalVlmIpc.KEY_TRACK_ID).orEmpty(),
            data.getLong(LocalVlmIpc.KEY_FRAME_ID, 0L),
            data.getLong(LocalVlmIpc.KEY_CAPTURE_NANOS, -1L),
        )
    }.getOrNull()

    private fun notifyFocused(
        request: FocusedActiveRequest,
        reason: LocalVlmFocusedObjectFailure,
    ) = notifyFocused(
        request,
        LocalVlmFocusedObjectOutcome.Rejected(request.request.correlation, reason),
    )

    private fun notifyFocused(
        request: FocusedActiveRequest,
        outcome: LocalVlmFocusedObjectOutcome,
    ) {
        focusedCallbacks.dispatch(request.callback, outcome)
    }

    private fun abandonActiveLocked(reason: LocalVlmFocusedObjectFailure): FocusedDelivery? {
        val active = activeRequest ?: return null
        activeRequest = null
        active.image.delete()
        return when (active) {
            is EnvironmentActiveRequest -> {
                cadence.fail(clockNanos())
                null
            }
            is FocusedActiveRequest -> FocusedDelivery(
                active,
                LocalVlmFocusedObjectOutcome.Rejected(active.request.correlation, reason),
            )
        }
    }

    private fun handleServiceLoss(unbindBeforeRetry: Boolean) {
        var shouldUnbind = false
        var delivery: FocusedDelivery? = null
        var ticket: LocalVlmReconnectTicket? = null
        synchronized(lock) {
            if (closed) return
            service = null
            binding = false
            prewarmReady = false
            prewarmInFlight = false
            prewarmStartedNanos = -1L
            delivery = abandonActiveLocked(LocalVlmFocusedObjectFailure.UNAVAILABLE)
            if (unbindBeforeRetry) {
                shouldUnbind = bound
                bound = false
            }
            ticket = reconnectPolicy.schedule()
        }
        if (shouldUnbind) unbindQuietly()
        delivery?.deliver()
        ticket?.let(::postReconnect)
    }

    private fun bindIfNeeded(): Boolean {
        synchronized(lock) {
            if (closed) return false
            if (service != null || bound || binding) return true
            binding = true
        }
        val accepted = runCatching {
            context.bindService(
                Intent(context, LocalVlmInferenceService::class.java),
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            )
        }.getOrDefault(false)
        var shouldUnbind = false
        var ticket: LocalVlmReconnectTicket? = null
        val usable = synchronized(lock) {
            binding = false
            if (closed) {
                shouldUnbind = accepted
                bound = false
                false
            } else {
                bound = accepted
                if (!accepted && service == null) ticket = reconnectPolicy.schedule()
                accepted
            }
        }
        if (shouldUnbind) unbindQuietly()
        ticket?.let(::postReconnect)
        return usable
    }

    private fun postReconnect(ticket: LocalVlmReconnectTicket) {
        responseHandler.postDelayed(
            {
                if (!reconnectPolicy.consume(ticket)) return@postDelayed
                var shouldUnbind = false
                synchronized(lock) {
                    if (closed || service != null) return@synchronized
                    shouldUnbind = bound
                    bound = false
                }
                if (shouldUnbind) unbindQuietly()
                bindIfNeeded()
            },
            ticket.delayMillis,
        )
    }

    private fun unbindQuietly() {
        runCatching { context.unbindService(connection) }
    }

    private fun FocusedDelivery.deliver() {
        notifyFocused(request, outcome)
    }

    private fun persist(requestId: Long, bytes: ByteArray): File {
        val directory = inboxDirectory()
        val temporary = File(directory, "frame-$requestId.jpg.tmp")
        val destination = File(directory, "frame-$requestId.jpg")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(temporary.renameTo(destination)) { "could not atomically publish VLM input" }
            check(destination.setReadable(false, false) && destination.setReadable(true, true))
            check(destination.setWritable(false, false) && destination.setWritable(true, true))
            return destination
        } catch (error: Throwable) {
            destination.delete()
            throw error
        } finally {
            temporary.delete()
        }
    }

    private fun cleanupInbox() {
        val directory = inboxDirectory()
        directory.listFiles()?.filter { it.isFile && it.name.startsWith("frame-") }?.forEach(File::delete)
    }

    private fun requestPrewarmLocked(nowNanos: Long) {
        val target = service ?: return
        if (prewarmReady || prewarmInFlight || nowNanos < nextPrewarmAttemptNanos) return
        val requestId = requestIds.incrementAndGet()
        try {
            target.send(Message.obtain(null, LocalVlmIpc.REQUEST_PREWARM).apply {
                replyTo = responseMessenger
                data = Bundle().apply { putLong(LocalVlmIpc.KEY_REQUEST_ID, requestId) }
            })
            prewarmRequestId = requestId
            prewarmInFlight = true
            prewarmStartedNanos = nowNanos
        } catch (_: RemoteException) {
            nextPrewarmAttemptNanos = nowNanos + PREWARM_RETRY_NANOS
        }
    }

    private fun inboxDirectory() = File(context.cacheDir, INBOX_DIRECTORY).apply { mkdirs() }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private sealed class ActiveRequest(
        open val requestId: Long,
        open val frameId: Long,
        open val captureNanos: Long,
        open val startedNanos: Long,
        open val image: File,
    )

    private class EnvironmentActiveRequest(
        override val requestId: Long,
        override val frameId: Long,
        override val captureNanos: Long,
        override val startedNanos: Long,
        override val image: File,
        val descriptor: LocalVlmSceneDescriptor,
    ) : ActiveRequest(requestId, frameId, captureNanos, startedNanos, image) {
        private var consecutiveMismatches = 0
        var superseded: Boolean = false
            private set

        fun observe(current: LocalVlmSceneDescriptor, gate: LocalVlmSceneChangeGate) {
            if (superseded) return
            consecutiveMismatches = if (gate.compare(descriptor, current).materiallyDifferent) {
                consecutiveMismatches + 1
            } else {
                0
            }
            superseded = consecutiveMismatches >= REQUEST_SCENE_MISMATCH_CONFIRMATIONS
        }

        private companion object {
            const val REQUEST_SCENE_MISMATCH_CONFIRMATIONS = 2
        }
    }

    private class FocusedActiveRequest(
        override val requestId: Long,
        override val frameId: Long,
        override val captureNanos: Long,
        override val startedNanos: Long,
        override val image: File,
        val request: LocalVlmFocusedObjectRequest,
        val callback: LocalVlmFocusedObjectCallback,
    ) : ActiveRequest(requestId, frameId, captureNanos, startedNanos, image)

    private data class FocusedDelivery(
        val request: FocusedActiveRequest,
        val outcome: LocalVlmFocusedObjectOutcome,
    )

    private data class SceneObservation(
        val frameId: Long,
        val decision: LocalVlmSceneChangeDecision,
    )

    private companion object {
        const val INBOX_DIRECTORY = "local-vlm-inbox"
        const val MIN_JPEG_BYTES = 4
        const val MAX_JPEG_BYTES = 4 * 1_024 * 1_024
        const val VLM_JPEG_QUALITY = 82
        const val VLM_INPUT_EDGE_PIXELS = 224
        const val DESCRIPTOR_GRID_EDGE = 16
        const val PREWARM_RETRY_NANOS = 10_000_000_000L
        const val QNN_PRIORITY_RETRY_NANOS = 250_000_000L
        const val MAXIMUM_FOCUSED_VQA_SUBMISSION_AGE_NANOS = 1_500_000_000L
        const val MAXIMUM_CARRY_RELIABILITY_REDUCTION = 0.35
        const val STABLE_SCENE_SOURCE_ID = "qwen3-vl-2b-environment-stable-scene"
        const val LOG_TAG = "MplLocalVlm"
    }
}
