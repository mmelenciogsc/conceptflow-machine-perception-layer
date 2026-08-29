// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidWifiDirectGroupRecoveryTest {
    @Test
    fun `missing group is created`() {
        assertEquals(
            WifiDirectGroupRecoveryDecision.CREATE,
            AndroidWifiDirectGroupRecovery.recoveryDecision(
                groupPresent = false,
                isGroupOwner = false,
                clientCount = 0,
            ),
        )
    }

    @Test
    fun `only an empty owner group can be replaced`() {
        assertEquals(
            WifiDirectGroupRecoveryDecision.REMOVE_AND_RECREATE,
            AndroidWifiDirectGroupRecovery.recoveryDecision(
                groupPresent = true,
                isGroupOwner = true,
                clientCount = 0,
            ),
        )
        assertEquals(
            WifiDirectGroupRecoveryDecision.REFUSE_NOT_OWNER,
            AndroidWifiDirectGroupRecovery.recoveryDecision(
                groupPresent = true,
                isGroupOwner = false,
                clientCount = 0,
            ),
        )
        assertEquals(
            WifiDirectGroupRecoveryDecision.REFUSE_ACTIVE_CLIENTS,
            AndroidWifiDirectGroupRecovery.recoveryDecision(
                groupPresent = true,
                isGroupOwner = true,
                clientCount = 1,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative client count is rejected`() {
        AndroidWifiDirectGroupRecovery.recoveryDecision(
            groupPresent = true,
            isGroupOwner = true,
            clientCount = -1,
        )
    }

    @Test
    fun `preferred band validation accepts only two point four gigahertz`() {
        assertTrue(AndroidWifiDirectGroupRecovery.is2Ghz(2_412))
        assertTrue(AndroidWifiDirectGroupRecovery.is2Ghz(2_484))
        assertFalse(AndroidWifiDirectGroupRecovery.is2Ghz(5_180))
        assertFalse(AndroidWifiDirectGroupRecovery.is2Ghz(0))
        assertTrue(AndroidWifiDirectGroupRecovery.is5Ghz(5_180))
        assertFalse(AndroidWifiDirectGroupRecovery.is5Ghz(2_412))
        assertTrue(
            AndroidWifiDirectGroupRecovery.bandMatches(
                WifiDirectRecoveryBand.AUTO,
                5_180,
            ),
        )
        assertFalse(
            AndroidWifiDirectGroupRecovery.bandMatches(
                WifiDirectRecoveryBand.AUTO,
                0,
            ),
        )
    }

    @Test
    fun `success outcome preserves requested band and replacement state`() {
        assertEquals(
            WifiDirectGroupRecoveryOutcome.RECREATED_5GHZ,
            AndroidWifiDirectGroupRecovery.successOutcome(
                removedExistingGroup = true,
                band = WifiDirectRecoveryBand.FIVE_GHZ,
            ),
        )
        assertEquals(
            WifiDirectGroupRecoveryOutcome.CREATED_AUTO,
            AndroidWifiDirectGroupRecovery.successOutcome(
                removedExistingGroup = false,
                band = WifiDirectRecoveryBand.AUTO,
            ),
        )
        assertTrue(
            WifiDirectGroupRecoveryResult(
                WifiDirectGroupRecoveryOutcome.REMOVED_FOR_NEGOTIATION,
                removedExistingGroup = true,
            ).succeeded,
        )
    }

    @Test
    fun `generated group credentials satisfy platform format without identity data`() {
        val credentials = AndroidWifiDirectGroupRecovery.credentialsFrom(
            ByteArray(38) { index -> index.toByte() },
        )

        assertTrue(credentials.networkName.matches(Regex("DIRECT-[A-Z2-9]{2}-[A-Z2-9]{12}")))
        assertTrue(credentials.passphrase.matches(Regex("[A-Z2-9]{24}")))
        assertFalse(credentials.networkName.contains("Rokid", ignoreCase = true))
        assertFalse(credentials.networkName.contains("Poco", ignoreCase = true))
    }

    @Test
    fun `recovery timing remains bounded`() {
        assertTrue(AndroidWifiDirectGroupRecovery.CHANNEL_SETTLE_MILLIS in 100L..2_000L)
        assertTrue(AndroidWifiDirectGroupRecovery.GROUP_REMOVAL_SETTLE_MILLIS in 100L..2_000L)
        assertTrue(AndroidWifiDirectGroupRecovery.CREATE_FALLBACK_DELAY_MILLIS in 100L..2_000L)
        assertTrue(AndroidWifiDirectGroupRecovery.GROUP_VALIDATION_DELAY_MILLIS in 100L..1_000L)
        assertTrue(AndroidWifiDirectGroupRecovery.MAXIMUM_VALIDATION_ATTEMPTS in 1..20)
        assertTrue(AndroidWifiDirectGroupRecovery.OVERALL_TIMEOUT_MILLIS in 5_000L..30_000L)
    }
}
