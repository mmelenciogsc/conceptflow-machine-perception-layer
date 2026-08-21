// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.conceptflow.mpl.rokid.core.AudioInputSource
import org.conceptflow.mpl.rokid.core.CaptureGateEvent
import org.conceptflow.mpl.rokid.core.CueEnvelope
import org.conceptflow.mpl.rokid.core.ElapsedRealtimeClock
import org.conceptflow.mpl.rokid.core.FrameSource
import org.conceptflow.mpl.rokid.core.FrameSourceStateController
import org.conceptflow.mpl.rokid.core.GrpcRemotePerceptionClient
import org.conceptflow.mpl.rokid.core.InProcessCueTransport
import org.conceptflow.mpl.rokid.core.InspectableCueRenderer
import org.conceptflow.mpl.rokid.core.PcmAudioChunk
import org.conceptflow.mpl.rokid.core.PhysicalTraceInputGate
import org.conceptflow.mpl.rokid.core.RemoteCall
import org.conceptflow.mpl.rokid.core.RemotePerceptionClient
import org.conceptflow.mpl.rokid.core.RenderDisposition
import org.conceptflow.mpl.rokid.core.RuntimeCommand
import org.conceptflow.mpl.rokid.core.StreamDiagnosticSession
import org.conceptflow.mpl.rokid.core.TraceCallback
import org.conceptflow.mpl.rokid.hardware.AudioRecordInputSource
import org.conceptflow.mpl.rokid.hardware.Camera2FrameSource
import org.conceptflow.mpl.rokid.hardware.PlatformHapticOutput
import org.conceptflow.mpl.rokid.hardware.PlatformStereoAudioOutput
import org.conceptflow.mpl.rokid.hardware.SensorManagerPoseSource
import org.conceptflow.mpl.v1.Direction
import org.conceptflow.mpl.v1.Earcon
import org.conceptflow.mpl.v1.Haptic
import org.conceptflow.mpl.v1.HapticPattern
import org.conceptflow.mpl.v1.PerceptionCue
import org.conceptflow.mpl.v1.PerceptionResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RokidRuntimeService : Service() {
    private lateinit var audioOutput: PlatformStereoAudioOutput
    private lateinit var renderer: InspectableCueRenderer
    private lateinit var transport: InProcessCueTransport
    private val frameSources = FrameSourceStateController()
    private var poseSource: SensorManagerPoseSource? = null
    private var microphoneSource: AudioRecordInputSource? = null
    private var diagnosticSession: StreamDiagnosticSession? = null
    private val physicalTraceLock = Any()
    private var physicalTrace: PhysicalTraceRun? = null
    private val cueIds = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val binder = RuntimeBinder()

    override fun onCreate() {
        super.onCreate()
        audioOutput = PlatformStereoAudioOutput()
        renderer = InspectableCueRenderer(
            clock = ElapsedRealtimeClock,
            audio = audioOutput,
            haptics = PlatformHapticOutput(this),
        )
        transport = InProcessCueTransport().also { it.connect(renderer::render) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        abandonPhysicalTrace()
        stopSensorInputs()
        if (::transport.isInitialized) transport.close()
        if (::audioOutput.isInitialized) audioOutput.close()
        mainHandler.removeCallbacksAndMessages(null)
        Log.i(TAG, "state=stopped")
        super.onDestroy()
    }

    private fun startCapture(onTerminal: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "state=rejected reason=camera_permission_denied")
            mainHandler.post(onTerminal)
            return
        }
        if (frameSources.hasActiveSource) {
            Log.i(TAG, "state=capturing result=already_active")
            return
        }

        val sensor = SensorManagerPoseSource(this)
        poseSource = sensor
        runCatching { sensor.start { } }
            .onFailure {
                sensor.close()
                if (poseSource === sensor) poseSource = null
                Log.w(TAG, "state=capturing pose=unavailable")
            }

        val source = Camera2FrameSource(this)
        if (!frameSources.attach(source)) {
            source.close()
            Log.i(TAG, "state=capturing result=already_active")
            return
        }
        source.start(object : FrameSource.Listener {
            override fun onFrame(frame: org.conceptflow.mpl.v1.FramePayload) {
                if (frameSources.isCurrent(source)) {
                    Log.i(
                        TAG,
                        "state=capturing frame_id=${frame.frameId} " +
                            "width=${frame.image.width} height=${frame.image.height}",
                    )
                }
            }

            override fun onError(message: String) {
                if (frameSources.stopIfCurrent(source)) {
                    poseSource?.close()
                    poseSource = null
                    Log.w(TAG, "state=stopped reason=${safeReason(message)}")
                    mainHandler.post(onTerminal)
                }
            }
        })
        if (source.isRunning && frameSources.isCurrent(source)) {
            Log.i(TAG, "state=capturing")
        } else {
            frameSources.stopIfCurrent(source)
            mainHandler.post(onTerminal)
        }
    }

    private fun startStreamTest(onTerminal: () -> Unit) {
        val missingPermissions = buildList {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add("camera")
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add("microphone")
            }
        }
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "state=rejected reason=${missingPermissions.joinToString("_")}_permission_denied")
            mainHandler.post(onTerminal)
            return
        }
        if (frameSources.hasActiveSource || poseSource?.isRunning == true || microphoneSource?.isRunning == true ||
            hasActivePhysicalTrace()
        ) {
            Log.i(TAG, "state=stream_test result=already_active")
            return
        }

        val diagnostic = StreamDiagnosticSession(ElapsedRealtimeClock.nowNanos())
        diagnosticSession = diagnostic
        val sensor = SensorManagerPoseSource(this)
        poseSource = sensor
        runCatching {
            sensor.start { sample ->
                if (diagnostic.recordImuSample(sample.hasNonZeroSignal(), sample.pose.monotonicTimestampNs)) {
                    Log.i(TAG, "stream=imu status=active")
                }
            }
        }.onFailure {
            sensor.close()
            if (poseSource === sensor) poseSource = null
            Log.w(TAG, "stream=imu status=unavailable")
        }

        val microphone = AudioRecordInputSource(this)
        microphoneSource = microphone
        runCatching {
            microphone.start(object : AudioInputSource.Listener {
                override fun onAudioChunk(chunk: PcmAudioChunk) {
                    if (diagnostic.recordMicrophoneChunk(chunk.pcm16LittleEndian)) {
                        Log.i(
                            TAG,
                            "stream=microphone status=active sample_rate_hz=${chunk.sampleRateHz} channels=${chunk.channelCount}",
                        )
                    }
                }

                override fun onError(message: String) {
                    if (microphoneSource === microphone) microphoneSource = null
                    Log.w(TAG, "stream=microphone status=unavailable reason=${safeReason(message)}")
                }
            })
        }.onFailure {
            microphone.close()
            if (microphoneSource === microphone) microphoneSource = null
            Log.w(TAG, "stream=microphone status=unavailable reason=${safeReason(it.message ?: "start_failed")}")
        }

        val camera = Camera2FrameSource(this)
        if (frameSources.attach(camera)) {
            camera.start(object : FrameSource.Listener {
                override fun onCaptureGate(event: CaptureGateEvent) {
                    diagnostic.recordCaptureGate(event)
                }

                override fun onFrame(frame: org.conceptflow.mpl.v1.FramePayload) {
                    if (frameSources.isCurrent(camera) && diagnostic.recordCameraFrame(frame.frameData.size())) {
                        Log.i(
                            TAG,
                            "stream=camera status=active first_frame_id=${frame.frameId} " +
                                "width=${frame.image.width} height=${frame.image.height}",
                        )
                    }
                }

                override fun onError(message: String) {
                    if (frameSources.stopIfCurrent(camera)) {
                        Log.w(TAG, "stream=camera status=unavailable reason=${safeReason(message)}")
                    }
                }
            })
        } else {
            camera.close()
            Log.w(TAG, "stream=camera status=unavailable reason=already_active")
        }

        Log.i(TAG, "state=stream_test_started duration_ms=$STREAM_TEST_DURATION_MS")
        mainHandler.postDelayed(
            { finishStreamTest(diagnostic, onTerminal) },
            STREAM_TEST_DURATION_MS,
        )
    }

    private fun finishStreamTest(diagnostic: StreamDiagnosticSession, onTerminal: () -> Unit) {
        if (diagnosticSession !== diagnostic) return
        stopSensorInputs()
        val snapshot = diagnostic.finish(ElapsedRealtimeClock.nowNanos())
        diagnosticSession = null
        Log.i(
            TAG,
            "state=stream_test_complete result=${if (snapshot.passed) "pass" else "fail"} " +
                "camera_frames=${snapshot.cameraFrames} camera_bytes=${snapshot.cameraBytes} " +
                "camera_analyzed=${snapshot.cameraFramesAnalyzed} " +
                "camera_dropped_dark=${snapshot.cameraFramesDroppedDark} " +
                "camera_dropped_blurry=${snapshot.cameraFramesDroppedBlurry} " +
                "camera_dropped_cadence=${snapshot.cameraFramesDroppedCadence} " +
                "camera_motion_tier_samples=${snapshot.cameraMotionTierSamples} " +
                "imu_samples=${snapshot.imuSamples} imu_signal_samples=${snapshot.imuSignalSamples} " +
                "imu_observed_hz=${"%.1f".format(java.util.Locale.ROOT, snapshot.imuObservedSamplesPerSecond)} " +
                "imu_max_gap_ms=${"%.1f".format(java.util.Locale.ROOT, snapshot.imuMaximumGapMillis)} " +
                "microphone_chunks=${snapshot.microphoneChunks} " +
                "microphone_bytes=${snapshot.microphoneBytes} " +
                "microphone_nonzero_samples=${snapshot.microphoneNonZeroSamples} " +
                "microphone_peak_absolute=${snapshot.microphonePeakAbsolute} " +
                "duration_ms=${snapshot.durationMillis}",
        )
        mainHandler.post(onTerminal)
    }

    private fun startPhysicalTrace(onTerminal: () -> Unit) {
        val missingPermissions = buildList {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add("camera")
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add("microphone")
            }
        }
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "state=physical_trace_rejected reason=${missingPermissions.joinToString("_")}_permission_denied")
            mainHandler.post(onTerminal)
            return
        }
        if (frameSources.hasActiveSource || poseSource?.isRunning == true || microphoneSource?.isRunning == true ||
            hasActivePhysicalTrace()
        ) {
            Log.w(TAG, "state=physical_trace_rejected reason=input_busy")
            mainHandler.post(onTerminal)
            return
        }

        val run = PhysicalTraceRun(
            inputGate = PhysicalTraceInputGate(),
            diagnostic = StreamDiagnosticSession(ElapsedRealtimeClock.nowNanos()),
            client = GrpcRemotePerceptionClient.adbReverseLoopback(
                port = PHYSICAL_TRACE_PORT,
                deadlineMillis = PHYSICAL_TRACE_RPC_DEADLINE_MS,
            ),
            onTerminal = onTerminal,
        )
        synchronized(physicalTraceLock) { physicalTrace = run }
        run.timeout = Runnable { finishPhysicalTrace(run, outcome = "timeout") }
        mainHandler.postDelayed(run.timeout, PHYSICAL_TRACE_TIMEOUT_MS)

        val sensor = SensorManagerPoseSource(this)
        poseSource = sensor
        runCatching {
            sensor.start { sample ->
                if (isActivePhysicalTrace(run)) {
                    if (run.diagnostic.recordImuSample(sample.hasNonZeroSignal(), sample.pose.monotonicTimestampNs)) {
                        Log.i(TAG, "state=physical_trace stream=imu status=active")
                    }
                    run.inputGate.recordPose(sample)
                    dispatchPhysicalTraceIfReady(run)
                }
            }
        }.onFailure {
            sensor.close()
            if (poseSource === sensor) poseSource = null
            failPhysicalTrace(run, "imu_unavailable")
            return
        }

        val microphone = AudioRecordInputSource(this)
        microphoneSource = microphone
        runCatching {
            microphone.start(object : AudioInputSource.Listener {
                override fun onAudioChunk(chunk: PcmAudioChunk) {
                    if (!isActivePhysicalTrace(run)) return
                    if (run.diagnostic.recordMicrophoneChunk(chunk.pcm16LittleEndian)) {
                        Log.i(
                            TAG,
                            "state=physical_trace stream=microphone status=active " +
                                "sample_rate_hz=${chunk.sampleRateHz} channels=${chunk.channelCount}",
                        )
                    }
                    run.inputGate.recordMicrophoneActivity(
                        payloadBytes = chunk.pcm16LittleEndian.size,
                        hasNonZeroSignal = chunk.pcm16LittleEndian.any { it.toInt() != 0 },
                    )
                    dispatchPhysicalTraceIfReady(run)
                }

                override fun onError(message: String) {
                    if (microphoneSource === microphone) microphoneSource = null
                    failPhysicalTrace(run, "microphone_${safeReason(message)}")
                }
            })
        }.onFailure {
            microphone.close()
            if (microphoneSource === microphone) microphoneSource = null
            failPhysicalTrace(run, "microphone_${safeReason(it.message ?: "start_failed")}")
            return
        }

        val camera = Camera2FrameSource(this)
        if (!frameSources.attach(camera)) {
            camera.close()
            failPhysicalTrace(run, "camera_busy")
            return
        }
        camera.start(object : FrameSource.Listener {
            override fun onCaptureGate(event: CaptureGateEvent) {
                run.diagnostic.recordCaptureGate(event)
            }

            override fun onFrame(frame: org.conceptflow.mpl.v1.FramePayload) {
                if (!isActivePhysicalTrace(run) || !run.inputGate.recordFrame(frame)) return
                run.diagnostic.recordCameraFrame(frame.frameData.size())
                frameSources.stopIfCurrent(camera)
                Log.i(TAG, "state=physical_trace stream=camera status=active frame_id=${frame.frameId}")
                dispatchPhysicalTraceIfReady(run)
            }

            override fun onError(message: String) {
                frameSources.stopIfCurrent(camera)
                failPhysicalTrace(run, "camera_${safeReason(message)}")
            }
        })
        if (!camera.isRunning) {
            frameSources.stopIfCurrent(camera)
            failPhysicalTrace(run, "camera_start_failed")
            return
        }

        Log.i(
            TAG,
            "state=physical_trace_started transport=adb_reverse_loopback port=$PHYSICAL_TRACE_PORT " +
                "raw_microphone_transmitted=false timeout_ms=$PHYSICAL_TRACE_TIMEOUT_MS",
        )
    }

    private fun dispatchPhysicalTraceIfReady(run: PhysicalTraceRun) {
        if (!isActivePhysicalTrace(run)) return
        val frame = run.inputGate.takeReadyFrame()
        if (frame == null || !run.dispatchStarted.compareAndSet(false, true)) return
        run.poseAttached = frame.hasPose()
        run.uplinkStartedMonotonicNs = ElapsedRealtimeClock.nowNanos()
        Log.i(
            TAG,
            "state=physical_trace_transport frame_id=${frame.frameId} frame_bytes=${frame.frameData.size()} " +
                "pose_attached=${run.poseAttached}",
        )
        val call = run.client.execute(frame, object : TraceCallback {
            override fun onSuccess(value: PerceptionResult) {
                mainHandler.post { completePhysicalTrace(run, value) }
            }

            override fun onFailure(error: Throwable) {
                failPhysicalTrace(run, "transport_${safeReason(error.message ?: error.javaClass.simpleName)}")
            }
        })
        synchronized(physicalTraceLock) {
            if (physicalTrace === run) run.remoteCall = call else call.cancel()
        }
    }

    private fun completePhysicalTrace(run: PhysicalTraceRun, result: PerceptionResult) {
        if (!isActivePhysicalTrace(run)) return
        val events = transport.deliver(result)
        val rendered = events.count { it.disposition == RenderDisposition.RENDERED }
        val audioPlayed = events.count { it.audioPlayed }
        val hapticPlayed = events.count { it.hapticPlayed }
        finishPhysicalTrace(
            run = run,
            outcome = if (rendered > 0) "pass" else "no_renderable_cue",
            cueCount = result.cuesCount,
            renderedCount = rendered,
            audioCount = audioPlayed,
            hapticCount = hapticPlayed,
        )
    }

    private fun failPhysicalTrace(run: PhysicalTraceRun, reason: String) {
        mainHandler.post {
            finishPhysicalTrace(run, outcome = safeReason(reason))
        }
    }

    private fun finishPhysicalTrace(
        run: PhysicalTraceRun,
        outcome: String,
        cueCount: Int = 0,
        renderedCount: Int = 0,
        audioCount: Int = 0,
        hapticCount: Int = 0,
    ) {
        val removed = synchronized(physicalTraceLock) {
            if (physicalTrace !== run) false else {
                physicalTrace = null
                true
            }
        }
        if (!removed) return
        mainHandler.removeCallbacks(run.timeout)
        run.inputGate.clear()
        run.remoteCall?.cancel()
        run.client.close()
        stopSensorInputs()
        val finishedMonotonicNs = ElapsedRealtimeClock.nowNanos()
        val snapshot = run.diagnostic.finish(finishedMonotonicNs)
        val inputsPassed = snapshot.passed && run.poseAttached
        val finalOutcome = if (outcome == "pass" && inputsPassed) "pass" else if (outcome == "pass") {
            "input_incomplete"
        } else {
            outcome
        }
        val uplinkLatencyMillis = if (run.uplinkStartedMonotonicNs > 0L) {
            (finishedMonotonicNs - run.uplinkStartedMonotonicNs).coerceAtLeast(0L) / 1_000_000L
        } else {
            0L
        }
        Log.i(
            TAG,
            "state=physical_trace_complete result=$finalOutcome " +
                "camera_frames=${snapshot.cameraFrames} camera_bytes=${snapshot.cameraBytes} " +
                "camera_analyzed=${snapshot.cameraFramesAnalyzed} " +
                "camera_dropped_dark=${snapshot.cameraFramesDroppedDark} " +
                "camera_dropped_blurry=${snapshot.cameraFramesDroppedBlurry} " +
                "camera_dropped_cadence=${snapshot.cameraFramesDroppedCadence} " +
                "camera_motion_tier_samples=${snapshot.cameraMotionTierSamples} " +
                "imu_samples=${snapshot.imuSamples} imu_signal_samples=${snapshot.imuSignalSamples} " +
                "imu_observed_hz=${"%.1f".format(java.util.Locale.ROOT, snapshot.imuObservedSamplesPerSecond)} " +
                "imu_max_gap_ms=${"%.1f".format(java.util.Locale.ROOT, snapshot.imuMaximumGapMillis)} " +
                "pose_attached=${run.poseAttached} microphone_chunks=${snapshot.microphoneChunks} " +
                "microphone_bytes=${snapshot.microphoneBytes} " +
                "microphone_nonzero_samples=${snapshot.microphoneNonZeroSamples} " +
                "microphone_peak_absolute=${snapshot.microphonePeakAbsolute} " +
                "cues=$cueCount rendered=$renderedCount audio=$audioCount haptic=$hapticCount " +
                "transport_ms=$uplinkLatencyMillis duration_ms=${snapshot.durationMillis}",
        )
        mainHandler.postDelayed(
            run.onTerminal,
            if (audioCount > 0) CUE_SERVICE_LIFETIME_MS else 0L,
        )
    }

    private fun isActivePhysicalTrace(run: PhysicalTraceRun): Boolean =
        synchronized(physicalTraceLock) { physicalTrace === run }

    private fun hasActivePhysicalTrace(): Boolean =
        synchronized(physicalTraceLock) { physicalTrace != null }

    private fun abandonPhysicalTrace() {
        val run = synchronized(physicalTraceLock) {
            physicalTrace.also { physicalTrace = null }
        } ?: return
        mainHandler.removeCallbacks(run.timeout)
        run.inputGate.clear()
        run.remoteCall?.cancel()
        run.client.close()
    }

    private fun stopSensorInputs() {
        frameSources.stopCurrent()
        poseSource?.close()
        poseSource = null
        microphoneSource?.close()
        microphoneSource = null
    }

    private fun playCue(direction: Direction, onTerminal: () -> Unit) {
        val id = cueIds.incrementAndGet()
        val cue = PerceptionCue.newBuilder()
            .setCueId("local-cue-$id")
            .setFrameId(id)
            .setCreatedMonotonicTimestampNs(ElapsedRealtimeClock.nowNanos())
            .setTtlMs(1_000)
            .setDescription(if (direction == Direction.DIRECTION_LEFT) "Left cue" else "Right cue")
            .setConfidence(1.0)
            .setPriority(1)
            .setDirection(direction)
            .setEarcon(Earcon.newBuilder().setEarconId("orientation").setGain(0.55f).setPitch(1f))
            .setHaptic(
                Haptic.newBuilder()
                    .setPattern(HapticPattern.HAPTIC_PATTERN_PULSE)
                    .setIntensity(0.35f)
                    .setDurationMs(70),
            )
            .build()
        val event = transport.deliver(CueEnvelope("local-session", "development-cues", cue))
        Log.i(
            TAG,
            "state=cue direction=${direction.name} disposition=${event?.disposition?.name ?: "UNAVAILABLE"}",
        )
        mainHandler.postDelayed(
            { if (!frameSources.hasActiveSource) onTerminal() },
            CUE_SERVICE_LIFETIME_MS,
        )
    }

    inner class RuntimeBinder : Binder() {
        fun execute(command: RuntimeCommand, onTerminal: () -> Unit) {
            when (command) {
                RuntimeCommand.START_CAPTURE -> startCapture(onTerminal)
                RuntimeCommand.START_STREAM_TEST -> startStreamTest(onTerminal)
                RuntimeCommand.START_PHYSICAL_TRACE -> startPhysicalTrace(onTerminal)
                RuntimeCommand.PLAY_LEFT_CUE -> playCue(Direction.DIRECTION_LEFT, onTerminal)
                RuntimeCommand.PLAY_RIGHT_CUE -> playCue(Direction.DIRECTION_RIGHT, onTerminal)
                RuntimeCommand.STOP -> {
                    abandonPhysicalTrace()
                    stopSensorInputs()
                    diagnosticSession = null
                    Log.i(TAG, "state=stop_requested")
                    mainHandler.post(onTerminal)
                }
            }
        }
    }

    private fun safeReason(message: String): String = message
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .take(MAX_REASON_LENGTH)

    private fun org.conceptflow.mpl.rokid.core.ImuSample.hasNonZeroSignal(): Boolean =
        pose.rotation.x != 0.0 || pose.rotation.y != 0.0 || pose.rotation.z != 0.0 ||
            pose.rotation.w != 1.0 ||
            angularVelocityRadiansPerSecond.x != 0.0 || angularVelocityRadiansPerSecond.y != 0.0 ||
            angularVelocityRadiansPerSecond.z != 0.0 ||
            linearAccelerationMetersPerSecondSquared.x != 0.0 ||
            linearAccelerationMetersPerSecondSquared.y != 0.0 ||
            linearAccelerationMetersPerSecondSquared.z != 0.0

    companion object {
        private const val TAG = "ConceptFlowRokid"
        private const val CUE_SERVICE_LIFETIME_MS = 1_500L
        private const val STREAM_TEST_DURATION_MS = 8_000L
        private const val PHYSICAL_TRACE_TIMEOUT_MS = 8_000L
        private const val PHYSICAL_TRACE_RPC_DEADLINE_MS = 2_000L
        private const val PHYSICAL_TRACE_PORT = 50_051
        private const val MAX_REASON_LENGTH = 80
    }
}

private class PhysicalTraceRun(
    val inputGate: PhysicalTraceInputGate,
    val diagnostic: StreamDiagnosticSession,
    val client: RemotePerceptionClient,
    val onTerminal: () -> Unit,
) {
    val dispatchStarted = AtomicBoolean(false)
    @Volatile var remoteCall: RemoteCall? = null
    @Volatile var poseAttached = false
    @Volatile var uplinkStartedMonotonicNs = 0L
    lateinit var timeout: Runnable
}
