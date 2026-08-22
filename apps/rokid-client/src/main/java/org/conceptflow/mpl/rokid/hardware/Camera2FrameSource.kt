// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import org.conceptflow.mpl.rokid.core.ElapsedRealtimeClock
import org.conceptflow.mpl.rokid.core.CaptureGateEvent
import org.conceptflow.mpl.rokid.core.CapturePipelineSnapshot
import org.conceptflow.mpl.rokid.core.CaptureTimingEvent
import org.conceptflow.mpl.rokid.core.FrameLimits
import org.conceptflow.mpl.rokid.core.FrameSource
import org.conceptflow.mpl.rokid.core.MonotonicFrameSequence
import org.conceptflow.mpl.rokid.core.PixelDimensions
import org.conceptflow.mpl.rokid.core.SystemWallClock
import org.conceptflow.mpl.rokid.core.buildJpegFrame
import org.conceptflow.mpl.rokid.core.selectClosestCaptureSize
import java.util.UUID
import java.util.concurrent.Executor

class Camera2FrameSource(
    context: Context,
    private val limits: FrameLimits = FrameLimits(),
) : FrameSource {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val stateLock = Any()
    private val lifecycle = CameraRunLifecycle()
    private val sequence = MonotonicFrameSequence()
    private val processor = AdaptiveJpegProcessor(limits)
    private val captureCadence = AdaptivePhysicalCaptureCadence()
    private val capturePipeline = BoundedCaptureRequestPipeline()
    private val sessionId = "camera-${UUID.randomUUID()}"
    private var listener: FrameSource.Listener? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var previewReader: ImageReader? = null
    private var imageReader: ImageReader? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var scheduledCaptureOpportunity: Runnable? = null
    private var teardownInProgress = false

    override val isRunning: Boolean get() = lifecycle.isRunning

    override fun start(listener: FrameSource.Listener) {
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onError(CAMERA_PERMISSION_UNAVAILABLE_MESSAGE)
            return
        }

        val runId = synchronized(stateLock) {
            check(!teardownInProgress) { "Camera frame source teardown is still in progress" }
            val id = lifecycle.begin()
            processor.reset()
            capturePipeline.beginRun(id)
            this.listener = listener
            id
        }
        val thread = HandlerThread("bounded-camera-capture")
        val handler = try {
            thread.start()
            Handler(thread.looper)
        } catch (_: RuntimeException) {
            closeUnownedThread(thread)
            failRun(runId, CAMERA_START_FAILURE_MESSAGE)
            return
        }

        val attached = synchronized(stateLock) {
            if (!lifecycle.isActive(runId)) {
                false
            } else {
                cameraThread = thread
                cameraHandler = handler
                true
            }
        }
        if (!attached) {
            closeUnownedThread(thread)
            return
        }

        try {
            openCamera(runId, handler)
        } catch (_: CameraAccessException) {
            failRun(runId, CAMERA_OPEN_FAILURE_MESSAGE)
        } catch (error: SecurityException) {
            failRun(runId, cameraPermissionFailure(error).message ?: CAMERA_PERMISSION_UNAVAILABLE_MESSAGE)
        } catch (_: RuntimeException) {
            failRun(runId, CAMERA_START_FAILURE_MESSAGE)
        }
    }

    @Throws(CameraAccessException::class)
    private fun openCamera(runId: Long, handler: Handler) {
        if (!lifecycle.isActive(runId)) return
        val cameraId = selectCameraId()
        val size = selectJpegSize(cameraId)
        val previewSize = selectHeadlessPreviewSize(
            cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.map { PixelDimensions(it.width, it.height) }
                .orEmpty(),
        ) ?: error("Camera has no bounded YUV preview output size")
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        val preview = try {
            ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2)
        } catch (error: RuntimeException) {
            closeSafely { reader.close() }
            throw error
        }
        if (!attachImageReaders(runId, reader, preview)) {
            closeSafely { reader.close() }
            closeSafely { preview.close() }
            return
        }
        reader.setOnImageAvailableListener(
            { source ->
                try {
                    consumeLatestImage(
                        source,
                        runId,
                        handler,
                        imageAvailableMonotonicTimestampNanos = ElapsedRealtimeClock.nowNanos(),
                    )
                } catch (_: RuntimeException) {
                    failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
                }
            },
            handler,
        )
        preview.setOnImageAvailableListener(
            { source ->
                try {
                    source.acquireLatestImage()?.close()
                } catch (_: RuntimeException) {
                    failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
                }
            },
            handler,
        )
        synchronized(stateLock) {
            lifecycle.runIfActive(runId) {
                if (appContext.checkSelfPermission(Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    throw SecurityException(CAMERA_PERMISSION_UNAVAILABLE_MESSAGE)
                }
                cameraManager.openCamera(
                    cameraId,
                    cameraStateCallback(runId, reader, preview, handler),
                    handler,
                )
            }
        }
    }

    private fun cameraStateCallback(
        runId: Long,
        reader: ImageReader,
        preview: ImageReader,
        handler: Handler,
    ): CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        private val callbackCameraCloser = CallbackResourceCloser<CameraDevice> { it.close() }

        override fun onOpened(camera: CameraDevice) {
            if (!attachCameraDevice(runId, camera)) {
                callbackCameraCloser.close(camera)
                return
            }
            try {
                synchronized(stateLock) {
                    lifecycle.runIfActive(runId) {
                        createWarmupSession(runId, camera, reader, preview, handler)
                    }
                }
            } catch (_: CameraAccessException) {
                completeTerminalCallback(camera, callbackCameraCloser) {
                    failRun(runId, CAMERA_CONFIGURATION_FAILURE_MESSAGE, camera)
                }
            } catch (_: RuntimeException) {
                completeTerminalCallback(camera, callbackCameraCloser) {
                    failRun(runId, CAMERA_CONFIGURATION_FAILURE_MESSAGE, camera)
                }
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            completeTerminalCallback(camera, callbackCameraCloser) {
                failRun(runId, CAMERA_DISCONNECTED_MESSAGE, camera)
            }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            completeTerminalCallback(camera, callbackCameraCloser) {
                failRun(runId, CAMERA_DEVICE_FAILURE_MESSAGE, camera)
            }
        }
    }

    @Throws(CameraAccessException::class)
    private fun selectCameraId(): String {
        val ids = cameraManager.cameraIdList
        check(ids.isNotEmpty()) { "No Camera2 device is available" }
        return ids.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.first()
    }

    @Throws(CameraAccessException::class)
    private fun selectJpegSize(cameraId: String): Size {
        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Camera has no stream configuration map")
        val candidates = map.getOutputSizes(ImageFormat.JPEG).map { PixelDimensions(it.width, it.height) }
        val selected = selectClosestCaptureSize(candidates, PixelDimensions(limits.maxWidth, limits.maxHeight))
            ?: error("Camera has no JPEG output sizes")
        return Size(selected.width, selected.height)
    }

    @Throws(CameraAccessException::class)
    private fun createWarmupSession(
        runId: Long,
        camera: CameraDevice,
        reader: ImageReader,
        preview: ImageReader,
        handler: Handler,
    ) {
        val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }.build()
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!attachOrClose(session, { attachCaptureSession(runId, it) }, { it.close() })) return
                try {
                    session.setRepeatingRequest(previewRequest, null, handler)
                    if (!handler.postDelayed(
                            { finishWarmupAndCreateJpegSession(runId, camera, reader, session, handler) },
                            THREE_A_WARMUP_MILLIS,
                        )
                    ) {
                        failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
                    }
                } catch (_: CameraAccessException) {
                    failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
                } catch (_: RuntimeException) {
                    failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                closeSafely { session.close() }
                failRun(runId, CAMERA_CONFIGURATION_FAILURE_MESSAGE)
            }
        }
        camera.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(preview.surface)),
                Executor { command -> handler.post(command) },
                callback,
            ),
        )
    }

    private fun finishWarmupAndCreateJpegSession(
        runId: Long,
        camera: CameraDevice,
        reader: ImageReader,
        warmupSession: CameraCaptureSession,
        handler: Handler,
    ) {
        if (!detachCaptureSession(runId, warmupSession)) return
        try {
            warmupSession.stopRepeating()
        } catch (_: CameraAccessException) {
            closeSafely { warmupSession.close() }
            failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
            return
        } catch (_: RuntimeException) {
            closeSafely { warmupSession.close() }
            failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
            return
        }
        closeSafely { warmupSession.close() }
        try {
            if (lifecycle.isActive(runId)) {
                createJpegCaptureSession(runId, camera, reader, handler)
            }
        } catch (_: CameraAccessException) {
            failRun(runId, CAMERA_CONFIGURATION_FAILURE_MESSAGE)
        } catch (_: RuntimeException) {
            failRun(runId, CAMERA_CONFIGURATION_FAILURE_MESSAGE)
        }
    }

    @Throws(CameraAccessException::class)
    private fun createJpegCaptureSession(
        runId: Long,
        camera: CameraDevice,
        reader: ImageReader,
        handler: Handler,
    ) {
        val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }
        if (!attachCaptureRequestBuilder(runId, requestBuilder)) return
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!attachOrClose(session, { attachCaptureSession(runId, it) }, { it.close() })) return
                dispatchToListener(runId) {
                    it.onCaptureSessionReady(ElapsedRealtimeClock.nowNanos())
                }
                runCaptureOpportunity(runId, handler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                closeSafely { session.close() }
                failRun(runId, CAMERA_CONFIGURATION_FAILURE_MESSAGE)
            }
        }
        camera.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(reader.surface)),
                Executor { command -> handler.post(command) },
                callback,
            ),
        )
    }

    private fun runCaptureOpportunity(runId: Long, handler: Handler) {
        var ticket: CaptureRequestTicket? = null
        try {
            synchronized(stateLock) {
                if (!lifecycle.isActive(runId)) return
                val session = captureSession ?: return
                val requestBuilder = captureRequestBuilder ?: return
                val requestTimestampNanos = ElapsedRealtimeClock.nowNanos()
                captureCadence.recordOpportunity(requestTimestampNanos)
                ticket = capturePipeline.tryAcquire(runId, requestTimestampNanos)
                ticket?.let {
                    val request = requestBuilder.apply { setTag(it) }.build()
                    session.capture(request, captureCallback(runId), handler)
                }
            }
            publishCapturePipelineSnapshot(runId)
            scheduleNextCaptureOpportunity(runId, handler)
        } catch (_: CameraAccessException) {
            ticket?.let(capturePipeline::recordCaptureFailed)
            publishCapturePipelineSnapshot(runId)
            failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
        } catch (_: RuntimeException) {
            ticket?.let(capturePipeline::recordCaptureFailed)
            publishCapturePipelineSnapshot(runId)
            failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
        }
    }

    private fun captureCallback(runId: Long): CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long,
            ) {
                val ticket = request.tag as? CaptureRequestTicket ?: return
                synchronized(stateLock) {
                    if (!lifecycle.isActive(runId)) return
                    capturePipeline.recordCaptureStarted(ticket, timestamp)
                }
                publishCapturePipelineSnapshot(runId)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) = Unit

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                synchronized(stateLock) {
                    if (!lifecycle.isActive(runId)) return
                    (request.tag as? CaptureRequestTicket)?.let(capturePipeline::recordCaptureFailed)
                }
                publishCapturePipelineSnapshot(runId)
                failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
            }
        }

    private fun scheduleNextCaptureOpportunity(runId: Long, handler: Handler) {
        lateinit var opportunity: Runnable
        opportunity = Runnable {
            val shouldRun = synchronized(stateLock) {
                if (scheduledCaptureOpportunity !== opportunity) {
                    false
                } else {
                    scheduledCaptureOpportunity = null
                    lifecycle.isActive(runId)
                }
            }
            if (shouldRun) runCaptureOpportunity(runId, handler)
        }
        val attached = synchronized(stateLock) {
            if (!lifecycle.isActive(runId) || scheduledCaptureOpportunity != null) {
                false
            } else {
                scheduledCaptureOpportunity = opportunity
                true
            }
        }
        if (!attached) return
        val delayMillis = captureCadence.delayUntilNextOpportunityMillis(ElapsedRealtimeClock.nowNanos())
        if (!handler.postDelayed(opportunity, delayMillis)) {
            synchronized(stateLock) {
                if (scheduledCaptureOpportunity === opportunity) scheduledCaptureOpportunity = null
            }
            failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
            return
        }
        val stillScheduled = synchronized(stateLock) {
            lifecycle.isActive(runId) && scheduledCaptureOpportunity === opportunity
        }
        if (!stillScheduled) {
            handler.removeCallbacks(opportunity)
        }
    }

    private fun rescheduleNextCaptureOpportunity(runId: Long, handler: Handler) {
        val previous = synchronized(stateLock) {
            if (!lifecycle.isActive(runId)) return
            scheduledCaptureOpportunity.also { scheduledCaptureOpportunity = null }
        }
        previous?.let(handler::removeCallbacks)
        scheduleNextCaptureOpportunity(runId, handler)
    }

    private fun publishCapturePipelineSnapshot(runId: Long) {
        val snapshot = capturePipeline.snapshot()
        dispatchToListener(runId) { it.onCapturePipelineSnapshot(snapshot) }
    }

    private fun consumeLatestImage(
        reader: ImageReader,
        runId: Long,
        handler: Handler,
        imageAvailableMonotonicTimestampNanos: Long,
    ) {
        val image = reader.acquireLatestImage() ?: return
        image.use {
            val sensorTimestamp = if (image.timestamp > 0L) image.timestamp else ElapsedRealtimeClock.nowNanos()
            val (association, pipelineSnapshot) = synchronized(stateLock) {
                if (!lifecycle.isActive(runId)) return
                capturePipeline.associateLatestImage(runId, sensorTimestamp) to capturePipeline.snapshot()
            }
            val buffer = image.planes.singleOrNull()?.buffer ?: run {
                dispatchToListener(runId) { it.onCapturePipelineSnapshot(pipelineSnapshot) }
                return
            }
            val size = buffer.remaining()
            if (size <= 0 || size > MAX_SOURCE_JPEG_BYTES) {
                dispatchToListener(runId) { it.onCapturePipelineSnapshot(pipelineSnapshot) }
                return
            }
            val bytes = ByteArray(size)
            buffer.get(bytes)
            val timestamp = sequence.normalizeTimestamp(sensorTimestamp)
            if (listenerFor(runId) == null) return
            val processorStartedMonotonicTimestampNanos = ElapsedRealtimeClock.nowNanos()
            val processed = processor.process(bytes, timestamp) ?: run {
                dispatchToListener(runId) { it.onCapturePipelineSnapshot(pipelineSnapshot) }
                return
            }
            val processorFinishedMonotonicTimestampNanos = ElapsedRealtimeClock.nowNanos()
            val gateEvent = CaptureGateEvent(
                inputDimensions = processed.inputDimensions,
                outputDimensions = processed.dimensions,
                emitted = processed.decision.emit,
                dropReason = processed.decision.reason,
                targetFramesPerSecond = processed.decision.targetFramesPerSecond,
                meanLuma = processed.decision.analysis.meanLuma,
                darkFraction = processed.decision.analysis.darkFraction,
                laplacianVariance = processed.decision.analysis.laplacianVariance,
                motionScore = processed.decision.analysis.motionScore,
            )
            if (!dispatchToListener(runId) { it.onCaptureGate(gateEvent) }) return
            val cadenceChanged = synchronized(stateLock) {
                if (!lifecycle.isActive(runId)) return
                captureCadence.update(processed.decision.targetFramesPerSecond)
            }
            if (cadenceChanged) {
                rescheduleNextCaptureOpportunity(runId, handler)
            }
            val output = processed.jpeg
            val emittedMonotonicTimestampNanos = if (output == null) {
                null
            } else {
                val frameId = sequence.nextId()
                val frame = buildJpegFrame(
                    requestId = "camera-$frameId",
                    sessionId = sessionId,
                    streamId = "camera2-jpeg",
                    frameId = frameId,
                    timestampNanos = timestamp,
                    wallTimeMillis = SystemWallClock.nowMillis(),
                    width = processed.dimensions.width,
                    height = processed.dimensions.height,
                    bytes = output,
                    synthetic = false,
                )
                if (!dispatchToListener(runId) { it.onFrame(frame) }) return
                ElapsedRealtimeClock.nowNanos()
            }
            val listenerFinishedMonotonicTimestampNanos =
                emittedMonotonicTimestampNanos ?: ElapsedRealtimeClock.nowNanos()
            val timingEvent = CaptureTimingEvent(
                analyzedMonotonicTimestampNanos = processorFinishedMonotonicTimestampNanos,
                emittedMonotonicTimestampNanos = emittedMonotonicTimestampNanos,
                requestToImageLatencyNanos = association.requestedAtMonotonicTimestampNanos?.let {
                    (imageAvailableMonotonicTimestampNanos - it).coerceAtLeast(0L)
                },
                imageAcquisitionDurationNanos =
                    (processorStartedMonotonicTimestampNanos - imageAvailableMonotonicTimestampNanos)
                        .coerceAtLeast(0L),
                processorDurationNanos =
                    (processorFinishedMonotonicTimestampNanos - processorStartedMonotonicTimestampNanos)
                        .coerceAtLeast(0L),
                listenerPathDurationNanos =
                    (listenerFinishedMonotonicTimestampNanos - processorFinishedMonotonicTimestampNanos)
                        .coerceAtLeast(0L),
            )
            if (!dispatchToListener(runId) { it.onCaptureTiming(timingEvent) }) return
            dispatchToListener(runId) { it.onCapturePipelineSnapshot(pipelineSnapshot) }
        }
    }

    override fun stop() {
        val resources = synchronized(stateLock) {
            val runId = lifecycle.finishCurrent() ?: return
            teardownInProgress = true
            val terminalSnapshot = checkNotNull(capturePipeline.endRun(runId))
            val target = listener
            deliverTerminalPipelineSnapshot(target, terminalSnapshot)
            listener = null
            detachResourcesLocked()
        }
        completeTeardown(resources)
    }

    private fun failRun(runId: Long, message: String, callbackCamera: CameraDevice? = null): Boolean {
        val stopped = synchronized(stateLock) {
            if (!lifecycle.finish(runId)) return false
            teardownInProgress = true
            val terminalSnapshot = checkNotNull(capturePipeline.endRun(runId))
            val target = listener
            deliverTerminalPipelineSnapshot(target, terminalSnapshot)
            listener = null
            target to detachResourcesLocked(callbackCamera)
        }
        completeTeardown(stopped.second) { stopped.first?.onError(message) }
        return true
    }

    private fun dispatchToListener(
        runId: Long,
        action: (FrameSource.Listener) -> Unit,
    ): Boolean = synchronized(stateLock) {
        val target = listener ?: return@synchronized false
        lifecycle.runIfActive(runId) { action(target) }
    }

    private fun deliverTerminalPipelineSnapshot(
        target: FrameSource.Listener?,
        snapshot: CapturePipelineSnapshot,
    ) {
        try {
            target?.onCapturePipelineSnapshot(snapshot)
        } catch (_: RuntimeException) {
            // Teardown must remain fail-closed even if a diagnostic listener fails.
        }
    }

    private fun completeTeardown(
        resources: CameraResourceCloser,
        afterReset: () -> Unit = {},
    ) {
        try {
            drainCameraCallbacksBeforeReset(resources) {
                processor.reset()
                captureCadence.reset()
            }
            afterReset()
        } finally {
            synchronized(stateLock) { teardownInProgress = false }
        }
    }

    private fun attachImageReaders(
        runId: Long,
        reader: ImageReader,
        preview: ImageReader,
    ): Boolean = synchronized(stateLock) {
        if (!lifecycle.isActive(runId)) return@synchronized false
        imageReader = reader
        previewReader = preview
        true
    }

    private fun attachCameraDevice(runId: Long, camera: CameraDevice): Boolean = synchronized(stateLock) {
        if (!lifecycle.isActive(runId)) return@synchronized false
        cameraDevice = camera
        true
    }

    private fun attachCaptureRequestBuilder(
        runId: Long,
        requestBuilder: CaptureRequest.Builder,
    ): Boolean = synchronized(stateLock) {
        if (!lifecycle.isActive(runId)) return@synchronized false
        captureRequestBuilder = requestBuilder
        true
    }

    private fun attachCaptureSession(runId: Long, session: CameraCaptureSession): Boolean = synchronized(stateLock) {
        if (!lifecycle.isActive(runId)) return@synchronized false
        captureSession = session
        true
    }

    private fun detachCaptureSession(runId: Long, session: CameraCaptureSession): Boolean =
        synchronized(stateLock) {
            if (!lifecycle.isActive(runId) || captureSession !== session) return@synchronized false
            captureSession = null
            true
        }

    private fun listenerFor(runId: Long): FrameSource.Listener? = synchronized(stateLock) {
        listener.takeIf { lifecycle.isActive(runId) }
    }

    private fun detachResourcesLocked(callbackCamera: CameraDevice? = null): CameraResourceCloser {
        val handler = cameraHandler
        val thread = cameraThread
        val preview = previewReader
        val reader = imageReader
        val device = cameraDevice
        val session = captureSession
        val captureOpportunity = scheduledCaptureOpportunity
        cameraHandler = null
        cameraThread = null
        previewReader = null
        imageReader = null
        cameraDevice = null
        captureSession = null
        captureRequestBuilder = null
        scheduledCaptureOpportunity = null
        return CameraResourceCloser(
            listOf(
                { captureOpportunity?.let { handler?.removeCallbacks(it) } },
                { handler?.removeCallbacksAndMessages(null) },
                { session?.close() },
                { if (device !== callbackCamera) device?.close() },
                { preview?.close() },
                { reader?.close() },
                { closeUnownedThread(thread) },
            ),
        )
    }

    private fun closeResources(resources: CameraResourceCloser) = resources.close()
}

