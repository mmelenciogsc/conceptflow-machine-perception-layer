// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.protobuf.ByteString
import org.conceptflow.mpl.host.core.AndroidCapabilityDetector
import org.conceptflow.mpl.host.core.CancellationHandle
import org.conceptflow.mpl.host.core.CorrelationResult
import org.conceptflow.mpl.host.core.CueModalityPolicy
import org.conceptflow.mpl.host.core.CueScheduler
import org.conceptflow.mpl.host.core.CueSchedulingPolicy
import org.conceptflow.mpl.host.core.ElapsedHostClock
import org.conceptflow.mpl.host.core.InProcessCueDispatchTransport
import org.conceptflow.mpl.host.core.InProcessHostTransport
import org.conceptflow.mpl.host.core.ResultCorrelator
import org.conceptflow.mpl.host.core.RuntimeCapabilities
import org.conceptflow.mpl.host.core.SessionEvent
import org.conceptflow.mpl.host.core.SessionState
import org.conceptflow.mpl.host.core.SessionStateMachine
import org.conceptflow.mpl.host.core.TransportCallback
import org.conceptflow.mpl.host.feedback.AccessibilityAwareSpeechFeedback
import org.conceptflow.mpl.host.feedback.HostCueDispatcher
import org.conceptflow.mpl.host.feedback.PlatformHostAudioFeedback
import org.conceptflow.mpl.host.feedback.PlatformHostHapticFeedback
import org.conceptflow.mpl.host.focus.SpatialFocusAccessibilityFormatter
import org.conceptflow.mpl.host.focus.SpatialFocusAnnouncementPolicy
import org.conceptflow.mpl.host.focus.SpatialFocusCommand
import org.conceptflow.mpl.host.focus.SpatialFocusState
import org.conceptflow.mpl.host.vision.AcceleratorTarget
import org.conceptflow.mpl.host.vision.AndroidGnssEnvironmentSource
import org.conceptflow.mpl.host.vision.BoundedMetricDepthCalibrationStore
import org.conceptflow.mpl.host.vision.BviClassCatalog
import org.conceptflow.mpl.host.vision.CameraIntrinsics
import org.conceptflow.mpl.host.vision.DepthEnvironment
import org.conceptflow.mpl.host.vision.DepthModelRoutingRequest
import org.conceptflow.mpl.host.vision.DepthStageResult
import org.conceptflow.mpl.host.vision.EnvironmentDepthCoordinator
import org.conceptflow.mpl.host.vision.EnvironmentAwareMachineVisionPipeline
import org.conceptflow.mpl.host.vision.EnvironmentSelectionMode
import org.conceptflow.mpl.host.vision.GnssAcquisitionState
import org.conceptflow.mpl.host.vision.GnssQualitySample
import org.conceptflow.mpl.host.vision.GuidedCalibrationSample
import org.conceptflow.mpl.host.vision.InstanceMaskGeometry
import org.conceptflow.mpl.host.vision.MetricDepthCalibrationBinding
import org.conceptflow.mpl.host.vision.MachineVisionModelProfile
import org.conceptflow.mpl.host.vision.MachineVisionModelProfiles
import org.conceptflow.mpl.host.vision.PrivateModelBundleVerifier
import org.conceptflow.mpl.host.vision.QualcommAcceleratorPlanner
import org.conceptflow.mpl.host.vision.QualcommRuntimeEvidence
import org.conceptflow.mpl.host.vision.ReferenceDistance
import org.conceptflow.mpl.host.vision.RelativeDepthRepresentation
import org.conceptflow.mpl.host.vision.SceneSemanticDetection
import org.conceptflow.mpl.host.vision.SegmentationStageResult
import org.conceptflow.mpl.host.vision.SegmentedObject
import org.conceptflow.mpl.host.vision.StagedMachineVisionInferenceAdapter
import org.conceptflow.mpl.host.vision.TemporalEnvironmentAwareMachineVisionPipeline
import org.conceptflow.mpl.host.vision.TemporalMotionEvidence
import org.conceptflow.mpl.host.vision.TimestampedPose
import org.conceptflow.mpl.host.vision.TwoAnchorMetricDepthCalibrator
import org.conceptflow.mpl.host.vision.UnitQuaternion
import org.conceptflow.mpl.host.vision.VisualKeyframeSignal
import org.conceptflow.mpl.host.vision.VisionFrame
import org.conceptflow.mpl.protocol.SyntheticImageFixtures
import org.conceptflow.mpl.transport.LiveLinkEndpointRole
import org.conceptflow.mpl.transport.LiveLinkNetworkTopology
import org.conceptflow.mpl.transport.LiveLinkProvisioningStore
import org.conceptflow.mpl.v1.CapabilitySet
import org.conceptflow.mpl.v1.CueCategory
import org.conceptflow.mpl.v1.CueModality
import org.conceptflow.mpl.v1.Direction
import org.conceptflow.mpl.v1.Earcon
import org.conceptflow.mpl.v1.EphemeralIdentity
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.Haptic
import org.conceptflow.mpl.v1.HapticPattern
import org.conceptflow.mpl.v1.ImageDescriptor
import org.conceptflow.mpl.v1.ImageEncoding
import org.conceptflow.mpl.v1.NegotiateRequest
import org.conceptflow.mpl.v1.NegotiateResponse
import org.conceptflow.mpl.v1.PerceptionCue
import org.conceptflow.mpl.v1.PerceptionResult
import org.conceptflow.mpl.v1.ProtocolVersion
import org.conceptflow.mpl.v1.Speech
import org.conceptflow.mpl.v1.Urgency
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

