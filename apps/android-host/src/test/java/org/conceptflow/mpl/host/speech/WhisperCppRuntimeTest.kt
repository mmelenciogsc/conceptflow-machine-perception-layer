// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WhisperCppRuntimeTest {
    @Test
    fun `thread policy remains bounded for little and big CPUs`() {
        assertEquals(2, AndroidWhisperCppEngine.recommendedThreadCount(1))
        assertEquals(2, AndroidWhisperCppEngine.recommendedThreadCount(4))
        assertEquals(4, AndroidWhisperCppEngine.recommendedThreadCount(8))
        assertEquals(4, AndroidWhisperCppEngine.recommendedThreadCount(64))
    }

    @Test
    fun `no-speech inference cannot carry transcript content`() {
        assertThrows(IllegalArgumentException::class.java) {
            WhisperInference(false, "not allowed", 1L)
        }
    }

    @Test
    fun `timed-out transcription cannot carry partial transcript content`() {
        assertThrows(IllegalArgumentException::class.java) {
            WhisperTranscription("partial private speech", 1L, timedOut = true)
        }
    }
}