internal class AdaptivePhysicalCaptureCadence(
    private val relaxedFramesPerSecond: Double = 3.0,
    private val motionFramesPerSecond: Double = 5.0,
) {
    @Volatile private var currentFramesPerSecond = relaxedFramesPerSecond
    @Volatile private var lastOpportunityNanos = 0L

    init {
        require(relaxedFramesPerSecond.isFinite() && motionFramesPerSecond.isFinite())
        require(relaxedFramesPerSecond in 0.1..motionFramesPerSecond)
        require(motionFramesPerSecond <= 10.0)
    }

    fun update(targetFramesPerSecond: Double): Boolean {
        require(targetFramesPerSecond.isFinite() && targetFramesPerSecond > 0.0)
        val updated = targetFramesPerSecond.coerceIn(relaxedFramesPerSecond, motionFramesPerSecond)
        if (updated == currentFramesPerSecond) return false
        currentFramesPerSecond = updated
        return true
    }

    fun intervalMillis(): Long = kotlin.math.ceil(1_000.0 / currentFramesPerSecond).toLong().coerceAtLeast(1L)

    fun recordOpportunity(timestampNanos: Long) {
        require(timestampNanos > 0L)
        lastOpportunityNanos = timestampNanos
    }

    fun delayUntilNextOpportunityMillis(nowNanos: Long): Long {
        require(nowNanos >= 0L)
        val opportunityNanos = lastOpportunityNanos
        if (opportunityNanos == 0L || nowNanos <= opportunityNanos) return intervalMillis()
        val periodNanos = intervalMillis() * 1_000_000L
        val remainingNanos = (periodNanos - (nowNanos - opportunityNanos)).coerceAtLeast(0L)
        return (remainingNanos + 999_999L) / 1_000_000L
    }

    fun reset() {
        currentFramesPerSecond = relaxedFramesPerSecond
        lastOpportunityNanos = 0L
    }
}

