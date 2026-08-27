// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidWifiDirectEndpointResolverTest {
    @Test
    fun `only exact project service identity is eligible for connection`() {
        assertTrue(
            AndroidWifiDirectEndpointResolver.matchesService(
                "CONCEPTFlow-MPL-AndroidNode",
                "_cf-mpl._tcp",
            ),
        )
        assertTrue(
            AndroidWifiDirectEndpointResolver.matchesService(
                "CONCEPTFlow-MPL-AndroidNode",
                "_cf-mpl._tcp.local.",
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.matchesService(
                "CONCEPTFlow-MPL-RokidNode",
                "_cf-mpl._tcp",
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.matchesService(
                "CONCEPTFlow-MPL-AndroidNode",
                "_http._tcp",
            ),
        )
    }

    @Test
    fun `retry cadence is bounded and nondecreasing`() {
        val delays = AndroidWifiDirectEndpointResolver.RETRY_DELAYS_MILLIS
        assertTrue(delays.isNotEmpty())
        assertTrue(delays.all { it in 1_000L..15_000L })
        assertTrue(delays.asList().zipWithNext().all { (previous, next) -> next >= previous })
    }

    @Test
    fun `discovery cannot restart while group negotiation or a group is active`() {
        assertTrue(AndroidWifiDirectEndpointResolver.discoveryAllowed(WifiDirectPhase.IDLE))
        assertTrue(AndroidWifiDirectEndpointResolver.discoveryAllowed(WifiDirectPhase.STARTING))
        assertTrue(AndroidWifiDirectEndpointResolver.discoveryAllowed(WifiDirectPhase.DISCOVERING))
        assertFalse(AndroidWifiDirectEndpointResolver.discoveryAllowed(WifiDirectPhase.WAITING_FOR_RADIO))
        assertFalse(AndroidWifiDirectEndpointResolver.discoveryAllowed(WifiDirectPhase.CONNECTING))
        assertFalse(AndroidWifiDirectEndpointResolver.discoveryAllowed(WifiDirectPhase.GROUP_READY))
        assertFalse(AndroidWifiDirectEndpointResolver.discoveryAllowed(WifiDirectPhase.CLOSED))
        assertTrue(
            AndroidWifiDirectEndpointResolver.CONNECTION_FORMATION_TIMEOUT_MILLIS <
                AndroidWifiDirectEndpointResolver.MAXIMUM_RESOLUTION_TIMEOUT_MILLIS,
        )
    }

    @Test
    fun `platform failure reasons have stable diagnostic names`() {
        assertTrue(AndroidWifiDirectEndpointResolver.failureReasonName(0) == "ERROR")
        assertTrue(AndroidWifiDirectEndpointResolver.failureReasonName(1) == "P2P_UNSUPPORTED")
        assertTrue(AndroidWifiDirectEndpointResolver.failureReasonName(2) == "BUSY")
        assertTrue(AndroidWifiDirectEndpointResolver.failureReasonName(99) == "UNKNOWN")
    }

    @Test
    fun `radio readiness requires wifi and rejects an explicitly disabled p2p stack`() {
        assertTrue(AndroidWifiDirectEndpointResolver.radioAvailable(wifiEnabled = true, p2pEnabled = null))
        assertTrue(AndroidWifiDirectEndpointResolver.radioAvailable(wifiEnabled = true, p2pEnabled = true))
        assertFalse(AndroidWifiDirectEndpointResolver.radioAvailable(wifiEnabled = true, p2pEnabled = false))
        assertFalse(AndroidWifiDirectEndpointResolver.radioAvailable(wifiEnabled = false, p2pEnabled = null))
        assertFalse(AndroidWifiDirectEndpointResolver.radioAvailable(wifiEnabled = false, p2pEnabled = true))
    }
}
