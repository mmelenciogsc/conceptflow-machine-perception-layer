// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host

import org.conceptflow.mpl.host.speech.PrivateSpeechResultSummary
import org.conceptflow.mpl.host.vision.LiveMetricCalibrationState
import org.conceptflow.mpl.host.vision.LiveMetricFusionReason
import org.conceptflow.mpl.host.vision.LiveMetricFusionResult
import org.conceptflow.mpl.host.vision.MachineVisionInference
import org.conceptflow.mpl.host.vision.MachineVisionModelProfiles
import org.conceptflow.mpl.host.vision.QnnLiveFrameResult
import org.conceptflow.mpl.host.vision.SemanticMaskObservation
import org.conceptflow.mpl.transport.LiveLinkCloseEvidence
import org.conceptflow.mpl.transport.LiveLinkDiagnosticCode
import org.conceptflow.mpl.transport.LiveLinkFailureLane
import org.conceptflow.mpl.transport.RokidNodeCommandDelivery
import org.conceptflow.mpl.v1.RokidNodeCommandOperation
import org.conceptflow.mpl.v1.BatteryChargeState
import org.conceptflow.mpl.v1.LiveLinkTelemetry
import org.conceptflow.mpl.v1.ImageEncoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMachineVisionStatusAccumulatorTest {
    @Test
    fun `AVC fallback is counted separately from decoder failures`() {
        val status = LiveMachineVisionStatusAccumulator("depth-indoor-392")
        status.sessionReady(ImageEncoding.IMAGE_ENCODING_AVC_ANNEX_B_INTRA)
        status.avcDecodeFailed(fallbackInitiated = true)
        status.avcDecodeFailed(fallbackInitiated = false)

        val snapshot = status.snapshot()
        assertEquals(2L, snapshot.avcDecodeFailures)
        assertEquals(1L, snapshot.avcTransportFallbacks)
        assertTrue(snapshot.accessibleSummary().contains("failures 2; fallbacks 1"))
        assertTrue(snapshot.accessibleSummary().contains("Current camera transport: AVC intra"))
    }

    @Test
    fun `private speech status exposes outcome metadata but never transcript content`() {
        val status = LiveMachineVisionStatusAccumulator("automatic-pending")
        status.privateSpeechResult(
            PrivateSpeechResultSummary(
                pending = true,
                speechDetected = true,
                transcriptCharacterCount = 19,
                transcriptionTimedOut = false,
            ),
        )

        val summary = status.snapshot().accessibleSummary()
        assertTrue(summary.contains("Private speech result pending: true"))
        assertTrue(summary.contains("transcript characters: 19"))
        assertFalse(summary.contains("where is the chair"))
    }

    @Test
    fun `peer pressure telemetry is exposed without sensor content`() {
        val status = LiveMachineVisionStatusAccumulator("automatic-pending")
        status.peerTelemetry(
            LiveLinkTelemetry.newBuilder()
                .setSampledMonotonicTimestampNs(1L)
                .setPendingCameraFrames(1)
                .setPendingImuBatches(2)
                .setPendingAudioBlocks(3)
                .setPendingTouchEvents(4)
                .setDroppedCameraFrames(5)
                .setDroppedImuBatches(6)
                .setDroppedAudioBlocks(7)
                .setTouchOverflowEvents(8)
                .setSentRealtimeMessages(9)
                .setSentCameraMessages(10)
                .setCameraFramesAnalyzed(14)
                .setCameraFramesEmitted(11)
                .setCameraRelaxedTierSamples(8)
                .setCameraMotionTierSamples(6)
                .setCameraFramesDroppedDark(1)
                .setCameraFramesDroppedBlurry(1)
                .setCameraFramesDroppedCadence(1)
                .setCurrentCameraTargetFps(5)
                .setBatteryLevelPercent(88)
                .setBatteryChargeState(BatteryChargeState.BATTERY_CHARGE_STATE_DISCHARGING)
                .setBatteryVoltageMicrovolts(4_100_000L)
                .setBatteryCurrentMicroamps(-420_000L)
                .setBatteryChargeCounterMicroampHours(600_000L)
                .setBatteryTemperatureDeciCelsius(330)
                .setExternalPowerConnected(false)
                .build(),
        )
        status.peerTelemetry(
            LiveLinkTelemetry.newBuilder()
                .setSampledMonotonicTimestampNs(2L)
                .setPendingCameraFrames(1)
                .setPendingImuBatches(2)
                .setPendingAudioBlocks(3)
                .setPendingTouchEvents(4)
                .setDroppedCameraFrames(5)
                .setDroppedImuBatches(6)
                .setDroppedAudioBlocks(7)
                .setTouchOverflowEvents(8)
                .setSentRealtimeMessages(9)
                .setSentCameraMessages(10)
                .setCameraFramesAnalyzed(14)
                .setCameraFramesEmitted(11)
                .setCameraRelaxedTierSamples(8)
                .setCameraMotionTierSamples(6)
                .setCameraFramesDroppedDark(1)
                .setCameraFramesDroppedBlurry(1)
                .setCameraFramesDroppedCadence(1)
                .setCurrentCameraTargetFps(5)
                .setBatteryLevelPercent(86)
                .setBatteryChargeCounterMicroampHours(580_000L)
                .setBatteryTemperatureDeciCelsius(345)
                .build(),
        )

        val snapshot = status.snapshot()
        assertEquals(2L, snapshot.peerPressure?.samplesReceived)
        assertEquals(8L, snapshot.peerPressure?.touchOverflowEvents)
        assertEquals(9L, snapshot.peerPressure?.sentRealtimeMessages)
        assertEquals(10L, snapshot.peerPressure?.sentCameraMessages)
        assertEquals(8L, snapshot.peerPressure?.cameraRelaxedTierSamples)
        assertEquals(6L, snapshot.peerPressure?.cameraMotionTierSamples)
        assertEquals(5, snapshot.peerPressure?.currentCameraTargetFramesPerSecond)
        assertTrue(snapshot.accessibleSummary().contains("Rokid queue telemetry: samples 2"))
        assertTrue(snapshot.accessibleSummary().contains("sent realtime messages 9, camera messages 10"))
        assertTrue(snapshot.accessibleSummary().contains("relaxed tier 8, motion tier 6"))
        assertEquals(2L, snapshot.peerPower?.samplesReceived)
        assertEquals(88, snapshot.peerPower?.initialLevelPercent)
        assertEquals(86, snapshot.peerPower?.latestLevelPercent)
        assertEquals(86, snapshot.peerPower?.minimumLevelPercent)
        assertEquals(-20_000L, snapshot.peerPower?.chargeCounterDeltaMicroampHours)
        assertEquals(345, snapshot.peerPower?.maximumTemperatureDeciCelsius)
        assertTrue(snapshot.accessibleSummary().contains("Rokid power telemetry: samples 2"))
        assertFalse(snapshot.accessibleSummary().contains("timestamp"))
    }

    @Test
    fun `node command acknowledgement remains in accessible aggregate status`() {
        val status = LiveMachineVisionStatusAccumulator("automatic-pending")
        val operation = RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_PLAY_BRAND_SEQUENCE

        status.nodeCommandRequested(operation, fromGlassesGesture = false)
        assertTrue(status.snapshot().accessibleSummary().contains("Rokid Node command: requested"))

        status.nodeCommandResult(
            RokidNodeCommandDelivery(
                commandId = 7L,
                originatingGestureId = 0L,
                operation = operation,
                acceptedForExecution = true,
            ),
        )

        val snapshot = status.snapshot()
        assertEquals(LiveRokidNodeCommandPhase.ACCEPTED, snapshot.nodeCommandPhase)
        assertEquals(operation, snapshot.lastNodeCommandOperation)
        assertFalse(snapshot.lastNodeCommandFromGlassesGesture)
        assertTrue(
            snapshot.accessibleSummary().contains(
                "Rokid Node command: accepted; operation play brand sequence; source Android control",
            ),
        )
    }

    @Test
    fun `glasses gesture command source is retained without its identifier`() {
        val status = LiveMachineVisionStatusAccumulator("automatic-pending")
        val operation = RokidNodeCommandOperation.ROKID_NODE_COMMAND_OPERATION_ACTIVATE_NODE

        status.nodeCommandRequested(operation, fromGlassesGesture = true)
        status.nodeCommandResult(
            RokidNodeCommandDelivery(
                commandId = 9L,
                originatingGestureId = 4L,
                operation = operation,
                acceptedForExecution = false,
            ),
        )

        val text = status.snapshot().accessibleSummary()
        assertTrue(text.contains("Rokid Node command: rejected; operation activate; source glasses gesture"))
        assertFalse(text.contains("commandId"))
        assertFalse(text.contains("gestureId"))
    }

    @Test
    fun `reports bounded aggregate percentiles without captured content`() {
        val status = LiveMachineVisionStatusAccumulator("depth-indoor-392")
        status.phase(LiveMachineVisionPhase.STREAMING)
        status.cameraReceived()
        status.cameraReceived(admittedToTimeline = false)
        status.cameraReplaced()
        status.imuReceived(8, acceptedPoses = 6, rejectedPoses = 2, propagatedTracks = 0)
        repeat(4) { index ->
            status.inferenceStarted()
            val observations = List(index) { observation ->
                SemanticMaskObservation(
                    trackId = "track-$observation",
                    classId = "door",
                    confidence = 0.9,
                    relativeDepthSamples = listOf(2.0),
                )
            }
            status.inferenceSucceeded(
                endToEndNs = (index + 1L) * 1_000_000L,
                captureToReceiveNs = (index + 1L) * 500_000L,
                clockUncertaintyNs = 100_000L,
                result = QnnLiveFrameResult(
                    frameId = index + 1L,
                    selectedDepthProfileId = "depth-indoor-392",
                    segmentedObjectCount = index,
                    finiteYoloValues = 830_600,
                    finitePositiveDepthValues = 153_664,
                    decodeLatencyNanos = 50_000L,
                    yoloPreprocessLatencyNanos = 400_000L,
                    segmentationLatencyNanos = 200_000L,
                    yoloPostprocessLatencyNanos = 100_000L,
                    modelSetupLatencyNanos = 25_000L,
                    depthPreprocessLatencyNanos = 200_000L,
                    depthLatencyNanos = 300_000L,
                    depthPostprocessLatencyNanos = 50_000L,
                    totalLatencyNanos = 1_325_000L,
                    calibrationState = LiveMetricCalibrationState.UNCALIBRATED_INTRINSICS_MISSING,
                    inference = MachineVisionInference(
                        frameId = index + 1L,
                        completedMonotonicTimestampNanos = index + 2L,
                        fixedVocabularySha256 = MachineVisionModelProfiles.fixedVocabularySha256,
                        depthProfileId = "depth-indoor-392",
                        observations = observations,
                    ),
                ),
                fusion = LiveMetricFusionResult(
                    reason = LiveMetricFusionReason.PROFILE_BOUND_METRIC_SEMANTICS_MISSING,
                    detailCode = "profile_bound_metric_semantics_missing",
                    relativeTrackCount = observations.size,
                    metricTracks = emptyList(),
                    temporalTracks = emptyList(),
                ),
            )
        }

        val snapshot = status.snapshot()
        assertEquals(2L, snapshot.framesReceived)
        assertEquals(1L, snapshot.cameraFramesRejectedStale)
        assertEquals(1L, snapshot.framesDroppedBeforeInference)
        assertEquals(4L, snapshot.inferenceSuccesses)
        assertEquals(0L, snapshot.perceptionResultsRejectedStale)
        assertEquals(3, snapshot.currentDetectedInstances)
        assertEquals(6L, snapshot.totalDetectedInstances)
        assertEquals(830_600, snapshot.lastFiniteYoloValues)
        assertEquals(3_322_400L, snapshot.totalFiniteYoloValues)
        assertEquals(153_664, snapshot.lastFinitePositiveDepthValues)
        assertEquals(614_656L, snapshot.totalFinitePositiveDepthValues)
        assertEquals(6L, snapshot.imuPoseSamplesAccepted)
        assertEquals(2L, snapshot.imuPoseSamplesRejected)
        assertEquals(3, snapshot.currentRelativeTracks)
        assertEquals(0, snapshot.currentMetricTracks)
        assertEquals("PROFILE_BOUND_METRIC_SEMANTICS_MISSING", snapshot.lastMetricReason)
        assertEquals(2.0, snapshot.endToEndMs.p50!!, 0.0)
        assertEquals(4.0, snapshot.endToEndMs.p95!!, 0.0)
        assertEquals(0.4, snapshot.yoloPreprocessMs.p95!!, 0.0)
        assertEquals(0.2, snapshot.depthPreprocessMs.p95!!, 0.0)
        assertEquals(1.325, snapshot.executorTotalMs.p95!!, 0.0)
        val text = snapshot.accessibleSummary()
        assertTrue(text.contains("Camera frames reconstructed: 2; rejected stale: 1"))
        assertTrue(text.contains("Host stage p95 milliseconds"))
        assertTrue(text.contains("Detected instances: current 3; total 6"))
        assertTrue(text.contains("Finite outputs: last YOLO 830600"))
        assertTrue(text.contains("pose accepted: 6; pose rejected: 2"))
        assertTrue(text.contains("Perception tracks: relative 3; metric 0; propagated 0"))
        assertFalse(text.contains("session"))
        assertFalse(text.contains("address"))
        assertFalse(text.contains("class"))
    }

    @Test
    fun `post inference freshness includes clock uncertainty and reports stale suppression`() {
        val gate = LivePerceptionResultFreshnessGate(maximumResultAgeNanos = 1_500_000_000L)
        assertTrue(
            gate.accept(
                captureNanos = 1_000_000_000L,
                completedNanos = 2_400_000_000L,
                clockUncertaintyNanos = 100_000_000L,
            ),
        )
        assertFalse(
            gate.accept(
                captureNanos = 1_000_000_000L,
                completedNanos = 2_400_000_001L,
                clockUncertaintyNanos = 100_000_000L,
            ),
        )
        assertFalse(
            gate.accept(
                captureNanos = 2_000_000_000L,
                completedNanos = 1_999_999_999L,
                clockUncertaintyNanos = 0L,
            ),
        )

        val status = LiveMachineVisionStatusAccumulator("automatic-pending")
        status.inferenceStarted()
        status.perceptionResultRejectedStale()
        val snapshot = status.snapshot()
        assertEquals(1L, snapshot.perceptionResultsRejectedStale)
        assertTrue(snapshot.accessibleSummary().contains("results rejected stale: 1"))
        assertTrue(snapshot.accessibleSummary().contains("PERCEPTION_RESULT_STALE"))
    }

    @Test
    fun `native metric status remains available when pose propagation is gated`() {
        val status = LiveMachineVisionStatusAccumulator("depth-indoor-392")
        val frameId = 1L
        val observation = SemanticMaskObservation("track-1", "door", 0.9, listOf(2.0))
        val nativeSemantics = requireNotNull(
            org.conceptflow.mpl.host.vision.OfficialDepthAnythingV2MetricSemanticsProvider
                .resolve(MachineVisionModelProfiles.depthIndoorBalanced),
        )
        val provenance = nativeSemantics.provenance
        status.inferenceStarted()
        status.inferenceSucceeded(
            10L,
            5L,
            1L,
            result = QnnLiveFrameResult(
                frameId,
                MachineVisionModelProfiles.depthIndoorBalanced.id,
                1,
                QnnLiveFrameResult.YOLO_FINITE_VALUE_COUNT,
                QnnLiveFrameResult.DEPTH_FINITE_VALUE_COUNT,
                1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 8L,
                LiveMetricCalibrationState.CALIBRATED_INTRINSICS_PRESENT,
                MachineVisionInference(
                    frameId,
                    2L,
                    MachineVisionModelProfiles.fixedVocabularySha256,
                    MachineVisionModelProfiles.depthIndoorBalanced.id,
                    listOf(observation),
                ),
            ),
            fusion = LiveMetricFusionResult(
                LiveMetricFusionReason.CAMERA_METRIC_TRACKS_READY_PROPAGATION_EXTRINSIC_MISSING,
                "camera_metric_ready_head_camera_extrinsic_missing",
                1,
                listOf(
                    org.conceptflow.mpl.host.vision.MetricSemanticTrack(
                        frameId,
                        "track-1",
                        "door",
                        0.9,
                        requireNotNull(nativeSemantics.estimate(2.0)),
                        org.conceptflow.mpl.host.vision.DepthEnvironment.INDOOR,
                    ),
                ),
                emptyList(),
                provenance,
            ),
        )

        val snapshot = status.snapshot()
        assertEquals(
            LiveMetricCalibrationState.PROFILE_BOUND_NATIVE_METRIC_CALIBRATED_INTRINSICS_PRESENT,
            snapshot.calibrationState,
        )
        assertEquals(1, snapshot.currentMetricTracks)
        assertEquals(
            "CAMERA_METRIC_TRACKS_READY_PROPAGATION_EXTRINSIC_MISSING",
            snapshot.lastMetricReason,
        )
    }

    @Test
    fun `native metric aggregate retains derived intrinsics provenance`() {
        val status = LiveMachineVisionStatusAccumulator("depth-indoor-392")
        val nativeSemantics = requireNotNull(
            org.conceptflow.mpl.host.vision.OfficialDepthAnythingV2MetricSemanticsProvider
                .resolve(MachineVisionModelProfiles.depthIndoorBalanced),
        )
        val inference = MachineVisionInference(
            1L,
            2L,
            MachineVisionModelProfiles.fixedVocabularySha256,
            MachineVisionModelProfiles.depthIndoorBalanced.id,
            emptyList(),
        )
        status.inferenceStarted()
        status.inferenceSucceeded(
            10L,
            5L,
            1L,
            QnnLiveFrameResult(
                1L,
                MachineVisionModelProfiles.depthIndoorBalanced.id,
                0,
                QnnLiveFrameResult.YOLO_FINITE_VALUE_COUNT,
                QnnLiveFrameResult.DEPTH_FINITE_VALUE_COUNT,
                1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 8L,
                LiveMetricCalibrationState.DERIVED_INTRINSICS_PRESENT,
                inference,
            ),
            LiveMetricFusionResult(
                LiveMetricFusionReason.CAMERA_METRIC_TRACKS_READY_PROPAGATION_EXTRINSIC_MISSING,
                "camera_metric_ready_head_camera_extrinsic_missing",
                0,
                emptyList(),
                emptyList(),
                nativeSemantics.provenance,
            ),
        )

        assertEquals(
            LiveMetricCalibrationState.PROFILE_BOUND_NATIVE_METRIC_DERIVED_INTRINSICS_PRESENT,
            status.snapshot().calibrationState,
        )
    }

    @Test
    fun `failed snapshot preserves a sanitized actionable code`() {
        val status = LiveMachineVisionStatusAccumulator("automatic-pending")
        status.linkDiagnostic(LiveLinkDiagnosticCode.TLS_KEY_TYPE_UNSUPPORTED)
        status.failed("QNN_RUNTIME_LOAD_FAILED")

        val snapshot = status.snapshot()

        assertEquals(LiveMachineVisionPhase.FAILED, snapshot.phase)
        assertEquals("QNN_RUNTIME_LOAD_FAILED", snapshot.lastFailureCode)
        assertEquals("TLS_KEY_TYPE_UNSUPPORTED", snapshot.lastLinkDiagnosticCode)
        assertTrue(snapshot.accessibleSummary().contains("QNN_RUNTIME_LOAD_FAILED"))
        assertTrue(snapshot.accessibleSummary().contains("TLS_KEY_TYPE_UNSUPPORTED"))
    }

    @Test
    fun `authenticated close evidence is exposed without connection details`() {
        val status = LiveMachineVisionStatusAccumulator("automatic-pending")
        status.linkCloseEvidence(
            LiveLinkCloseEvidence(
                hostAuthenticatedCloseSeen = true,
                hostFailureLane = LiveLinkFailureLane.CAMERA,
            ),
        )

        val snapshot = status.snapshot()
        assertTrue(snapshot.closeEvidence.hostAuthenticatedCloseSeen)
        assertEquals(LiveLinkFailureLane.CAMERA, snapshot.closeEvidence.hostFailureLane)
        assertTrue(snapshot.accessibleSummary().contains("host authenticated close seen true"))
        assertTrue(snapshot.accessibleSummary().contains("host failure lane camera"))
        assertFalse(snapshot.accessibleSummary().contains("session"))
        assertFalse(snapshot.accessibleSummary().contains("address"))
    }

    @Test
    fun `first authenticated session clears only transient pre-session diagnostics`() {
        val transient = LiveMachineVisionStatusAccumulator("automatic-pending")
        transient.linkDiagnostic(LiveLinkDiagnosticCode.NETWORK_IO)
        transient.sessionReady()

        assertEquals(null, transient.snapshot().lastLinkDiagnosticCode)
        assertFalse(transient.snapshot().accessibleSummary().contains("NETWORK_IO"))

        val security = LiveMachineVisionStatusAccumulator("automatic-pending")
        security.linkDiagnostic(LiveLinkDiagnosticCode.TLS_PEER_PIN_MISMATCH)
        security.sessionReady()

        assertEquals("TLS_PEER_PIN_MISMATCH", security.snapshot().lastLinkDiagnosticCode)
    }

    @Test
    fun `successful reconnect retains a genuine in-session network diagnostic`() {
        val status = LiveMachineVisionStatusAccumulator("automatic-pending")
        status.sessionReady()
        status.linkDiagnostic(LiveLinkDiagnosticCode.NETWORK_IO)
        status.linkInterrupted()
        status.sessionReady()
        status.phase(LiveMachineVisionPhase.STREAMING)

        val snapshot = status.snapshot()
        assertEquals(1L, snapshot.linkInterruptions)
        assertEquals("NETWORK_IO", snapshot.lastLinkDiagnosticCode)
        assertTrue(snapshot.accessibleSummary().contains("Previous link interruption diagnostic: NETWORK_IO"))
        assertFalse(snapshot.accessibleSummary().contains(". Link diagnostic: NETWORK_IO"))
    }

    @Test
    fun `link interruption completes an in-flight microphone window`() {
        listOf(LiveMicrophonePhase.REQUESTING, LiveMicrophonePhase.ACTIVE).forEach { phase ->
            val status = LiveMachineVisionStatusAccumulator("automatic-pending")
            status.microphoneState(phase)

            status.linkInterrupted()

            assertEquals(LiveMicrophonePhase.COMPLETE, status.snapshot().microphonePhase)
        }
    }
}