internal data class CaptureRequestTicket(
    val runId: Long,
    val sequence: Long,
    val requestedAtMonotonicTimestampNanos: Long,
)

internal data class CaptureImageAssociation(
    val requestedAtMonotonicTimestampNanos: Long?,
    val exactTimestampMatch: Boolean,
    val supersededRequestCount: Int,
)

/**
 * Caps Camera2/HAL work without retaining missed opportunities. Three slots
 * cover 600 ms at 5 FPS, above the observed 433.8 ms median request latency.
 */
internal class BoundedCaptureRequestPipeline(private val maximumOutstandingRequests: Int = 3) {
    private data class PendingRequest(
        val ticket: CaptureRequestTicket,
        var sensorTimestampNanos: Long? = null,
    )

    private var activeRunId = 0L
    private var nextSequence = 0L
    private val pending = LinkedHashMap<Long, PendingRequest>()
    private var requestsSubmitted = 0L
    private var opportunitiesBackpressured = 0L
    private var requestsSuperseded = 0L
    private var imagesWithoutExactRequestMatch = 0L
    private var captureFailures = 0L
    private var lateCallbacks = 0L
    private var maximumOutstandingObserved = 0

    init {
        require(maximumOutstandingRequests in 1..MAX_CAPTURE_PIPELINE_DEPTH)
    }

