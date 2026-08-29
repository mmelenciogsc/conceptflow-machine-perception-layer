// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host

import org.conceptflow.mpl.host.focus.BeaconQuality
import org.conceptflow.mpl.host.focus.BeaconQualityReason
import org.conceptflow.mpl.host.focus.FocusedVqaRejection
import org.conceptflow.mpl.host.focus.SpatialFocusDwell
import org.conceptflow.mpl.host.focus.SpatialFocusItem
import org.conceptflow.mpl.host.focus.SpatialFocusMenuOption
import org.conceptflow.mpl.host.focus.SpatialFocusMode
import org.conceptflow.mpl.host.focus.SpatialFocusState
import org.conceptflow.mpl.host.vision.LocalVlmFocusedObjectCorrelation
import org.conceptflow.mpl.host.vision.LocalVlmFocusedObjectFailure
import org.conceptflow.mpl.host.vision.MetricVector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusedVqaControllerCorrelationTest {
    @Test
    fun `callback source must match every live focus and frame field`() {
        val state = focusedState()
        val exact = correlation()

        assertTrue(state.matchesFocusedVqaSource(exact))
        assertFalse(state.matchesFocusedVqaSource(exact.copy(sessionGeneration = 3L)))
        assertFalse(state.matchesFocusedVqaSource(exact.copy(snapshotId = 4L)))
        assertFalse(state.matchesFocusedVqaSource(exact.copy(focusGeneration = 6L)))
        assertFalse(state.matchesFocusedVqaSource(exact.copy(stableTrackId = "track-8")))
        assertFalse(state.matchesFocusedVqaSource(exact.copy(sourceFrameId = 8L)))
        assertFalse(state.matchesFocusedVqaSource(exact.copy(sourceCaptureTimestampNanos = 401L)))
        assertFalse(state.copy(target = null).matchesFocusedVqaSource(exact))
    }

    @Test
    fun `local failures map to bounded operator-visible focus failures`() {
        assertEquals(FocusedVqaRejection.BUSY, LocalVlmFocusedObjectFailure.BUSY.toFocusRejection())
        assertEquals(
            FocusedVqaRejection.INVALID_REQUEST,
            LocalVlmFocusedObjectFailure.INVALID_REQUEST.toFocusRejection(),
        )
        assertEquals(
            FocusedVqaRejection.STALE_FRAME,
            LocalVlmFocusedObjectFailure.STALE_OR_MISMATCHED.toFocusRejection(),
        )
        assertEquals(
            FocusedVqaRejection.TIMED_OUT,
            LocalVlmFocusedObjectFailure.TIMED_OUT.toFocusRejection(),
        )
        listOf(
            LocalVlmFocusedObjectFailure.DEFERRED_FOR_QNN,
            LocalVlmFocusedObjectFailure.INFERENCE_FAILED,
            LocalVlmFocusedObjectFailure.UNAVAILABLE,
        ).forEach { failure ->
            assertEquals(FocusedVqaRejection.UNAVAILABLE, failure.toFocusRejection())
        }
    }

    private fun correlation() = LocalVlmFocusedObjectCorrelation(
        focusRequestId = 11L,
        sessionGeneration = 2L,
        snapshotId = 3L,
        focusGeneration = 5L,
        stableTrackId = "track-7",
        sourceFrameId = 7L,
        sourceCaptureTimestampNanos = 400L,
    )

    private fun focusedState() = SpatialFocusState(
        revision = 1L,
        sessionGeneration = 2L,
        snapshotId = 3L,
        sourceWorldRevision = 4L,
        publishedTimestampNanos = 500L,
        validUntilTimestampNanos = 1_000L,
        focusGeneration = 5L,
        mode = SpatialFocusMode.VQA_PENDING,
        selectedIndex = 0,
        itemCount = 1,
        menuIndex = 0,
        menuOption = SpatialFocusMenuOption.VQA,
        dwell = SpatialFocusDwell.NONE,
        dwellStartedTimestampNanos = 0L,
        dwellDeadlineTimestampNanos = 0L,
        target = SpatialFocusItem(
            stableTrackId = "track-7",
            classId = "doorway",
            sourceFrameId = 7L,
            sourceCaptureTimestampNanos = 400L,
            expiresAtTimestampNanos = 1_000L,
            confidence = 0.9,
            headVectorMeters = MetricVector3(0.0, 0.0, 2.0),
            distanceMeters = 2.0,
            uncertaintyMeters = 0.1,
            beaconQuality = BeaconQuality(false, BeaconQualityReason.WORLD_ANCHOR_UNAVAILABLE),
        ),
        displayPhrase = "doorway",
        talkBackPhrase = "doorway",
        statusReason = "vqa_requested",
    )
}
