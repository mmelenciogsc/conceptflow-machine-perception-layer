// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDiagnosticSessionTest {
    @Test
    fun passesOnlyAfterAllThreeStreamsProduceData() {
        val session = StreamDiagnosticSession(1_000_000L)

        assertTrue(session.recordCameraFrame(20))
        assertTrue(session.recordImuSample(hasNonZeroSignal = false))
        assertFalse(session.finish(2_000_000L).passed)
    }

    @Test
    fun countsPayloadsAndStopsAcceptingAfterFinish() {
        val session = StreamDiagnosticSession(1_000_000L)
        assertTrue(session.recordCameraFrame(20))
        assertFalse(session.recordCameraFrame(30))
        assertTrue(session.recordImuSample(hasNonZeroSignal = false))
        assertFalse(session.recordImuSample(hasNonZeroSignal = true))
        assertTrue(session.recordMicrophoneChunk(byteArrayOf(0, 0, 1, 0)))
        assertFalse(session.recordMicrophoneChunk(byteArrayOf(0, 0, 0, 0, 0xff.toByte(), 0x7f)))

        val snapshot = session.finish(11_000_000L)

        assertTrue(snapshot.passed)
        assertEquals(10L, snapshot.durationMillis)
        assertEquals(2L, snapshot.cameraFrames)
        assertEquals(50L, snapshot.cameraBytes)
        assertEquals(2L, snapshot.imuSamples)
        assertEquals(1L, snapshot.imuSignalSamples)
        assertEquals(2L, snapshot.microphoneChunks)
        assertEquals(10L, snapshot.microphoneBytes)
        assertEquals(2L, snapshot.microphoneNonZeroSamples)
        assertEquals(32_767, snapshot.microphonePeakAbsolute)
        assertFalse(session.recordMicrophoneChunk(byteArrayOf(1, 0)))
        assertEquals(snapshot, session.finish(21_000_000L))
    }
}