    @Synchronized
    fun beginRun(runId: Long) {
        require(runId > 0L)
        activeRunId = runId
        pending.clear()
        requestsSubmitted = 0L
        opportunitiesBackpressured = 0L
        requestsSuperseded = 0L
        imagesWithoutExactRequestMatch = 0L
        captureFailures = 0L
        lateCallbacks = 0L
        maximumOutstandingObserved = 0
    }

    @Synchronized
    fun tryAcquire(runId: Long, requestedAtMonotonicTimestampNanos: Long): CaptureRequestTicket? {
        require(requestedAtMonotonicTimestampNanos > 0L)
        if (runId != activeRunId) return null
        if (pending.size >= maximumOutstandingRequests) {
            opportunitiesBackpressured += 1L
            return null
        }
        check(nextSequence < Long.MAX_VALUE) { "Capture request sequence exhausted" }
        val ticket = CaptureRequestTicket(runId, ++nextSequence, requestedAtMonotonicTimestampNanos)
        pending[ticket.sequence] = PendingRequest(ticket)
        requestsSubmitted += 1L
        maximumOutstandingObserved = maxOf(maximumOutstandingObserved, pending.size)
        return ticket
    }

    @Synchronized
    fun recordCaptureStarted(ticket: CaptureRequestTicket, sensorTimestampNanos: Long): Boolean {
        if (ticket.runId != activeRunId) return false
        val request = pending[ticket.sequence]
        if (request?.ticket != ticket || sensorTimestampNanos <= 0L) {
            lateCallbacks += 1L
            return false
        }
        val existingTimestamp = request.sensorTimestampNanos
        if (existingTimestamp != null) {
            if (existingTimestamp == sensorTimestampNanos) return true
            lateCallbacks += 1L
            return false
        }
        if (pending.values.any { it.sensorTimestampNanos == sensorTimestampNanos }) {
            lateCallbacks += 1L
            return false
        }
        request.sensorTimestampNanos = sensorTimestampNanos
        return true
    }

