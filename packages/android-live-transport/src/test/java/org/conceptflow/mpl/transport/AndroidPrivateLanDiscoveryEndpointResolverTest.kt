// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPrivateLanDiscoveryEndpointResolverTest {
    @Test
    fun `announcement timing leaves a bounded server bind window`() {
        assertTrue(
            AndroidPrivateLanDiscoveryEndpointResolver.ANNOUNCEMENT_START_DELAY_MILLIS in 100L..500L,
        )
        assertTrue(
            AndroidPrivateLanDiscoveryEndpointResolver.ANNOUNCEMENT_INTERVAL_MILLIS <
                AndroidPrivateLanDiscoveryEndpointResolver.DISCOVERY_BEFORE_STATIC_FALLBACK_MILLIS,
        )
    }

    @Test
    fun `beacon codec is deterministic bounded and port scoped`() {
        val encoded = AndroidPrivateLanDiscoveryEndpointResolver.encodeBeacon(39_431, 7L)

        assertEquals(19, encoded.size)
        assertTrue(
            AndroidPrivateLanDiscoveryEndpointResolver.decodeBeacon(
                encoded,
                offset = 0,
                length = encoded.size,
                expectedRealtimePort = 39_431,
            ),
        )
        assertFalse(
            AndroidPrivateLanDiscoveryEndpointResolver.decodeBeacon(
                encoded,
                offset = 0,
                length = encoded.size,
                expectedRealtimePort = 39_432,
            ),
        )
        assertFalse(
            AndroidPrivateLanDiscoveryEndpointResolver.decodeBeacon(
                encoded.copyOf(encoded.size - 1),
                offset = 0,
                length = encoded.size - 1,
                expectedRealtimePort = 39_431,
            ),
        )
    }

    @Test
    fun `beacon codec rejects corrupted marker version and sequence`() {
        val encoded = AndroidPrivateLanDiscoveryEndpointResolver.encodeBeacon(39_431, 7L)

        assertFalse(
            AndroidPrivateLanDiscoveryEndpointResolver.decodeBeacon(
                encoded.copyOf().apply { this[0] = 0 },
                offset = 0,
                length = encoded.size,
                expectedRealtimePort = 39_431,
            ),
        )
        assertFalse(
            AndroidPrivateLanDiscoveryEndpointResolver.decodeBeacon(
                encoded.copyOf().apply { this[8] = 2 },
                offset = 0,
                length = encoded.size,
                expectedRealtimePort = 39_431,
            ),
        )
        assertFalse(
            AndroidPrivateLanDiscoveryEndpointResolver.decodeBeacon(
                encoded.copyOf().apply { fill(0, fromIndex = 11, toIndex = size) },
                offset = 0,
                length = encoded.size,
                expectedRealtimePort = 39_431,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AndroidPrivateLanDiscoveryEndpointResolver.encodeBeacon(39_431, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidPrivateLanDiscoveryEndpointResolver.encodeBeacon(0, 1L)
        }
    }

    @Test
    fun `directed broadcast follows the selected private route`() {
        val address = InetAddress.getByName("192.168.27.44") as Inet4Address
        val destinations = AndroidPrivateLanDiscoveryEndpointResolver.broadcastDestinations(address, 24)
            .map(InetAddress::getHostAddress)

        assertTrue(destinations.contains("255.255.255.255"))
        assertTrue(destinations.contains("239.255.77.77"))
        assertTrue(destinations.contains("192.168.27.255"))
        assertEquals(destinations.distinct(), destinations)
    }

    @Test
    fun `only private or link local ipv4 sources are accepted`() {
        assertTrue(
            AndroidPrivateLanDiscoveryEndpointResolver.isPrivateOrLinkLocal(
                InetAddress.getByName("10.2.3.4"),
            ),
        )
        assertTrue(
            AndroidPrivateLanDiscoveryEndpointResolver.isPrivateOrLinkLocal(
                InetAddress.getByName("169.254.5.6"),
            ),
        )
        assertFalse(
            AndroidPrivateLanDiscoveryEndpointResolver.isPrivateOrLinkLocal(
                InetAddress.getByName("203.0.113.2"),
            ),
        )
        assertFalse(
            AndroidPrivateLanDiscoveryEndpointResolver.isPrivateOrLinkLocal(
                InetAddress.getByName("2001:db8::1"),
            ),
        )
    }

    @Test
    fun `configured peer remains the fallback on its provisioned subnet`() {
        val configured = InetAddress.getByName("192.168.27.44") as Inet4Address
        val client = InetAddress.getByName("192.168.27.81") as Inet4Address
        val gateway = InetAddress.getByName("192.168.27.1") as Inet4Address

        val fallback = AndroidPrivateLanDiscoveryEndpointResolver.selectFallback(
            configured,
            client,
            24,
            gateway,
        )

        assertEquals(configured, fallback.address)
        assertEquals(PrivateLanFallbackSource.CONFIGURED_ADDRESS, fallback.source)
    }

    @Test
    fun `private default gateway is selected when hotspot subnet differs`() {
        val configured = InetAddress.getByName("192.168.27.44") as Inet4Address
        val client = InetAddress.getByName("192.168.61.18") as Inet4Address
        val gateway = InetAddress.getByName("192.168.61.1") as Inet4Address

        val fallback = AndroidPrivateLanDiscoveryEndpointResolver.selectFallback(
            configured,
            client,
            24,
            gateway,
        )

        assertEquals(gateway, fallback.address)
        assertEquals(PrivateLanFallbackSource.HOTSPOT_GATEWAY, fallback.source)
    }

    @Test
    fun `missing or public gateway cannot replace provisioned peer`() {
        val configured = InetAddress.getByName("10.2.3.4") as Inet4Address
        val client = InetAddress.getByName("192.168.61.18") as Inet4Address

        val missing = AndroidPrivateLanDiscoveryEndpointResolver.selectFallback(
            configured,
            client,
            24,
            null,
        )
        val public = AndroidPrivateLanDiscoveryEndpointResolver.selectFallback(
            configured,
            client,
            24,
            InetAddress.getByName("203.0.113.1") as Inet4Address,
        )

        assertEquals(configured, missing.address)
        assertEquals(configured, public.address)
        assertEquals(PrivateLanFallbackSource.CONFIGURED_ADDRESS, missing.source)
        assertEquals(PrivateLanFallbackSource.CONFIGURED_ADDRESS, public.source)
    }

    @Test
    fun `subnet comparison honors non-octet prefixes`() {
        assertTrue(
            AndroidPrivateLanDiscoveryEndpointResolver.sameSubnet(
                InetAddress.getByName("10.8.16.1") as Inet4Address,
                InetAddress.getByName("10.8.31.254") as Inet4Address,
                20,
            ),
        )
        assertFalse(
            AndroidPrivateLanDiscoveryEndpointResolver.sameSubnet(
                InetAddress.getByName("10.8.16.1") as Inet4Address,
                InetAddress.getByName("10.8.32.1") as Inet4Address,
                20,
            ),
        )
    }
}
