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
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import org.conceptflow.mpl.rokid.core.ElapsedRealtimeClock
import org.conceptflow.mpl.rokid.core.CaptureGateEvent
import org.conceptflow.mpl.rokid.core.CameraCalibrationCapabilityState
import org.conceptflow.mpl.rokid.core.CameraSourceDiagnostic
import org.conceptflow.mpl.rokid.core.CameraSourceDiagnosticDomain
import org.conceptflow.mpl.rokid.core.CapturePipelineSnapshot
import org.conceptflow.mpl.rokid.core.CaptureTimingEvent
import org.conceptflow.mpl.rokid.core.FrameLimits
import org.conceptflow.mpl.rokid.core.FrameSource
import org.conceptflow.mpl.rokid.core.MonotonicFrameSequence
import org.conceptflow.mpl.rokid.core.PixelDimensions
import org.conceptflow.mpl.rokid.core.SquareAspectFillTransform
import org.conceptflow.mpl.rokid.core.SystemWallClock
import org.conceptflow.mpl.rokid.core.buildI420Frame
import org.conceptflow.mpl.rokid.core.buildRgbFrame
import org.conceptflow.mpl.rokid.core.buildAvcIntraFrame
import org.conceptflow.mpl.v1.CameraIntrinsics
import java.util.UUID
import java.util.concurrent.Executor