    @Synchronized
    fun associateLatestImage(runId: Long, sensorTimestampNanos: Long): CaptureImageAssociation {
        require(sensorTimestampNanos > 0L)
        if (runId != activeRunId) {
            return CaptureImageAssociation(null, exactTimestampMatch = false, supersededRequestCount = 0)
        }
        if (pending.isEmpty()) {
            imagesWithoutExactRequestMatch += 1L
            return CaptureImageAssociation(null, exactTimestampMatch = false, supersededRequestCount = 0)
        }
        val exact = pending.values.firstOrNull { it.sensorTimestampNanos == sensorTimestampNanos }
        val selected = exact ?: pending.values
            .filter { timestamped ->
                timestamped.sensorTimestampNanos?.let { it <= sensorTimestampNanos } == true
            }
            .maxByOrNull { it.sensorTimestampNanos ?: Long.MIN_VALUE }
            ?: pending.values.first()
        val retiredSequences = pending.keys.filter { it <= selected.ticket.sequence }
        retiredSequences.forEach(pending::remove)
        val superseded = (retiredSequences.size - 1).coerceAtLeast(0)
        requestsSuperseded += superseded.toLong()
        if (exact == null) imagesWithoutExactRequestMatch += 1L
        return CaptureImageAssociation(
            requestedAtMonotonicTimestampNanos = selected.ticket.requestedAtMonotonicTimestampNanos
                .takeIf { exact != null },
            exactTimestampMatch = exact != null,
            supersededRequestCount = superseded,
        )
    }

