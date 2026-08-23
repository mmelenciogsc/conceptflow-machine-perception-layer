// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMachineVisionStatusAccumulatorTest {
    @Test
    fun `reports bounded aggregate percentiles without captured content`() {
        val status = LiveMachineVisionStatusAccumulator("depth-indoor-392")
        status.phase(LiveMachineVisionPhase.STREAMING)
        status.cameraReceived()
        status.cameraReceived()
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
        assertEquals(1L, snapshot.framesDroppedBeforeInference)
        assertEquals(4L, snapshot.inferenceSuccesses)
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

        val snapshot = status.snapshot()
        assertEquals(1L, snapshot.linkInterruptions)
        assertEquals("NETWORK_IO", snapshot.lastLinkDiagnosticCode)
        assertTrue(snapshot.accessibleSummary().contains("NETWORK_IO"))
    }
}
