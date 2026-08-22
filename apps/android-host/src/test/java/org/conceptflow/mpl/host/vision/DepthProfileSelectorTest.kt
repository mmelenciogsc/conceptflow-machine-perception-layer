// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthProfileSelectorTest {
    private val config = DepthProfileSelectorConfig(
        consecutiveEvidenceRequired = 2,
        minimumHoldNanos = 1_000L,
        maximumEvidenceAgeNanos = 100L,
    )

    @Test
    fun requiresEvidenceQuorumAndHoldsProfileAgainstRapidSwitches() {
        val selector = DepthProfileSelector(config)
        val first = selector.evaluate(evidence(10L, 0.85, 0.05), 20L)
        assertEquals(null, first.environment)
        assertFalse(first.changed)

        val indoor = selector.evaluate(evidence(30L, 0.90, 0.02), 40L)
        assertEquals(DepthEnvironment.INDOOR, indoor.environment)
        assertTrue(indoor.changed)

        repeat(2) { index ->
            val now = 100L + index
            val held = selector.evaluate(evidence(now, 0.02, 0.92), now)
            assertEquals(DepthEnvironment.INDOOR, held.environment)
            assertEquals("minimum_hold_active", held.reason)
        }

        selector.evaluate(evidence(2_000L, 0.02, 0.92), 2_000L)
        val outdoor = selector.evaluate(evidence(2_001L, 0.01, 0.95), 2_001L)
        assertEquals(DepthEnvironment.OUTDOOR, outdoor.environment)
        assertTrue(outdoor.changed)
    }

    @Test
    fun staleOrAmbiguousEvidenceNeverForcesAProfile() {
        val selector = DepthProfileSelector(config)
        assertEquals(
            "stale_or_future_evidence",
            selector.evaluate(evidence(10L, 0.9, 0.0), 200L).reason,
        )
        assertEquals(
            "insufficient_environment_evidence",
            selector.evaluate(evidence(200L, 0.55, 0.45), 200L).reason,
        )
        assertEquals(null, selector.current())
    }

    private fun evidence(timestamp: Long, indoor: Double, outdoor: Double) = EnvironmentEvidence(
        timestampNanos = timestamp,
        indoorProbability = indoor,
        outdoorProbability = outdoor,
        independentSignalCount = 2,
    )
}