    @Synchronized
    fun recordCaptureFailed(ticket: CaptureRequestTicket): Boolean {
        if (ticket.runId != activeRunId) return false
        val removed = pending.remove(ticket.sequence)
        if (removed?.ticket == ticket) {
            captureFailures += 1L
            return true
        }
        lateCallbacks += 1L
        return false
    }

    @Synchronized
    fun endRun(runId: Long): CapturePipelineSnapshot? {
        if (runId != activeRunId) return null
        activeRunId = 0L
        pending.clear()
        return snapshot()
    }

    @Synchronized
    fun snapshot(): CapturePipelineSnapshot = CapturePipelineSnapshot(
        requestsSubmitted = requestsSubmitted,
        opportunitiesBackpressured = opportunitiesBackpressured,
        requestsSuperseded = requestsSuperseded,
        imagesWithoutExactRequestMatch = imagesWithoutExactRequestMatch,
        captureFailures = captureFailures,
        lateCallbacks = lateCallbacks,
        outstandingRequests = pending.size,
        maximumOutstandingRequests = maximumOutstandingObserved,
    )
}

internal class CameraResourceCloser(private var closeActions: List<() -> Unit>) {
    @Synchronized
    fun close() {
        val actions = closeActions
        closeActions = emptyList()
        actions.forEach(::closeSafely)
    }
}

