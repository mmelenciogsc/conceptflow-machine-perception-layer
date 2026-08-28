// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.conceptflow.mpl.rokid.core.AudioInputSource
import org.conceptflow.mpl.rokid.core.ActiveStreamLease
import org.conceptflow.mpl.rokid.core.AuthenticatedSessionToneGate
import org.conceptflow.mpl.rokid.core.AuthenticatedStreamPeer
import org.conceptflow.mpl.rokid.core.CaptureGateEvent
import org.conceptflow.mpl.rokid.core.CameraCalibrationCapabilityState
import org.conceptflow.mpl.rokid.core.CapturePipelineSnapshot
import org.conceptflow.mpl.rokid.core.CaptureTimingEvent
import org.conceptflow.mpl.rokid.core.CueEnvelope
import org.conceptflow.mpl.rokid.core.DefaultRokidLiveTransport
import org.conceptflow.mpl.rokid.core.ElapsedRealtimeClock
import org.conceptflow.mpl.rokid.core.FrameSource
import org.conceptflow.mpl.rokid.core.FrameSourceStateController
import org.conceptflow.mpl.rokid.core.GrpcRemotePerceptionClient
import org.conceptflow.mpl.rokid.core.InProcessCueTransport
import org.conceptflow.mpl.rokid.core.InspectableCueRenderer
import org.conceptflow.mpl.rokid.core.ImuTransmissionBatch
import org.conceptflow.mpl.rokid.core.ImuTransmissionGate
import org.conceptflow.mpl.rokid.core.IdleControlArmDecision
import org.conceptflow.mpl.rokid.core.IdleControlLifecycle
import org.conceptflow.mpl.rokid.core.IdleLifecycleTransition
import org.conceptflow.mpl.rokid.core.IdleControlPolicy
import org.conceptflow.mpl.rokid.core.IdleControlRestoreReason
import org.conceptflow.mpl.rokid.core.LiveLinkCaptureController
import org.conceptflow.mpl.rokid.core.LiveLinkCaptureSnapshot
import org.conceptflow.mpl.rokid.core.LiveLinkCaptureStopReason
import org.conceptflow.mpl.rokid.core.LiveMicrophoneCaptureState
import org.conceptflow.mpl.rokid.core.PcmAudioChunk
import org.conceptflow.mpl.rokid.core.PhysicalTraceInputGate
import org.conceptflow.mpl.rokid.core.ProcessStartupAnnouncementGate
import org.conceptflow.mpl.rokid.core.PreAuthenticationRendezvousDeadlineGate
import org.conceptflow.mpl.rokid.core.RemoteCall
import org.conceptflow.mpl.rokid.core.RemotePerceptionClient
import org.conceptflow.mpl.rokid.core.RendezvousAlarmDecision
import org.conceptflow.mpl.rokid.core.RendezvousAlarmPolicy
import org.conceptflow.mpl.rokid.core.RendezvousAlarmPrecision
import org.conceptflow.mpl.rokid.core.RendezvousBackoff
import org.conceptflow.mpl.rokid.core.RendezvousGeneration
import org.conceptflow.mpl.rokid.core.RendezvousTerminalDecision
import org.conceptflow.mpl.rokid.core.RenderDisposition
import org.conceptflow.mpl.rokid.core.RokidRendezvousPolicy
import org.conceptflow.mpl.rokid.core.RokidLocalControlCommand
import org.conceptflow.mpl.rokid.core.RokidMicrophoneIntentHandler
import org.conceptflow.mpl.rokid.core.RokidTouchEventHub
import org.conceptflow.mpl.rokid.core.RokidSystemTouchEventHub
import org.conceptflow.mpl.rokid.core.RuntimeCommand
import org.conceptflow.mpl.rokid.core.RuntimeCommandAuthorization
import org.conceptflow.mpl.rokid.core.SensorStreamPacketizer
import org.conceptflow.mpl.rokid.core.StreamLeaseController
import org.conceptflow.mpl.rokid.core.StreamLeaseDecision
import org.conceptflow.mpl.rokid.core.StreamLeasePolicy
import org.conceptflow.mpl.rokid.core.StreamLeaseSpec
import org.conceptflow.mpl.rokid.core.StreamDiagnosticSession
import org.conceptflow.mpl.rokid.core.TraceCallback
import org.conceptflow.mpl.rokid.hardware.AudioRecordInputSource
import org.conceptflow.mpl.rokid.hardware.BoundedRendezvousWakeLease
import org.conceptflow.mpl.rokid.hardware.Camera2FrameSource
import org.conceptflow.mpl.rokid.hardware.PlatformHapticOutput
import org.conceptflow.mpl.rokid.hardware.PlatformStereoAudioOutput
import org.conceptflow.mpl.rokid.hardware.RokidBrandedAudio
import org.conceptflow.mpl.rokid.hardware.SensorManagerPoseSource
import org.conceptflow.mpl.transport.LiveLinkEndpointRole
import org.conceptflow.mpl.transport.LiveLinkNetworkTopology
import org.conceptflow.mpl.transport.LIVE_LINK_DIAGNOSTIC_SCHEMA_VERSION
import org.conceptflow.mpl.transport.LiveLinkProvisioningStore
import org.conceptflow.mpl.transport.MicrophoneGestureDispatch
import org.conceptflow.mpl.transport.RokidGestureDispatch
import org.conceptflow.mpl.transport.RokidLiveLinkClient
import org.conceptflow.mpl.transport.AndroidWifiDirectEndpointResolver
import org.conceptflow.mpl.transport.StaticLiveLinkEndpointResolver
import org.conceptflow.mpl.transport.WifiDirectNodeRole
import org.conceptflow.mpl.v1.Direction
import org.conceptflow.mpl.v1.Earcon
import org.conceptflow.mpl.v1.Haptic
import org.conceptflow.mpl.v1.HapticPattern
import org.conceptflow.mpl.v1.PerceptionCue
import org.conceptflow.mpl.v1.PerceptionResult
import org.conceptflow.mpl.v1.RokidGestureOperation
import org.conceptflow.mpl.v1.RokidNodeCommand
import org.conceptflow.mpl.v1.RokidNodeCommandOperation
import org.conceptflow.mpl.v1.SensorStreamKind
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.security.SecureRandom

class RokidRuntimeService : Service() {
    private lateinit var audioOutput: PlatformStereoAudioOutput
    private lateinit var renderer: InspectableCueRenderer
    private lateinit var transport: InProcessCueTransport
    private val frameSources = FrameSourceStateController()
    private var poseSource: SensorManagerPoseSource? = null
    private var microphoneSource: AudioRecordInputSource? = null
    @Volatile private var diagnosticSession: StreamDiagnosticSession? = null
    private val physicalTraceLock = Any()
    private var physicalTrace: PhysicalTraceRun? = null
    private var liveLinkRun: LiveLinkServiceRun? = null
    private val cueIds = AtomicLong(0L)
    private var liveMicrophoneCueActive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val binder = RuntimeBinder()
    private lateinit var idleModeStore: IdleControlModeStore
    private val foregroundLifecycle = IdleControlLifecycle()
    private var idleForeground = false
    private var activeForegroundTypeMask = 0
    private var visibleArmEligible = false
    private var startedIdleEstablished = false
    private var activationBrandingPending = false
    private var activationGesturePending = false
    private var pendingServiceShutdown: PendingServiceShutdown? = null
    private val rendezvousGeneration = RendezvousGeneration()
    private val rendezvousBackoff = RendezvousBackoff()
    private lateinit var rendezvousWakeLease: BoundedRendezvousWakeLease
    private lateinit var brandedAudio: RokidBrandedAudio
    private lateinit var brandedAudioStateStore: BrandedAudioStateStore
    private lateinit var inputCommandGateStore: RokidInputCommandGateStore
    private lateinit var legacySensorSpoolStore: LegacySensorSpoolStore
    private val touchEventSink = RokidTouchEventHub.Sink { event, observedMonotonicTimestampNs ->
        mainHandler.post {
            val controller = liveLinkRun?.controller ?: return@post
            if (!controller.offerTouchEvent(event, observedMonotonicTimestampNs)) {
                Log.w(TAG, "state=touch_stream result=not_queued")
            }
        }
    }
    private val systemTouchEventSink = RokidSystemTouchEventHub.Sink { event ->
        mainHandler.post {
            val controller = liveLinkRun?.controller ?: return@post
            if (!controller.offerSystemTouchEvent(event)) {
                Log.w(TAG, "state=system_touch_stream result=not_queued")
            }
        }
    }
    private val microphoneIntentHandler = RokidMicrophoneIntentHandler handler@{ command ->
        val controller = liveLinkRun?.controller ?: return@handler false
        val dispatch = when (command) {
            RokidLocalControlCommand.MICROPHONE_START_INTENT ->
                controller.requestMicrophoneFromUserGesture()
            RokidLocalControlCommand.MICROPHONE_STOP_INTENT ->
                controller.stopMicrophoneFromUserGesture()
            RokidLocalControlCommand.ENABLE_NODE,
            RokidLocalControlCommand.DISABLE_NODE,
            -> return@handler false
        }
        dispatch == MicrophoneGestureDispatch.QUEUED
    }
    private var rendezvousAlarm: PendingIntent? = null
    private var processRendezvousCapability = 0L

