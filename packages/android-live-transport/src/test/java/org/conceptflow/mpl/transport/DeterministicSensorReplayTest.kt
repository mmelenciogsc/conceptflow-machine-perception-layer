// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicSensorReplayTest {
    @Test
    fun `original slowed and accelerated timing remain deterministic`() {
        val records = listOf(record(1L, 100L), record(2L, 200L), record(3L, 300L))
        val replay = DeterministicSensorReplay(records)
        val observer = RecordingObserver()

        assertEquals(1, replay.drainThrough(100L, observer = observer))
        assertEquals(1, replay.drainThrough(100L, speed = 2.0, observer = observer))
        assertEquals(listOf(1L, 2L), observer.ids)

        replay.reset()
        observer.ids.clear()
        assertEquals(1, replay.drainThrough(200L, speed = 0.5, observer = observer))
        assertEquals(listOf(1L), observer.ids)
        assertEquals(2, replay.remaining())
    }

    @Test
    fun `stepwise mode preserves order without replay after exhaustion`() {
        val replay = DeterministicSensorReplay(listOf(record(7L, 0L), record(8L, 0L)))
        val observer = RecordingObserver()

        assertTrue(replay.step(observer))
        assertTrue(replay.step(observer))
        assertFalse(replay.step(observer))
        assertEquals(listOf(7L, 8L), observer.ids)
    }

    @Test
    fun `constructor rejects unbounded or time-reversed traces`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeterministicSensorReplay(listOf(record(1L, 2L), record(2L, 1L)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeterministicSensorReplay(listOf(record(1L, 0L), record(2L, 1L)), maximumRecords = 1)
        }
    }

    private fun record(id: Long, offset: Long): SensorReplayRecord = SensorReplayRecord(
        offset,
        LiveSensorDelivery(
            sensor = SensorStreamEnvelope.newBuilder().setSessionId("replay").setLeaseId("replay-$id").build(),
            receiveMonotonicNs = offset,
            normalizedRemoteSend = normalized(offset),
            normalizedCameraCapture = null,
            normalizedImuBatchCreated = null,
            normalizedImuSamples = emptyList(),
            normalizedMicrophoneCapture = null,
            normalizedTouchObserved = null,
        ),
    )

    private fun normalized(value: Long) = NormalizedMonotonicTimestamp(
        value,
        value,
        0L,
        value,
        0L,
        ClockNormalizationEvidence(1L, 0L, 0L, 0L, null, 1L),
    )

    private class RecordingObserver : PocoLiveLinkObserver {
        val ids = mutableListOf<Long>()
        override fun onSessionReady(session: LiveLinkSession) = Unit
        override fun onSensor(delivery: LiveSensorDelivery) {
            ids += delivery.sensor.leaseId.removePrefix("replay-").toLong()
        }
        override fun onDisconnected(reason: LiveLinkDisconnectReason) = Unit
    }
}
