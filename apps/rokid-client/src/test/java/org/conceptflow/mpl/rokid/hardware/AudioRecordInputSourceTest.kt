// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRecordInputSourceTest {
    @Test
    fun threadJoinGuardRejectsCurrentAndMissingThreads() {
        val current = Thread.currentThread()

        assertFalse(shouldJoinAudioThread(null, current))
        assertFalse(shouldJoinAudioThread(current, current))
        assertTrue(shouldJoinAudioThread(Thread("audio-worker"), current))
    }
}