class Camera2FrameSource(
    context: Context,
    @Suppress("UNUSED_PARAMETER") limits: FrameLimits = FrameLimits(),
    relaxedFramesPerSecond: Double = 3.0,
    motionFramesPerSecond: Double = 5.0,
    private val sequence: MonotonicFrameSequence = MonotonicFrameSequence(),
    private val outputFormat: CameraTransferPixelFormat = CameraTransferPixelFormat.RGB8,
) : FrameSource {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val stateLock = Any()
    private val lifecycle = CameraRunLifecycle()
    private val processor = AdaptiveYuv420Processor(outputFormat = outputFormat)
    private val captureCadence = AdaptivePhysicalCaptureCadence(
        relaxedFramesPerSecond = relaxedFramesPerSecond,
        motionFramesPerSecond = motionFramesPerSecond,
    )
    private val capturePipeline = BoundedCaptureRequestPipeline()
    private val sessionId = "camera-${UUID.randomUUID()}"
    private var listener: FrameSource.Listener? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var imageProcessingThread: HandlerThread? = null
    private var imageProcessingHandler: Handler? = null
    private var previewDrainThread: HandlerThread? = null
    private var previewDrainHandler: Handler? = null
    private var cameraReaders: CombinedCameraReaders<ImageReader>? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var scheduledCaptureOpportunity: Runnable? = null
    private var teardownInProgress = false
    private var outputPipelineLogged = false
    private var avcEncoder: HardwareAvcIntraFrameEncoder? = null

    override val isRunning: Boolean get() = lifecycle.isRunning

    override fun start(listener: FrameSource.Listener) {
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onError(CAMERA_PERMISSION_UNAVAILABLE_MESSAGE)
            return
        }

        if (outputFormat == CameraTransferPixelFormat.AVC_INTRA) {
            avcEncoder = try {
                HardwareAvcIntraFrameEncoder(640, 640, 5)
            } catch (_: Throwable) {
                listener.onError("Hardware AVC camera transport is unavailable")
                return
            }
        }

        val runId = synchronized(stateLock) {
            check(!teardownInProgress) { "Camera frame source teardown is still in progress" }
            val id = lifecycle.begin()
            processor.reset()
            capturePipeline.beginRun(id)
            outputPipelineLogged = false
            this.listener = listener
            id
        }
        val thread = HandlerThread("bounded-camera-capture")
        val processingThread = HandlerThread("camera-yuv-processing")
        val previewThread = HandlerThread("camera-preview-drain")
        val handlers = try {
            thread.start()
            processingThread.start()
            previewThread.start()
            Triple(
                Handler(thread.looper),
                Handler(processingThread.looper),
                Handler(previewThread.looper),
            )
        } catch (_: RuntimeException) {
            closeUnownedThread(thread)
            closeUnownedThread(processingThread)
            closeUnownedThread(previewThread)
            failRun(runId, CAMERA_START_FAILURE_MESSAGE)
            return
        }
        val handler = handlers.first
        val processingHandler = handlers.second
        val previewHandler = handlers.third

        val attached = synchronized(stateLock) {
            if (!lifecycle.isActive(runId)) {
                false
            } else {
                cameraThread = thread
                cameraHandler = handler
                imageProcessingThread = processingThread
                imageProcessingHandler = processingHandler
                previewDrainThread = previewThread
                previewDrainHandler = previewHandler
                true
            }
        }
        if (!attached) {
            closeUnownedThread(thread)
            closeUnownedThread(processingThread)
            closeUnownedThread(previewThread)
            return
        }

        try {
            openCamera(runId, handler, processingHandler, previewHandler)
        } catch (error: CameraAccessException) {
            val diagnostic = cameraAccessErrorDiagnostic(error.reason)
            failRun(
                runId,
                CAMERA_OPEN_FAILURE_MESSAGE,
                recoverable = diagnostic.recoverable,
                diagnostic = diagnostic,
            )
        } catch (error: SecurityException) {
            failRun(runId, cameraPermissionFailure(error).message ?: CAMERA_PERMISSION_UNAVAILABLE_MESSAGE)
        } catch (_: RuntimeException) {
            failRun(runId, CAMERA_START_FAILURE_MESSAGE)
        }
    }

    @Throws(CameraAccessException::class)
    private fun openCamera(
        runId: Long,
        handler: Handler,
        processingHandler: Handler,
        previewHandler: Handler,
    ) {
        if (!lifecycle.isActive(runId)) return
        val cameraId = selectCameraId()
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val size = selectContinuousYuvSize(characteristics)
        val calibration = prepareCameraCalibration(
            characteristics,
            PixelDimensions(size.width, size.height),
        )
        val powerEfficientFpsRange = selectPowerEfficientAeFpsRange(
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { it.lower..it.upper }
                .orEmpty(),
        )?.let { Range(it.first, it.last) }
        if (!dispatchToListener(runId) { it.onCameraCalibrationCapability(calibration.capability) }) return
        if (!lifecycle.isActive(runId)) return
        val previewSize = selectHeadlessPreviewSize(
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.map { PixelDimensions(it.width, it.height) }
                .orEmpty(),
        ) ?: error("Camera has no bounded YUV preview output size")
        val reader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.YUV_420_888,
            CONTINUOUS_IMAGE_READER_MAX_IMAGES,
        )
        val preview = try {
            ImageReader.newInstance(
                previewSize.width,
                previewSize.height,
                ImageFormat.YUV_420_888,
                WARMUP_IMAGE_READER_MAX_IMAGES,
            )
        } catch (error: RuntimeException) {
            closeSafely { reader.close() }
            throw error
        }
        val readers = CombinedCameraReaders(
            preview = preview,
            scheduledCapture = reader,
        )
        if (!attachImageReaders(runId, readers)) {
            readers.close(ImageReader::close)
            return
        }
        readers.scheduledCapture.setOnImageAvailableListener(
            { source ->
                try {
                    consumeLatestImage(
                        source,
                        runId,
                        handler,
                        imageAvailableMonotonicTimestampNanos = ElapsedRealtimeClock.nowNanos(),
                        calibrationMetadata = calibration.metadata,
                    )
                } catch (_: RuntimeException) {
                    failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
                }
            },
            processingHandler,
        )
        readers.preview.setOnImageAvailableListener(
            { source ->
                try {
                    source.acquireLatestImage()?.close()
                } catch (_: RuntimeException) {
                    failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
                }
            },
            previewHandler,
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
                    cameraStateCallback(
                        runId,
                        readers,
                        handler,
                        calibration.captureContract,
                        powerEfficientFpsRange,
                    ),
                    handler,
                )
            }
        }
    }

    private fun cameraStateCallback(
        runId: Long,
        readers: CombinedCameraReaders<ImageReader>,
        handler: Handler,
        captureContract: CameraCalibrationCaptureContract?,
        powerEfficientFpsRange: Range<Int>?,
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
                        createCombinedCaptureSession(
                            runId,
                            camera,
                            readers,
                            handler,
                            captureContract,
                            powerEfficientFpsRange,
                        )
                    }
                }
            } catch (error: CameraAccessException) {
                val diagnostic = cameraAccessErrorDiagnostic(error.reason)
                completeTerminalCallback(camera, callbackCameraCloser) {
                    failRun(
                        runId,
                        CAMERA_CONFIGURATION_FAILURE_MESSAGE,
                        camera,
                        recoverable = diagnostic.recoverable,
                        diagnostic = diagnostic,
                    )
                }
            } catch (_: RuntimeException) {
                completeTerminalCallback(camera, callbackCameraCloser) {
                    failRun(runId, CAMERA_CONFIGURATION_FAILURE_MESSAGE, camera)
                }
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            completeTerminalCallback(camera, callbackCameraCloser) {
                failRun(
                    runId,
                    CAMERA_DISCONNECTED_MESSAGE,
                    camera,
                    recoverable = true,
                    diagnostic = cameraDisconnectedDiagnostic(),
                )
            }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            val diagnostic = cameraDeviceErrorDiagnostic(error)
            completeTerminalCallback(camera, callbackCameraCloser) {
                failRun(
                    runId,
                    CAMERA_DEVICE_FAILURE_MESSAGE,
                    camera,
                    recoverable = diagnostic.recoverable,
                    diagnostic = diagnostic,
                )
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
    private fun selectContinuousYuvSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Camera has no stream configuration map")
        val candidates = map.getOutputSizes(ImageFormat.YUV_420_888).map { PixelDimensions(it.width, it.height) }
        val selected = selectContinuousYuvSize(candidates)
            ?: error("Camera has no exact 648x648 YUV_420_888 output size")
        return Size(selected.width, selected.height)
    }

    @Throws(CameraAccessException::class)
    private fun createCombinedCaptureSession(
        runId: Long,
        camera: CameraDevice,
        readers: CombinedCameraReaders<ImageReader>,
        handler: Handler,
        captureContract: CameraCalibrationCaptureContract?,
        powerEfficientFpsRange: Range<Int>?,
    ) {
        val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(readers.preview.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            powerEfficientFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }.build()
        val scheduledCaptureRequestBuilder = buildScheduledCaptureRequest(
            camera,
            readers.scheduledCapture,
            captureContract,
            powerEfficientFpsRange,
        )
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!attachOrClose(session, { attachCaptureSession(runId, it) }, { it.close() })) return
                if (!attachCaptureRequestBuilder(runId, scheduledCaptureRequestBuilder)) return
                Log.i(
                    CAMERA_LOG_TAG,
                    "camera_stream_config sensor_fps=${powerEfficientFpsRange?.lower ?: -1}-" +
                        "${powerEfficientFpsRange?.upper ?: -1} output=${outputFormat.name.lowercase()} " +
                        "width=640 height=640",
                )
                try {
                    // Keep a low-resolution repeating target active for the complete session so
                    // the vendor HAL does not tear streaming down between scheduled captures.
                    session.setRepeatingRequest(previewRequest, null, handler)
                    if (!handler.postDelayed(
                            {
                                finishWarmupAndStartScheduledCaptures(
                                    runId,
                                    session,
                                    handler,
                                )
                            },
                            THREE_A_WARMUP_MILLIS,
                        )
                    ) {
                        failRun(runId, CAMERA_CAPTURE_FAILURE_MESSAGE)
                    }
                } catch (error: CameraAccessException) {
                    val diagnostic = cameraAccessErrorDiagnostic(error.reason)
                    failRun(
                        runId,
                        CAMERA_CAPTURE_FAILURE_MESSAGE,
                        recoverable = diagnostic.recoverable,
                        diagnostic = diagnostic,
                    )
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
                readers.sessionOutputs().map { OutputConfiguration(it.surface) },
                Executor { command -> handler.post(command) },
                callback,
            ),
        )
    }

    private fun finishWarmupAndStartScheduledCaptures(
        runId: Long,
        session: CameraCaptureSession,
        handler: Handler,
    ) {
        val ready = synchronized(stateLock) {
            lifecycle.isActive(runId) && captureSession === session && captureRequestBuilder != null
        }
        if (!ready) return
        if (!dispatchToListener(runId) { it.onCaptureSessionReady(ElapsedRealtimeClock.nowNanos()) }) return
        runCaptureOpportunity(runId, handler)
    }

    @Throws(CameraAccessException::class)
    private fun buildScheduledCaptureRequest(
        camera: CameraDevice,
        reader: ImageReader,
        captureContract: CameraCalibrationCaptureContract?,
        powerEfficientFpsRange: Range<Int>?,
    ): CaptureRequest.Builder =
        camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            powerEfficientFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (captureContract?.requestDistortionCorrectionOff == true) {
                set(
                    CaptureRequest.DISTORTION_CORRECTION_MODE,
                    CaptureRequest.DISTORTION_CORRECTION_MODE_OFF,
                )
            }
            if (captureContract?.requestCropRegion == true) {
                val crop = captureContract.cropRegion
                set(
                    CaptureRequest.SCALER_CROP_REGION,
                    android.graphics.Rect(
                        crop.left.toInt(),
                        crop.top.toInt(),
                        (crop.left + crop.width).toInt(),
                        (crop.top + crop.height).toInt(),
                    ),
                )
            }
            if (captureContract?.requestFocalLength == true) {
                set(CaptureRequest.LENS_FOCAL_LENGTH, captureContract.focalLengthMillimeters!!.toFloat())
            }
            if (Build.VERSION.SDK_INT >= 30 && captureContract?.requestUnitZoom == true) {
                set(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f)
            }
            if (Build.VERSION.SDK_INT >= 31 && captureContract?.requestRotateAndCropNone == true) {
                set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
            }
            if (captureContract?.requestVideoStabilizationOff == true) {
                set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                )
            }
            if (captureContract?.requestOpticalStabilizationOff == true) {
                set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                )
            }
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
                    // Exactly one 648x648 request is submitted for this timer opportunity. The
                    // preview keepalive is independent; a full scheduled pipeline is counted as
                    // backpressure and is not replayed later.
                    session.capture(request, captureCallback(runId), handler)
                }
            }
            publishCapturePipelineSnapshot(runId)
            scheduleNextCaptureOpportunity(runId, handler)
        } catch (error: CameraAccessException) {
            ticket?.let(capturePipeline::recordCaptureFailed)
            publishCapturePipelineSnapshot(runId)
            val diagnostic = cameraAccessErrorDiagnostic(error.reason)
            failRun(
                runId,
                CAMERA_CAPTURE_FAILURE_MESSAGE,
                recoverable = diagnostic.recoverable,
                diagnostic = diagnostic,
            )
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
            ) {
                val ticket = request.tag as? CaptureRequestTicket ?: return
                val crop = result.get(CaptureResult.SCALER_CROP_REGION)?.let {
                    CameraCalibrationCrop(
                        left = it.left.toDouble(),
                        top = it.top.toDouble(),
                        width = it.width().toDouble(),
                        height = it.height().toDouble(),
                    )
                }
                val focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH)?.toDouble()
                val unitZoom = if (Build.VERSION.SDK_INT >= 30) {
                    result.get(CaptureResult.CONTROL_ZOOM_RATIO)?.let { it == 1.0f }
                } else {
                    null
                }
                val rotateAndCropNone = if (Build.VERSION.SDK_INT >= 31) {
                    result.get(CaptureResult.SCALER_ROTATE_AND_CROP)?.let {
                        it == CaptureResult.SCALER_ROTATE_AND_CROP_NONE
                    }
                } else {
                    null
                }
                val distortionCorrectionOff = result
                    .get(CaptureResult.DISTORTION_CORRECTION_MODE)
                    ?.let { it == CaptureResult.DISTORTION_CORRECTION_MODE_OFF }
                val videoStabilizationOff = result
                    .get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                    ?.let { it == CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_OFF }
                val opticalStabilizationOff = result
                    .get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                    ?.let { it == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_OFF }
                val sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                synchronized(stateLock) {
                    if (!lifecycle.isActive(runId)) return
                    capturePipeline.recordCaptureCompleted(
                        ticket,
                        sensorTimestamp,
                        CaptureResultCalibrationMetadata(
                            crop,
                            focalLength,
                            unitZoom,
                            rotateAndCropNone,
                            distortionCorrectionOff,
                            videoStabilizationOff,
                            opticalStabilizationOff,
                        ),
                    )
                }
            }

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
                failRun(
                    runId,
                    CAMERA_CAPTURE_FAILURE_MESSAGE,
                    recoverable = true,
                    diagnostic = captureFailureDiagnostic(failure.reason),
                )
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
        calibrationMetadata: Camera2CalibrationMetadata?,
    ) {
        val image = reader.acquireLatestImage() ?: return
        val acquired = processAndCloseCameraImage(image) { borrowedImage ->
            val sensorTimestamp = if (borrowedImage.timestamp > 0L) {
                borrowedImage.timestamp
            } else {
                ElapsedRealtimeClock.nowNanos()
            }
            val (association, pipelineSnapshot) = synchronized(stateLock) {
                if (!lifecycle.isActive(runId)) return@processAndCloseCameraImage null
                capturePipeline.associateLatestImage(runId, sensorTimestamp) to capturePipeline.snapshot()
            }
            val timestamp = sequence.normalizeTimestamp(sensorTimestamp)
            if (listenerFor(runId) == null) return@processAndCloseCameraImage null
            val processorStartedMonotonicTimestampNanos = ElapsedRealtimeClock.nowNanos()
            val processed = processor.process(borrowYuv420Frame(borrowedImage), timestamp)
            ProcessedCameraImage(
                timestamp = timestamp,
                association = association,
                pipelineSnapshot = pipelineSnapshot,
                processorStartedMonotonicTimestampNanos = processorStartedMonotonicTimestampNanos,
                processorFinishedMonotonicTimestampNanos = ElapsedRealtimeClock.nowNanos(),
                processed = processed,
            )
        } ?: return
        val processed = acquired.processed
        val gateEvent = CaptureGateEvent(
            inputDimensions = processed.inputDimensions,
            outputDimensions = processed.outputDimensions,
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
        val output = processed.rgb8 ?: processed.i420
        val emittedMonotonicTimestampNanos = if (output == null) {
            null
        } else {
            if (!outputPipelineLogged) {
                outputPipelineLogged = true
                Log.i(
                    CAMERA_LOG_TAG,
                    "camera_frame_pipeline output=${outputFormat.name.lowercase()} " +
                        "native_conversion=${
                            processed.i420NativeConversion
                                ?: (processed.rgbConversionBackend == RgbConversionBackend.NATIVE_INTEGER)
                        }",
                )
            }
            val transform = SquareAspectFillTransform.centered(
                processed.inputDimensions.width,
                processed.inputDimensions.height,
                processed.outputDimensions.width,
            )
            val frameId = sequence.nextId()
            val intrinsics = calibrationMetadata?.let {
                resolveCameraIntrinsicsForCapture(
                    it,
                    acquired.association.calibrationMetadata,
                    processed.inputDimensions,
                )?.let { captured -> transformCameraIntrinsicsForSquareOutput(captured, transform) }
            }
            val frame = if (processed.i420 != null && outputFormat == CameraTransferPixelFormat.AVC_INTRA) {
                val encoded = checkNotNull(avcEncoder).encode(output, acquired.timestamp)
                buildAvcIntraFrame(
                    requestId = "camera-$frameId",
                    sessionId = sessionId,
                    streamId = "camera2-avc-annex-b-intra",
                    frameId = frameId,
                    timestampNanos = acquired.timestamp,
                    wallTimeMillis = SystemWallClock.nowMillis(),
                    width = processed.outputDimensions.width,
                    height = processed.outputDimensions.height,
                    bytes = encoded,
                    synthetic = false,
                    takeOwnership = true,
                    intrinsics = intrinsics,
                )
            } else if (processed.i420 != null) {
                buildI420Frame(
                    requestId = "camera-$frameId",
                    sessionId = sessionId,
                    streamId = "camera2-yuv-i420",
                    frameId = frameId,
                    timestampNanos = acquired.timestamp,
                    wallTimeMillis = SystemWallClock.nowMillis(),
                    width = processed.outputDimensions.width,
                    height = processed.outputDimensions.height,
                    bytes = output,
                    synthetic = false,
                    takeOwnership = true,
                    intrinsics = intrinsics,
                )
            } else {
                buildRgbFrame(
                    requestId = "camera-$frameId",
                    sessionId = sessionId,
                    streamId = "camera2-yuv-rgb8",
                    frameId = frameId,
                    timestampNanos = acquired.timestamp,
                    wallTimeMillis = SystemWallClock.nowMillis(),
                    width = processed.outputDimensions.width,
                    height = processed.outputDimensions.height,
                    bytes = output,
                    synthetic = false,
                    takeOwnership = true,
                    intrinsics = intrinsics,
                )
            }
            if (!dispatchToListener(runId) { it.onFrame(frame) }) return
            ElapsedRealtimeClock.nowNanos()
        }
        val listenerFinishedMonotonicTimestampNanos =
            emittedMonotonicTimestampNanos ?: ElapsedRealtimeClock.nowNanos()
        val timingEvent = CaptureTimingEvent(
            analyzedMonotonicTimestampNanos = acquired.processorFinishedMonotonicTimestampNanos,
            emittedMonotonicTimestampNanos = emittedMonotonicTimestampNanos,
            requestToImageLatencyNanos = acquired.association.requestedAtMonotonicTimestampNanos?.let {
                (imageAvailableMonotonicTimestampNanos - it).coerceAtLeast(0L)
            },
            imageAcquisitionDurationNanos =
                (
                    acquired.processorStartedMonotonicTimestampNanos -
                        imageAvailableMonotonicTimestampNanos
                    )
                    .coerceAtLeast(0L),
            processorDurationNanos =
                (
                    acquired.processorFinishedMonotonicTimestampNanos -
                        acquired.processorStartedMonotonicTimestampNanos
                    )
                    .coerceAtLeast(0L),
            nativeRgbConversion = processed.rgbConversionBackend?.let {
                it == RgbConversionBackend.NATIVE_INTEGER
            },
            listenerPathDurationNanos =
                (
                    listenerFinishedMonotonicTimestampNanos -
                        acquired.processorFinishedMonotonicTimestampNanos
                    )
                    .coerceAtLeast(0L),
        )
        if (!dispatchToListener(runId) { it.onCaptureTiming(timingEvent) }) return
        dispatchToListener(runId) { it.onCapturePipelineSnapshot(acquired.pipelineSnapshot) }
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

    private fun failRun(
        runId: Long,
        message: String,
        callbackCamera: CameraDevice? = null,
        recoverable: Boolean = false,
        diagnostic: CameraSourceDiagnostic? = null,
    ): Boolean {
        val stopped = synchronized(stateLock) {
            if (!lifecycle.finish(runId)) return false
            teardownInProgress = true
            val terminalSnapshot = checkNotNull(capturePipeline.endRun(runId))
            val target = listener
            deliverTerminalPipelineSnapshot(target, terminalSnapshot)
            listener = null
            target to detachResourcesLocked(callbackCamera)
        }
        completeTeardown(stopped.second) {
            if (recoverable) {
                if (diagnostic == null) {
                    stopped.first?.onRecoverableError(CAMERA_RESTART_REQUESTED_MESSAGE)
                } else {
                    stopped.first?.onRecoverableError(CAMERA_RESTART_REQUESTED_MESSAGE, diagnostic)
                }
            } else {
                if (diagnostic == null) {
                    stopped.first?.onError(message)
                } else {
                    stopped.first?.onError(message, diagnostic)
                }
            }
        }
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
        readers: CombinedCameraReaders<ImageReader>,
    ): Boolean = synchronized(stateLock) {
        if (!lifecycle.isActive(runId)) return@synchronized false
        check(cameraReaders == null) { "Camera readers are already attached" }
        cameraReaders = readers
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

    private fun listenerFor(runId: Long): FrameSource.Listener? = synchronized(stateLock) {
        listener.takeIf { lifecycle.isActive(runId) }
    }

    private fun detachResourcesLocked(callbackCamera: CameraDevice? = null): CameraResourceCloser {
        val handler = cameraHandler
        val thread = cameraThread
        val processingHandler = imageProcessingHandler
        val processingThread = imageProcessingThread
        val previewHandler = previewDrainHandler
        val previewThread = previewDrainThread
        val readers = cameraReaders
        val device = cameraDevice
        val session = captureSession
        val captureOpportunity = scheduledCaptureOpportunity
        val encoder = avcEncoder
        cameraHandler = null
        cameraThread = null
        imageProcessingHandler = null
        imageProcessingThread = null
        previewDrainHandler = null
        previewDrainThread = null
        cameraReaders = null
        cameraDevice = null
        captureSession = null
        captureRequestBuilder = null
        scheduledCaptureOpportunity = null
        avcEncoder = null
        return CameraResourceCloser(
            listOf(
                { captureOpportunity?.let { handler?.removeCallbacks(it) } },
                { handler?.removeCallbacksAndMessages(null) },
                { processingHandler?.removeCallbacksAndMessages(null) },
                { previewHandler?.removeCallbacksAndMessages(null) },
                { session?.close() },
                { if (device !== callbackCamera) device?.close() },
                { readers?.close(ImageReader::close) },
                { closeUnownedThread(thread) },
                { closeUnownedThread(processingThread) },
                { closeUnownedThread(previewThread) },
                { encoder?.close() },
            ),
        )
    }

    private fun closeResources(resources: CameraResourceCloser) = resources.close()
}

private data class PreparedCameraCalibration(
    val metadata: Camera2CalibrationMetadata?,
    val capability: CameraCalibrationCapabilityState,
    val captureContract: CameraCalibrationCaptureContract?,
)

private data class ProcessedCameraImage(
    val timestamp: Long,
    val association: CaptureImageAssociation,
    val pipelineSnapshot: CapturePipelineSnapshot,
    val processorStartedMonotonicTimestampNanos: Long,
    val processorFinishedMonotonicTimestampNanos: Long,
    val processed: ProcessedYuvCameraFrame,
)

private fun prepareCameraCalibration(
    characteristics: CameraCharacteristics,
    output: PixelDimensions,
): PreparedCameraCalibration {
    val intrinsicCalibration = characteristics
        .get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        ?.map(Float::toDouble)
        .orEmpty()
    val preCorrectionActiveArray = characteristics
        .get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        ?: return PreparedCameraCalibration(
            null,
            CameraCalibrationCapabilityState.UNAVAILABLE,
            captureContract = null,
        )
    val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        ?: return PreparedCameraCalibration(
            null,
            CameraCalibrationCapabilityState.UNAVAILABLE,
            captureContract = null,
        )
    val requestKeys = characteristics.availableCaptureRequestKeys.orEmpty()
    val resultKeys = characteristics.availableCaptureResultKeys.orEmpty()
    val distortionCorrectionOffSupported = characteristics
        .get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
        ?.contains(CameraCharacteristics.DISTORTION_CORRECTION_MODE_OFF) == true
    val requestDistortionCorrectionOff = distortionCorrectionOffSupported &&
        requestKeys.contains(CaptureRequest.DISTORTION_CORRECTION_MODE)
    if (!requestDistortionCorrectionOff &&
        activeArray != preCorrectionActiveArray
    ) {
        return PreparedCameraCalibration(
            null,
            CameraCalibrationCapabilityState.REJECTED,
            captureContract = null,
        )
    }
    val pixelArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        ?.map(Float::toDouble)
        ?.distinct()
        .orEmpty()
    val distortionCoefficients = characteristics
        .get(CameraCharacteristics.LENS_DISTORTION)
        ?.map(Float::toDouble)
    val physicalCandidate = if (pixelArray != null && physicalSize != null && focalLengths.size == 1) {
        CameraPhysicalIntrinsicsMetadata(
            focalLengthMillimeters = focalLengths.single(),
            sensorPhysicalWidthMillimeters = physicalSize.width.toDouble(),
            sensorPhysicalHeightMillimeters = physicalSize.height.toDouble(),
            pixelArrayWidth = pixelArray.width.toDouble(),
            pixelArrayHeight = pixelArray.height.toDouble(),
            evidence = CameraPhysicalIntrinsicsEvidence.ROKID_CAMERA2_METADATA_FINGERPRINT,
        )
    } else {
        null
    }
    val coordinates = CameraCalibrationCoordinateSpace(
        width = preCorrectionActiveArray.width().toDouble(),
        height = preCorrectionActiveArray.height().toDouble(),
    )
    val physicalFallback = physicalCandidate?.takeIf {
        characteristics.physicalCameraIds.isEmpty() && activeArray == preCorrectionActiveArray &&
            matchesRokidCamera2PhysicalDerivationFingerprint(
                intrinsicCalibration,
                distortionCoefficients,
                coordinates,
                preCorrectionActiveArray.left.toDouble(),
                preCorrectionActiveArray.top.toDouble(),
                it,
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION),
                characteristics.get(CameraCharacteristics.SCALER_CROPPING_TYPE) ==
                    CameraCharacteristics.SCALER_CROPPING_TYPE_CENTER_ONLY,
            )
    }
    val cameraPose = camera2PoseMetadata(characteristics)
    val expectedCrop = CameraCalibrationCrop(
        preCorrectionActiveArray.left.toDouble(),
        preCorrectionActiveArray.top.toDouble(),
        preCorrectionActiveArray.width().toDouble(),
        preCorrectionActiveArray.height().toDouble(),
    )
    val requestCrop = requestKeys.contains(CaptureRequest.SCALER_CROP_REGION)
    val requestFocalLength = focalLengths.size == 1 && requestKeys.contains(CaptureRequest.LENS_FOCAL_LENGTH)
    val requestUnitZoom = Build.VERSION.SDK_INT >= 30 &&
        characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.contains(1.0f) == true &&
        requestKeys.contains(CaptureRequest.CONTROL_ZOOM_RATIO)
    val requestRotateAndCropNone = Build.VERSION.SDK_INT >= 31 &&
        characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
            ?.contains(CameraCharacteristics.SCALER_ROTATE_AND_CROP_NONE) == true &&
        requestKeys.contains(CaptureRequest.SCALER_ROTATE_AND_CROP)
    val requestVideoStabilizationOff = characteristics
        .get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        ?.contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_OFF) == true &&
        requestKeys.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE)
    val requestOpticalStabilizationOff = characteristics
        .get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        ?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF) == true &&
        requestKeys.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)
    val captureContract = CameraCalibrationCaptureContract(
        cropRegion = expectedCrop,
        focalLengthMillimeters = focalLengths.singleOrNull(),
        requestCropRegion = requestCrop,
        verifyCropRegion = resultKeys.contains(CaptureResult.SCALER_CROP_REGION),
        requestFocalLength = requestFocalLength,
        verifyFocalLength = focalLengths.size == 1 && resultKeys.contains(CaptureResult.LENS_FOCAL_LENGTH),
        requestUnitZoom = requestUnitZoom,
        verifyUnitZoom = Build.VERSION.SDK_INT >= 30 &&
            resultKeys.contains(CaptureResult.CONTROL_ZOOM_RATIO),
        requestRotateAndCropNone = requestRotateAndCropNone,
        verifyRotateAndCropNone = Build.VERSION.SDK_INT >= 31 &&
            resultKeys.contains(CaptureResult.SCALER_ROTATE_AND_CROP),
        requestDistortionCorrectionOff = requestDistortionCorrectionOff,
        verifyDistortionCorrectionOff = resultKeys.contains(CaptureResult.DISTORTION_CORRECTION_MODE),
        requestVideoStabilizationOff = requestVideoStabilizationOff,
        verifyVideoStabilizationOff = resultKeys.contains(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),
        requestOpticalStabilizationOff = requestOpticalStabilizationOff,
        verifyOpticalStabilizationOff = resultKeys.contains(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE),
    )
    val metadata = Camera2CalibrationMetadata(
        intrinsicCalibration = intrinsicCalibration,
        distortionCoefficients = distortionCoefficients,
        coordinateSpace = coordinates,
        coordinateSpaceLeftInPixelArray = preCorrectionActiveArray.left.toDouble(),
        coordinateSpaceTopInPixelArray = preCorrectionActiveArray.top.toDouble(),
        physicalFallback = physicalFallback,
        captureContract = captureContract,
        pose = cameraPose,
    )
    return when (val scaled = scaleCameraCalibration(metadata, output)) {
        is CameraCalibrationScaleResult.Accepted -> {
            val sanitized = metadata.copy(
                distortionCoefficients = scaled.calibration.distortionCoefficients,
            )
            val capability = if (
                scaled.calibration.provenance ==
                org.conceptflow.mpl.v1.CameraIntrinsicsProvenance.CAMERA_INTRINSICS_PROVENANCE_DERIVED
            ) {
                CameraCalibrationCapabilityState.DERIVED_UNQUANTIFIED
            } else if (scaled.calibration.distortionCoefficients.isEmpty()) {
                CameraCalibrationCapabilityState.VERIFIED_WITHOUT_DISTORTION
            } else {
                CameraCalibrationCapabilityState.VERIFIED_WITH_DISTORTION
            }
            PreparedCameraCalibration(sanitized, capability, captureContract)
        }
        is CameraCalibrationScaleResult.Rejected ->
            PreparedCameraCalibration(
                null,
                CameraCalibrationCapabilityState.REJECTED,
                captureContract = null,
            )
    }
}

