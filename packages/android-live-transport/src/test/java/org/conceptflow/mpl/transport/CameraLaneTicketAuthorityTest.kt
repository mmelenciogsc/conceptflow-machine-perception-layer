// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraLaneTicketAuthorityTest {
    private val binding = LiveSessionBinding("session-a", "lease-a", ByteArray(32) { 1 })
    private val authority = CameraLaneTicketAuthority(SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256"))

    @Test
    fun `ticket is bound authenticated expiring and one use`() {
        val ticket = authority.issue(binding, nowNs = 1_000, lifetimeNs = 100)
        authority.consume(ticket, binding, nowNs = 1_050)

        val replay = assertThrows(CameraTicketException::class.java) {
            authority.consume(ticket, binding, nowNs = 1_051)
        }
        assertEquals(CameraTicketFailure.REPLAYED, replay.failure)
    }

    @Test
    fun `wrong binding and modification fail authentication without consuming ticket`() {
        val ticket = authority.issue(binding, nowNs = 1_000, lifetimeNs = 100)
        val wrongBinding = LiveSessionBinding("session-b", "lease-a", ByteArray(32) { 1 })

        val wrong = assertThrows(CameraTicketException::class.java) {
            authority.consume(ticket, wrongBinding, nowNs = 1_010)
        }
        assertEquals(CameraTicketFailure.AUTHENTICATION_FAILED, wrong.failure)

        val modified = ticket.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val tampered = assertThrows(CameraTicketException::class.java) {
            authority.consume(modified, binding, nowNs = 1_010)
        }
        assertEquals(CameraTicketFailure.AUTHENTICATION_FAILED, tampered.failure)

        authority.consume(ticket, binding, nowNs = 1_010)
    }

    @Test
    fun `expired ticket is rejected and sensitive values are redacted`() {
        val ticket = authority.issue(binding, nowNs = 1_000, lifetimeNs = 100)
        val expired = assertThrows(CameraTicketException::class.java) {
            authority.consume(ticket, binding, nowNs = 1_101)
        }

        assertEquals(CameraTicketFailure.EXPIRED, expired.failure)
        assertFalse(binding.toString().contains("session-a"))
        assertFalse(authority.toString().contains("HmacSHA256"))
    }
}