internal fun drainCameraCallbacksBeforeReset(
    resources: CameraResourceCloser,
    resetState: () -> Unit,
) {
    resources.close()
    resetState()
}

internal class CallbackResourceCloser<T>(private val closeAction: (T) -> Unit) {
    private var closed = false

    @Synchronized
    fun close(resource: T) {
        if (closed) return
        closed = true
        closeSafely { closeAction(resource) }
    }
}

internal fun <T> completeTerminalCallback(
    resource: T,
    closer: CallbackResourceCloser<T>,
    transition: () -> Unit,
) {
    try {
        transition()
    } finally {
        closer.close(resource)
    }
}

internal class CameraRunLifecycle {
    private var nextRunId = 0L
    private var activeRunId: Long? = null

    @get:Synchronized
    val isRunning: Boolean get() = activeRunId != null

    @Synchronized
    fun begin(): Long {
        check(activeRunId == null) { "Camera frame source is already running" }
        val runId = ++nextRunId
        activeRunId = runId
        return runId
    }

    @Synchronized
    fun isActive(runId: Long): Boolean = activeRunId == runId

    @Synchronized
    fun runIfActive(runId: Long, action: () -> Unit): Boolean {
        if (activeRunId != runId) return false
        action()
        return true
    }

    @Synchronized
    fun finish(runId: Long): Boolean {
        if (activeRunId != runId) return false
        activeRunId = null
        return true
    }