open class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var capabilityView: TextView
    private lateinit var machineVisionView: TextView
    private lateinit var liveMachineVisionView: TextView
    private lateinit var environmentStatusView: TextView
    private lateinit var cueStatusView: TextView
    private lateinit var spatialFocusStatusView: TextView
    private val spatialFocusAnnouncementPolicy = SpatialFocusAnnouncementPolicy()
    private lateinit var liveMicrophoneButton: Button
    private lateinit var playRokidBrandButton: Button
    private lateinit var automaticEnvironmentButton: Button
    private lateinit var indoorEnvironmentButton: Button
    private lateinit var outdoorEnvironmentButton: Button
    private val clock = ElapsedHostClock
    private val session = SessionStateMachine(clock)
    private val correlator = ResultCorrelator(clock)
    private lateinit var scheduler: CueScheduler
    private val frameIds = AtomicLong(0L)
    private var activeRequestId: String? = null
    private var activeOperation: CancellationHandle? = null
    private lateinit var audio: PlatformHostAudioFeedback
    private lateinit var haptics: PlatformHostHapticFeedback
    private lateinit var speech: AccessibilityAwareSpeechFeedback
    private lateinit var cueDispatch: InProcessCueDispatchTransport
    private lateinit var negotiatedCapabilities: CapabilitySet
    private val environmentCoordinator = EnvironmentDepthCoordinator()
    private lateinit var gnssEnvironmentSource: AndroidGnssEnvironmentSource
    private var environmentMode = EnvironmentSelectionMode.AUTOMATIC
    private var lastGnssEvidenceNanos: Long? = null
    private val liveNodeStatusObserver: (LiveMachineVisionStatus?) -> Unit = { liveStatus ->
        runOnUiThread {
            liveMachineVisionView.text = liveStatus?.accessibleSummary()
                ?: getString(R.string.live_machine_vision_idle)
            updateLiveMicrophoneControl(liveStatus)
            updateLiveNodeControl(liveStatus)
            showMachineVisionReadiness(liveStatus)
        }
    }
    private val spatialFocusObserver: (SpatialFocusState?) -> Unit = { focus ->
        runOnUiThread { showSpatialFocus(focus) }
    }

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startGnssEvidenceBurst(speak = true)
        } else {
            showAutomaticEnvironmentStatus(R.string.environment_location_permission_required, speak = true)
        }
    }

    private val nearbyWifiPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startLiveMachineVisionService()
        } else {
            announceLiveNodeStatus(R.string.live_wifi_direct_permission_required)
        }
    }

    private val transport = InProcessHostTransport(
        negotiation = { request ->
            NegotiateResponse.newBuilder()
                .setSelectedVersion(request.supportedVersionsList.first())
                .setIdentity(EphemeralIdentity.newBuilder().setSessionId("synthetic-host-session"))
                .build()
        },
        processor = { frame -> syntheticResult(frame) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.session_status)
        capabilityView = findViewById(R.id.capability_status)
        machineVisionView = findViewById(R.id.machine_vision_status)
        liveMachineVisionView = findViewById(R.id.live_machine_vision_status)
        environmentStatusView = findViewById(R.id.environment_status)
        cueStatusView = findViewById(R.id.cue_status)
        spatialFocusStatusView = findViewById(R.id.spatial_focus_status)
        liveMicrophoneButton = findViewById(R.id.request_live_microphone)
        playRokidBrandButton = findViewById(R.id.play_rokid_brand_sequence)
        automaticEnvironmentButton = findViewById(R.id.environment_automatic)
        indoorEnvironmentButton = findViewById(R.id.environment_indoor)
        outdoorEnvironmentButton = findViewById(R.id.environment_outdoor)
        audio = PlatformHostAudioFeedback()
        haptics = PlatformHostHapticFeedback(this)
        speech = AccessibilityAwareSpeechFeedback(this)
        val capabilities = AndroidCapabilityDetector(this).detect()
        val modalityPolicy = CueModalityPolicy(
            allowEarcon = capabilities.audioOutput,
            allowSpeech = capabilities.audioOutput,
            allowHaptic = capabilities.haptics,
        )
        scheduler = CueScheduler(CueSchedulingPolicy(modalities = modalityPolicy))
        negotiatedCapabilities = CapabilitySet.newBuilder()
            .addImageEncodings(ImageEncoding.IMAGE_ENCODING_RGB8)
            .addImageEncodings(ImageEncoding.IMAGE_ENCODING_GRAY8)
            .addImageEncodings(ImageEncoding.IMAGE_ENCODING_JPEG)
            .addImageEncodings(ImageEncoding.IMAGE_ENCODING_PNG)
            .setMaxWidth(1_920)
            .setMaxHeight(1_080)
            .setMaxFrameBytes(1_048_576)
            .setSupportsCancellation(true)
            .setSupportsSupersession(true)
            .apply {
                if (capabilities.audioOutput) {
                    addCueModalities(CueModality.CUE_MODALITY_EARCON)
                    addCueModalities(CueModality.CUE_MODALITY_SPEECH)
                }
                if (capabilities.haptics) addCueModalities(CueModality.CUE_MODALITY_HAPTIC)
            }
            .build()
        val dispatcher = HostCueDispatcher(audio, haptics, speech) { cueText ->
            runOnUiThread {
                cueStatusView.text = getString(R.string.cue_accessible_status, cueText)
            }
        }
        cueDispatch = InProcessCueDispatchTransport(dispatcher::dispatch)
        gnssEnvironmentSource = AndroidGnssEnvironmentSource(this, clock, onSample = { sample ->
            AndroidNodeForegroundService.offerGnss(sample)
            if (environmentCoordinator.updateGnss(sample)) {
                lastGnssEvidenceNanos = sample.timestampNanos
                runOnUiThread {
                    if (environmentMode == EnvironmentSelectionMode.AUTOMATIC) {
                        showAutomaticEnvironmentStatus(R.string.environment_gnss_available, speak = false)
                    }
                }
            }
        })

        environmentMode = loadEnvironmentMode()
        environmentCoordinator.setMode(environmentMode)

        findViewById<Button>(R.id.connect).setOnClickListener { connect() }
        findViewById<Button>(R.id.process_frame).setOnClickListener { processSyntheticFrame() }
        findViewById<Button>(R.id.machine_vision_diagnostic).setOnClickListener { runMachineVisionDiagnostic() }
        findViewById<Button>(R.id.start_live_machine_vision).setOnClickListener { startLiveMachineVision() }
        liveMicrophoneButton.setOnClickListener { requestLiveMicrophone() }
        playRokidBrandButton.setOnClickListener { playRokidBrandSequence() }
        findViewById<Button>(R.id.stop_live_machine_vision).setOnClickListener { stopLiveMachineVision() }
        automaticEnvironmentButton.setOnClickListener {
            selectEnvironmentMode(EnvironmentSelectionMode.AUTOMATIC, requestLocationPermission = true)
        }
        indoorEnvironmentButton.setOnClickListener {
            selectEnvironmentMode(EnvironmentSelectionMode.FORCE_INDOOR)
        }
        outdoorEnvironmentButton.setOnClickListener {
            selectEnvironmentMode(EnvironmentSelectionMode.FORCE_OUTDOOR)
        }
        findViewById<Button>(R.id.environment_diagnostic).setOnClickListener { runEnvironmentDiagnostic() }
        findViewById<Button>(R.id.cancel).setOnClickListener { cancelCurrent() }
        findViewById<Button>(R.id.disconnect).setOnClickListener { disconnect() }
        findViewById<Button>(R.id.focus_previous).setOnClickListener {
            sendFocusCommand(SpatialFocusCommand.PREVIOUS)
        }
        findViewById<Button>(R.id.focus_next).setOnClickListener {
            sendFocusCommand(SpatialFocusCommand.NEXT)
        }
        findViewById<Button>(R.id.focus_activate).setOnClickListener {
            sendFocusCommand(SpatialFocusCommand.ACTIVATE)
        }
        findViewById<Button>(R.id.focus_back).setOnClickListener {
            sendFocusCommand(SpatialFocusCommand.BACK)
        }
        showCapabilities(capabilities)
        showMachineVisionReadiness()
        showEnvironmentMode(speak = false)
        announceState(getString(R.string.idle_status), speak = false)
    }

    override fun onStart() {
        super.onStart()
        AndroidNodeRuntimeState.addObserver(liveNodeStatusObserver)
        AndroidNodeRuntimeState.addFocusObserver(spatialFocusObserver)
        if (environmentMode == EnvironmentSelectionMode.AUTOMATIC && hasFineLocationPermission()) {
            startGnssEvidenceBurst(speak = false)
        }
    }

    override fun onStop() {
        AndroidNodeRuntimeState.removeObserver(liveNodeStatusObserver)
        AndroidNodeRuntimeState.removeFocusObserver(spatialFocusObserver)
        gnssEnvironmentSource.stop()
        super.onStop()
    }

    override fun onDestroy() {
        activeOperation?.cancel()
        gnssEnvironmentSource.close()
        cueDispatch.close()
        transport.close()
        audio.close()
        speech.close()
        super.onDestroy()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_C -> true.also { connect() }
        KeyEvent.KEYCODE_P -> true.also { processSyntheticFrame() }
        KeyEvent.KEYCODE_V -> true.also { runMachineVisionDiagnostic() }
        KeyEvent.KEYCODE_L -> true.also { startLiveMachineVision() }
        KeyEvent.KEYCODE_M -> true.also {
            if (liveMicrophoneButton.isEnabled) requestLiveMicrophone()
        }
        KeyEvent.KEYCODE_B -> true.also {
            if (playRokidBrandButton.isEnabled) playRokidBrandSequence()
        }
        KeyEvent.KEYCODE_S -> true.also { stopLiveMachineVision() }
        KeyEvent.KEYCODE_A -> true.also {
            selectEnvironmentMode(EnvironmentSelectionMode.AUTOMATIC, requestLocationPermission = true)
        }
        KeyEvent.KEYCODE_I -> true.also { selectEnvironmentMode(EnvironmentSelectionMode.FORCE_INDOOR) }
        KeyEvent.KEYCODE_O -> true.also { selectEnvironmentMode(EnvironmentSelectionMode.FORCE_OUTDOOR) }
        KeyEvent.KEYCODE_E -> true.also { runEnvironmentDiagnostic() }
        KeyEvent.KEYCODE_X -> true.also { cancelCurrent() }
        KeyEvent.KEYCODE_D -> true.also { disconnect() }
        KeyEvent.KEYCODE_DPAD_LEFT -> true.also { sendFocusCommand(SpatialFocusCommand.PREVIOUS) }
        KeyEvent.KEYCODE_DPAD_RIGHT -> true.also { sendFocusCommand(SpatialFocusCommand.NEXT) }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
            true.also { sendFocusCommand(SpatialFocusCommand.ACTIVATE) }
        KeyEvent.KEYCODE_ESCAPE -> true.also { sendFocusCommand(SpatialFocusCommand.BACK) }
        else -> super.onKeyUp(keyCode, event)
    }

    private fun sendFocusCommand(command: SpatialFocusCommand) {
        AndroidNodeForegroundService.focusCommand(this, command)
    }

    @Suppress("DEPRECATION")
    private fun showSpatialFocus(state: SpatialFocusState?) {
        val presentation = SpatialFocusAccessibilityFormatter.presentation(state)
        if (spatialFocusStatusView.text.toString() != presentation.statusText) {
            spatialFocusStatusView.text = presentation.statusText
        }
        if (spatialFocusAnnouncementPolicy.shouldAnnouncePresentation(presentation)) {
            spatialFocusStatusView.announceForAccessibility(requireNotNull(presentation.announcement).text)
        }
    }

    private fun startLiveMachineVision() {
        if (requiresNearbyWifiPermission() && !hasNearbyWifiPermission()) {
            nearbyWifiPermissionRequest.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            return
        }
        startLiveMachineVisionService()
    }

    private fun startLiveMachineVisionService() {
        AndroidNodeForegroundService.start(this, environmentMode)
        liveMachineVisionView.text = getString(R.string.android_node_starting)
    }

    private fun requiresNearbyWifiPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            LiveLinkProvisioningStore(this)
                .loadConfig(LiveLinkEndpointRole.POCO_HOST)
                .networkTopology == LiveLinkNetworkTopology.WIFI_DIRECT_REQUIRED
        }.getOrDefault(false)
    }

    private fun hasNearbyWifiPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED

    private fun announceLiveNodeStatus(messageResource: Int) {
        val message = getString(messageResource)
        liveMachineVisionView.text = message
        speech.speak(message)
    }

    private fun stopLiveMachineVision() {
        AndroidNodeForegroundService.stop(this)
        liveMicrophoneButton.isEnabled = false
        playRokidBrandButton.isEnabled = false
        liveMachineVisionView.text = getString(R.string.live_machine_vision_idle)
    }

    private fun requestLiveMicrophone() {
        if (!liveMicrophoneButton.isEnabled) return
        liveMicrophoneButton.isEnabled = false
        AndroidNodeForegroundService.requestMicrophone(this)
    }

    private fun playRokidBrandSequence() {
        if (!playRokidBrandButton.isEnabled) return
        playRokidBrandButton.isEnabled = false
        AndroidNodeForegroundService.playBrandSequence(this)
        liveMachineVisionView.text = getString(R.string.rokid_brand_sequence_requested)
    }

    private fun updateLiveMicrophoneControl(status: LiveMachineVisionStatus?) {
        liveMicrophoneButton.isEnabled = status?.let {
            liveMicrophoneControlEnabled(it.phase, it.microphonePhase, it.speechRuntime.phase)
        } ?: false
    }

    private fun updateLiveNodeControl(status: LiveMachineVisionStatus?) {
        playRokidBrandButton.isEnabled = status?.phase == LiveMachineVisionPhase.STREAMING
    }

    private fun showCapabilities(capabilities: RuntimeCapabilities) {
        capabilityView.text = getString(
            R.string.capability_summary,
            yesNo(capabilities.camera),
            yesNo(capabilities.rotationVector),
            yesNo(capabilities.audioOutput),
            yesNo(capabilities.haptics),
            yesNo(capabilities.locationProvider),
            yesNo(capabilities.validatedNetwork),
        )
    }

    private fun showMachineVisionReadiness(liveStatus: LiveMachineVisionStatus? = null) {
        val modelStatus = PrivateModelBundleVerifier().inspect(filesDir.resolve("models"))
        val availableModels = modelStatus.artifacts.count { it.available }
        val socManufacturer: String
        val socModel: String
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            socManufacturer = Build.SOC_MANUFACTURER
            socModel = Build.SOC_MODEL
        } else {
            socManufacturer = Build.MANUFACTURER
            socModel = Build.HARDWARE
        }
        val plan = QualcommAcceleratorPlanner.select(
            QualcommRuntimeEvidence(
                socManufacturer = socManufacturer,
                socModel = socModel,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                qnnAdapterInitialized = false,
                htpBackendInitialized = false,
                cpuReferenceBackendAvailable = true,
            ),
        )
        val backend = when (liveQnnBackendEvidence(liveStatus)) {
            LiveQnnBackendEvidence.HTP_EXECUTED -> getString(
                R.string.machine_vision_backend_htp_live,
                requireNotNull(liveStatus).inferenceSuccesses,
            )
            LiveQnnBackendEvidence.INITIALIZING -> getString(R.string.machine_vision_backend_htp_initializing)
            LiveQnnBackendEvidence.QNN_FAILED -> getString(R.string.machine_vision_backend_htp_failed)
            LiveQnnBackendEvidence.LIVE_FAILED_BEFORE_QNN_EXECUTION ->
                getString(R.string.machine_vision_backend_live_failed_before_qnn)
            LiveQnnBackendEvidence.NOT_EXERCISED -> when (plan.target) {
                AcceleratorTarget.QNN_HTP -> getString(R.string.machine_vision_backend_htp)
                AcceleratorTarget.CPU_REFERENCE -> {
                    if (plan.reason == "QNN adapter unavailable") {
                        getString(R.string.machine_vision_backend_not_exercised)
                    } else {
                        getString(R.string.machine_vision_backend_reference, plan.reason)
                    }
                }
                AcceleratorTarget.UNAVAILABLE -> getString(R.string.machine_vision_backend_unavailable)
            }
        }
        machineVisionView.text = getString(
            R.string.machine_vision_readiness,
            BviClassCatalog.bviClassesList.size,
            availableModels,
            MachineVisionModelProfiles.requiredProfiles.size,
            backend,
        )
    }

    private fun runMachineVisionDiagnostic() {
        val message = runCatching {
            val intrinsics = CameraIntrinsics(1_920, 1_080, 1_000.0, 1_000.0, 960.0, 540.0)
            val balancedProfile = MachineVisionModelProfiles.depthIndoorBalanced
            val lowPowerProfile = MachineVisionModelProfiles.depthIndoorLowPower
            val calibration = requireNotNull(
                TwoAnchorMetricDepthCalibrator().calibrate(
                    listOf(
                        GuidedCalibrationSample("door", ReferenceDistance.NEAR_TWO_FEET, 2.0, 0.95),
                        GuidedCalibrationSample("door", ReferenceDistance.FAR_EIGHT_FEET, 8.0, 0.95),
                    ),
                    RelativeDepthRepresentation.DEPTH,
                    MetricDepthCalibrationBinding.forProfile(balancedProfile, intrinsics),
                ),
            )
            val calibrationStore = BoundedMetricDepthCalibrationStore(listOf(calibration))
            val now = clock.nowNanos()
            val captureTimestamp = (now - 2L).coerceAtLeast(0L)
            val frame = VisionFrame(
                frameId = frameIds.incrementAndGet(),
                captureMonotonicTimestampNanos = captureTimestamp,
                width = 1_920,
                height = 1_080,
                synthetic = true,
                cameraIntrinsics = intrinsics,
            )
            val adapter = object : StagedMachineVisionInferenceAdapter {
                override fun segment(frame: VisionFrame) = SegmentationStageResult(
                    frame.frameId,
                    frame.captureMonotonicTimestampNanos + 1L,
                    MachineVisionModelProfiles.fixedVocabularySha256,
                    listOf(
                        SegmentedObject(
                            trackId = "synthetic-door",
                            classId = "door",
                            confidence = 0.95,
                            maskGeometry = InstanceMaskGeometry(
                                1_920,
                                1_080,
                                735,
                                27,
                                1_185,
                                1_052,
                                960.0,
                                539.5,
                                400_000,
                            ),
                            maskFingerprint = SYNTHETIC_MASK_FINGERPRINT,
                            temporalMotionEvidence = TemporalMotionEvidence.CONFIRMED_STATIC_WORLD,
                        ),
                    ),
                )

                override fun inferDepth(
                    frame: VisionFrame,
                    depthProfile: MachineVisionModelProfile,
                    segmentedObjects: List<SegmentedObject>,
                ): DepthStageResult {
                    check(depthProfile.id == balancedProfile.id)
                    check(segmentedObjects.singleOrNull()?.trackId == "synthetic-door")
                    return DepthStageResult(
                        frame.frameId,
                        frame.captureMonotonicTimestampNanos + 2L,
                        depthProfile.id,
                        mapOf("synthetic-door" to listOf(4.5, 5.0, 5.5)),
                        mapOf("synthetic-door" to SYNTHETIC_MASK_FINGERPRINT),
                    )
                }
            }
            val staged = EnvironmentAwareMachineVisionPipeline(adapter, calibrationStore).also {
                it.setEnvironmentMode(EnvironmentSelectionMode.FORCE_INDOOR)
            }
            val temporal = TemporalEnvironmentAwareMachineVisionPipeline(staged)
            val initial = temporal.processFrame(
                frame = frame,
                pose = TimestampedPose(captureTimestamp, UnitQuaternion.IDENTITY),
                signal = VisualKeyframeSignal(0.0, 0.0),
                nowNanos = now,
                depthRoutingRequest = DepthModelRoutingRequest(
                    environment = DepthEnvironment.INDOOR,
                    maximumEndToEndLatencyMillis = 140.0,
                ),
                availableDepthProfileIds = setOf(lowPowerProfile.id, balancedProfile.id),
            )
            val metricTrack = initial.stagedResult?.perception?.tracks?.singleOrNull()
                ?: error("missing synthetic metric track")
            val initialTemporalTrack = initial.temporalTracks.singleOrNull()
                ?: error("missing synthetic temporal track")
            check(initial.reason == "keyframe_updated")
            check(initial.stagedResult.profileDecision?.selectedProfile?.id == balancedProfile.id)
            check(!initialTemporalTrack.propagated)

            val propagated = temporal.updatePose(
                TimestampedPose(
                    captureTimestamp + 100_000_000L,
                    UnitQuaternion(0.7071067811865476, 0.0, 0.7071067811865475, 0.0),
                ),
            )
            val propagatedTrack = propagated.tracks.singleOrNull()
                ?: error("missing propagated synthetic track")
            check(propagated.accepted)
            check(propagatedTrack.stableTrackId == "synthetic-door")
            check(propagatedTrack.propagated && !propagatedTrack.translationApplied)
            check(propagatedTrack.uncertaintyMeters > initialTemporalTrack.uncertaintyMeters)

            getString(
                R.string.machine_vision_diagnostic_passed,
                metricTrack.classId,
                metricTrack.representativeDistance.distanceMeters,
            )
        }.getOrElse {
            getString(R.string.machine_vision_diagnostic_failed)
        }
        machineVisionView.text = message
        speech.speak(message)
    }

    private fun runEnvironmentDiagnostic() {
        val diagnostic = EnvironmentDepthCoordinator()
        val now = clock.nowNanos()
        val firstTimestamp = (now - 3L).coerceAtLeast(0L)
        diagnostic.updateGnss(
            GnssQualitySample(
                timestampNanos = firstTimestamp,
                visibleSatelliteCount = 16,
                usedInFixCount = 9,
                meanCarrierToNoiseDbHz = 35.0,
                horizontalAccuracyMeters = 5.0,
                locationFixAgeNanos = 1L,
            ),
        )
        var decision = diagnostic.routeFrame(
            VisionFrame(frameIds.incrementAndGet(), firstTimestamp, 1_920, 1_080, true),
            OUTDOOR_DIAGNOSTIC_DETECTIONS,
            nowNanos = now,
            bothProfilesAvailable = false,
        )
        repeat(2) { index ->
            val timestamp = (firstTimestamp + index + 1L).coerceAtMost(now)
            decision = diagnostic.routeFrame(
                VisionFrame(frameIds.incrementAndGet(), timestamp, 1_920, 1_080, true),
                OUTDOOR_DIAGNOSTIC_DETECTIONS,
                nowNanos = now,
                bothProfilesAvailable = false,
            )
        }
        val passed = decision.selectedEnvironment == DepthEnvironment.OUTDOOR &&
            decision.selectedProfile == MachineVisionModelProfiles.depthOutdoor
        environmentStatusView.text = getString(
            if (passed) R.string.environment_diagnostic_passed else R.string.environment_diagnostic_failed,
        )
        speech.speak(environmentStatusView.text.toString())
    }

    private fun selectEnvironmentMode(
        mode: EnvironmentSelectionMode,
        requestLocationPermission: Boolean = false,
    ) {
        environmentMode = mode
        environmentCoordinator.setMode(mode)
        getPreferences(MODE_PRIVATE).edit().putString(ENVIRONMENT_MODE_KEY, mode.name).apply()
        if (mode == EnvironmentSelectionMode.AUTOMATIC) {
            showEnvironmentMode(speak = true)
            if (hasFineLocationPermission()) {
                startGnssEvidenceBurst(speak = false)
            } else if (requestLocationPermission) {
                locationPermissionRequest.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ),
                )
            }
        } else {
            gnssEnvironmentSource.stop()
            lastGnssEvidenceNanos = null
            showEnvironmentMode(speak = true)
        }
    }

    private fun showEnvironmentMode(speak: Boolean) {
        val selected = getString(R.string.environment_mode_selected)
        val notSelected = getString(R.string.environment_mode_not_selected)
        ViewCompat.setStateDescription(
            automaticEnvironmentButton,
            if (environmentMode == EnvironmentSelectionMode.AUTOMATIC) selected else notSelected,
        )
        ViewCompat.setStateDescription(
            indoorEnvironmentButton,
            if (environmentMode == EnvironmentSelectionMode.FORCE_INDOOR) selected else notSelected,
        )
        ViewCompat.setStateDescription(
            outdoorEnvironmentButton,
            if (environmentMode == EnvironmentSelectionMode.FORCE_OUTDOOR) selected else notSelected,
        )
        when (environmentMode) {
            EnvironmentSelectionMode.AUTOMATIC -> showAutomaticEnvironmentStatus(
                if (hasRecentGnssEvidence()) {
                    R.string.environment_gnss_available
                } else {
                    R.string.environment_camera_pending
                },
                speak,
            )
            EnvironmentSelectionMode.FORCE_INDOOR -> showManualEnvironmentStatus(DepthEnvironment.INDOOR, speak)
            EnvironmentSelectionMode.FORCE_OUTDOOR -> showManualEnvironmentStatus(DepthEnvironment.OUTDOOR, speak)
        }
    }

    private fun showAutomaticEnvironmentStatus(detailResource: Int, speak: Boolean) {
        val message = getString(R.string.environment_automatic_status, getString(detailResource))
        environmentStatusView.text = message
        if (speak) speech.speak(message)
    }

    private fun showManualEnvironmentStatus(environment: DepthEnvironment, speak: Boolean) {
        val name = environment.name.lowercase()
        val message = getString(R.string.environment_manual_status, name, name)
        environmentStatusView.text = message
        if (speak) speech.speak(message)
    }

    private fun startGnssEvidenceBurst(speak: Boolean) {
        val detail = when (gnssEnvironmentSource.startBurst()) {
            GnssAcquisitionState.STARTED -> R.string.environment_gnss_started
            GnssAcquisitionState.ALREADY_ACTIVE, GnssAcquisitionState.THROTTLED -> {
                R.string.environment_gnss_throttled
            }
            GnssAcquisitionState.PERMISSION_REQUIRED -> R.string.environment_location_permission_required
            GnssAcquisitionState.LOCATION_DISABLED,
            GnssAcquisitionState.PROVIDER_UNAVAILABLE,
            GnssAcquisitionState.FAILED,
            GnssAcquisitionState.STOPPED,
            -> R.string.environment_location_unavailable
        }
        showAutomaticEnvironmentStatus(detail, speak)
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasRecentGnssEvidence(): Boolean = lastGnssEvidenceNanos?.let {
        val ageNanos = clock.nowNanos() - it
        ageNanos in 0L..GNSS_EVIDENCE_MAXIMUM_AGE_NANOS
    } ?: false

    private fun loadEnvironmentMode(): EnvironmentSelectionMode {
        val stored = getPreferences(MODE_PRIVATE).getString(ENVIRONMENT_MODE_KEY, null)
        return runCatching { EnvironmentSelectionMode.valueOf(stored.orEmpty()) }
            .getOrDefault(EnvironmentSelectionMode.AUTOMATIC)
    }

    private fun connect() {
        if (session.dispatch(SessionEvent.Connect) !is SessionState.Connecting) return
        announceState(getString(R.string.connecting_status))
        session.dispatch(SessionEvent.TransportConnected)
        val request = NegotiateRequest.newBuilder()
            .setClientInstanceId("android-host-runtime")
            .addSupportedVersions(ProtocolVersion.newBuilder().setMajor(1).setMinor(0).setPatch(0))
            .setCapabilities(negotiatedCapabilities)
            .build()
        val operation = transport.negotiate(request, object : TransportCallback<NegotiateResponse> {
            override fun onSuccess(value: NegotiateResponse) {
                val state = session.dispatch(SessionEvent.Negotiated(value.identity.sessionId))
                announceState(
                    if (state is SessionState.Active) getString(R.string.connected_status) else getString(R.string.connect_error_status),
                )
            }

            override fun onFailure(error: Throwable) {
                session.dispatch(SessionEvent.TransportFailed(error.message ?: "Connection failed", retryable = true))
                announceState(getString(R.string.connect_error_status))
            }
        })
        activeOperation = operation.takeUnless { session.state is SessionState.Active }
    }

    private fun processSyntheticFrame() {
        if (session.state !is SessionState.Active) {
            announceState(getString(R.string.connect_first_status))
            return
        }
        val frame = syntheticFrame(frameIds.incrementAndGet())
        activeRequestId = frame.requestId
        correlator.register(frame)
        announceState(getString(R.string.processing_status, frame.frameId), speak = false)
        val operation = transport.process(frame, object : TransportCallback<PerceptionResult> {
            override fun onSuccess(value: PerceptionResult) {
                when (correlator.correlate(value)) {
                    is CorrelationResult.Accepted -> deliverCues(value)
                    is CorrelationResult.Rejected -> announceState(getString(R.string.stale_result_status))
                }
            }

            override fun onFailure(error: Throwable) {
                announceState(getString(R.string.processing_error_status))
            }
        })
        activeOperation = operation.takeIf { activeRequestId != null }
    }

    private fun deliverCues(result: PerceptionResult) {
        result.cuesList.forEach { scheduler.submit(it, clock.nowNanos()) }
        val next = scheduler.next(clock.nowNanos())
        if (next != null) {
            val delivered = cueDispatch.send(next)
            scheduler.complete(next.cueId)
            if (delivered) {
                announceState(
                    getString(R.string.cue_delivered_status, next.direction.name.removePrefix("DIRECTION_").lowercase()),
                    speak = false,
                )
            } else {
                announceState(getString(R.string.no_cue_status), speak = false)
            }
        } else {
            announceState(getString(R.string.no_cue_status), speak = false)
        }
        activeRequestId = null
        activeOperation = null
    }

    private fun cancelCurrent() {
        activeOperation?.cancel()
        activeRequestId?.let(correlator::cancel)
        activeOperation = null
        activeRequestId = null
        session.dispatch(SessionEvent.Cancel)
        session.dispatch(SessionEvent.TransportClosed)
        announceState(getString(R.string.cancelled_status))
    }

    private fun disconnect() {
        activeOperation?.cancel()
        activeOperation = null
        activeRequestId = null
        session.dispatch(SessionEvent.Disconnect)
        announceState(getString(R.string.disconnected_status))
    }

    private fun announceState(message: String, speak: Boolean = true) {
        statusView.text = message
        if (speak) speech.speak(message)
    }

    private fun yesNo(value: Boolean): String = getString(if (value) R.string.available else R.string.unavailable)

    private fun syntheticFrame(frameId: Long): FramePayload {
        val bytes = SyntheticImageFixtures.onePixelJpeg()
        val descriptor = ImageDescriptor.newBuilder()
            .setWidth(1)
            .setHeight(1)
            .setEncoding(ImageEncoding.IMAGE_ENCODING_JPEG)
            .setMediaType("image/jpeg")
            .setPayloadBytes(bytes.size.toLong())
            .setSha256(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .build()
        return FramePayload.newBuilder()
            .setRequestId("host-request-$frameId")
            .setSessionId("synthetic-host-session")
            .setStreamId("host-synthetic-camera")
            .setFrameId(frameId)
            .setCaptureMonotonicTimestampNs(clock.nowNanos())
            .setImage(descriptor)
            .setFrameData(ByteString.copyFrom(bytes))
            .setSynthetic(true)
            .build()
    }

    private fun syntheticResult(frame: FramePayload): PerceptionResult {
        val now = clock.nowNanos()
        val cue = PerceptionCue.newBuilder()
            .setCueId("host-cue-${frame.frameId}")
            .setFrameId(frame.frameId)
            .setCreatedMonotonicTimestampNs(now)
            .setTtlMs(1_200)
            .setCategory(CueCategory.CUE_CATEGORY_OBSTACLE)
            .setDescription("Synthetic obstacle cue")
            .setConfidence(0.9)
            .setPriority(80)
            .setDirection(if (frame.frameId % 2L == 0L) Direction.DIRECTION_RIGHT else Direction.DIRECTION_LEFT)
            .setUrgency(Urgency.URGENCY_HIGH)
            .setEarcon(Earcon.newBuilder().setEarconId("obstacle").setGain(0.5f).setPitch(1f))
            .setSpeech(Speech.newBuilder().setText("Synthetic obstacle ahead").setInterrupt(true))
            .setHaptic(
                Haptic.newBuilder()
                    .setPattern(HapticPattern.HAPTIC_PATTERN_PULSE)
                    .setIntensity(0.35f)
                    .setDurationMs(70),
            )
            .build()
        return PerceptionResult.newBuilder()
            .setResultId("host-result-${frame.frameId}")
            .setRequestId(frame.requestId)
            .setSessionId(frame.sessionId)
            .setStreamId(frame.streamId)
            .setFrameId(frame.frameId)
            .setCaptureMonotonicTimestampNs(frame.captureMonotonicTimestampNs)
            .setCompletedMonotonicTimestampNs(now)
            .addCues(cue)
            .build()
    }

    private companion object {
        const val ENVIRONMENT_MODE_KEY = "environment_selection_mode"
        const val GNSS_EVIDENCE_MAXIMUM_AGE_NANOS = 15_000_000_000L
        val SYNTHETIC_MASK_FINGERPRINT = "d".repeat(64)
        val OUTDOOR_DIAGNOSTIC_DETECTIONS = listOf(
            SceneSemanticDetection("crosswalk", 0.95),
            SceneSemanticDetection("traffic_light", 0.95),
        )
    }
}
