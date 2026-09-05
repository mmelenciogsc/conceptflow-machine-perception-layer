// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTransportFallbackPolicyTest {
    @Test
    fun configuredI420CannotBeDemotedAgain() {
        val policy = CameraTransportFallbackPolicy(LiveCameraTransport.I420)
        assertFalse(policy.allowsAvcIntra())
        assertEquals(CameraTransportFallbackDispatch.CONFIGURED_I420, policy.requestI420Demotion())
        assertEquals(0L, policy.snapshot().fallbackCount)
    }

    @Test
    fun avcDemotionIsOneWayForProcessLifetime() {
        val policy = CameraTransportFallbackPolicy(LiveCameraTransport.AVC_INTRA)
        assertTrue(policy.allowsAvcIntra())
        assertEquals(
            CameraTransportFallbackDispatch.DEMOTED_RECONNECT_REQUIRED,
            policy.requestI420Demotion(),
        )
        assertFalse(policy.allowsAvcIntra())
        assertEquals(CameraTransportFallbackDispatch.ALREADY_DEMOTED, policy.requestI420Demotion())
        assertEquals(LiveCameraTransport.I420, policy.snapshot().activeTransport)
        assertEquals(1L, policy.snapshot().fallbackCount)
    }

    @Test
    fun concurrentFailuresProduceExactlyOneDemotion() {
        val policy = CameraTransportFallbackPolicy(LiveCameraTransport.AVC_INTRA)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll(
                List(32) { Callable { policy.requestI420Demotion() } },
            ).map { it.get() }
            assertEquals(1, results.count { it == CameraTransportFallbackDispatch.DEMOTED_RECONNECT_REQUIRED })
            assertEquals(31, results.count { it == CameraTransportFallbackDispatch.ALREADY_DEMOTED })
        } finally {
            executor.shutdownNow()
        }
    }
}