private fun camera2PoseMetadata(characteristics: CameraCharacteristics): Camera2PoseMetadata? {
    val rotation = characteristics.get(CameraCharacteristics.LENS_POSE_ROTATION)
        ?.map(Float::toDouble)
        ?: return null
    val translation = characteristics.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
        ?.map(Float::toDouble)
        .orEmpty()
    val reference = when (characteristics.get(CameraCharacteristics.LENS_POSE_REFERENCE)) {
        CameraCharacteristics.LENS_POSE_REFERENCE_PRIMARY_CAMERA -> Camera2PoseReference.PRIMARY_CAMERA
        CameraCharacteristics.LENS_POSE_REFERENCE_GYROSCOPE -> Camera2PoseReference.GYROSCOPE
        CameraCharacteristics.LENS_POSE_REFERENCE_UNDEFINED -> Camera2PoseReference.UNDEFINED
        else -> Camera2PoseReference.AUTOMOTIVE
    }
    return Camera2PoseMetadata(rotation, translation, reference)
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
    val calibrationMetadata: CaptureResultCalibrationMetadata? = null,
)

internal data class CaptureResultCalibrationMetadata(
    val cropRegion: CameraCalibrationCrop?,
    val focalLengthMillimeters: Double?,
    val unitZoom: Boolean? = null,
    val rotateAndCropNone: Boolean? = null,
    val distortionCorrectionOff: Boolean? = null,
    val videoStabilizationOff: Boolean? = null,
    val opticalStabilizationOff: Boolean? = null,
)

