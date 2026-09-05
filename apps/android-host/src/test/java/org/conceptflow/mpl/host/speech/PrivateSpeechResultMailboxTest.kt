// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateSpeechResultMailboxTest {
    @Test
    fun `one private result is retained until consumed`() {
        var now = 1_000_000_000L
        val mailbox = PrivateSpeechResultMailbox(2_000_000_000L) { now }
        mailbox.offer(result("first"))
        mailbox.offer(result("replacement"))

        assertTrue(mailbox.summary().pending)
        assertEquals("replacement", mailbox.consume()?.transcript)
        assertFalse(mailbox.summary().pending)
        assertNull(mailbox.consume())
    }

    @Test
    fun `private result expires without exposing transcript in summary`() {
        var now = 1_000_000_000L
        val mailbox = PrivateSpeechResultMailbox(2_000_000_000L) { now }
        mailbox.offer(result("private words"))

        assertEquals("private words".length, mailbox.summary().transcriptCharacterCount)
        now += 2_000_000_000L

        assertFalse(mailbox.summary().pending)
        assertNull(mailbox.consume())
    }

    private fun result(transcript: String) = PrivateSpeechResult(
        sessionGeneration = 1L,
        purpose = SpeechWindowPurpose.USER_QUERY,
        speechDetected = true,
        transcript = transcript,
        transcriptionTimedOut = false,
        captureStartTimestampNs = 1L,
        captureEndTimestampNs = 2L,
    )
}
