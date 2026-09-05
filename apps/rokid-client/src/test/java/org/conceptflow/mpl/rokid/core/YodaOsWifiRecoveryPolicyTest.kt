// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YodaOsWifiRecoveryPolicyTest {
    @Test
    fun compatibilityIsExactToTheVerifiedNonDisplayRokidFamily() {
        assertTrue(YodaOsWifiControlCompatibility.isSupported("Rokid", "Rokid", "glasses", "glasses"))
        assertTrue(YodaOsWifiControlCompatibility.isSupported("rokid", "ROKID", "GLASSES", "glasses"))
        assertFalse(YodaOsWifiControlCompatibility.isSupported("Rokid", "Rokid", "phone", "glasses"))
        assertFalse(YodaOsWifiControlCompatibility.isSupported("Rokid", "Other", "glasses", "glasses"))
        assertFalse(YodaOsWifiControlCompatibility.isSupported("Other", "Rokid", "glasses", "glasses"))
    }

    @Test
    fun recoveryEpisodeHasFiniteNondecreasingAttemptsAndCanBeRestarted() {
        val schedule = YodaOsWifiRecoverySchedule()

        assertEquals(0L, schedule.nextDelayMillis())
        assertEquals(1_000L, schedule.nextDelayMillis())
        assertEquals(3_000L, schedule.nextDelayMillis())
        assertEquals(8_000L, schedule.nextDelayMillis())
        assertNull(schedule.nextDelayMillis())
        assertEquals(4, schedule.maximumAttempts)
        assertEquals(listOf(4_000L, 8_000L), YodaOsWifiRecoverySchedule.DEFAULT_RELEASE_DELAYS_MILLIS)

        schedule.restart()
        assertEquals(0L, schedule.nextDelayMillis())
    }

    @Test
    fun temporaryGroupReleaseNeverRemovesPeerOwnedOrActiveGroups() {
        assertEquals(
            YodaOsTemporaryP2pGroupDecision.ALREADY_ABSENT,
            YodaOsTemporaryP2pGroupPolicy.releaseDecision(false, false, 0),
        )
        assertEquals(
            YodaOsTemporaryP2pGroupDecision.REMOVE_EMPTY_OWNER,
            YodaOsTemporaryP2pGroupPolicy.releaseDecision(true, true, 0),
        )
        assertEquals(
            YodaOsTemporaryP2pGroupDecision.RETAIN_NOT_OWNER,
            YodaOsTemporaryP2pGroupPolicy.releaseDecision(true, false, 0),
        )
        assertEquals(
            YodaOsTemporaryP2pGroupDecision.RETAIN_ACTIVE_CLIENTS,
            YodaOsTemporaryP2pGroupPolicy.releaseDecision(true, true, 1),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun temporaryGroupReleaseRejectsImpossibleClientCount() {
        YodaOsTemporaryP2pGroupPolicy.releaseDecision(true, true, -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun recoveryScheduleRejectsAHotLoop() {
        YodaOsWifiRecoverySchedule(listOf(0L, 1_000L, 500L))
    }
}
