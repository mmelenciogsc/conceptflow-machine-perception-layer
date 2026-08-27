// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.conceptflow.mpl.host.vision.HeadPoseObservation
import org.conceptflow.mpl.host.vision.LiveMetricFusionReason
import org.conceptflow.mpl.host.vision.LiveMetricFusionResult
import org.conceptflow.mpl.host.vision.LightweightTrackCovariance
import org.conceptflow.mpl.host.vision.LightweightTrackState
import org.conceptflow.mpl.host.vision.MachineVisionInference
import org.conceptflow.mpl.host.vision.MetricDepthEstimate
import org.conceptflow.mpl.host.vision.MetricSemanticTrack
import org.conceptflow.mpl.host.vision.QnnLiveFrameResult
import org.conceptflow.mpl.host.vision.TemporalMotionEvidence
import org.conceptflow.mpl.host.vision.TrackCoordinateValidity
import org.conceptflow.mpl.host.vision.TrackEstimateValidity
import org.conceptflow.mpl.host.vision.UnitQuaternion
import org.conceptflow.mpl.v1.RokidTouchAction
import org.conceptflow.mpl.v1.RokidTouchEvent
import org.conceptflow.mpl.v1.RokidTouchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptionBusTest {
    @Test
    fun `latest state is versioned compact and expires entities`() {
        val bus = PerceptionBus(stateTtlNanos = 1_000_000L)
        bus.beginSession(7L, 1_000L)
        bus.publishHeadPose(HeadPoseObservation(1_010L, UnitQuaternion.IDENTITY, 3))
        val state = bus.publishPerception(result(), fusion(), 1_000L, 1_100L)

        assertEquals(PerceptionValidityReason.PERCEPTION_READY, state.validity)
        assertEquals(1, state.entities.size)
        assertNotNull(bus.latestAfter(state.revision - 1L, 1_150L))
        assertNull(bus.latestAfter(state.revision, 1_150L))
        val expired = requireNotNull(bus.latestAfter(state.revision - 1L, 1_001_101L))
        assertEquals(PerceptionValidityReason.SENSOR_STREAM_ACTIVE, expired.validity)
        assertTrue(expired.entities.isEmpty())

        val bytes = PerceptionBusBinaryCodec.encodeWorld(state)
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x43465753, input.int)
        assertEquals(1, input.short.toInt())
        assertEquals(PerceptionValidityReason.PERCEPTION_READY.wireValue, input.short.toInt())
        assertEquals(state.revision, input.long)
    }

    @Test
    fun `touch queue rejects overflow and preserves order`() {
        val bus = PerceptionBus(touchCapacity = 2)
        bus.beginSession(1L, 1L)
        assertTrue(bus.publishTouch(touch(1L)))
        assertTrue(bus.publishTouch(touch(2L)))
        assertFalse(bus.publishTouch(touch(3L)))

        assertEquals(listOf(1L, 2L), bus.drainTouch(2).map { it.eventId })
        assertEquals(1L, bus.stats().touchEventsRejected)
        val encoded = PerceptionBusBinaryCodec.encodeTouchBatch(listOf())
        assertEquals(0x43465442, ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN).int)
    }

    @Test
    fun `maintained tracks retain camera head and evidence backed world frames`() {
        val bus = PerceptionBus()
        bus.beginSession(1L, 0L)
        val camera = maintained(
            "camera",
            cameraVector = org.conceptflow.mpl.host.vision.MetricVector3(1.0, 0.0, 2.0),
        )
        val head = maintained(
            "head",
            cameraVector = org.conceptflow.mpl.host.vision.MetricVector3(1.0, 0.0, 2.0),
            headVector = org.conceptflow.mpl.host.vision.MetricVector3(2.0, 0.0, 1.0),
        )
        val world = maintained(
            "world",
            cameraVector = org.conceptflow.mpl.host.vision.MetricVector3(1.0, 0.0, 2.0),
            headVector = org.conceptflow.mpl.host.vision.MetricVector3(2.0, 0.0, 1.0),
            worldVector = org.conceptflow.mpl.host.vision.MetricVector3(4.0, 0.0, 7.0),
        )
        val imageOnly = maintained("image-only", cameraVector = null)

        val state = bus.publishTrackedPerception(
            9L,
            1_000L,
            1_100L,
            "depth-profile",
            "measured",
            listOf(camera, head, world, imageOnly),
        )

        assertEquals(PerceptionCoordinateFrame.CAMERA, state.entities[0].coordinateFrame)
        assertEquals(camera.cameraRelativeVectorMeters, state.entities[0].positionMeters)
        assertFalse(state.entities[0].propagated)
        assertEquals(PerceptionCoordinateFrame.HEAD, state.entities[1].coordinateFrame)
        assertEquals(head.headRelativeVectorMeters, state.entities[1].positionMeters)
        assertFalse(state.entities[1].propagated)
        assertEquals(PerceptionCoordinateFrame.WORLD, state.entities[2].coordinateFrame)
        assertEquals(world.localWorldPositionMeters, state.entities[2].positionMeters)
        assertTrue(state.entities[2].propagated)
        assertEquals(PerceptionCoordinateFrame.CAMERA, state.entities[3].coordinateFrame)
        assertNull(state.entities[3].positionMeters)
    }

    @Test
    fun `new session and invalidation clear prior head state`() {
        val bus = PerceptionBus()
        bus.beginSession(1L, 0L)
        bus.publishHeadPose(HeadPoseObservation(10L, UnitQuaternion.IDENTITY, 3))
        val withHead = bus.publishTrackedPerception(0L, 10L, 10L, "", "pose", emptyList())
        assertNotNull(withHead.head)

        bus.beginSession(2L, 20L)
        assertNull(requireNotNull(bus.latestAfter(0L, 20L)).head)
        bus.publishHeadPose(HeadPoseObservation(30L, UnitQuaternion.IDENTITY, 3))
        bus.invalidate(PerceptionValidityReason.DISCONNECTED, 40L)
        assertNull(requireNotNull(bus.latestAfter(0L, 40L)).head)
    }

    private fun touch(id: Long) = TimedTouchEvent(
        RokidTouchEvent.newBuilder().setEventId(id).setObservedMonotonicTimestampNs(id)
            .setSourceUptimeMs(id).setKey(RokidTouchKey.ROKID_TOUCH_KEY_SWIPE_FORWARD)
            .setAction(RokidTouchAction.ROKID_TOUCH_ACTION_DOWN).setScanCode(115).build(),
        id,
        1L,
    )

    private fun fusion() = LiveMetricFusionResult(
        LiveMetricFusionReason.CAMERA_METRIC_TRACKS_READY_PROPAGATION_EXTRINSIC_MISSING,
        "head_camera_extrinsic_missing",
        1,
        listOf(
            MetricSemanticTrack(
                9L,
                "track-1",
                "chair",
                .9,
                MetricDepthEstimate(2.0, null, false,
                    org.conceptflow.mpl.host.vision.MetricDepthProvenance(
                        org.conceptflow.mpl.host.vision.MetricDepthProvenanceKind.PINNED_OFFICIAL_NATIVE_METRIC,
                        "depth-anything-v2-metric-indoor-392-fp16",
                        "depth-anything/Depth-Anything-V2-Metric-Hypersim-Small",
                        "3bc65d4e14a6786a61acec16453c50e12bf5f338",
                        "b782898d8a3e8be1f639de33837ed85e9b4b73e40f8f5e5cd99067588d722545",
                        20.0,
                    ),
                    org.conceptflow.mpl.host.vision.MetricDepthUncertaintyBasis.UNQUANTIFIED_MODEL_ERROR),
                org.conceptflow.mpl.host.vision.DepthEnvironment.INDOOR,
                1_000L,
                1_100L,
                temporalMotionEvidence = TemporalMotionEvidence.CONFIRMED_STATIC_WORLD,
            ),
        ),
        emptyList(),
    )

    private fun result() = QnnLiveFrameResult(
        9L,
        "depth-anything-v2-metric-indoor-392-fp16",
        1,
        QnnLiveFrameResult.YOLO_FINITE_VALUE_COUNT,
        QnnLiveFrameResult.DEPTH_FINITE_VALUE_COUNT,
        0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L,
        org.conceptflow.mpl.host.vision.LiveMetricCalibrationState.PROFILE_BOUND_NATIVE_METRIC_AVAILABLE,
        MachineVisionInference(
            9L,
            1_100L,
            org.conceptflow.mpl.host.vision.MachineVisionModelProfiles.fixedVocabularySha256,
            "depth-anything-v2-metric-indoor-392-fp16",
            listOf(
                org.conceptflow.mpl.host.vision.SemanticMaskObservation(
                    "track-1", "chair", .9, listOf(2.0), temporalMotionEvidence = TemporalMotionEvidence.CONFIRMED_STATIC_WORLD,
                ),
            ),
        ),
    )

    private fun maintained(
        id: String,
        cameraVector: org.conceptflow.mpl.host.vision.MetricVector3?,
        headVector: org.conceptflow.mpl.host.vision.MetricVector3? = null,
        worldVector: org.conceptflow.mpl.host.vision.MetricVector3? = null,
    ) = LightweightTrackState(
        stableTrackId = id,
        sourceTrackId = id,
        classId = "chair",
        sourceFrameId = 9L,
        sourceCaptureTimestampNanos = 1_000L,
        sourceInferenceTimestampNanos = 1_010L,
        outputTimestampNanos = 1_100L,
        expiresAtTimestampNanos = 2_000_000_000L,
        confidence = 0.9,
        coordinateValidity = TrackCoordinateValidity(
            image2d = TrackEstimateValidity.OBSERVED,
            cameraRelative = if (cameraVector == null) {
                TrackEstimateValidity.UNAVAILABLE
            } else {
                TrackEstimateValidity.OBSERVED
            },
            headRelative = if (headVector == null) {
                TrackEstimateValidity.UNAVAILABLE
            } else {
                TrackEstimateValidity.OBSERVED
            },
            localWorld = if (worldVector == null) {
                TrackEstimateValidity.UNAVAILABLE
            } else {
                TrackEstimateValidity.TRANSLATION_EVIDENCE_PROPAGATED
            },
        ),
        imageGeometry = org.conceptflow.mpl.host.vision.InstanceMaskGeometry(
            640, 480, 10, 10, 30, 30,
        ),
        cameraRelativeVectorMeters = cameraVector,
        headRelativeVectorMeters = headVector,
        localWorldPositionMeters = worldVector,
        metricDepth = MetricDepthEstimate(2.0, 0.1, false),
        depthFresh = true,
        centroidVelocityXPixelsPerSecond = 0.0,
        centroidVelocityYPixelsPerSecond = 0.0,
        approachVelocityMetersPerSecond = null,
        missedKeyframes = 0,
        headCameraTranslationApplied = false,
        covariance = LightweightTrackCovariance(4.0, 0.0, 4.0, 0.01, 0.01, 0.04),
    )
}