internal fun resolveCameraIntrinsicsForCapture(
    metadata: Camera2CalibrationMetadata,
    captureResult: CaptureResultCalibrationMetadata?,
    output: PixelDimensions,
): CameraIntrinsics? {
    if (captureResult == null) {
        // Preserve availability when callbacks are reordered: this is the
        // documented, unquantified static centered-crop derivation.
        return metadata.toProtocolCameraIntrinsics(output)
    }
    val contract = metadata.captureContract ?: return metadata.toProtocolCameraIntrinsics(output)
    if (!captureResult.satisfies(contract)) return null
    return metadata.toProtocolCameraIntrinsics(
        output,
        captureResult.cropRegion.takeIf { contract.verifyCropRegion },
        captureResult.focalLengthMillimeters.takeIf { contract.verifyFocalLength },
    )
}

private fun CaptureResultCalibrationMetadata.satisfies(
    contract: CameraCalibrationCaptureContract,
): Boolean {
    if (contract.verifyCropRegion && cropRegion != contract.cropRegion) return false
    if (contract.verifyFocalLength && (
            focalLengthMillimeters == null || contract.focalLengthMillimeters == null ||
                kotlin.math.abs(focalLengthMillimeters - contract.focalLengthMillimeters) >
                RESULT_FOCAL_LENGTH_TOLERANCE_MM
            )
    ) return false
    if (contract.verifyUnitZoom && unitZoom != true) return false
    if (contract.verifyRotateAndCropNone && rotateAndCropNone != true) return false
    if (contract.verifyDistortionCorrectionOff && distortionCorrectionOff != true) return false
    if (contract.verifyVideoStabilizationOff && videoStabilizationOff != true) return false
    if (contract.verifyOpticalStabilizationOff && opticalStabilizationOff != true) return false
    return true
}

