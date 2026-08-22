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
    private val sessionId = "camera-${UUID.randomUUID()}"
    private var listener: FrameSource.Listener? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var previewReader: ImageReader? = null
    private var imageReader: ImageReader? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureRequest: CaptureRequest? = null

    override val isRunning: Boolean get() = lifecycle.isRunning

    override fun start(listener: FrameSource.Listener) {
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onError(CAMERA_PERMISSION_UNAVAILABLE_MESSAGE)
            return
        }

        val runId = synchronized(stateLock) {
            val id = lifecycle.begin()
            processor.reset()
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
                    consumeLatestImage(source, runId)
                    scheduleNextCapture(runId, handler)
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
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }.build()
        if (!attachCaptureRequest(runId, request)) return
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!attachOrClose(session, { attachCaptureSession(runId, it) }, { it.close() })) return
                captureNext(runId, handler)
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

    private fun captureNext(runId: Long, handler: Handler) {
        val capture = synchronized(stateLock) {
            if (!lifecycle.isActive(runId)) return
            val session = captureSession ?: return
            val request = captureRequest ?: return
            session to request
        }
        try {
            captureCadence.recordCaptureRequest(ElapsedRealtimeClock.nowNanos())
            capture.first.capture(capture.second, object : CameraCaptureSession.CaptureCallback() {
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
                    failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
                }
            }, handler)
        } catch (_: CameraAccessException) {
            failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
        } catch (_: RuntimeException) {
            failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
        }
    }

    private fun scheduleNextCapture(runId: Long, handler: Handler) {
        if (lifecycle.isActive(runId) &&
            !handler.postDelayed(
                { captureNext(runId, handler) },
                captureCadence.delayAfterCompletionMillis(ElapsedRealtimeClock.nowNanos()),
            )
        ) {
            failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
        }
    }

    private fun consumeLatestImage(reader: ImageReader, runId: Long) {
        val image = reader.acquireLatestImage() ?: return
        image.use {
            if (!lifecycle.isActive(runId)) return
            val buffer = image.planes.singleOrNull()?.buffer ?: return
            val size = buffer.remaining()
            if (size <= 0 || size > MAX_SOURCE_JPEG_BYTES) return
            val bytes = ByteArray(size)
            buffer.get(bytes)
            val sensorTimestamp = if (image.timestamp > 0L) image.timestamp else ElapsedRealtimeClock.nowNanos()
            val timestamp = sequence.normalizeTimestamp(sensorTimestamp)
            val target = listenerFor(runId) ?: return
            val processed = processor.process(bytes, timestamp) ?: return
            target.onCaptureGate(
                CaptureGateEvent(
                    inputDimensions = processed.inputDimensions,
                    outputDimensions = processed.dimensions,
                    emitted = processed.decision.emit,
                    dropReason = processed.decision.reason,
                    targetFramesPerSecond = processed.decision.targetFramesPerSecond,
                    meanLuma = processed.decision.analysis.meanLuma,
                    darkFraction = processed.decision.analysis.darkFraction,
                    laplacianVariance = processed.decision.analysis.laplacianVariance,
                    motionScore = processed.decision.analysis.motionScore,
                ),
            )
            captureCadence.update(processed.decision.targetFramesPerSecond)
            val output = processed.jpeg ?: return
            val frameId = sequence.nextId()
            target.onFrame(
                buildJpegFrame(
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
                ),
            )
        }
    }

    override fun stop() {
        val resources = synchronized(stateLock) {
            lifecycle.finishCurrent() ?: return
            listener = null
            detachResourcesLocked()
        }
        processor.reset()
        captureCadence.reset()
        closeResources(resources)
    }

    private fun failRun(runId: Long, message: String, callbackCamera: CameraDevice? = null): Boolean {
        val stopped = synchronized(stateLock) {
            if (!lifecycle.finish(runId)) return false
            val target = listener
            listener = null
            target to detachResourcesLocked(callbackCamera)
        }
        processor.reset()
        captureCadence.reset()
        closeResources(stopped.second)
        stopped.first?.onError(message)
        return true
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

    private fun attachCaptureRequest(runId: Long, request: CaptureRequest): Boolean = synchronized(stateLock) {
        if (!lifecycle.isActive(runId)) return@synchronized false
        captureRequest = request
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
        cameraHandler = null
        cameraThread = null
        previewReader = null
        imageReader = null
        cameraDevice = null
        captureSession = null
        captureRequest = null
        return CameraResourceCloser(
            listOf(
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
    private val relaxedFramesPerSecond: Double = 2.0,
    private val motionFramesPerSecond: Double = 5.0,
) {
    @Volatile private var currentFramesPerSecond = relaxedFramesPerSecond
    @Volatile private var lastCaptureRequestNanos = 0L

    init {
        require(relaxedFramesPerSecond.isFinite() && motionFramesPerSecond.isFinite())
        require(relaxedFramesPerSecond in 0.1..motionFramesPerSecond)
        require(motionFramesPerSecond <= 10.0)
    }

    fun update(targetFramesPerSecond: Double) {
        require(targetFramesPerSecond.isFinite() && targetFramesPerSecond > 0.0)
        currentFramesPerSecond = targetFramesPerSecond.coerceIn(relaxedFramesPerSecond, motionFramesPerSecond)
    }

    fun intervalMillis(): Long = kotlin.math.floor(1_000.0 / currentFramesPerSecond).toLong().coerceAtLeast(1L)

    fun recordCaptureRequest(timestampNanos: Long) {
        require(timestampNanos > 0L)
        lastCaptureRequestNanos = timestampNanos
    }

    fun delayAfterCompletionMillis(nowNanos: Long): Long {
        require(nowNanos >= 0L)
        val requestNanos = lastCaptureRequestNanos
        if (requestNanos == 0L || nowNanos <= requestNanos) return intervalMillis()
        val periodNanos = intervalMillis() * 1_000_000L
        val remainingNanos = (periodNanos - (nowNanos - requestNanos)).coerceAtLeast(0L)
        return (remainingNanos + 999_999L) / 1_000_000L
    }

    fun reset() {
        currentFramesPerSecond = relaxedFramesPerSecond
        lastCaptureRequestNanos = 0L
    }
}

internal class CameraResourceCloser(private var closeActions: List<() -> Unit>) {
    @Synchronized
    fun close() {
        val actions = closeActions
        closeActions = emptyList()
        actions.forEach(::closeSafely)
    }
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
