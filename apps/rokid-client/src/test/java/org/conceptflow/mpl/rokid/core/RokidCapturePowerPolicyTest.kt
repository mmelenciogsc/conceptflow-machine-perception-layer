// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RokidCapturePowerPolicyTest {
    private val policy = RokidCapturePowerPolicy()

    @Test
    fun `start is rejected below threshold even when charging`() {
        val decision = policy.beforeStart(
            RokidBatteryState(49, RokidBatteryStatus.CHARGING, healthGood = true),
        )
        assertEquals(RokidCapturePowerDecision.REJECT_START_LOW_BATTERY, decision)
    }

    @Test
    fun `full or discharging battery above threshold may start`() {
        assertEquals(
            RokidCapturePowerDecision.ALLOW,
            policy.beforeStart(RokidBatteryState(50, RokidBatteryStatus.DISCHARGING, true)),
        )
        assertEquals(
            RokidCapturePowerDecision.ALLOW,
            policy.beforeStart(RokidBatteryState(100, RokidBatteryStatus.FULL, true)),
        )
    }

    @Test
    fun `active stream stops at critical threshold regardless of cable state`() {
        assertEquals(
            RokidCapturePowerDecision.STOP_ACTIVE_LOW_BATTERY,
            policy.whileActive(RokidBatteryState(25, RokidBatteryStatus.CHARGING, true)),
        )
        assertEquals(
            RokidCapturePowerDecision.ALLOW,
            policy.whileActive(RokidBatteryState(26, RokidBatteryStatus.DISCHARGING, true)),
        )
    }

    @Test
    fun `unhealthy battery fails closed and unavailable telemetry remains compatible`() {
        assertEquals(
            RokidCapturePowerDecision.REJECT_UNHEALTHY_BATTERY,
            policy.beforeStart(RokidBatteryState(100, RokidBatteryStatus.FULL, false)),
        )
        assertEquals(
            RokidCapturePowerDecision.ALLOW,
            policy.beforeStart(RokidBatteryState(null, RokidBatteryStatus.UNKNOWN, null)),
        )
    }
}
