// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.net.Inet6Address
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLinkPrivateConfigTest {
    @Test
    fun `network topology names are strict and backward compatible`() {
        assertEquals(
            LiveLinkNetworkTopology.PRIVATE_LAN,
            LiveLinkNetworkTopology.parse("private_lan"),
        )
        assertEquals(
            LiveLinkNetworkTopology.WIFI_DIRECT_REQUIRED,
            LiveLinkNetworkTopology.parse("wifi_direct_required"),
        )
        assertEquals(
            LiveLinkNetworkTopology.PRIVATE_LAN_DISCOVERY,
            LiveLinkNetworkTopology.parse("private_lan_discovery"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            LiveLinkNetworkTopology.parse("wifi_direct_preferred")
        }
    }

    @Test
    fun `camera transport is explicit and strict`() {
        assertEquals(LiveCameraTransport.I420, LiveCameraTransport.parse("i420"))
        assertEquals(LiveCameraTransport.AVC_INTRA, LiveCameraTransport.parse("avc_intra"))
        assertThrows(IllegalArgumentException::class.java) {
            LiveCameraTransport.parse("avc")
        }
    }

    @Test
    fun `accepts only private or link-local numeric addresses without DNS`() {
        assertEquals("10.4.3.2", LiveLinkPrivateConfig.parsePrivateIpLiteral("10.4.3.2").hostAddress)
        assertEquals("172.31.9.8", LiveLinkPrivateConfig.parsePrivateIpLiteral("172.31.9.8").hostAddress)
        assertEquals("192.168.7.3", LiveLinkPrivateConfig.parsePrivateIpLiteral("192.168.7.3").hostAddress)
        assertEquals("169.254.8.2", LiveLinkPrivateConfig.parsePrivateIpLiteral("169.254.8.2").hostAddress)
        assertTrue(LiveLinkPrivateConfig.parsePrivateIpLiteral("fd12::9") is Inet6Address)
        assertTrue(LiveLinkPrivateConfig.parsePrivateIpLiteral("fe80::9") is Inet6Address)

        listOf(
            "0.0.0.0",
            "127.0.0.1",
            "224.0.0.1",
            "8.8.8.8",
            "localhost",
            "::",
            "::1",
            "ff02::1",
            "2001:4860:4860::8888",
        ).forEach { rejected ->
            assertThrows(IllegalArgumentException::class.java) {
                LiveLinkPrivateConfig.parsePrivateIpLiteral(rejected)
            }
        }
    }

    @Test
    fun `configuration representation never exposes operational metadata`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            LiveLinkPrivateConfig.parsePrivateIpLiteral("private-host.internal")
        }
        assertFalse(error.message.orEmpty().contains("private-host"))
    }
}