    @Synchronized
    fun finishCurrent(): Long? {
        val runId = activeRunId ?: return null
        activeRunId = null
        return runId
    }
}

private fun closeSafely(action: () -> Unit): Boolean = try {
    action()
    true
} catch (_: RuntimeException) {
    false
}

internal fun <T> attachOrClose(
    resource: T,
    attach: (T) -> Boolean,
    close: (T) -> Unit,
): Boolean {
    if (attach(resource)) return true
    closeSafely { close(resource) }
    return false
}

private fun closeUnownedThread(thread: HandlerThread?) {
    if (thread == null) return
    closeSafely { thread.quitSafely() }
    if (shouldJoinCameraThread(thread)) {
        try {
            thread.join(1_000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

private const val MAX_SOURCE_JPEG_BYTES = 20 * 1_024 * 1_024
private const val THREE_A_WARMUP_MILLIS = 1_000L
private const val MAX_PREVIEW_PIXELS = 1_280L * 720L
private const val MAX_CAPTURE_PIPELINE_DEPTH = 8

internal fun selectHeadlessPreviewSize(
    candidates: Collection<PixelDimensions>,
    preferred: PixelDimensions = PixelDimensions(640, 480),
): PixelDimensions? {
    val bounded = candidates.filter { it.area <= MAX_PREVIEW_PIXELS }
    bounded.firstOrNull { it == preferred }?.let { return it }
    return bounded.minWithOrNull(
        compareBy<PixelDimensions> { kotlin.math.abs(it.area - preferred.area) }
            .thenBy {
                kotlin.math.abs(
                    it.width.toLong() * preferred.height - preferred.width.toLong() * it.height,
                )
            }
            .thenBy { it.width }
            .thenBy { it.height },
    )
}

internal fun shouldJoinCameraThread(
    cameraThread: Thread?,
    currentThread: Thread = Thread.currentThread(),
): Boolean = cameraThread != null && cameraThread !== currentThread

internal const val CAMERA_PERMISSION_UNAVAILABLE_MESSAGE =
    "Camera permission became unavailable before the camera could open; capture remains stopped"
internal const val CAMERA_START_FAILURE_MESSAGE = "Camera could not start; capture remains stopped"
internal const val CAMERA_OPEN_FAILURE_MESSAGE = "Camera could not be opened; capture remains stopped"
internal const val CAMERA_CONFIGURATION_FAILURE_MESSAGE =
    "Camera capture session could not be configured; capture remains stopped"
internal const val CAMERA_CAPTURE_FAILURE_MESSAGE = "Camera capture failed; capture remains stopped"
internal const val CAMERA_DISCONNECTED_MESSAGE = "Camera disconnected; capture remains stopped"
internal const val CAMERA_DEVICE_FAILURE_MESSAGE = "Camera device failed; capture remains stopped"

internal fun cameraPermissionFailure(cause: SecurityException? = null): IllegalStateException =
    IllegalStateException(CAMERA_PERMISSION_UNAVAILABLE_MESSAGE, cause)
