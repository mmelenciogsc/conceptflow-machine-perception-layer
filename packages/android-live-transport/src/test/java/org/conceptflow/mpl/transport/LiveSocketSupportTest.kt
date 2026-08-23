// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSocketSupportTest {
    @Test
    fun `specific protocol and ticket failures are not hidden by broad security classification`() {
        assertEquals(
            LiveLinkDisconnectReason.PROTOCOL,
            classifyDisconnect(LaneProtocolException(LaneProtocolFailure.SEQUENCE_REPLAY_OR_GAP)),
        )
        val authority = CameraLaneTicketAuthority(SecretKeySpec(ByteArray(32) { 1 }, "HmacSHA256"))
        val ticketFailure = runCatching {
            authority.consume(byteArrayOf(1), LiveSessionBinding("session", "lease", ByteArray(32)), 0)
        }.exceptionOrNull()
        assertEquals(LiveLinkDisconnectReason.AUTHENTICATION, classifyDisconnect(requireNotNull(ticketFailure)))
        assertEquals(
            LiveLinkDisconnectReason.AUTHENTICATION,
            classifyDisconnect(SSLHandshakeException("test failure")),
        )
    }


    @Test
    fun `authenticated remote session completion is not classified as a network failure`() {
        assertEquals(
            LiveLinkDisconnectReason.REMOTE_COMPLETED,
            classifyDisconnect(RemoteSessionCompletedException()),
        )
    }

    @Test
    fun `shutdown interruption remains a stopped terminal outcome`() {
        assertEquals(LiveLinkDisconnectReason.STOPPED, classifyDisconnect(InterruptedException("shutdown")))
    }
}