/**
 * Caps Camera2/HAL work without retaining missed opportunities. Three slots
 * cover 600 ms at 5 FPS, above the observed 433.8 ms median request latency.
 */
internal class BoundedCaptureRequestPipeline(private val maximumOutstandingRequests: Int = 3) {
    private data class PendingRequest(
        val ticket: CaptureRequestTicket,
        var sensorTimestampNanos: Long? = null,
        var calibrationMetadata: CaptureResultCalibrationMetadata? = null,
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

    /** Stores result metadata only when Camera2's result timestamp identifies this exact request. */
    @Synchronized
    fun recordCaptureCompleted(
        ticket: CaptureRequestTicket,
        sensorTimestampNanos: Long,
        calibrationMetadata: CaptureResultCalibrationMetadata,
    ): Boolean {
        if (ticket.runId != activeRunId) return false
        val request = pending[ticket.sequence]
        if (request?.ticket != ticket || request.sensorTimestampNanos != sensorTimestampNanos) {
            lateCallbacks += 1L
            return false
        }
        request.calibrationMetadata = calibrationMetadata
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
            calibrationMetadata = exact?.calibrationMetadata,
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

/** The preview keepalive and scheduled capture readers share one session and one lifetime. */
internal class CombinedCameraReaders<T : Any>(
    val preview: T,
    val scheduledCapture: T,
) {
    private var closed = false

    init {
        require(preview !== scheduledCapture) { "Camera outputs must use distinct readers" }
    }

    fun sessionOutputs(): List<T> = listOf(preview, scheduledCapture)

    @Synchronized
    fun close(closeAction: (T) -> Unit) {
        if (closed) return
        closed = true
        sessionOutputs().forEach { resource -> closeSafely { closeAction(resource) } }
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
    // Camera2 may post onClosed while closing. Close before teardown quits its callback looper.
    closer.close(resource)
    transition()
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
    joinCameraThreadBounded(thread)
}

private const val THREE_A_WARMUP_MILLIS = 1_000L
private const val MAX_PREVIEW_PIXELS = 1_280L * 720L
private const val MAX_CAPTURE_PIPELINE_DEPTH = 8
private const val RESULT_FOCAL_LENGTH_TOLERANCE_MM = 0.001
internal const val CONTINUOUS_IMAGE_READER_MAX_IMAGES = 2
private const val WARMUP_IMAGE_READER_MAX_IMAGES = 2

// This is deliberately independent of FrameLimits: those bounds remain available to legacy
// diagnostics and a future exclusive high-resolution mode, while continuous capture stays square.
internal val CONTINUOUS_YUV_CAPTURE_SIZE = PixelDimensions(648, 648)

internal fun selectContinuousYuvSize(
    candidates: Collection<PixelDimensions>,
    required: PixelDimensions = CONTINUOUS_YUV_CAPTURE_SIZE,
): PixelDimensions? = candidates.firstOrNull { it == required }

private fun borrowYuv420Frame(image: Image): Yuv420Frame {
    require(image.format == ImageFormat.YUV_420_888)
    val dimensions = PixelDimensions(image.width, image.height)
    val planes = image.planes
    require(planes.size == 3)
    val chromaWidth = (dimensions.width + 1) / 2
    val chromaHeight = (dimensions.height + 1) / 2
    return Yuv420Frame(
        dimensions = dimensions,
        y = borrowYuvPlane(planes[0], dimensions.width, dimensions.height),
        u = borrowYuvPlane(planes[1], chromaWidth, chromaHeight),
        v = borrowYuvPlane(planes[2], chromaWidth, chromaHeight),
    )
}

private fun borrowYuvPlane(
    plane: Image.Plane,
    width: Int,
    height: Int,
): Yuv420Plane {
    val requiredBytes = minimumYuvPlaneBytes(width, height, plane.rowStride, plane.pixelStride)
    val source = plane.buffer.duplicate()
    require(source.remaining() >= requiredBytes)
    return ByteBufferYuv420Plane(source, plane.rowStride, plane.pixelStride)
}

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

/** Selects the lowest fixed sensor cadence; variable ranges are a bounded fallback only. */
internal fun selectPowerEfficientAeFpsRange(candidates: Collection<IntRange>): IntRange? =
    candidates
        .filter { it.first > 0 && it.last >= it.first }
        .minWithOrNull(
            compareBy<IntRange> { if (it.first == it.last) 0 else 1 }
                .thenBy { it.last }
                .thenBy { it.first },
        )

internal fun shouldJoinCameraThread(
    cameraThread: Thread?,
    currentThread: Thread = Thread.currentThread(),
): Boolean = cameraThread != null && cameraThread !== currentThread

internal fun joinCameraThreadBounded(
    cameraThread: Thread?,
    currentThread: Thread = Thread.currentThread(),
    timeoutMillis: Long = CAMERA_THREAD_JOIN_TIMEOUT_MILLIS,
    onTimeout: (threadName: String, timeoutMillis: Long) -> Unit = { threadName, timeout ->
        Log.w(
            CAMERA_LOG_TAG,
            "camera_teardown_thread_join_timed_out thread=$threadName timeout_ms=$timeout; continuing=true",
        )
    },
): Boolean {
    require(timeoutMillis > 0L)
    if (!shouldJoinCameraThread(cameraThread, currentThread)) return true
    checkNotNull(cameraThread)
    try {
        cameraThread.join(timeoutMillis)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return false
    }
    if (!cameraThread.isAlive) return true
    onTimeout(cameraThread.name, timeoutMillis)
    return false
}

internal fun cameraDeviceErrorIsRecoverable(error: Int): Boolean = when (error) {
    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE,
    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE,
    CameraDevice.StateCallback.ERROR_CAMERA_SERVICE,
    -> true
    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> false
    else -> false
}

internal fun cameraAccessErrorIsRecoverable(reason: Int): Boolean = when (reason) {
    CameraAccessException.CAMERA_DISCONNECTED,
    CameraAccessException.CAMERA_ERROR,
    CameraAccessException.CAMERA_IN_USE,
    CameraAccessException.MAX_CAMERAS_IN_USE,
    -> true
    CameraAccessException.CAMERA_DISABLED -> false
    else -> false
}

internal fun cameraDeviceErrorDiagnostic(error: Int): CameraSourceDiagnostic =
    CameraSourceDiagnostic(
        domain = CameraSourceDiagnosticDomain.DEVICE_STATE_CALLBACK,
        numericCode = error,
        symbolicCode = when (error) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE"
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE"
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED"
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "ERROR_CAMERA_DEVICE"
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "ERROR_CAMERA_SERVICE"
            else -> "UNKNOWN"
        },
        recoverable = cameraDeviceErrorIsRecoverable(error),
    )

internal fun cameraDisconnectedDiagnostic(): CameraSourceDiagnostic = CameraSourceDiagnostic(
    domain = CameraSourceDiagnosticDomain.DEVICE_STATE_CALLBACK,
    numericCode = null,
    symbolicCode = "ON_DISCONNECTED",
    recoverable = true,
)

internal fun cameraAccessErrorDiagnostic(reason: Int): CameraSourceDiagnostic =
    CameraSourceDiagnostic(
        domain = CameraSourceDiagnosticDomain.CAMERA_ACCESS_EXCEPTION,
        numericCode = reason,
        symbolicCode = when (reason) {
            CameraAccessException.CAMERA_DISABLED -> "CAMERA_DISABLED"
            CameraAccessException.CAMERA_DISCONNECTED -> "CAMERA_DISCONNECTED"
            CameraAccessException.CAMERA_ERROR -> "CAMERA_ERROR"
            CameraAccessException.CAMERA_IN_USE -> "CAMERA_IN_USE"
            CameraAccessException.MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
            else -> "UNKNOWN"
        },
        recoverable = cameraAccessErrorIsRecoverable(reason),
    )

internal fun captureFailureDiagnostic(reason: Int): CameraSourceDiagnostic =
    CameraSourceDiagnostic(
        domain = CameraSourceDiagnosticDomain.CAPTURE_CALLBACK,
        numericCode = reason,
        symbolicCode = when (reason) {
            CaptureFailure.REASON_ERROR -> "REASON_ERROR"
            CaptureFailure.REASON_FLUSHED -> "REASON_FLUSHED"
            else -> "UNKNOWN"
        },
        recoverable = true,
    )

internal const val CAMERA_PERMISSION_UNAVAILABLE_MESSAGE =
    "Camera permission became unavailable before the camera could open; capture remains stopped"
internal const val CAMERA_START_FAILURE_MESSAGE = "Camera could not start; capture remains stopped"
internal const val CAMERA_OPEN_FAILURE_MESSAGE = "Camera could not be opened; capture remains stopped"
internal const val CAMERA_CONFIGURATION_FAILURE_MESSAGE =
    "Camera capture session could not be configured; capture remains stopped"
internal const val CAMERA_CAPTURE_FAILURE_MESSAGE = "Camera capture failed; capture remains stopped"
internal const val CAMERA_DISCONNECTED_MESSAGE = "Camera disconnected; capture remains stopped"
internal const val CAMERA_DEVICE_FAILURE_MESSAGE = "Camera device failed; capture remains stopped"
internal const val CAMERA_RESTART_REQUESTED_MESSAGE = "Camera device failed; capture restart requested"
internal const val CAMERA_THREAD_JOIN_TIMEOUT_MILLIS = 500L
private const val CAMERA_LOG_TAG = "ConceptFlowCamera2"

internal fun cameraPermissionFailure(cause: SecurityException? = null): IllegalStateException =
    IllegalStateException(CAMERA_PERMISSION_UNAVAILABLE_MESSAGE, cause)