    override fun onCreate() {
        super.onCreate()
        idleModeStore = IdleControlModeStore(this)
        rendezvousWakeLease = BoundedRendezvousWakeLease(this)
        processRendezvousCapability = SecureRandom().nextLong()
        audioOutput = PlatformStereoAudioOutput()
        renderer = InspectableCueRenderer(
            clock = ElapsedRealtimeClock,
            audio = audioOutput,
            haptics = PlatformHapticOutput(this),
        )
        transport = InProcessCueTransport().also { it.connect(renderer::render) }
        brandedAudio = RokidBrandedAudio(this, mainHandler)
        brandedAudioStateStore = BrandedAudioStateStore(this)
        inputCommandGateStore = RokidInputCommandGateStore(this)
        legacySensorSpoolStore = LegacySensorSpoolStore(this)
        RokidTouchEventHub.install(touchEventSink)
        RokidSystemTouchEventHub.install(systemTouchEventSink)
        Log.i(TAG, "state=node_ready audio=deferred_until_explicit_activation")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun currentBootCount(): Int? = runCatching {
        Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrNull()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A newly created Service must acknowledge startForegroundService promptly. Once YodaOS
        // has accepted the visible broker's foreground promotion, repeating startForeground from
        // a background retry is rejected even though the Service is already foreground.
        // Every startForegroundService() delivery creates a fresh platform acknowledgement
        // obligation, including when this Service instance still believes it is foreground.
        // YodaOS can retain that in-process flag after detaching the ServiceRecord association;
        // skipping this confirmation then produces a delayed foreground-start ANR and kills the
        // camera process. Reconfirm before dispatching any command; showForeground preserves the
        // already-authorized type mask and fails closed if the platform rejects the transition.
        if (intent != null &&
            !showForeground(ForegroundNotificationMode.IDLE, confirmStartRequest = true)
        ) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val localControl = RokidLocalControlCommand.fromAction(intent?.action)
        if (localControl != null) {
            val observedMonotonicNs = intent?.getLongExtra(
                EXTRA_GESTURE_OBSERVED_MONOTONIC_NS,
                SystemClock.elapsedRealtimeNanos(),
            )?.takeIf { it > 0L } ?: SystemClock.elapsedRealtimeNanos()
            return handleLocalControl(localControl, observedMonotonicNs, startId)
        }
        return when (intent?.action) {
            ACTION_ENABLE_IDLE_CONTROL -> enableIdleControlFromVisibleActivity(startId)
            ACTION_RESTORE_AFTER_BOOT -> restoreIdleControlIfInternal(
                intent,
                IdleControlRestoreReason.BOOT_COMPLETED,
                startId,
            )
            ACTION_RESTORE_AFTER_PACKAGE_REPLACED ->
                restoreIdleControlIfInternal(
                    intent,
                    IdleControlRestoreReason.PACKAGE_REPLACED,
                    startId,
                )
            ACTION_RENDEZVOUS_RETRY -> resumeRendezvousFromAlarm(intent, startId)
            ACTION_RECOVER_SAME_BOOT -> resumeExplicitSameBootArm(startId)
            null -> restoreIdleControl(IdleControlRestoreReason.STICKY_RESTART, startId)
            else -> {
                Log.w(TAG, "state=idle_control_start_rejected reason=unknown_internal_action")
                stopSelfResult(startId)
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        RokidTouchEventHub.clear(touchEventSink)
        RokidSystemTouchEventHub.clear(systemTouchEventSink)
        rendezvousGeneration.invalidate()
        cancelRendezvousAlarm()
        if (::rendezvousWakeLease.isInitialized) rendezvousWakeLease.close()
        stopLiveLink(LiveLinkCaptureStopReason.SERVICE_DESTROYED)
        abandonPhysicalTrace()
        stopSensorInputs()
        if (::transport.isInitialized) transport.close()
        if (::audioOutput.isInitialized) audioOutput.close()
        if (::brandedAudio.isInitialized) brandedAudio.close()
        idleForeground = false
        activeForegroundTypeMask = 0
        visibleArmEligible = false
        startedIdleEstablished = false
        activationBrandingPending = false
        activationGesturePending = false
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
        if (hasActiveInputs()) {
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
        val firstFrameLogged = AtomicBoolean(false)
        if (!frameSources.attach(source)) {
            source.close()
            Log.i(TAG, "state=capturing result=already_active")
            return
        }
        source.start(object : FrameSource.Listener {
            override fun onCameraCalibrationCapability(state: CameraCalibrationCapabilityState) {
                Log.i(TAG, "state=capturing camera_intrinsics=${state.diagnosticLabel}")
            }

            override fun onFrame(frame: org.conceptflow.mpl.v1.FramePayload) {
                if (frameSources.isCurrent(source)) {
                    Log.i(
                        TAG,
                        "state=capturing frame_id=${frame.frameId} " +
                            "width=${frame.image.width} height=${frame.image.height}" +
                            if (firstFrameLogged.compareAndSet(false, true)) {
                                " ${cameraExtrinsicDiagnostic(frame)}"
                            } else {
                                ""
                            },
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
        if (hasActiveInputs()) {
            Log.i(TAG, "state=stream_test result=already_active")
            return
        }

        val leaseController = StreamLeaseController(
            clock = ElapsedRealtimeClock,
            policy = StreamLeasePolicy(
                maximumLeaseDurationMillis = STREAM_TEST_DURATION_MS,
                maximumMicrophoneDurationMillis = STREAM_TEST_MICROPHONE_DURATION_MS,
            ),
        )
        val leaseDecision = leaseController.open(
            AuthenticatedStreamPeer(ADB_DIAGNOSTIC_PEER),
            StreamLeaseSpec(
                sessionId = "adb-stream-diagnostic",
                requestedStreams = setOf(
                    SensorStreamKind.SENSOR_STREAM_KIND_CAMERA,
                    SensorStreamKind.SENSOR_STREAM_KIND_IMU,
                    SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE,
                ),
                requestedDurationMillis = STREAM_TEST_DURATION_MS,
                userRequestedMicrophone = true,
            ),
        )
        val lease = (leaseDecision as? StreamLeaseDecision.Granted)?.lease ?: run {
            Log.w(TAG, "state=stream_test result=lease_rejected")
            mainHandler.post(onTerminal)
            return
        }
        val diagnostic = StreamDiagnosticSession(ElapsedRealtimeClock.nowNanos())
        val imuGate = ImuTransmissionGate()
        val packetizer = SensorStreamPacketizer(ElapsedRealtimeClock)
        val transmission = PowerAwareTransmissionCounters()
        diagnosticSession = diagnostic
        val sensor = SensorManagerPoseSource(this)
        poseSource = sensor
        runCatching {
            sensor.start { sample ->
                if (diagnostic.recordImuSample(sample.hasNonZeroSignal(), sample.pose.monotonicTimestampNs)) {
                    Log.i(TAG, "stream=imu status=active")
                }
                imuGate.offer(sample)?.let { batch ->
                    recordImuTransmission(packetizer, lease, batch, transmission)
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
                    packetizer.microphone(lease, chunk)?.let { envelope ->
                        transmission.microphonePackets.incrementAndGet()
                        transmission.microphonePayloadBytes.addAndGet(envelope.microphoneChunk.audioData.size().toLong())
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
                override fun onCameraCalibrationCapability(state: CameraCalibrationCapabilityState) {
                    Log.i(TAG, "stream=camera intrinsics=${state.diagnosticLabel}")
                }

                override fun onCaptureSessionReady(readyMonotonicTimestampNanos: Long) {
                    diagnostic.recordCameraCaptureSessionReady(readyMonotonicTimestampNanos)
                }

                override fun onCaptureGate(event: CaptureGateEvent) {
                    diagnostic.recordCaptureGate(event)
                }

                override fun onCaptureTiming(event: CaptureTimingEvent) {
                    diagnostic.recordCaptureTiming(event)
                }

                override fun onCapturePipelineSnapshot(snapshot: CapturePipelineSnapshot) {
                    diagnostic.recordCapturePipelineSnapshot(snapshot)
                }

                override fun onFrame(frame: org.conceptflow.mpl.v1.FramePayload) {
                    if (frameSources.isCurrent(camera) && diagnostic.recordCameraFrame(frame.frameData.size())) {
                        Log.i(
                            TAG,
                            "stream=camera status=active first_frame_id=${frame.frameId} " +
                                "width=${frame.image.width} height=${frame.image.height} " +
                                cameraExtrinsicDiagnostic(frame),
                        )
                    }
                    packetizer.camera(lease, frame).let { packets ->
                        if (packets.isNotEmpty()) {
                            transmission.cameraPackets.addAndGet(packets.size.toLong())
                            transmission.cameraPayloadBytes.addAndGet(frame.frameData.size().toLong())
                        }
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

        val pollImu = object : Runnable {
            override fun run() {
                if (diagnosticSession !== diagnostic) return
                imuGate.poll(ElapsedRealtimeClock.nowNanos())?.let { batch ->
                    recordImuTransmission(packetizer, lease, batch, transmission)
                }
                mainHandler.postDelayed(this, IMU_GATE_POLL_MILLIS)
            }
        }
        mainHandler.postDelayed(pollImu, IMU_GATE_POLL_MILLIS)
        mainHandler.postDelayed(
            {
                if (diagnosticSession === diagnostic) {
                    microphoneSource?.close()
                    microphoneSource = null
                    Log.i(TAG, "stream=microphone status=lease_expired")
                }
            },
            STREAM_TEST_MICROPHONE_DURATION_MS,
        )
        Log.i(
            TAG,
            "state=stream_test_started duration_ms=$STREAM_TEST_DURATION_MS " +
                "microphone_lease_ms=$STREAM_TEST_MICROPHONE_DURATION_MS transport=packetizer_diagnostic",
        )
        mainHandler.postDelayed(
            {
                mainHandler.removeCallbacks(pollImu)
                imuGate.flush(ElapsedRealtimeClock.nowNanos())?.let { batch ->
                    recordImuTransmission(packetizer, lease, batch, transmission)
                }
                leaseController.clear()
                finishStreamTest(diagnostic, transmission, imuGate, onTerminal)
            },
            STREAM_TEST_DURATION_MS,
        )
    }

    private fun cameraExtrinsicDiagnostic(frame: org.conceptflow.mpl.v1.FramePayload): String {
        if (!frame.hasIntrinsics() || !frame.intrinsics.hasHeadFromCameraExtrinsic()) {
            return "head_camera_rotation=unavailable"
        }
        val extrinsic = frame.intrinsics.headFromCameraExtrinsic
        val rotation = extrinsic.headFromCameraRotation
        return "head_camera_rotation=${extrinsic.provenance.name} " +
            "quaternion_xyzw=${rotation.x},${rotation.y},${rotation.z},${rotation.w} " +
            "translation_available=${extrinsic.translationAvailable}"
    }

    private fun startLiveLinkTest(
        onTerminal: () -> Unit,
        managedStandby: Boolean = false,
        runDurationMillis: Long = if (managedStandby) {
            LiveLinkCaptureController.SOAK_RUN_DURATION_MILLIS
        } else {
            LiveLinkCaptureController.DEFAULT_RUN_DURATION_MILLIS
        },
    ): Boolean {
        if (pendingServiceShutdown != null) {
            Log.w(TAG, "state=live_link_rejected reason=service_stopping")
            mainHandler.post(onTerminal)
            return false
        }
        if (liveLinkRun != null) {
            Log.i(TAG, "state=live_link_rendezvous result=already_active")
            mainHandler.post(onTerminal)
            return false
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "state=live_link_rejected reason=camera_permission_denied")
            mainHandler.post(onTerminal)
            return false
        }
        if (hasActiveInputs()) {
            Log.w(TAG, "state=live_link_rejected reason=input_busy")
            mainHandler.post(onTerminal)
            return false
        }

        val spool: RokidCaptureSpool?
        val liveTransport = try {
            val configuration = LiveLinkProvisioningStore(this).loadConfig(LiveLinkEndpointRole.ROKID_CLIENT)
            require(configuration.identityAlias == LIVE_LINK_IDENTITY_ALIAS)
            require(configuration.connectTimeoutMs <= MAX_RENDEZVOUS_CONNECT_TIMEOUT_MS)
            spool = if (legacySensorSpoolStore.isEnabled()) RokidCaptureSpool(this) else null
            val client = if (spool == null) {
                RokidLiveLinkClient.fromConfig(
                    configuration,
                    endpointResolver = when (configuration.networkTopology) {
                        LiveLinkNetworkTopology.PRIVATE_LAN ->
                            StaticLiveLinkEndpointResolver(configuration.address)
                        LiveLinkNetworkTopology.WIFI_DIRECT_REQUIRED ->
                            AndroidWifiDirectEndpointResolver(this, WifiDirectNodeRole.ROKID_CLIENT)
                    },
                )
            } else {
                RokidLiveLinkClient.fromConfig(
                    configuration,
                    spool,
                    endpointResolver = when (configuration.networkTopology) {
                        LiveLinkNetworkTopology.PRIVATE_LAN ->
                            StaticLiveLinkEndpointResolver(configuration.address)
                        LiveLinkNetworkTopology.WIFI_DIRECT_REQUIRED ->
                            AndroidWifiDirectEndpointResolver(this, WifiDirectNodeRole.ROKID_CLIENT)
                    },
                )
            }
            DefaultRokidLiveTransport(client)
        } catch (_: Throwable) {
            Log.w(TAG, "state=live_link_rejected reason=configuration_unavailable")
            mainHandler.post(onTerminal)
            return false
        }

        cancelRendezvousAlarm()
        lateinit var run: LiveLinkServiceRun
        lateinit var controller: LiveLinkCaptureController
        controller = LiveLinkCaptureController(
            clock = ElapsedRealtimeClock,
            frameSources = frameSources,
            transport = liveTransport,
            sensorSpool = spool,
            frameSourceFactory = { lease, sequence ->
                Camera2FrameSource(
                    context = this,
                    relaxedFramesPerSecond = lease.cameraRelaxedFps.toDouble(),
                    motionFramesPerSecond = lease.cameraMotionFps.toDouble(),
                    sequence = sequence,
                )
            },
            poseSourceFactory = { SensorManagerPoseSource(this) },
            audioSourceFactory = { AudioRecordInputSource(this) },
            microphonePermissionAvailable = {
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            },
            beforeMicrophoneStart = {
                showForeground(ForegroundNotificationMode.CAMERA_MICROPHONE_ACTIVE)
            },
            runDurationMillis = runDurationMillis,
            beforeProducerStart = {
                val eligible = !managedStandby || (visibleArmEligible && idleModeStore.isEnabled())
                if (!eligible) {
                    Log.w(TAG, "state=live_link_rejected reason=visible_arm_eligibility_unavailable")
                    false
                } else {
                    val promotion = foregroundLifecycle.beforeLiveCapture()
                    val promoted = when {
                        promotion.showCameraStartingForeground ->
                            showForeground(ForegroundNotificationMode.CAMERA_STARTING)
                        foregroundLifecycle.mode ==
                            org.conceptflow.mpl.rokid.core.RokidForegroundMode.CAMERA_STARTING -> true
                        else -> false
                    }
                    if (!promoted) Log.w(TAG, "state=live_link_rejected reason=foreground_promotion_failed")
                    promoted
                }
            },
            dispatch = { operation -> mainHandler.post(operation) },
            onStatus = { snapshot ->
                logLiveLinkStatus(snapshot)
                if (liveLinkRun === run) {
                    if (run.authenticatedToneGate.observe(snapshot.sessionsReady)) {
                        brandedAudio.onAuthenticatedConnection()
                    }
                    if (run.preAuthenticationDeadline.observe(snapshot.sessionsReady)) {
                        mainHandler.removeCallbacks(run.rendezvousDeadline)
                        rendezvousWakeLease.release()
                    }
                    updateLiveLinkPolling(run, snapshot)
                    if (snapshot.state == org.conceptflow.mpl.rokid.core.LiveLinkCaptureState.STOPPED) {
                        val hadActiveSession = snapshot.sessionsReady > 0L || snapshot.producerStarts > 0L
                        showForeground(
                            if (hadActiveSession || !managedStandby) {
                                ForegroundNotificationMode.CAMERA_STOPPING
                            } else {
                                ForegroundNotificationMode.CAMERA_STANDBY
                            },
                        )
                    } else if (
                        snapshot.state == org.conceptflow.mpl.rokid.core.LiveLinkCaptureState.STREAMING &&
                        snapshot.producerStarts > 0L
                    ) {
                        val active = foregroundLifecycle.onLiveProducersStarted()
                        if (active.showCameraActiveForeground) {
                            showForeground(ForegroundNotificationMode.CAMERA_ACTIVE)
                        }
                    } else if (snapshot.state == org.conceptflow.mpl.rokid.core.LiveLinkCaptureState.CONNECTING &&
                        snapshot.producerStarts > 0L
                    ) {
                        val reconnecting = foregroundLifecycle.onLiveReconnecting()
                        if (reconnecting.showIdleForeground) {
                            showForeground(ForegroundNotificationMode.CAMERA_STANDBY)
                        }
                    }
                }
            },
            onTerminal = { snapshot -> finishLiveLink(run, snapshot) },
            onMicrophoneState = ::handleLiveMicrophoneState,
            onRokidNodeCommand = ::acceptRokidNodeCommand,
        )
        val generation = rendezvousGeneration.next()
        run = LiveLinkServiceRun(controller, onTerminal, generation, managedStandby)
        run.poll = object : Runnable {
            override fun run() {
                if (liveLinkRun !== run) return
                controller.poll()
                if (liveLinkRun === run && controller.snapshot().state ==
                    org.conceptflow.mpl.rokid.core.LiveLinkCaptureState.STREAMING
                ) {
                    mainHandler.postDelayed(this, IMU_GATE_POLL_MILLIS)
                }
            }
        }
        run.deadline = Runnable {
            if (liveLinkRun === run) controller.poll()
        }
        run.rendezvousDeadline = Runnable {
            if (liveLinkRun !== run) return@Runnable
            val snapshot = controller.snapshot()
            if (run.preAuthenticationDeadline.expireIfWaiting(snapshot.sessionsReady, snapshot.state)) {
                controller.stop(LiveLinkCaptureStopReason.RENDEZVOUS_TIMEOUT)
            }
        }
        if (managedStandby) {
            run.preAuthenticationDeadline.begin()
            if (!rendezvousWakeLease.acquire(PreAuthenticationRendezvousDeadlineGate.MAXIMUM_WAKE_LEASE_MILLIS)) {
                run.preAuthenticationDeadline.cancel()
                liveTransport.close()
                Log.e(TAG, "state=live_link_rejected reason=preauth_wake_lease_unavailable")
                mainHandler.post(onTerminal)
                return false
            }
            Log.i(
                TAG,
                "state=preauth_resource_lease cpu=${rendezvousWakeLease.cpuHeld()} " +
                    "wifi=${rendezvousWakeLease.wifiHeld()} bounded=true",
            )
        }
        liveLinkRun = run
        val started = controller.start() && liveLinkRun === run
        if (!started) rendezvousWakeLease.release()
        if (started) {
            if (managedStandby) {
                mainHandler.postDelayed(
                    run.rendezvousDeadline,
                    PreAuthenticationRendezvousDeadlineGate.DEFAULT_TIMEOUT_MILLIS,
                )
            }
            Log.i(
                TAG,
                "state=live_link_rendezvous_started diagnostic_schema=$LIVE_LINK_DIAGNOSTIC_SCHEMA_VERSION " +
                    "active_duration_ms=$runDurationMillis " +
                    "standby=sensors_off streams=camera_imu microphone=false",
            )
        }
        return started
    }

    private fun updateLiveLinkPolling(run: LiveLinkServiceRun, snapshot: LiveLinkCaptureSnapshot) {
        mainHandler.removeCallbacks(run.poll)
        mainHandler.removeCallbacks(run.deadline)
        if (snapshot.state == org.conceptflow.mpl.rokid.core.LiveLinkCaptureState.STREAMING) {
            mainHandler.postDelayed(run.poll, IMU_GATE_POLL_MILLIS)
        }
        snapshot.activeDeadlineNanos?.let { deadline ->
            val remainingNanos = (deadline - ElapsedRealtimeClock.nowNanos()).coerceAtLeast(0L)
            val delayMillis = ((remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(run.deadline, delayMillis)
        }
    }

    private fun initializeLiveIdentity(onTerminal: () -> Unit) {
        val result = runCatching {
            val store = LiveLinkProvisioningStore(this)
            val identity = store.ensureIdentity(LIVE_LINK_IDENTITY_ALIAS)
            val exportedBytes = store.publicCertificateFile().length()
            require(exportedBytes == identity.publicCertificateDer.size.toLong() && exportedBytes > 0L)
            exportedBytes
        }
        if (result.isSuccess) {
            Log.i(TAG, "state=live_identity_ready certificate_bytes=${result.getOrThrow()}")
        } else {
            Log.w(TAG, "state=live_identity_failed")
        }
        mainHandler.post(onTerminal)
    }

    private fun stopLiveLink(reason: LiveLinkCaptureStopReason): Boolean {
        val run = liveLinkRun ?: return false
        rendezvousWakeLease.release()
        run.controller.stop(reason)
        return true
    }

    private fun enableIdleControlFromVisibleActivity(startId: Int): Int {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!RuntimeCommandAuthorization.isAllowed(RuntimeCommand.ENABLE_IDLE_CONTROL, debuggable)) {
            Log.w(TAG, "state=idle_control_enable_failed reason=debug_only_command")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        return enableIdleControl(startId, deferActivationBranding = true, source = "visible_activity")
    }

    private fun enableIdleControlFromAccessibility(
        observedMonotonicNs: Long,
        startId: Int,
    ): Int {
        // Local activation feedback must never depend on the phone being online. The authenticated
        // connection has its own second tone, and the returned node command is an acknowledgement,
        // not permission for the already user-authorized local activation.
        activationGesturePending = false
        val result = enableIdleControl(
            startId,
            deferActivationBranding = false,
            source = "accessibility_gesture",
        )
        val dispatch = liveLinkRun?.controller?.requestRokidGesture(
            RokidGestureOperation.ROKID_GESTURE_OPERATION_ENABLE_NODE,
            observedMonotonicNs,
        ) ?: RokidGestureDispatch.TRANSPORT_STOPPED
        Log.i(TAG, "state=rokid_gesture operation=enable_node dispatch=${dispatch.name.lowercase()}")
        return result
    }

    private fun enableIdleControl(
        startId: Int,
        deferActivationBranding: Boolean,
        source: String,
        suppressActivationBranding: Boolean = false,
    ): Int {
        when (
            IdleControlPolicy.armDecision(
                hasActiveLiveLink = liveLinkRun != null,
                activeRunIsManagedStandby = liveLinkRun?.managedStandby == true,
                persistedEnabled = idleModeStore.isEnabled(),
                foregroundEstablished = idleForeground,
                visibleArmEligible = visibleArmEligible,
            )
        ) {
            IdleControlArmDecision.ARM -> Unit
            IdleControlArmDecision.ALREADY_ARMED -> {
                Log.i(TAG, "state=idle_control_enable result=already_armed")
                return START_STICKY
            }
            IdleControlArmDecision.REJECT_LIVE_COLLISION -> {
                Log.w(TAG, "state=idle_control_enable_failed reason=live_link_collision")
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }
        if (!idleModeStore.setEnabled(true, currentBootCount())) {
            Log.e(TAG, "state=idle_control_enable_failed reason=persistence_failed")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        visibleArmEligible = true
        val transition = foregroundLifecycle.onIdleEnabled()
        if (transition.showIdleForeground && !showForeground(ForegroundNotificationMode.CAMERA_STANDBY)) {
            visibleArmEligible = false
            idleModeStore.setEnabled(false)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!startLiveLinkTest(onTerminal = {}, managedStandby = true)) {
            visibleArmEligible = false
            idleModeStore.setEnabled(false)
            if (idleForeground) stopForeground(STOP_FOREGROUND_REMOVE)
            idleForeground = false
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        startedIdleEstablished = true
        activationBrandingPending = deferActivationBranding && !suppressActivationBranding
        if (!deferActivationBranding && !suppressActivationBranding) playActivationBranding()
        logIdleStatus("explicit_${source}_started_foreground")
        return START_STICKY
    }

    private fun resumeExplicitSameBootArm(startId: Int): Int {
        val authorized = IdleControlPolicy.mayResumeSameBoot(
            enabled = idleModeStore.isEnabled(),
            armedBootCount = idleModeStore.armedBootCount(),
            currentBootCount = currentBootCount(),
        )
        if (!authorized) {
            Log.w(TAG, "state=same_boot_recovery result=rejected reason=not_authorized")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        visibleArmEligible = true
        val result = enableIdleControl(
            startId = startId,
            deferActivationBranding = false,
            source = "same_boot_recovery",
            suppressActivationBranding = true,
        )
        Log.i(TAG, "state=same_boot_recovery result=started sensors=authenticated_session_only")
        return result
    }

    private fun handleLocalControl(
        command: RokidLocalControlCommand,
        observedMonotonicNs: Long,
        startId: Int,
    ): Int = when (command) {
        RokidLocalControlCommand.ENABLE_NODE ->
            enableIdleControlFromAccessibility(observedMonotonicNs, startId)
        RokidLocalControlCommand.DISABLE_NODE ->
            requestDisableFromAccessibility(observedMonotonicNs, startId)
        RokidLocalControlCommand.MICROPHONE_START_INTENT,
        RokidLocalControlCommand.MICROPHONE_STOP_INTENT,
        -> handleLocalMicrophoneIntent(command, startId)
    }

    private fun requestDisableFromAccessibility(
        observedMonotonicNs: Long,
        startId: Int,
    ): Int {
        val dispatch = liveLinkRun?.controller?.requestRokidGesture(
            RokidGestureOperation.ROKID_GESTURE_OPERATION_DISABLE_NODE,
            observedMonotonicNs,
        ) ?: RokidGestureDispatch.TRANSPORT_STOPPED
        Log.i(TAG, "state=rokid_gesture operation=disable_node dispatch=${dispatch.name.lowercase()}")
        if (dispatch == RokidGestureDispatch.QUEUED) {
            mainHandler.postDelayed(
                { if (idleModeStore.isEnabled()) disableIdleControl(onTerminal = {}) },
                LOCAL_DISABLE_FAILSAFE_MILLIS,
            )
            return START_STICKY
        }
        return disableIdleControlFromAccessibility(startId)
    }

    private fun acceptRokidNodeCommand(command: RokidNodeCommand): Boolean {
        val active = idleModeStore.isEnabled() && pendingServiceShutdown == null
        val accepted = when (command.operation) {
            RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_ACTIVATE_NODE -> active
            RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_SLEEP_NODE -> active
            RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_PLAY_BRAND_SEQUENCE -> active
            else -> false
        }
        if (!accepted) return false
        mainHandler.postDelayed(
            {
                when (command.operation) {
                    RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_ACTIVATE_NODE -> {
                        if (activationGesturePending && idleModeStore.isEnabled()) {
                            activationGesturePending = false
                            activationBrandingPending = false
                            playActivationBranding()
                        }
                    }
                    RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_SLEEP_NODE ->
                        disableIdleControl(onTerminal = {})
                    RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_PLAY_BRAND_SEQUENCE ->
                        brandedAudio.playDebugFullBrandTest(onTerminal = {})
                    else -> Unit
                }
            },
            NODE_COMMAND_ACK_GRACE_MILLIS,
        )
        Log.i(
            TAG,
            "state=rokid_node_command operation=${command.operation.name.lowercase()} " +
                "accepted_for_execution=true",
        )
        return true
    }

    private fun disableIdleControlFromAccessibility(startId: Int): Int {
        if (!idleModeStore.isEnabled()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!idleForeground) {
            val restoreResult = restoreIdleControl(IdleControlRestoreReason.STICKY_RESTART, startId)
            if (restoreResult != START_STICKY) return restoreResult
        }
        disableIdleControl(onTerminal = {})
        return START_NOT_STICKY
    }

    private fun handleLocalMicrophoneIntent(
        command: RokidLocalControlCommand,
        startId: Int,
    ): Int {
        val active = idleModeStore.isEnabled() && pendingServiceShutdown == null
        if (!active) {
            Log.i(
                TAG,
                "state=local_microphone_intent command=${command.name.lowercase()} " +
                    "node_active=false controller_available=false queued=false",
            )
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!idleForeground) {
            val restoreResult = restoreIdleControl(IdleControlRestoreReason.STICKY_RESTART, startId)
            if (restoreResult != START_STICKY) return restoreResult
        }
        val accepted = microphoneIntentHandler.handle(command)
        Log.i(
            TAG,
            "state=local_microphone_intent command=${command.name.lowercase()} " +
                "node_active=true controller_available=${liveLinkRun != null} queued=$accepted",
        )
        return START_STICKY
    }

    private fun playActivationBranding() {
        val bootCount = currentBootCount()
        val includeBootBrandLine = if (bootCount == null) {
            PROCESS_STARTUP_ANNOUNCEMENT_GATE.claim()
        } else {
            brandedAudioStateStore.claimBootBrandLine(bootCount)
        }
        val includeProductLine = brandedAudioStateStore.claimProductLine(System.currentTimeMillis())
        brandedAudio.onActivationReady(includeBootBrandLine, includeProductLine)
        Log.i(
            TAG,
            "state=activation_brand_audio ready=true boot_brand_line=$includeBootBrandLine " +
                "product_line=$includeProductLine boot_count=${bootCount ?: "unavailable"}",
        )
    }

    private fun restoreIdleControl(reason: IdleControlRestoreReason, startId: Int): Int {
        visibleArmEligible = false
        val decision = IdleControlPolicy.restore(idleModeStore.isEnabled(), reason)
        if (!decision.keepIdleService) {
            Log.i(TAG, "state=idle_restore_skipped reason=not_enabled source=${reason.name.lowercase()}")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        check(!decision.startCapture)
        val transition = foregroundLifecycle.onIdleEnabled()
        if (transition.showIdleForeground && !showForeground(ForegroundNotificationMode.IDLE)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        startedIdleEstablished = true
        Log.i(
            TAG,
            "state=idle_control_restored source=${reason.name.lowercase()} " +
                "capture_eligible=false capture=false camera=false imu=false microphone=false network=false",
        )
        return START_STICKY
    }

    private fun restoreIdleControlIfInternal(
        intent: Intent,
        reason: IdleControlRestoreReason,
        startId: Int,
    ): Int {
        @Suppress("DEPRECATION")
        val proof = intent.getParcelableExtra(EXTRA_INTERNAL_RESTORE_PROOF) as? PendingIntent
        if (proof == null || proof.creatorUid != applicationInfo.uid || proof.creatorPackage != packageName) {
            Log.w(TAG, "state=persistent_service_restore_rejected reason=invalid_internal_proof")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        return restoreIdleControl(reason, startId)
    }

    private fun showForeground(
        mode: ForegroundNotificationMode,
        confirmStartRequest: Boolean = false,
    ): Boolean = runCatching {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                IDLE_NOTIFICATION_CHANNEL_ID,
                getString(R.string.idle_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.idle_notification_text)
                setSound(null, null)
                enableVibration(false)
            },
        )
        val notification = Notification.Builder(this, IDLE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentTitle(getString(R.string.idle_notification_title))
            .setContentText(
                getString(
                    when (mode) {
                        ForegroundNotificationMode.IDLE -> R.string.idle_notification_text
                        ForegroundNotificationMode.CAMERA_STANDBY -> R.string.standby_notification_text
                        ForegroundNotificationMode.CAMERA_STARTING -> R.string.starting_notification_text
                        ForegroundNotificationMode.CAMERA_ACTIVE -> R.string.active_notification_text
                        ForegroundNotificationMode.CAMERA_MICROPHONE_ACTIVE ->
                            R.string.microphone_active_notification_text
                        ForegroundNotificationMode.CAMERA_STOPPING -> R.string.stopping_notification_text
                    },
                ),
            )
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val requestedTypeMask = when {
            mode == ForegroundNotificationMode.IDLE &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            mode == ForegroundNotificationMode.IDLE -> 0
            else -> {
                var sensorTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        0
                    }
                // The visible arm authorizes the foreground service type needed for a later,
                // explicitly requested microphone sublease. No AudioRecord is opened here.
                if (mode == ForegroundNotificationMode.CAMERA_STANDBY ||
                    mode == ForegroundNotificationMode.CAMERA_MICROPHONE_ACTIVE
                ) {
                    sensorTypes = sensorTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                sensorTypes
            }
        }
        val currentForegroundAlreadyCoversRequest = idleForeground &&
            (requestedTypeMask == 0 || activeForegroundTypeMask and requestedTypeMask == requestedTypeMask)
        if (currentForegroundAlreadyCoversRequest && !confirmStartRequest) {
            // On this YodaOS build, replacing the foreground notification through NotificationManager
            // detaches it from the Service, while repeating startForeground after the visible broker
            // exits is rejected. Preserve the accepted association instead; status remains available
            // through aggregate diagnostics and the notification retains its truthful standby text.
            return@runCatching true
        }
        val effectiveTypeMask = if (currentForegroundAlreadyCoversRequest ||
            (confirmStartRequest && activeForegroundTypeMask != 0)
        ) {
            activeForegroundTypeMask
        } else {
            requestedTypeMask
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && effectiveTypeMask != 0) {
            startForeground(IDLE_NOTIFICATION_ID, notification, effectiveTypeMask)
        } else {
            startForeground(IDLE_NOTIFICATION_ID, notification)
        }
        idleForeground = true
        if (!currentForegroundAlreadyCoversRequest && requestedTypeMask != 0) {
            activeForegroundTypeMask = requestedTypeMask
        }
        true
    }.getOrElse {
        Log.e(TAG, "state=foreground_transition_failed mode=${mode.name.lowercase()}")
        false
    }

    private fun disableIdleControl(onTerminal: () -> Unit) {
        pendingServiceShutdown?.let {
            it.callbacks += onTerminal
            Log.i(TAG, "state=service_shutdown result=already_pending")
            return
        }
        val persistenceSucceeded = idleModeStore.setEnabled(false)
        visibleArmEligible = false
        rendezvousGeneration.invalidate()
        cancelRendezvousAlarm()
        rendezvousWakeLease.release()
        val liveCaptureActive = liveLinkRun != null
        val transition = foregroundLifecycle.disable(persistenceSucceeded, liveCaptureActive)
        check(transition.stopSources)
        if (!persistenceSucceeded) {
            Log.e(
                TAG,
                "state=idle_control_disable persistence=failed current_service_will_stop restore_may_recur=true",
            )
        }
        val shutdown = PendingServiceShutdown(
            callbacks = mutableListOf(onTerminal),
            notBeforeMonotonicNanos = ElapsedRealtimeClock.nowNanos() +
                CUE_SERVICE_LIFETIME_MS * NANOS_PER_MILLISECOND,
        )
        pendingServiceShutdown = shutdown
        shutdown.watchdog = Runnable {
            if (pendingServiceShutdown !== shutdown) return@Runnable
            val watchdogTransition = foregroundLifecycle.onShutdownWatchdog()
            if (watchdogTransition.stopService) {
                Log.w(TAG, "state=service_shutdown reason=live_terminal_watchdog")
                scheduleServiceShutdownCompletion("watchdog")
            }
        }
        if (transition.requestLiveTerminal) {
            showForeground(ForegroundNotificationMode.CAMERA_STOPPING)
            mainHandler.postDelayed(shutdown.watchdog, LIVE_TERMINAL_WATCHDOG_MS)
            stopLiveLink(LiveLinkCaptureStopReason.USER_REQUESTED)
        }
        abandonPhysicalTrace()
        stopSensorInputs()
        diagnosticSession = null
        startedIdleEstablished = false
        activationBrandingPending = false
        activationGesturePending = false
        Log.i(
            TAG,
            "state=idle_control_disabled capture=false camera=false imu=false microphone=false network=false",
        )
        playControlConfirmation(enabled = false)
        if (transition.stopService) scheduleServiceShutdownCompletion("no_live_capture")
    }

    private fun scheduleServiceShutdownCompletion(reason: String) {
        val shutdown = pendingServiceShutdown ?: return
        if (shutdown.completionScheduled) return
        shutdown.completionScheduled = true
        mainHandler.removeCallbacks(shutdown.watchdog)
        val remainingNanos = (shutdown.notBeforeMonotonicNanos - ElapsedRealtimeClock.nowNanos())
            .coerceAtLeast(0L)
        mainHandler.postDelayed(
            {
                if (pendingServiceShutdown !== shutdown) return@postDelayed
                pendingServiceShutdown = null
                if (idleForeground) stopForeground(STOP_FOREGROUND_REMOVE)
                idleForeground = false
                Log.i(TAG, "state=service_shutdown_complete reason=$reason")
                shutdown.callbacks.forEach { it() }
                stopSelf()
            },
            remainingNanos / NANOS_PER_MILLISECOND,
        )
    }

    private fun logIdleStatus(source: String) {
        Log.i(
            TAG,
            "state=idle_control enabled=${idleModeStore.isEnabled()} foreground=$idleForeground " +
            "visible_arm_eligible=$visibleArmEligible capture_authority=poco_authenticated_session " +
            "source=$source rendezvous=${if (liveLinkRun == null) "inactive" else "active"} " +
                "wakeup_alarm=${currentRendezvousAlarmPrecision().name.lowercase()} microphone=false",
        )
    }

    private fun finishLiveLink(run: LiveLinkServiceRun, snapshot: LiveLinkCaptureSnapshot) {
        if (liveLinkRun !== run) return
        liveLinkRun = null
        mainHandler.removeCallbacks(run.poll)
        mainHandler.removeCallbacks(run.deadline)
        mainHandler.removeCallbacks(run.rendezvousDeadline)
        run.preAuthenticationDeadline.cancel()
        rendezvousWakeLease.release()
        Log.i(
            TAG,
            "state=live_link_complete result=${snapshot.stopReason?.name?.lowercase() ?: "unknown"} " +
                liveLinkAggregate(snapshot),
        )
        val decision = if (run.managedStandby) {
            RokidRendezvousPolicy.afterTerminal(
                snapshot.stopReason,
                snapshot.lastDisconnectReason,
                idleModeStore.isEnabled(),
                visibleArmEligible,
            )
        } else {
            RendezvousTerminalDecision.STOP
        }
        if (snapshot.sessionsReady > 0L) rendezvousBackoff.resetAfterAuthenticatedSession()
        if (decision == RendezvousTerminalDecision.FAIL_CLOSED) visibleArmEligible = false
        val terminalTransition = foregroundLifecycle.onLiveTerminal(idleModeStore.isEnabled())
        applyTerminalTransition(terminalTransition)
        mainHandler.post(run.onTerminal)
        when (decision) {
            RendezvousTerminalDecision.RETRY_IMMEDIATELY ->
                mainHandler.post { restartRendezvousAfterNormalRotation(run.generation) }
            RendezvousTerminalDecision.RETRY_AFTER_COOLDOWN ->
                scheduleRendezvousRetry(run.generation)
            RendezvousTerminalDecision.FAIL_CLOSED,
            RendezvousTerminalDecision.STOP,
            -> Unit
        }
    }

    private fun restartRendezvousAfterNormalRotation(completedGeneration: Long) {
        if (!rendezvousGeneration.isCurrent(completedGeneration) ||
            liveLinkRun != null || !idleModeStore.isEnabled() || !visibleArmEligible ||
            pendingServiceShutdown != null
        ) {
            return
        }
        if (startLiveLinkTest(onTerminal = {}, managedStandby = true)) {
            Log.i(TAG, "state=live_link_rotation result=immediate_rendezvous_started")
        } else if (rendezvousGeneration.isCurrent(completedGeneration)) {
            Log.w(TAG, "state=live_link_rotation result=immediate_rendezvous_failed")
            scheduleRendezvousRetry(completedGeneration)
        }
    }

    private fun scheduleRendezvousRetry(completedGeneration: Long) {
        cancelRendezvousAlarm()
        val delayMillis = rendezvousBackoff.nextDelayMillis()
        val alarmManager = getSystemService(AlarmManager::class.java)
        val precision = currentRendezvousAlarmPrecision(alarmManager)
        val alarmIntent = Intent(this, RokidRuntimeService::class.java)
            .setAction(ACTION_RENDEZVOUS_RETRY)
            .putExtra(EXTRA_RENDEZVOUS_GENERATION, completedGeneration)
            .putExtra(EXTRA_PROCESS_RENDEZVOUS_CAPABILITY, processRendezvousCapability)
        val alarm = PendingIntent.getService(
            this,
            RENDEZVOUS_ALARM_REQUEST_CODE,
            alarmIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAtMillis = if (Long.MAX_VALUE - SystemClock.elapsedRealtime() < delayMillis) {
            Long.MAX_VALUE
        } else {
            SystemClock.elapsedRealtime() + delayMillis
        }
        val scheduled = runCatching {
            when (precision) {
                RendezvousAlarmPrecision.EXACT_ALLOW_IDLE -> alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    alarm,
                )
                RendezvousAlarmPrecision.INEXACT_ALLOW_IDLE -> alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    alarm,
                )
            }
        }.isSuccess
        if (!scheduled) {
            alarm.cancel()
            visibleArmEligible = false
            Log.e(TAG, "state=live_link_standby_failed reason=wakeup_alarm_rejected")
            return
        }
        rendezvousAlarm = alarm
        Log.i(
            TAG,
            "state=live_link_standby cooldown_ms=$delayMillis sensors=off wakeup_alarm=" +
                precision.name.lowercase(),
        )
    }

    private fun resumeRendezvousFromAlarm(intent: Intent, startId: Int): Int {
        val processCapabilityMatches = intent.getLongExtra(
            EXTRA_PROCESS_RENDEZVOUS_CAPABILITY,
            Long.MIN_VALUE,
        ) == processRendezvousCapability
        val generationMatches = rendezvousGeneration.isCurrent(
            intent.getLongExtra(EXTRA_RENDEZVOUS_GENERATION, Long.MIN_VALUE),
        )
        if (RendezvousAlarmPolicy.shouldConsumeScheduledAlarm(processCapabilityMatches, generationMatches)) {
            rendezvousAlarm?.cancel()
            rendezvousAlarm = null
        }
        val decision = RendezvousAlarmPolicy.decide(
            liveEpochActive = liveLinkRun != null,
            processCapabilityMatches = processCapabilityMatches,
            generationMatches = generationMatches,
            idleEnabled = idleModeStore.isEnabled(),
            visibleArmEligible = visibleArmEligible,
            serviceStopping = pendingServiceShutdown != null,
        )
        return when (decision) {
            RendezvousAlarmDecision.IGNORE_ACTIVE_EPOCH -> {
                Log.i(TAG, "state=live_link_alarm_ignored reason=epoch_already_active")
                START_STICKY
            }
            RendezvousAlarmDecision.REJECT -> {
                Log.w(TAG, "state=live_link_alarm_rejected reason=stale_or_ineligible")
                if (!idleForeground && liveLinkRun == null && pendingServiceShutdown == null) {
                    stopSelfResult(startId)
                }
                START_NOT_STICKY
            }
            RendezvousAlarmDecision.START_EPOCH -> {
                if (!startLiveLinkTest(onTerminal = {}, managedStandby = true)) {
                    visibleArmEligible = false
                    Log.e(TAG, "state=live_link_alarm_failed reason=rendezvous_start_rejected")
                }
                START_STICKY
            }
        }
    }

    private fun cancelRendezvousAlarm() {
        val alarm = rendezvousAlarm ?: return
        runCatching { getSystemService(AlarmManager::class.java).cancel(alarm) }
        alarm.cancel()
        rendezvousAlarm = null
    }

    private fun currentRendezvousAlarmPrecision(
        alarmManager: AlarmManager = getSystemService(AlarmManager::class.java),
    ): RendezvousAlarmPrecision {
        val exactAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        return RendezvousAlarmPolicy.precision(Build.VERSION.SDK_INT, exactAccess)
    }

    private fun applyTerminalTransition(transition: IdleLifecycleTransition) {
        when {
            transition.showIdleForeground -> {
                val idleMode = if (visibleArmEligible) {
                    ForegroundNotificationMode.CAMERA_STANDBY
                } else {
                    ForegroundNotificationMode.IDLE
                }
                if (!showForeground(idleMode)) {
                    if (idleForeground) stopForeground(STOP_FOREGROUND_REMOVE)
                    idleForeground = false
                    stopSelf()
                }
            }
            transition.stopService && pendingServiceShutdown != null -> scheduleServiceShutdownCompletion("live_terminal")
            transition.stopService -> {
                if (idleForeground) stopForeground(STOP_FOREGROUND_REMOVE)
                idleForeground = false
                stopSelf()
            }
        }
    }

    private fun logLiveLinkStatus(snapshot: LiveLinkCaptureSnapshot) {
        Log.i(
            TAG,
            "state=live_link status=${snapshot.state.name.lowercase()} " +
                "last_disconnect=${snapshot.lastDisconnectReason?.name?.lowercase() ?: "none"} " +
                "last_link_diagnostic=${snapshot.lastDiagnosticCode?.name?.lowercase() ?: "none"} " +
                liveLinkAggregate(snapshot),
        )
    }

    private fun handleLiveMicrophoneState(state: LiveMicrophoneCaptureState) {
        Log.i(
            TAG,
            "state=live_microphone status=${state.name.lowercase()} " +
                "raw_audio_logged=false persisted=false",
        )
        when (state) {
            LiveMicrophoneCaptureState.STARTED -> if (!liveMicrophoneCueActive) {
                liveMicrophoneCueActive = true
                playMicrophoneConfirmation(active = true)
            }
            LiveMicrophoneCaptureState.STOPPED,
            LiveMicrophoneCaptureState.SOURCE_FAILURE,
            -> {
                restoreForegroundAfterLiveMicrophone()
                if (liveMicrophoneCueActive) {
                    liveMicrophoneCueActive = false
                    playMicrophoneConfirmation(active = false)
                }
            }
            LiveMicrophoneCaptureState.REJECTED_PERMISSION,
            LiveMicrophoneCaptureState.REJECTED_STATE,
            -> Unit
        }
    }

    private fun restoreForegroundAfterLiveMicrophone() {
        val mode = when (liveLinkRun?.controller?.snapshot()?.state) {
            org.conceptflow.mpl.rokid.core.LiveLinkCaptureState.STREAMING ->
                ForegroundNotificationMode.CAMERA_ACTIVE
            org.conceptflow.mpl.rokid.core.LiveLinkCaptureState.CONNECTING ->
                ForegroundNotificationMode.CAMERA_STANDBY
            org.conceptflow.mpl.rokid.core.LiveLinkCaptureState.STOPPED ->
                ForegroundNotificationMode.CAMERA_STOPPING
            null -> return
        }
        showForeground(mode)
    }

    private fun playMicrophoneConfirmation(active: Boolean) {
        val id = cueIds.incrementAndGet()
        val cue = PerceptionCue.newBuilder()
            .setCueId("microphone-control-$id")
            .setFrameId(id)
            .setCreatedMonotonicTimestampNs(ElapsedRealtimeClock.nowNanos())
            .setTtlMs(750)
            .setDescription(if (active) "Microphone started" else "Microphone stopped")
            .setConfidence(1.0)
            .setPriority(2)
            .setDirection(Direction.DIRECTION_AHEAD)
            .setEarcon(
                Earcon.newBuilder()
                    .setEarconId("microphone-control")
                    .setGain(0.4f)
                    .setPitch(if (active) 1.1f else 0.9f),
            )
            .build()
        transport.deliver(CueEnvelope("local-session", "microphone-control", cue))
    }

    private fun liveLinkAggregate(snapshot: LiveLinkCaptureSnapshot): String =
        "sessions=${snapshot.sessionsReady} disconnects=${snapshot.disconnects} " +
            "producer_starts=${snapshot.producerStarts} camera_restarts=${snapshot.cameraSourceRestarts} " +
            "camera_error_domain=" +
            "${snapshot.lastCameraSourceDiagnostic?.domain?.diagnosticLabel ?: "none"} " +
            "camera_error_code=${snapshot.lastCameraSourceDiagnostic?.numericCode ?: "none"} " +
            "camera_error_symbol=${snapshot.lastCameraSourceDiagnostic?.symbolicCode ?: "none"} " +
            "camera_error_recoverable=${snapshot.lastCameraSourceDiagnostic?.recoverable ?: false} " +
            "camera_observed=${snapshot.cameraFramesObserved} " +
            "camera_queued=${snapshot.cameraFramesQueued} camera_dropped=${snapshot.cameraFramesDropped} " +
            "camera_chunks=${snapshot.cameraChunksQueued} imu_observed=${snapshot.imuSamplesObserved} " +
            "imu_batches=${snapshot.imuBatchesQueued} imu_queued=${snapshot.imuSamplesQueued} " +
            "imu_dropped=${snapshot.imuBatchesDropped} " +
            "microphone_starts=${snapshot.microphoneStarts} " +
            "microphone_observed=${snapshot.microphoneChunksObserved} " +
            "microphone_queued=${snapshot.microphoneChunksQueued} " +
            "microphone_dropped=${snapshot.microphoneChunksDropped} " +
            "touch_observed=${snapshot.touchEventsObserved} " +
            "touch_queued=${snapshot.touchEventsQueued} " +
            "touch_overflow=${snapshot.touchEventsOverflowed} " +
            "camera_request_to_image_p50_ns=${snapshot.cameraSourceTiming.requestToImage.p50Nanos} " +
            "camera_request_to_image_p95_ns=${snapshot.cameraSourceTiming.requestToImage.p95Nanos} " +
            "camera_request_to_image_p99_ns=${snapshot.cameraSourceTiming.requestToImage.p99Nanos} " +
            "camera_acquisition_p95_ns=${snapshot.cameraSourceTiming.imageAcquisition.p95Nanos} " +
            "camera_gate_resize_p50_ns=${snapshot.cameraSourceTiming.gateAndResizeProcessor.p50Nanos} " +
            "camera_gate_resize_p95_ns=${snapshot.cameraSourceTiming.gateAndResizeProcessor.p95Nanos} " +
            "camera_gate_resize_p99_ns=${snapshot.cameraSourceTiming.gateAndResizeProcessor.p99Nanos} " +
            "camera_listener_p95_ns=${snapshot.cameraSourceTiming.listenerPath.p95Nanos} " +
            "camera_requests=${snapshot.cameraSourceTiming.pipeline.requestsSubmitted} " +
            "camera_request_backpressure=${snapshot.cameraSourceTiming.pipeline.opportunitiesBackpressured} " +
            "camera_requests_superseded=${snapshot.cameraSourceTiming.pipeline.requestsSuperseded} " +
            "camera_transform_last_generation_completed=${snapshot.cameraTransform.completed} " +
            "camera_transform_last_generation_replaced=${snapshot.cameraTransform.replacedBeforeTransform} " +
            "camera_transform_last_generation_p50_ns=${snapshot.cameraTransform.latency.p50Nanos} " +
            "camera_transform_last_generation_p95_ns=${snapshot.cameraTransform.latency.p95Nanos} " +
            "camera_transform_last_generation_p99_ns=${snapshot.cameraTransform.latency.p99Nanos} " +
            legacySpoolAggregate(snapshot) +
            "close_attempted=${snapshot.closeEvidence.clientCloseAttempted} " +
            "close_request_written=${snapshot.closeEvidence.clientCloseRequestWritten} " +
            "close_writers_drained=${snapshot.closeEvidence.clientWritersDrained} " +
            "close_ack_received=${snapshot.closeEvidence.clientAcknowledgementReceived} " +
            "close_request_failure=${snapshot.closeEvidence.clientRequestFailure.name.lowercase()}"

    private fun legacySpoolAggregate(snapshot: LiveLinkCaptureSnapshot): String {
        val metrics = snapshot.legacySpoolMetrics ?: return "handoff=memory "
        return "handoff=legacy_spool spool_camera=${metrics.cameraRecords} " +
            "spool_imu=${metrics.imuRecords} spool_microphone=${metrics.microphoneRecords} " +
            "spool_artifact_bytes=${metrics.artifactBytesWritten} " +
            "spool_manifest_writes=${metrics.manifestWrites} " +
            "spool_manifest_bytes=${metrics.manifestBytesWritten} " +
            "spool_state_bytes=${metrics.recoveryStateBytesWritten} " +
            "spool_camera_transform_p95_ns=${metrics.cameraTransform.p95 ?: -1L} " +
            "spool_camera_store_p95_ns=${metrics.cameraStore.p95 ?: -1L} " +
            "spool_imu_store_p95_ns=${metrics.imuStore.p95 ?: -1L} " +
            "spool_manifest_p95_ns=${metrics.manifestPersist.p95 ?: -1L} "
    }

    private fun finishStreamTest(
        diagnostic: StreamDiagnosticSession,
        transmission: PowerAwareTransmissionCounters,
        imuGate: ImuTransmissionGate,
        onTerminal: () -> Unit,
    ) {
        if (diagnosticSession !== diagnostic) return
        stopSensorInputs()
        val snapshot = diagnostic.finish(ElapsedRealtimeClock.nowNanos())
        val imuStatistics = imuGate.statistics()
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
                "camera_luma_max=${snapshot.cameraMaximumMeanLuma.formatOneDecimal()} " +
                "camera_dark_fraction_min=${snapshot.cameraMinimumDarkFraction.formatThreeDecimals()} " +
                "camera_focus_max=${snapshot.cameraMaximumLaplacianVariance.formatOneDecimal()} " +
                "camera_motion_max=${snapshot.cameraMaximumMotionScore.formatThreeDecimals()} " +
                "camera_capture_session_ready=${snapshot.cameraCaptureSessionReady} " +
                "camera_capture_startup_ms=${snapshot.cameraCaptureSessionStartupMillis.formatOneDecimal()} " +
                "camera_timing_samples=${snapshot.cameraTimingSamples} " +
                "camera_timing_retained=${snapshot.cameraTimingRetainedSamples} " +
                "camera_timing_rejected=${snapshot.cameraTimingRejectedEvents} " +
                "camera_request_timing_samples=${snapshot.cameraRequestTimingSamples} " +
                "camera_request_timing_retained=${snapshot.cameraRequestTimingRetainedSamples} " +
                "camera_analyzed_active_ms=${snapshot.cameraAnalyzedActiveMillis.formatOneDecimal()} " +
                "camera_analyzed_fps=${snapshot.cameraAnalyzedObservedFramesPerSecond.formatThreeDecimals()} " +
                "camera_emitted_timing_samples=${snapshot.cameraEmittedTimingSamples} " +
                "camera_emitted_active_ms=${snapshot.cameraEmittedActiveMillis.formatOneDecimal()} " +
                "camera_emitted_fps=${snapshot.cameraEmittedObservedFramesPerSecond.formatThreeDecimals()} " +
                "camera_request_image_ms_p50=${snapshot.cameraRequestToImageP50Millis.formatOneDecimal()} " +
                "camera_request_image_ms_p95=${snapshot.cameraRequestToImageP95Millis.formatOneDecimal()} " +
                "camera_request_image_ms_max=${snapshot.cameraRequestToImageMaximumMillis.formatOneDecimal()} " +
                "camera_image_acquire_ms_p50=${snapshot.cameraImageAcquisitionP50Millis.formatOneDecimal()} " +
                "camera_image_acquire_ms_p95=${snapshot.cameraImageAcquisitionP95Millis.formatOneDecimal()} " +
                "camera_image_acquire_ms_max=${snapshot.cameraImageAcquisitionMaximumMillis.formatOneDecimal()} " +
                "camera_processor_ms_p50=${snapshot.cameraProcessorP50Millis.formatOneDecimal()} " +
                "camera_processor_ms_p95=${snapshot.cameraProcessorP95Millis.formatOneDecimal()} " +
                "camera_processor_ms_max=${snapshot.cameraProcessorMaximumMillis.formatOneDecimal()} " +
                "camera_rgb_conversions=${snapshot.cameraRgbConversions} " +
                "camera_native_rgb_conversions=${snapshot.cameraNativeRgbConversions} " +
                "camera_listener_path_ms_p50=${snapshot.cameraListenerPathP50Millis.formatOneDecimal()} " +
                "camera_listener_path_ms_p95=${snapshot.cameraListenerPathP95Millis.formatOneDecimal()} " +
                "camera_listener_path_ms_max=${snapshot.cameraListenerPathMaximumMillis.formatOneDecimal()} " +
                "camera_requests_submitted=${snapshot.cameraRequestsSubmitted} " +
                "camera_opportunities_backpressured=${snapshot.cameraOpportunitiesBackpressured} " +
                "camera_requests_superseded=${snapshot.cameraRequestsSuperseded} " +
                "camera_images_unmatched=${snapshot.cameraImagesWithoutExactRequestMatch} " +
                "camera_capture_failures=${snapshot.cameraCaptureFailures} " +
                "camera_late_callbacks=${snapshot.cameraLateCallbacks} " +
                "camera_outstanding=${snapshot.cameraOutstandingRequests} " +
                "camera_max_outstanding=${snapshot.cameraMaximumOutstandingRequests} " +
                "imu_samples=${snapshot.imuSamples} imu_signal_samples=${snapshot.imuSignalSamples} " +
                "imu_observed_hz=${snapshot.imuObservedSamplesPerSecond.formatOneDecimal()} " +
                "imu_max_gap_ms=${snapshot.imuMaximumGapMillis.formatOneDecimal()} " +
                "microphone_chunks=${snapshot.microphoneChunks} " +
                "microphone_bytes=${snapshot.microphoneBytes} " +
                "microphone_nonzero_samples=${snapshot.microphoneNonZeroSamples} " +
                "microphone_peak_absolute=${snapshot.microphonePeakAbsolute} " +
                "camera_tx_packets=${transmission.cameraPackets.get()} " +
                "camera_tx_payload_bytes=${transmission.cameraPayloadBytes.get()} " +
                "imu_tx_selected=${imuStatistics.accepted} " +
                "imu_tx_suppressed=${imuStatistics.duplicatesSuppressed} " +
                "imu_tx_batches=${transmission.imuBatches.get()} " +
                "imu_tx_samples=${transmission.imuSamples.get()} " +
                "microphone_tx_packets=${transmission.microphonePackets.get()} " +
                "microphone_tx_payload_bytes=${transmission.microphonePayloadBytes.get()} " +
                "cold_start_duration_ms=${snapshot.durationMillis}",
        )
        mainHandler.post(onTerminal)
    }

    private fun recordImuTransmission(
        packetizer: SensorStreamPacketizer,
        lease: ActiveStreamLease,
        batch: ImuTransmissionBatch,
        counters: PowerAwareTransmissionCounters,
    ) {
        packetizer.imu(lease, batch)?.let {
            counters.imuBatches.incrementAndGet()
            counters.imuSamples.addAndGet(it.imuBatch.samplesCount.toLong())
        }
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
        if (hasActiveInputs()) {
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
            override fun onCameraCalibrationCapability(state: CameraCalibrationCapabilityState) {
                Log.i(TAG, "state=physical_trace stream=camera intrinsics=${state.diagnosticLabel}")
            }

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
                "camera_luma_max=${snapshot.cameraMaximumMeanLuma.formatOneDecimal()} " +
                "camera_dark_fraction_min=${snapshot.cameraMinimumDarkFraction.formatThreeDecimals()} " +
                "camera_focus_max=${snapshot.cameraMaximumLaplacianVariance.formatOneDecimal()} " +
                "camera_motion_max=${snapshot.cameraMaximumMotionScore.formatThreeDecimals()} " +
                "imu_samples=${snapshot.imuSamples} imu_signal_samples=${snapshot.imuSignalSamples} " +
                "imu_observed_hz=${snapshot.imuObservedSamplesPerSecond.formatOneDecimal()} " +
                "imu_max_gap_ms=${snapshot.imuMaximumGapMillis.formatOneDecimal()} " +
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

    private fun hasActiveInputs(): Boolean =
        frameSources.hasActiveSource || poseSource?.isRunning == true || microphoneSource?.isRunning == true ||
            diagnosticSession != null || hasActivePhysicalTrace() || liveLinkRun != null

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

    private fun playControlConfirmation(enabled: Boolean) {
        val id = cueIds.incrementAndGet()
        val direction = if (enabled) Direction.DIRECTION_LEFT else Direction.DIRECTION_RIGHT
        val cue = PerceptionCue.newBuilder()
            .setCueId("idle-control-$id")
            .setFrameId(id)
            .setCreatedMonotonicTimestampNs(ElapsedRealtimeClock.nowNanos())
            .setTtlMs(1_000)
            .setDescription(if (enabled) "Control enabled" else "Control stopped")
            .setConfidence(1.0)
            .setPriority(1)
            .setDirection(direction)
            .setEarcon(
                Earcon.newBuilder()
                    .setEarconId("idle-control")
                    .setGain(0.45f)
                    .setPitch(if (enabled) 1.15f else 0.82f),
            )
            .build()
        val event = transport.deliver(CueEnvelope("local-session", "idle-control", cue))
        Log.i(
            TAG,
            "state=idle_control_confirmation mode=${if (enabled) "enabled" else "stopped"} " +
                "disposition=${event?.disposition?.name ?: "UNAVAILABLE"}",
        )
    }

    inner class RuntimeBinder : Binder() {
        fun execute(command: RuntimeCommand, onTerminal: () -> Unit) {
            when (command) {
                RuntimeCommand.START_CAPTURE -> startCapture(onTerminal)
                RuntimeCommand.START_STREAM_TEST -> startStreamTest(onTerminal)
                RuntimeCommand.INITIALIZE_LIVE_IDENTITY -> initializeLiveIdentity(onTerminal)
                RuntimeCommand.START_LIVE_LINK_TEST -> {
                    visibleArmEligible = true
                    startLiveLinkTest(onTerminal)
                }
                RuntimeCommand.START_LIVE_LINK_SOAK_TEST -> {
                    visibleArmEligible = true
                    startLiveLinkTest(
                        onTerminal,
                        runDurationMillis = LiveLinkCaptureController.SOAK_RUN_DURATION_MILLIS,
                    )
                }
                RuntimeCommand.STOP_LIVE_LINK_TEST -> {
                    if (!stopLiveLink(LiveLinkCaptureStopReason.USER_REQUESTED)) mainHandler.post(onTerminal)
                }
                RuntimeCommand.START_PHYSICAL_TRACE -> startPhysicalTrace(onTerminal)
                RuntimeCommand.PLAY_LEFT_CUE -> playCue(Direction.DIRECTION_LEFT, onTerminal)
                RuntimeCommand.PLAY_RIGHT_CUE -> playCue(Direction.DIRECTION_RIGHT, onTerminal)
                RuntimeCommand.PLAY_FULL_BRAND_TEST -> {
                    val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                    if (debuggable) {
                        brandedAudio.playDebugFullBrandTest(onTerminal)
                    } else {
                        Log.w(TAG, "state=brand_audio_test_rejected reason=debug_only_command")
                        mainHandler.post(onTerminal)
                    }
                }
                RuntimeCommand.ENABLE_VALIDATED_GESTURE_COMMANDS -> {
                    val persisted = inputCommandGateStore.setEnabled(true)
                    Log.i(TAG, "state=input_command_gate enabled=$persisted")
                    mainHandler.post(onTerminal)
                }
                RuntimeCommand.DISABLE_GESTURE_COMMANDS -> {
                    val persisted = inputCommandGateStore.setEnabled(false)
                    Log.i(TAG, "state=input_command_gate enabled=false persisted=$persisted")
                    mainHandler.post(onTerminal)
                }
                RuntimeCommand.ENABLE_LEGACY_SENSOR_SPOOL -> {
                    val persisted = legacySensorSpoolStore.setEnabled(true)
                    Log.i(TAG, "state=legacy_sensor_spool enabled=$persisted applies_to=next_session")
                    mainHandler.post(onTerminal)
                }
                RuntimeCommand.DISABLE_LEGACY_SENSOR_SPOOL -> {
                    val persisted = legacySensorSpoolStore.setEnabled(false)
                    Log.i(TAG, "state=legacy_sensor_spool enabled=false persisted=$persisted applies_to=next_session")
                    mainHandler.post(onTerminal)
                }
                RuntimeCommand.ENABLE_IDLE_CONTROL -> mainHandler.post(onTerminal)
                RuntimeCommand.RECOVER_SAME_BOOT -> mainHandler.post(onTerminal)
                RuntimeCommand.DISABLE_IDLE_CONTROL -> disableIdleControl(onTerminal)
                RuntimeCommand.STOP -> disableIdleControl(onTerminal)
            }
        }

        fun isIdleControlEnabled(): Boolean = idleModeStore.isEnabled()

        fun isIdleControlArmed(): Boolean =
            IdleControlPolicy.isArmed(
                startedIdleEstablished = startedIdleEstablished,
                foregroundEstablished = idleForeground,
                persistedEnabled = idleModeStore.isEnabled(),
                visibleArmEligible = visibleArmEligible,
                serviceStopping = pendingServiceShutdown != null,
            )

        fun completeIdleControlEnable(): Boolean {
            if (!isIdleControlArmed()) return false
            if (activationBrandingPending) {
                activationBrandingPending = false
                playActivationBranding()
            }
            logIdleStatus("enable_complete")
            return true
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

    private fun Double.formatOneDecimal(): String = "%.1f".format(java.util.Locale.ROOT, this)

    private fun Double.formatThreeDecimals(): String = "%.3f".format(java.util.Locale.ROOT, this)

    companion object {
        private const val TAG = "ConceptFlowRokid"
        private const val CUE_SERVICE_LIFETIME_MS = 1_500L
        private const val STREAM_TEST_DURATION_MS = 8_000L
        private const val STREAM_TEST_MICROPHONE_DURATION_MS = 2_000L
        private const val IMU_GATE_POLL_MILLIS = 20L
        private const val ADB_DIAGNOSTIC_PEER = "authorized-adb-shell"
        private const val PHYSICAL_TRACE_TIMEOUT_MS = 8_000L
        private const val PHYSICAL_TRACE_RPC_DEADLINE_MS = 2_000L
        private const val PHYSICAL_TRACE_PORT = 50_051
        private const val LIVE_LINK_IDENTITY_ALIAS = "org.conceptflow.mpl.rokid.live-link.v1"
        private const val IDLE_NOTIFICATION_CHANNEL_ID = "rokid_idle_control"
        private const val IDLE_NOTIFICATION_ID = 1107
        private const val LIVE_TERMINAL_WATCHDOG_MS = 12_000L
        private const val MAX_RENDEZVOUS_CONNECT_TIMEOUT_MS = 5_000
        private const val RENDEZVOUS_ALARM_REQUEST_CODE = 1108
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_REASON_LENGTH = 80
        private const val LOCAL_DISABLE_FAILSAFE_MILLIS = 750L
        private const val NODE_COMMAND_ACK_GRACE_MILLIS = 200L
        private val PROCESS_STARTUP_ANNOUNCEMENT_GATE = ProcessStartupAnnouncementGate()

        const val ACTION_ENABLE_IDLE_CONTROL = "org.conceptflow.mpl.rokid.action.ENABLE_IDLE_CONTROL"
        const val ACTION_RESTORE_AFTER_BOOT = "org.conceptflow.mpl.rokid.internal.RESTORE_AFTER_BOOT"
        const val ACTION_RESTORE_AFTER_PACKAGE_REPLACED =
            "org.conceptflow.mpl.rokid.internal.RESTORE_AFTER_PACKAGE_REPLACED"
        const val ACTION_RENDEZVOUS_RETRY =
            "org.conceptflow.mpl.rokid.internal.RENDEZVOUS_RETRY"
        const val ACTION_RECOVER_SAME_BOOT =
            "org.conceptflow.mpl.rokid.internal.RECOVER_SAME_BOOT"
        const val ACTION_RESTORE_PROOF = "org.conceptflow.mpl.rokid.internal.RESTORE_PROOF"
        const val EXTRA_INTERNAL_RESTORE_PROOF = "org.conceptflow.mpl.rokid.extra.INTERNAL_RESTORE_PROOF"
        const val EXTRA_GESTURE_OBSERVED_MONOTONIC_NS =
            "org.conceptflow.mpl.rokid.extra.GESTURE_OBSERVED_MONOTONIC_NS"
        private const val EXTRA_RENDEZVOUS_GENERATION =
            "org.conceptflow.mpl.rokid.extra.RENDEZVOUS_GENERATION"
        private const val EXTRA_PROCESS_RENDEZVOUS_CAPABILITY =
            "org.conceptflow.mpl.rokid.extra.PROCESS_RENDEZVOUS_CAPABILITY"
    }
}

private class PowerAwareTransmissionCounters {
    val cameraPackets = AtomicLong(0L)
    val cameraPayloadBytes = AtomicLong(0L)
    val imuBatches = AtomicLong(0L)
    val imuSamples = AtomicLong(0L)
    val microphonePackets = AtomicLong(0L)
    val microphonePayloadBytes = AtomicLong(0L)
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

private class LiveLinkServiceRun(
    val controller: LiveLinkCaptureController,
    val onTerminal: () -> Unit,
    val generation: Long,
    val managedStandby: Boolean,
) {
    val preAuthenticationDeadline = PreAuthenticationRendezvousDeadlineGate()
    val authenticatedToneGate = AuthenticatedSessionToneGate()
    lateinit var poll: Runnable
    lateinit var deadline: Runnable
    lateinit var rendezvousDeadline: Runnable
}

private class PendingServiceShutdown(
    val callbacks: MutableList<() -> Unit>,
    val notBeforeMonotonicNanos: Long,
) {
    lateinit var watchdog: Runnable
    var completionScheduled: Boolean = false
}

private enum class ForegroundNotificationMode {
    IDLE,
    CAMERA_STANDBY,
    CAMERA_STARTING,
    CAMERA_ACTIVE,
    CAMERA_MICROPHONE_ACTIVE,
    CAMERA_STOPPING,
}
