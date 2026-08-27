// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.focus

import org.conceptflow.mpl.host.vision.InstanceMaskGeometry
import org.conceptflow.mpl.host.vision.LightweightTrackCovariance
import org.conceptflow.mpl.host.vision.LightweightTrackState
import org.conceptflow.mpl.host.vision.MetricDepthEstimate
import org.conceptflow.mpl.host.vision.MetricVector3
import org.conceptflow.mpl.host.vision.TrackCoordinateValidity
import org.conceptflow.mpl.host.vision.TrackEstimateValidity
import org.conceptflow.mpl.host.realtime.TimedTouchEvent
import org.conceptflow.mpl.v1.RokidTouchAction
import org.conceptflow.mpl.v1.RokidTouchEvent
import org.conceptflow.mpl.v1.RokidTouchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialFocusTest {
    @Test
    fun `survivors retain snapshot order and new targets append`() {
        val manager = SpatialFocusManager()
        manager.updateTracks(1L, 1L, 100L, listOf(track("far", 2.0), track("near", 1.0)))
        var state = manager.command(SpatialFocusCommand.ACTIVATE, 200L).state
        assertEquals("near", state.target!!.stableTrackId)

        state = manager.updateTracks(
            1L, 2L, 300L,
            listOf(track("new-nearest", 0.5), track("far", 2.1), track("near", 1.1)),
        )
        assertEquals("near", state.target!!.stableTrackId)
        assertEquals("far", manager.command(SpatialFocusCommand.NEXT, 301L).state.target!!.stableTrackId)
        assertEquals("new-nearest", manager.command(SpatialFocusCommand.NEXT, 302L).state.target!!.stableTrackId)
        assertEquals("new-nearest", manager.command(SpatialFocusCommand.NEXT, 303L).state.target!!.stableTrackId)
    }

    @Test
    fun `previous clamps and dwell becomes ready exactly once per generation`() {
        val manager = SpatialFocusManager()
        manager.updateTracks(1L, 1L, 0L, listOf(track("only", 0.9144)))
        var state = manager.command(SpatialFocusCommand.PREVIOUS, 100L).state
        assertEquals(0, state.selectedIndex)
        assertEquals(SpatialFocusDwell.PENDING, state.dwell)
        val generation = state.focusGeneration
        assertEquals(SpatialFocusDwell.PENDING, manager.advance(749_999_999L).dwell)
        state = manager.advance(750_000_100L)
        assertEquals(SpatialFocusDwell.READY, state.dwell)
        val readyRevision = state.revision
        assertEquals(readyRevision, manager.advance(900_000_000L).revision)
        assertEquals(generation, state.focusGeneration)
    }

    @Test
    fun `TalkBack policy is silent during dwell and announces ready generation once`() {
        val manager = SpatialFocusManager()
        val policy = SpatialFocusAnnouncementPolicy()
        manager.updateTracks(1L, 1L, 0L, listOf(track("only", 1.0)))
        val pending = manager.command(SpatialFocusCommand.NEXT, 100L).state
        assertFalse(policy.shouldAnnounce(pending))
        val ready = manager.advance(750_000_100L)
        assertTrue(policy.shouldAnnounce(ready))
        assertFalse(policy.shouldAnnounce(ready.copy(revision = ready.revision + 1L)))

        val menu = manager.command(SpatialFocusCommand.ACTIVATE, 750_000_101L).state
        assertTrue(policy.shouldAnnounce(menu))
        val trackRefresh = manager.updateTracks(1L, 2L, 750_000_102L, listOf(track("only", 1.1)))
        assertFalse(policy.shouldAnnounce(trackRefresh))
        val nextMenuOption = manager.command(SpatialFocusCommand.NEXT, 750_000_103L).state
        assertTrue(policy.shouldAnnounce(nextMenuOption))
        assertFalse(policy.shouldAnnounce(nextMenuOption.copy(revision = nextMenuOption.revision + 1L)))
    }

    @Test
    fun `focus removal and session reset cancel dwell`() {
        val manager = SpatialFocusManager()
        manager.updateTracks(1L, 1L, 0L, listOf(track("one", 1.0)))
        manager.command(SpatialFocusCommand.ACTIVATE, 1L)
        val removed = manager.updateTracks(1L, 2L, 2L, emptyList())
        assertEquals(SpatialFocusMode.INACTIVE, removed.mode)
        assertEquals(SpatialFocusDwell.NONE, removed.dwell)
        assertEquals(0, removed.itemCount)
        assertEquals(0L, manager.reset(2L, 3L).snapshotId)
    }

    @Test
    fun `focused target removal closes its action menu without retargeting`() {
        val manager = SpatialFocusManager()
        manager.updateTracks(1L, 1L, 0L, listOf(track("one", 1.0), track("two", 2.0)))
        manager.command(SpatialFocusCommand.ACTIVATE, 1L)
        val menu = manager.command(SpatialFocusCommand.ACTIVATE, 2L).state
        val focusGeneration = menu.focusGeneration

        val removed = manager.updateTracks(1L, 2L, 3L, listOf(track("two", 2.0)))

        assertEquals(SpatialFocusMode.INACTIVE, removed.mode)
        assertEquals(null, removed.target)
        assertEquals(1, removed.itemCount)
        assertEquals(SpatialFocusDwell.NONE, removed.dwell)
        assertTrue(removed.focusGeneration > focusGeneration)
    }

    @Test
    fun `focused state lifetime is bounded by target expiry`() {
        val manager = SpatialFocusManager()
        manager.updateTracks(1L, 1L, 1_000_000_000L, listOf(track("one", 1.0)))

        val state = manager.command(SpatialFocusCommand.ACTIVATE, 1_000_000_001L).state

        assertEquals(2_000_000_000L, state.validUntilTimestampNanos)
    }

    @Test
    fun `menu is exactly VQA beacon back and unavailable VQA never reports success`() {
        val gateway = RecordingGateway(accept = true)
        val manager = SpatialFocusManager(vqaGateway = gateway)
        manager.updateTracks(1L, 1L, 0L, listOf(track("one", 1.0)))
        manager.command(SpatialFocusCommand.ACTIVATE, 1L)
        var transition = manager.command(SpatialFocusCommand.ACTIVATE, 2L)
        assertEquals(SpatialFocusMenuOption.VQA, transition.state.menuOption)
        transition = manager.command(SpatialFocusCommand.ACTIVATE, 3L)
        assertTrue(transition.effect is SpatialFocusEffect.RequestVqa)
        assertEquals(SpatialFocusMode.VQA_PENDING, transition.state.mode)
        transition = manager.command(SpatialFocusCommand.BACK, 4L)
        assertTrue(transition.effect is SpatialFocusEffect.CancelVqa)
        assertEquals(SpatialFocusMode.BROWSING, transition.state.mode)

        manager.command(SpatialFocusCommand.ACTIVATE, 5L)
        assertEquals(SpatialFocusMenuOption.BEACON, manager.command(SpatialFocusCommand.NEXT, 6L).state.menuOption)
        assertEquals(SpatialFocusMenuOption.BACK, manager.command(SpatialFocusCommand.NEXT, 7L).state.menuOption)
        assertEquals(SpatialFocusMenuOption.BACK, manager.command(SpatialFocusCommand.NEXT, 8L).state.menuOption)
        assertEquals(SpatialFocusMode.BROWSING, manager.command(SpatialFocusCommand.ACTIVATE, 9L).state.mode)
    }

    @Test
    fun `navigation exits VQA result and active beacon before selecting another target`() {
        val gateway = RecordingGateway(accept = true)
        val manager = SpatialFocusManager(vqaGateway = gateway)
        manager.updateTracks(1L, 1L, 0L, listOf(track("one", 1.0), track("two", 2.0)))
        manager.command(SpatialFocusCommand.ACTIVATE, 1L)
        manager.command(SpatialFocusCommand.ACTIVATE, 2L)
        val request = (manager.command(SpatialFocusCommand.ACTIVATE, 3L).effect as SpatialFocusEffect.RequestVqa).request
        assertTrue(manager.completeVqa(request.correlation, "A chair.", 4L))
        val afterResult = manager.command(SpatialFocusCommand.NEXT, 5L)
        assertEquals(SpatialFocusMode.BROWSING, afterResult.state.mode)
        assertEquals("two", afterResult.state.target!!.stableTrackId)

        manager.command(SpatialFocusCommand.ACTIVATE, 6L)
        manager.command(SpatialFocusCommand.NEXT, 7L)
        assertEquals(SpatialFocusMode.BEACON_ACTIVE, manager.command(SpatialFocusCommand.ACTIVATE, 8L).state.mode)
        val afterBeacon = manager.command(SpatialFocusCommand.PREVIOUS, 9L)
        assertEquals(SpatialFocusMode.BROWSING, afterBeacon.state.mode)
        assertEquals("one", afterBeacon.state.target!!.stableTrackId)
        assertEquals(false, (afterBeacon.effect as SpatialFocusEffect.BeaconChanged).active)
    }

    @Test
    fun `VQA failure completes only the exact pending correlation`() {
        val gateway = RecordingGateway(accept = true)
        val manager = SpatialFocusManager(vqaGateway = gateway)
        manager.updateTracks(1L, 1L, 0L, listOf(track("one", 1.0)))
        manager.command(SpatialFocusCommand.ACTIVATE, 1L)
        manager.command(SpatialFocusCommand.ACTIVATE, 2L)
        val request = (manager.command(SpatialFocusCommand.ACTIVATE, 3L).effect as
            SpatialFocusEffect.RequestVqa).request

        assertFalse(
            manager.failVqa(
                request.correlation.copy(focusGeneration = request.correlation.focusGeneration + 1L),
                FocusedVqaRejection.STALE_FRAME,
                4L,
            ),
        )
        assertEquals(SpatialFocusMode.VQA_PENDING, manager.current()!!.mode)
        assertTrue(manager.failVqa(request.correlation, FocusedVqaRejection.STALE_FRAME, 5L))
        assertEquals(SpatialFocusMode.ACTION_MENU, manager.current()!!.mode)
        assertEquals(
            SpatialFocusOperatorNotice.VqaRejected(FocusedVqaRejection.STALE_FRAME),
            manager.current()!!.operatorNotice,
        )
    }

    @Test
    fun `VQA and beacon refusal remain typed and operator visible`() {
        val manager = SpatialFocusManager()
        manager.updateTracks(1L, 1L, 0L, listOf(track("one", 1.0)))
        manager.command(SpatialFocusCommand.ACTIVATE, 1L)
        manager.command(SpatialFocusCommand.ACTIVATE, 2L)
        val vqa = manager.command(SpatialFocusCommand.ACTIVATE, 3L).state
        assertEquals(
            SpatialFocusOperatorNotice.VqaRejected(FocusedVqaRejection.UNAVAILABLE),
            vqa.operatorNotice,
        )
        assertEquals("vqa_rejected_unavailable", vqa.statusReason)

        val noWorld = track("one", 1.0).copy(
            localWorldPositionMeters = null,
            coordinateValidity = track("one", 1.0).coordinateValidity.copy(
                localWorld = TrackEstimateValidity.UNAVAILABLE,
            ),
        )
        manager.updateTracks(1L, 2L, 4L, listOf(noWorld))
        manager.command(SpatialFocusCommand.NEXT, 5L)
        val beacon = manager.command(SpatialFocusCommand.ACTIVATE, 6L).state
        assertEquals(
            SpatialFocusOperatorNotice.BeaconRejected(BeaconQualityReason.HEAD_ORIENTATION_UNAVAILABLE),
            beacon.operatorNotice,
        )
        assertEquals("beacon_rejected_head_orientation_unavailable", beacon.statusReason)
    }

    @Test
    fun `clock and distance have exact display and TalkBack forms`() {
        val item = item(MetricVector3(-0.866, 0.0, 0.5), 0.9144)
        assertEquals("chair. 10:00. about 3 feet away.", SpatialFocusSpeechFormatter.display(item))
        assertEquals("chair. 10 o'clock. about 3 feet away.", SpatialFocusSpeechFormatter.talkBack(item))
    }

    @Test
    fun `beacon falls back to relative bearing without world translation`() {
        val gate = BeaconQualityGate()
        val noWorld = track("a", 2.0).copy(
            localWorldPositionMeters = null,
            coordinateValidity = track("a", 2.0).coordinateValidity.copy(
                localWorld = TrackEstimateValidity.UNAVAILABLE,
            ),
        )
        val relative = gate.evaluate(noWorld, 1L)
        assertTrue(relative.eligible)
        assertEquals(BeaconAnchorMode.ORIENTATION_STABILIZED_RELATIVE, relative.anchorMode)
        assertEquals(noWorld.headRelativeVectorMeters, relative.relativeHeadVectorMeters)
        val noQuality = track("b", 2.0).copy(
            covariance = track("b", 2.0).covariance.copy(localWorldVarianceMetersSquared = null),
        )
        assertEquals(
            BeaconAnchorMode.ORIENTATION_STABILIZED_RELATIVE,
            gate.evaluate(noQuality, 1L).anchorMode,
        )
        assertTrue(gate.evaluate(track("c", 2.0), 1L).eligible)
    }

    @Test
    fun `relative beacon captures orientation and survives source track expiry`() {
        val manager = SpatialFocusManager(beaconTtlNanos = 10_000_000_000L)
        val noWorld = track("one", 1.0).copy(
            localWorldPositionMeters = null,
            coordinateValidity = track("one", 1.0).coordinateValidity.copy(
                localWorld = TrackEstimateValidity.UNAVAILABLE,
            ),
        )
        manager.updateTracks(1L, 1L, 0L, listOf(noWorld))
        manager.command(SpatialFocusCommand.ACTIVATE, 1L)
        manager.command(SpatialFocusCommand.ACTIVATE, 2L)
        manager.command(SpatialFocusCommand.NEXT, 3L)
        val active = manager.command(
            SpatialFocusCommand.ACTIVATE,
            4L,
            BeaconHeadOrientation(4L, 3, 1.0, 0.0, 0.0, 0.0),
        ).state
        assertEquals(SpatialFocusMode.BEACON_ACTIVE, active.mode)
        assertEquals(BeaconAnchorMode.ORIENTATION_STABILIZED_RELATIVE, active.beacon!!.anchorMode)

        val retained = manager.updateTracks(1L, 2L, 2_000_000_001L, emptyList())
        assertEquals(SpatialFocusMode.BEACON_ACTIVE, retained.mode)
        assertEquals("one", retained.target!!.stableTrackId)
        assertEquals(0, retained.itemCount)
        assertTrue(retained.validUntilTimestampNanos > 2_000_000_001L)
    }

    @Test
    fun `VQA admission is bounded correlated and cancellable`() {
        val gate = FocusedVqaAdmissionGate(maximumFrameAgeNanos = 100L, cooldownNanos = 50L)
        val item = item(MetricVector3(0.0, 0.0, 1.0), 1.0).copy(
            sourceCaptureTimestampNanos = 50L,
            expiresAtTimestampNanos = 1_000L,
        )
        assertTrue(gate.admit(1L, 1L, 1L, item, 100L, false) is FocusedVqaAdmission.Rejected)
        val admitted = gate.admit(1L, 1L, 1L, item, 100L, true) as FocusedVqaAdmission.Admitted
        assertEquals(admitted.request.correlation, gate.activeCorrelation())
        assertTrue(gate.admit(1L, 1L, 1L, item, 101L, true) is FocusedVqaAdmission.Rejected)
        assertEquals(admitted.request.correlation, gate.cancel())
        assertTrue(gate.admit(1L, 1L, 2L, item, 120L, true) is FocusedVqaAdmission.Rejected)
        assertTrue(gate.admit(1L, 1L, 2L, item, 151L, true) is FocusedVqaAdmission.Rejected)
    }

    @Test
    fun `session reset clears VQA cooldown and restarts request identity within new generation`() {
        val gateway = RecordingGateway(accept = true)
        val manager = SpatialFocusManager(vqaGateway = gateway)
        manager.updateTracks(1L, 1L, 0L, listOf(track("one", 1.0)))
        manager.command(SpatialFocusCommand.ACTIVATE, 1L)
        manager.command(SpatialFocusCommand.ACTIVATE, 2L)
        val first = (manager.command(SpatialFocusCommand.ACTIVATE, 3L).effect as
            SpatialFocusEffect.RequestVqa).request

        manager.reset(2L, 4L)
        manager.updateTracks(2L, 2L, 5L, listOf(track("one", 1.0)))
        manager.command(SpatialFocusCommand.ACTIVATE, 6L)
        manager.command(SpatialFocusCommand.ACTIVATE, 7L)
        val second = (manager.command(SpatialFocusCommand.ACTIVATE, 8L).effect as
            SpatialFocusEffect.RequestVqa).request

        assertEquals(1L, first.correlation.requestId)
        assertEquals(1L, second.correlation.requestId)
        assertEquals(2L, second.correlation.sessionGeneration)
    }

    @Test
    fun `touch admission receives complete raw semantics while production mapping stays disabled`() {
        val timed = TimedTouchEvent(
            RokidTouchEvent.newBuilder()
                .setEventId(9L)
                .setObservedMonotonicTimestampNs(10L)
                .setSourceUptimeMs(11L)
                .setKey(RokidTouchKey.ROKID_TOUCH_KEY_SINGLE_TAP)
                .setAction(RokidTouchAction.ROKID_TOUCH_ACTION_DOWN)
                .setRepeatCount(4)
                .setCanceled(true)
                .setLongPress(true)
                .setScanCode(148)
                .build(),
            12L,
            1L,
        )
        var observed: TimedTouchEvent? = null
        val admission = SpatialFocusTouchAdmission { event ->
            observed = event
            null
        }

        assertEquals(null, admission.commandFor(timed))
        assertEquals(4, observed!!.event.repeatCount)
        assertTrue(observed!!.event.canceled)
        assertTrue(observed!!.event.longPress)
        assertEquals(null, DisabledSpatialFocusTouchAdmission.commandFor(timed))
    }

    private class RecordingGateway(private val accept: Boolean) : FocusedVqaGateway {
        override fun submit(request: FocusedVqaRequest) = accept
        override fun cancel(correlation: FocusedVqaCorrelation) = Unit
    }

    private fun item(vector: MetricVector3, distance: Double) = SpatialFocusItem(
        "track", "chair", 1L, 0L, 2_000_000_000L, 0.9, vector, distance, 0.1,
        BeaconQuality(
            true,
            BeaconQualityReason.ELIGIBLE,
            BeaconAnchorMode.WORLD_ANCHORED,
            worldAnchorMeters = MetricVector3(1.0, 0.0, 2.0),
            validUntilTimestampNanos = 2_000_000_000L,
        ),
    )

    private fun track(id: String, distance: Double): LightweightTrackState = LightweightTrackState(
        stableTrackId = id,
        sourceTrackId = id,
        classId = "chair",
        sourceFrameId = 1L,
        sourceCaptureTimestampNanos = 0L,
        sourceInferenceTimestampNanos = 0L,
        outputTimestampNanos = 0L,
        expiresAtTimestampNanos = 2_000_000_000L,
        confidence = 0.9,
        coordinateValidity = TrackCoordinateValidity(
            TrackEstimateValidity.OBSERVED,
            TrackEstimateValidity.OBSERVED,
            TrackEstimateValidity.OBSERVED,
            TrackEstimateValidity.TRANSLATION_EVIDENCE_PROPAGATED,
        ),
        imageGeometry = InstanceMaskGeometry(100, 100, 10, 10, 20, 20),
        cameraRelativeVectorMeters = MetricVector3(0.0, 0.0, distance),
        headRelativeVectorMeters = MetricVector3(0.0, 0.0, distance),
        localWorldPositionMeters = MetricVector3(0.0, 0.0, distance),
        metricDepth = MetricDepthEstimate(distance, 0.1, false),
        depthFresh = true,
        centroidVelocityXPixelsPerSecond = 0.0,
        centroidVelocityYPixelsPerSecond = 0.0,
        approachVelocityMetersPerSecond = 0.0,
        missedKeyframes = 0,
        headCameraTranslationApplied = true,
        covariance = LightweightTrackCovariance(1.0, 0.0, 1.0, 0.01, 0.01, 0.01),
    )
}
