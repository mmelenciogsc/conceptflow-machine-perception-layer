// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightweightTrackMaintenanceTest {
    @Test
    fun movingObjectKeepsStableIdentityAcrossSourceIdChange() {
        val maintainer = maintainer()
        val first = maintainer.updateKeyframe(
            frame(1L, 0L),
            listOf(track("qnn-a", 1L, 0L, left = 100, distanceMeters = 3.0)),
            pose(0L),
        ).tracks.single()

        val second = maintainer.updateKeyframe(
            frame(2L, 200_000_000L),
            listOf(track("qnn-b", 2L, 200_000_000L, left = 130, distanceMeters = 2.5)),
            pose(200_000_000L),
        ).tracks.single()

        assertEquals(first.stableTrackId, second.stableTrackId)
        assertEquals("qnn-b", second.sourceTrackId)
        assertTrue(second.centroidVelocityXPixelsPerSecond > 0.0)
        assertEquals(2.5, requireNotNull(second.approachVelocityMetersPerSecond), 1e-9)
        assertEquals(TrackEstimateValidity.OBSERVED, second.coordinateValidity.headRelative)
        assertEquals(TrackEstimateValidity.UNAVAILABLE, second.coordinateValidity.localWorld)
        assertNull(second.localWorldPositionMeters)
    }

    @Test
    fun headTurnRotatesStaticTrackWithoutInventingWorldTranslation() {
        val maintainer = maintainer()
        val measured = track(
            "door-a",
            1L,
            0L,
            classId = "door",
            motion = TemporalMotionEvidence.CONFIRMED_STATIC_WORLD,
        )
        maintainer.updateKeyframe(frame(1L, 0L), listOf(measured), pose(0L))
        val halfAngle = PI / 4.0
        val yawRight = UnitQuaternion(cos(halfAngle), 0.0, sin(halfAngle), 0.0)

        val rotated = maintainer.updatePose(TimestampedPose(100_000_000L, yawRight)).tracks.single()

        assertEquals(-measured.cameraVectorMeters!!.z, rotated.headRelativeVectorMeters!!.x, 1e-9)
        assertEquals(measured.cameraVectorMeters.x, rotated.headRelativeVectorMeters.z, 1e-9)
        assertEquals(TrackEstimateValidity.ORIENTATION_PROPAGATED, rotated.coordinateValidity.headRelative)
        assertEquals(TrackEstimateValidity.UNAVAILABLE, rotated.coordinateValidity.localWorld)
        assertFalse(rotated.headCameraTranslationApplied)
    }

    @Test
    fun shortOcclusionPredictsOnlyUntilBoundedTtl() {
        val maintainer = maintainer(ttlNanos = 1_000_000_000L, postInferenceHoldNanos = 0L)
        maintainer.updateKeyframe(
            frame(1L, 0L),
            listOf(track("chair-a", 1L, 0L, classId = "chair")),
            pose(0L),
        )

        val occluded = maintainer.updateKeyframe(frame(2L, 333_000_000L), emptyList(), pose(333_000_000L))
            .tracks.single()

        assertEquals(1, occluded.missedKeyframes)
        assertEquals(TrackEstimateValidity.MOTION_PREDICTED, occluded.coordinateValidity.image2d)
        assertTrue(occluded.covariance.imageXxPixelsSquared > 4.0)
        assertTrue(maintainer.snapshot(1_000_000_000L).isNotEmpty())
        assertTrue(maintainer.snapshot(1_000_000_001L).isEmpty())
    }

    @Test
    fun depthFreshnessExpiresIndependentlyOfTrackTtl() {
        val maintainer = maintainer(ttlNanos = 1_500_000_000L, maximumDepthAgeNanos = 500_000_000L)
        maintainer.updateKeyframe(frame(1L, 0L), listOf(track("post-a", 1L, 0L)), pose(0L))

        assertTrue(maintainer.snapshot(500_000_000L).single().depthFresh)
        val stale = maintainer.snapshot(500_000_001L).single()
        assertFalse(stale.depthFresh)
        assertNotNull(stale.metricDepth)
    }

    @Test
    fun sameSourceIdCannotBridgeAnImplausibleDepthJump() {
        val maintainer = maintainer()
        val first = maintainer.updateKeyframe(
            frame(1L, 0L),
            listOf(track("reused", 1L, 0L, distanceMeters = 3.0)),
            pose(0L),
        ).tracks.single()

        val update = maintainer.updateKeyframe(
            frame(2L, 200_000_000L),
            listOf(track("reused", 2L, 200_000_000L, distanceMeters = 0.2)),
            pose(200_000_000L),
        )

        assertEquals(2, update.tracks.size)
        assertTrue(update.tracks.any { it.stableTrackId == first.stableTrackId && it.missedKeyframes == 1 })
        assertTrue(update.tracks.any { it.stableTrackId != first.stableTrackId && it.missedKeyframes == 0 })
    }

    @Test
    fun appearanceDescriptorsAssociateOnlyThroughAnExplicitHook() {
        val descriptor = floatArrayOf(1.0f, 0.0f)
        val withoutHook = maintainer()
        val firstWithoutHook = withoutHook.updateKeyframe(
            frame(1L, 0L),
            listOf(track("source-a", 1L, 0L).copy(maskGeometry = null)),
            pose(0L),
            mapOf("source-a" to descriptor),
        ).tracks.single()
        val secondWithoutHook = withoutHook.updateKeyframe(
            frame(2L, 200_000_000L),
            listOf(track("source-b", 2L, 200_000_000L).copy(maskGeometry = null)),
            pose(200_000_000L),
            mapOf("source-b" to descriptor),
        ).tracks.first { it.missedKeyframes == 0 }
        assertFalse(firstWithoutHook.stableTrackId == secondWithoutHook.stableTrackId)

        val withHook = maintainer(
            appearanceSimilarity = TrackAppearanceSimilarity { previous, observed ->
                if (previous.contentEquals(observed)) 1.0 else 0.0
            },
        )
        val firstWithHook = withHook.updateKeyframe(
            frame(1L, 0L),
            listOf(track("source-a", 1L, 0L).copy(maskGeometry = null)),
            pose(0L),
            mapOf("source-a" to descriptor),
        ).tracks.single()
        val secondWithHook = withHook.updateKeyframe(
            frame(2L, 200_000_000L),
            listOf(track("source-b", 2L, 200_000_000L).copy(maskGeometry = null)),
            pose(200_000_000L),
            mapOf("source-b" to descriptor),
        ).tracks.single()
        assertEquals(firstWithHook.stableTrackId, secondWithHook.stableTrackId)
    }

    @Test
    fun staleCapturePoseCannotSeedLocalWorldState() {
        val maintainer = maintainer()
        val update = maintainer.updateKeyframe(
            frame(1L, 0L),
            listOf(
                track(
                    "door-a",
                    1L,
                    0L,
                    classId = "door",
                    motion = TemporalMotionEvidence.CONFIRMED_STATIC_WORLD,
                ),
            ),
            pose(300_000_000L, position(0.0, "vio-a")),
        )

        assertTrue(update.accepted)
        assertEquals("keyframe_updated_pose_too_far_from_keyframe", update.reason)
        assertEquals(TrackEstimateValidity.UNAVAILABLE, update.tracks.single().coordinateValidity.localWorld)
        assertEquals(
            TrackEstimateValidity.UNAVAILABLE,
            maintainer.updatePose(pose(400_000_000L, position(1.0, "vio-a")))
                .tracks.single().coordinateValidity.localWorld,
        )
    }

    @Test
    fun inferenceCompletingAfterTrackTtlIsNeverPublished() {
        val maintainer = maintainer(
            ttlNanos = 1_000_000_000L,
            postInferenceHoldNanos = 1_250_000_000L,
        )
        val staleInference = track("late", 1L, 0L).copy(
            sourceInferenceMonotonicTimestampNanos = 1_000_000_001L,
        )

        val update = maintainer.updateKeyframe(frame(1L, 0L), listOf(staleInference), pose(0L))

        assertTrue(update.tracks.isEmpty())
        assertTrue(update.sourceToStableTrackIds.isEmpty())
        assertEquals(listOf("maint-00000001"), update.evictedTrackIds)
    }

    @Test
    fun admittedLateInferenceRetainsBoundedPostInferenceContinuityWithoutRefreshingDepth() {
        val maintainer = maintainer(
            ttlNanos = 1_500_000_000L,
            maximumDepthAgeNanos = 500_000_000L,
            postInferenceHoldNanos = 1_250_000_000L,
        )
        val delayed = track("chair-a", 1L, 0L, classId = "chair").copy(
            sourceInferenceMonotonicTimestampNanos = 1_400_000_000L,
        )

        val published = maintainer.updateKeyframe(frame(1L, 0L), listOf(delayed), pose(0L))
            .tracks.single()

        assertEquals(2_650_000_000L, published.expiresAtTimestampNanos)
        assertFalse(published.depthFresh)
        assertEquals(TrackEstimateValidity.OBSERVED, published.coordinateValidity.image2d)
        assertTrue(maintainer.snapshot(2_650_000_000L).isNotEmpty())
        assertTrue(maintainer.snapshot(2_650_000_001L).isEmpty())
    }

    @Test
    fun lowConfidenceProposalRequiresThreeConsistentObservationsBeforePublication() {
        val maintainer = maintainer()

        val first = maintainer.updateKeyframe(
            frame(1L, 0L),
            listOf(track("candidate", 1L, 0L, confidence = 0.50)),
            pose(0L),
        ).tracks.single()
        val second = maintainer.updateKeyframe(
            frame(2L, 400_000_000L),
            listOf(track("candidate", 2L, 400_000_000L, confidence = 0.51)),
            pose(400_000_000L),
        ).tracks.single()
        val third = maintainer.updateKeyframe(
            frame(3L, 800_000_000L),
            listOf(track("candidate", 3L, 800_000_000L, confidence = 0.49)),
            pose(800_000_000L),
        ).tracks.single()

        assertFalse(first.confirmedForPublication)
        assertFalse(second.confirmedForPublication)
        assertTrue(third.confirmedForPublication)
        assertEquals(first.stableTrackId, third.stableTrackId)
    }

    @Test
    fun strongObservationPublishesImmediatelyAndConfirmationIsSticky() {
        val maintainer = maintainer()

        val strong = maintainer.updateKeyframe(
            frame(1L, 0L),
            listOf(track("chair", 1L, 0L, classId = "chair", confidence = 0.70)),
            pose(0L),
        ).tracks.single()
        val weaker = maintainer.updateKeyframe(
            frame(2L, 400_000_000L),
            listOf(track("chair", 2L, 400_000_000L, classId = "chair", confidence = 0.47)),
            pose(400_000_000L),
        ).tracks.single()

        assertTrue(strong.confirmedForPublication)
        assertTrue(weaker.confirmedForPublication)
    }

    @Test
    fun oneFrameLowConfidenceProposalIsPrunedBeforeItCanLeak() {
        val maintainer = maintainer()
        maintainer.updateKeyframe(
            frame(1L, 0L),
            listOf(track("candidate", 1L, 0L, confidence = 0.50)),
            pose(0L),
        )

        assertEquals(1, maintainer.updateKeyframe(frame(2L, 400_000_000L), emptyList(), pose(400_000_000L))
            .tracks.single().missedKeyframes)
        assertTrue(
            maintainer.updateKeyframe(frame(3L, 800_000_000L), emptyList(), pose(800_000_000L))
                .tracks.isEmpty(),
        )
    }

    @Test
    fun strongGeometryAndDepthPreserveIdentityWhileLabelVotingRejectsOneFrameFlicker() {
        val maintainer = maintainer()
        val chair = maintainer.updateKeyframe(
            frame(1L, 0L),
            listOf(track("chair-source", 1L, 0L, classId = "chair", confidence = 0.90)),
            pose(0L),
        ).tracks.single()

        val oneFrameFlicker = maintainer.updateKeyframe(
            frame(2L, 400_000_000L),
            listOf(track("stool-source", 2L, 400_000_000L, classId = "stool", confidence = 0.90)),
            pose(400_000_000L),
        ).tracks.single()
        val sustainedReplacement = maintainer.updateKeyframe(
            frame(3L, 800_000_000L),
            listOf(track("stool-source", 3L, 800_000_000L, classId = "stool", confidence = 0.90)),
            pose(800_000_000L),
        ).tracks.single()

        assertEquals(chair.stableTrackId, oneFrameFlicker.stableTrackId)
        assertEquals("chair", oneFrameFlicker.classId)
        assertEquals(chair.stableTrackId, sustainedReplacement.stableTrackId)
        assertEquals("stool", sustainedReplacement.classId)
    }

    @Test
    fun frameSuppliedExtrinsicIsSessionBoundAndResettable() {
        val maintainer = LightweightTrackMaintainer()
        val first = VerifiedHeadCameraExtrinsic(UnitQuaternion.IDENTITY, "a".repeat(64))
        val replacement = VerifiedHeadCameraExtrinsic(UnitQuaternion.IDENTITY, "b".repeat(64))

        assertTrue(maintainer.configureHeadCameraExtrinsic(first))
        assertFalse(maintainer.configureHeadCameraExtrinsic(replacement))
        maintainer.reset()
        assertTrue(maintainer.configureHeadCameraExtrinsic(replacement))
    }

    @Test
    fun capacityEvictionIsDeterministicAndPrefersLowestConfidence() {
        val maintainer = maintainer(capacity = 2)
        val update = maintainer.updateKeyframe(
            frame(1L, 0L),
            listOf(
                track("strong", 1L, 0L, classId = "door", confidence = 0.90, left = 50),
                track("weak", 1L, 0L, classId = "chair", confidence = 0.10, left = 250),
                track("medium", 1L, 0L, classId = "person", confidence = 0.80, left = 450),
            ),
            pose(0L),
        )

        assertEquals(listOf("door", "person"), update.tracks.map { it.classId }.sorted())
        assertEquals(listOf("maint-00000003"), update.evictedTrackIds)
        assertFalse(update.sourceToStableTrackIds.containsKey("weak"))
    }

    @Test
    fun localWorldValidityRequiresMatchingPositionEvidence() {
        val origin = position(0.0, "vio-a")
        val moved = position(1.0, "vio-a")
        val maintainer = maintainer()
        maintainer.updateKeyframe(
            frame(1L, 0L),
            listOf(
                track(
                    "door-a",
                    1L,
                    0L,
                    classId = "door",
                    motion = TemporalMotionEvidence.CONFIRMED_STATIC_WORLD,
                ),
            ),
            pose(0L, origin),
        )

        val translated = maintainer.updatePose(pose(100_000_000L, moved)).tracks.single()
        assertEquals(TrackEstimateValidity.TRANSLATION_EVIDENCE_PROPAGATED, translated.coordinateValidity.localWorld)
        // The camera-relative vector shrinks from 3 m to 2 m, while the world anchor remains at z=3 m.
        assertEquals(3.0, translated.localWorldPositionMeters!!.z, 1e-9)

        val orientationOnly = maintainer.updatePose(pose(200_000_000L)).tracks.single()
        assertEquals(TrackEstimateValidity.UNAVAILABLE, orientationOnly.coordinateValidity.localWorld)
        assertNull(orientationOnly.localWorldPositionMeters)
    }

    @Test
    fun latestOnlySchedulerUsesOneFpsStableAndThreeFpsMaterialCadence() {
        val scheduler = LatestOnlySemanticDepthScheduler()
        val stable = signal()
        assertTrue(scheduler.offer(frame(1L, 0L), stable))
        assertEquals(SemanticDepthRefreshReason.INITIAL, scheduler.takeLatest(0L).reason)

        assertTrue(scheduler.offer(frame(2L, 100_000_000L), stable))
        assertTrue(scheduler.offer(frame(3L, 1_000_000_000L), stable.copy(vlmRequested = true, vlmIdleCapacityAvailable = true)))
        val stableRefresh = scheduler.takeLatest(1_000_000_000L)
        assertEquals(3L, stableRefresh.frame!!.frameId)
        assertEquals(SemanticDepthRefreshReason.STABLE_CADENCE, stableRefresh.reason)
        assertEquals(SemanticDepthCadenceTier.STABLE, stableRefresh.cadenceTier)
        assertTrue(stableRefresh.opportunisticVlmAllowed)
        assertEquals(1L, stableRefresh.replacedPendingFrames)

        assertTrue(scheduler.offer(frame(4L, 1_332_000_000L), signal(motion = 1.0)))
        assertEquals(
            SemanticDepthRefreshReason.DEFERRED_BY_CADENCE,
            scheduler.takeLatest(1_332_000_000L).reason,
        )
        assertTrue(scheduler.offer(frame(5L, 1_334_000_000L), signal(motion = 1.0, vlm = true)))
        val forced = scheduler.takeLatest(1_334_000_000L)
        assertEquals(SemanticDepthRefreshReason.MOTION, forced.reason)
        assertEquals(SemanticDepthCadenceTier.MATERIAL, forced.cadenceTier)
        assertTrue(forced.forced)
        assertFalse(forced.opportunisticVlmAllowed)
    }

    @Test
    fun staleDepthOcclusionAndRapidApproachForceRefreshWithoutVlm() {
        listOf(
            signal(depthAgeNanos = 500_000_000L) to SemanticDepthRefreshReason.DEPTH_STALE,
            signal(occluded = 1) to SemanticDepthRefreshReason.OCCLUSION,
            signal(approach = 0.75) to SemanticDepthRefreshReason.RAPID_APPROACH,
            signal(minimumConfidence = 0.45) to SemanticDepthRefreshReason.LOW_CONFIDENCE,
        ).forEachIndexed { index, (refresh, expectedReason) ->
            val scheduler = LatestOnlySemanticDepthScheduler()
            scheduler.offer(frame(1L, 0L), signal())
            scheduler.takeLatest(0L)
            scheduler.offer(frame(2L, 200_000_000L), refresh.copy(vlmRequested = true, vlmIdleCapacityAvailable = true))

            val decision = scheduler.takeLatest(200_000_000L)
            assertEquals("case $index", expectedReason, decision.reason)
            assertEquals(SemanticDepthCadenceTier.URGENT, decision.cadenceTier)
            assertTrue(decision.forced)
            assertFalse(decision.opportunisticVlmAllowed)
        }
    }

    @Test
    fun directMotionOutranksRoutineDepthStalenessForVlmInterruption() {
        val scheduler = LatestOnlySemanticDepthScheduler()
        scheduler.offer(frame(1L, 0L), signal())
        scheduler.takeLatest(0L)
        scheduler.offer(
            frame(2L, 334_000_000L),
            signal(motion = 1.0, depthAgeNanos = 500_000_000L),
        )

        val decision = scheduler.takeLatest(334_000_000L)
        assertEquals(SemanticDepthRefreshReason.MOTION, decision.reason)
        assertEquals(SemanticDepthCadenceTier.MATERIAL, decision.cadenceTier)
    }

    @Test
    fun liveAdmissionDecimatesStableCameraCadenceAndUsesCameraRateOnlyAsMotionHint() {
        val stablePolicy = LiveSemanticDepthAdmissionPolicy()
        val stableDecisions = listOf(0L, 334_000_000L, 668_000_000L, 1_002_000_000L)
            .mapIndexed { index, timestamp ->
                stablePolicy.evaluate(frame(index + 1L, timestamp), emptyList(), true, true)
            }
        assertEquals(listOf(true, false, false, true), stableDecisions.map { it.runSemanticAndDepth })
        assertEquals(SemanticDepthCadenceTier.STABLE, stableDecisions.last().cadenceTier)
        assertTrue(stableDecisions.first().opportunisticVlmAllowed)

        val motionPolicy = LiveSemanticDepthAdmissionPolicy()
        val first = motionPolicy.evaluate(frame(1L, 0L), emptyList(), true, true)
        val deferred = motionPolicy.evaluate(frame(2L, 200_000_000L), emptyList(), true, true)
        val admitted = motionPolicy.evaluate(frame(3L, 400_000_000L), emptyList(), true, true)
        assertTrue(first.runSemanticAndDepth)
        assertFalse(deferred.runSemanticAndDepth)
        assertEquals(SemanticDepthRefreshReason.MOTION, admitted.reason)
        assertEquals(SemanticDepthCadenceTier.MATERIAL, admitted.cadenceTier)
        assertFalse(admitted.opportunisticVlmAllowed)

        val bootstrapPolicy = LiveSemanticDepthAdmissionPolicy()
        val bootstrap = listOf(0L, 200_000_000L, 400_000_000L, 600_000_000L, 800_000_000L, 1_000_000_000L)
            .mapIndexed { index, timestamp ->
                bootstrapPolicy.evaluate(
                    frame(index + 1L, timestamp),
                    emptyList(),
                    requestOpportunisticVlm = true,
                    vlmCapacityAvailable = true,
                    suppressCameraCadenceMotionForVlmBootstrap = true,
                )
            }
        assertEquals(listOf(true, false, false, false, false, true), bootstrap.map { it.runSemanticAndDepth })
        assertEquals(SemanticDepthCadenceTier.STABLE, bootstrap.last().cadenceTier)
        assertTrue(bootstrap.last().opportunisticVlmAllowed)
        val afterBootstrap = bootstrapPolicy.evaluate(
            frame(7L, 1_200_000_000L),
            emptyList(),
            requestOpportunisticVlm = true,
            vlmCapacityAvailable = true,
            suppressCameraCadenceMotionForVlmBootstrap = false,
        )
        assertEquals(SemanticDepthCadenceTier.MATERIAL, afterBootstrap.cadenceTier)
        assertFalse(afterBootstrap.opportunisticVlmAllowed)

        val urgentPolicy = LiveSemanticDepthAdmissionPolicy()
        urgentPolicy.evaluate(frame(1L, 0L), emptyList(), true, true)
        val lowConfidenceTracks = maintainer().updateKeyframe(
            frame(1L, 0L),
            listOf(track("uncertain", 1L, 0L, confidence = 0.40)),
            pose(0L),
        ).tracks
        val urgent = urgentPolicy.evaluate(frame(2L, 334_000_000L), lowConfidenceTracks, true, true)
        assertEquals(SemanticDepthRefreshReason.LOW_CONFIDENCE, urgent.reason)
        assertEquals(SemanticDepthCadenceTier.URGENT, urgent.cadenceTier)
        assertFalse(urgent.opportunisticVlmAllowed)

        val staleTrackScheduler = LatestOnlySemanticDepthScheduler()
        staleTrackScheduler.offer(frame(1L, 0L), signal())
        staleTrackScheduler.takeLatest(0L)
        staleTrackScheduler.offer(frame(2L, 334_000_000L), signal(oldestTrackAgeNanos = 750_000_000L))
        val staleTrack = staleTrackScheduler.takeLatest(334_000_000L)
        assertEquals(SemanticDepthRefreshReason.TRACK_STALE, staleTrack.reason)
        assertEquals(SemanticDepthCadenceTier.MATERIAL, staleTrack.cadenceTier)
    }

    private fun maintainer(
        capacity: Int = 8,
        ttlNanos: Long = 1_500_000_000L,
        maximumDepthAgeNanos: Long = 500_000_000L,
        postInferenceHoldNanos: Long = 1_250_000_000L,
        appearanceSimilarity: TrackAppearanceSimilarity? = null,
    ) = LightweightTrackMaintainer(
        capacity = capacity,
        trackTtlNanos = ttlNanos,
        minimumPostInferenceHoldNanos = postInferenceHoldNanos,
        maximumDepthAgeNanos = maximumDepthAgeNanos,
        appearanceSimilarity = appearanceSimilarity,
        headFromCamera = VerifiedHeadCameraExtrinsic(
            UnitQuaternion.IDENTITY,
            "a".repeat(64),
        ),
    )

    private fun track(
        sourceId: String,
        frameId: Long,
        timestampNanos: Long,
        classId: String = "person",
        confidence: Double = 0.9,
        left: Int = 100,
        distanceMeters: Double = 3.0,
        motion: TemporalMotionEvidence = TemporalMotionEvidence.DYNAMIC,
    ): MetricSemanticTrack {
        val geometry = geometry(left)
        return MetricSemanticTrack(
            frameId = frameId,
            trackId = sourceId,
            classId = classId,
            confidence = confidence,
            representativeDistance = MetricDepthEstimate(distanceMeters, 0.10, false),
            depthEnvironment = DepthEnvironment.INDOOR,
            sourceCaptureMonotonicTimestampNanos = timestampNanos,
            sourceInferenceMonotonicTimestampNanos = timestampNanos + 1L,
            maskGeometry = geometry,
            cameraVectorMeters = MetricVector3(
                (geometry.centroidXPixels - 320.0) * distanceMeters / 500.0,
                (geometry.centroidYPixels - 240.0) * distanceMeters / 500.0,
                distanceMeters,
            ),
            temporalMotionEvidence = motion,
        )
    }

    private fun frame(id: Long, timestampNanos: Long) = VisionFrame(
        id,
        timestampNanos,
        640,
        480,
        synthetic = true,
        cameraIntrinsics = CameraIntrinsics(640, 480, 500.0, 500.0, 320.0, 240.0),
    )

    private fun geometry(left: Int) = InstanceMaskGeometry(
        640,
        480,
        left,
        190,
        left + 80,
        290,
        left + 39.5,
        239.5,
        6_000,
    )

    private fun pose(timestampNanos: Long, positionEvidence: PositionEvidence? = null) = TimestampedPose(
        timestampNanos,
        UnitQuaternion.IDENTITY,
        positionEvidence,
    )

    private fun position(z: Double, frameId: String) = PositionEvidence(
        MetricVector3(0.0, 0.0, z),
        0.02,
        PositionEvidenceSource.VIO,
        frameId,
    )

    private fun signal(
        motion: Double = 0.0,
        depthAgeNanos: Long = 0L,
        occluded: Int = 0,
        approach: Double = 0.0,
        vlm: Boolean = false,
        minimumConfidence: Double = 1.0,
        oldestTrackAgeNanos: Long = 0L,
    ) = SemanticDepthRefreshSignal(
        visual = VisualKeyframeSignal(motion, 0.0),
        minimumTrackConfidence = minimumConfidence,
        oldestTrackAgeNanos = oldestTrackAgeNanos,
        depthAgeNanos = depthAgeNanos,
        occludedTrackCount = occluded,
        maximumApproachVelocityMetersPerSecond = approach,
        vlmRequested = vlm,
        vlmIdleCapacityAvailable = vlm,
    )
}
