// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host

import org.conceptflow.mpl.transport.LiveLinkDisconnectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveReconnectPolicyTest {
    @Test
    fun `network loss retries within bound while protocol and authentication fail closed`() {
        val policy = LiveReconnectPolicy(maximumInterruptions = 2)
        assertEquals(LiveReconnectDecision.RETRY, policy.onDisconnect(LiveLinkDisconnectReason.NETWORK))
        assertEquals(LiveReconnectDecision.RETRY, policy.onDisconnect(LiveLinkDisconnectReason.TIMEOUT))
        assertEquals(LiveReconnectDecision.FAIL_CLOSED, policy.onDisconnect(LiveLinkDisconnectReason.NETWORK))
        assertEquals(
            LiveReconnectDecision.FAIL_CLOSED,
            LiveReconnectPolicy().onDisconnect(LiveLinkDisconnectReason.AUTHENTICATION),
        )
        assertEquals(
            LiveReconnectDecision.COMPLETE,
            LiveReconnectPolicy().onDisconnect(LiveLinkDisconnectReason.LEASE_EXPIRED),
        )
        assertEquals(
            LiveReconnectDecision.COMPLETE,
            LiveReconnectPolicy().onDisconnect(LiveLinkDisconnectReason.REMOTE_COMPLETED),
        )
    }

    @Test
    fun `normal remote completion does not consume reconnect allowance`() {
        val policy = LiveReconnectPolicy(maximumInterruptions = 1)

        assertEquals(
            LiveReconnectDecision.COMPLETE,
            policy.onDisconnect(LiveLinkDisconnectReason.REMOTE_COMPLETED),
        )
        assertEquals(LiveReconnectDecision.RETRY, policy.onDisconnect(LiveLinkDisconnectReason.NETWORK))
    }

    @Test
    fun `old pending frame generation is stale immediately after reconnect reset`() {
        val generations = LiveSessionGeneration()
        val oldSession = generations.advance()
        assertTrue(generations.isCurrent(oldSession))
        val newSession = generations.advance()
        assertFalse(generations.isCurrent(oldSession))
        assertTrue(generations.isCurrent(newSession))
    }

    @Test
    fun `cancelled slow startup cannot publish resources`() {
        val gate = LiveStartupGate()
        val slowStartup = gate.begin()
        assertTrue(gate.mayPublish(slowStartup))

        gate.cancel()

        assertFalse(gate.mayPublish(slowStartup))
        val replacement = gate.begin()
        assertTrue(gate.mayPublish(replacement))
        assertFalse(gate.mayPublish(slowStartup))
    }
}
