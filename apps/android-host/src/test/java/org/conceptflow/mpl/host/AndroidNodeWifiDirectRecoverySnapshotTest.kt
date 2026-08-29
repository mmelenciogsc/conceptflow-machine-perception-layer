// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host

import org.conceptflow.mpl.transport.WifiDirectGroupRecoveryOutcome
import org.conceptflow.mpl.transport.WifiDirectGroupRecoveryResult
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidNodeWifiDirectRecoverySnapshotTest {
    @Test
    fun `accessible status reports idle and running without peer data`() {
        assertEquals(
            "wifi_direct_recovery_idle",
            AndroidNodeWifiDirectRecoverySnapshot(running = false).accessibleSummary(),
        )
        assertEquals(
            "wifi_direct_recovery_running",
            AndroidNodeWifiDirectRecoverySnapshot(running = true).accessibleSummary(),
        )
    }

    @Test
    fun `accessible status exposes only outcome and removal state`() {
        val snapshot = AndroidNodeWifiDirectRecoverySnapshot(
            running = false,
            result = WifiDirectGroupRecoveryResult(
                outcome = WifiDirectGroupRecoveryOutcome.RECREATED_2GHZ,
                removedExistingGroup = true,
            ),
        )

        assertEquals(
            "wifi_direct_recovery_recreated_2ghz_removed_existing_true",
            snapshot.accessibleSummary(),
        )
    }
}
