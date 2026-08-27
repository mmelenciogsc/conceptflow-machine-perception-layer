// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime

import com.google.protobuf.ByteString
import org.conceptflow.mpl.transport.ClockNormalizationEvidence
import org.conceptflow.mpl.transport.LiveSensorDelivery
import org.conceptflow.mpl.transport.NormalizedImuSampleTiming
import org.conceptflow.mpl.transport.NormalizedMonotonicTimestamp
import org.conceptflow.mpl.v1.AudioSampleEncoding
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImuBatch
import org.conceptflow.mpl.v1.ImuReading
import org.conceptflow.mpl.v1.MicrophoneChunk
import org.conceptflow.mpl.v1.Pose
import org.conceptflow.mpl.v1.RokidTouchAction
import org.conceptflow.mpl.v1.RokidTouchEvent
import org.conceptflow.mpl.v1.RokidTouchKey
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorTimelineTest {
    @Test
    fun `default camera freshness covers measured capture pipeline but remains bounded`() {
        val timeline = SensorTimeline()
        val frame = FramePayload.newBuilder().setFrameId(1L).build()

        assertTrue(timeline.acceptCamera(frame, 1L, 1_200_000_001L, 4L))
        assertFalse(timeline.acceptCamera(
            frame.toBuilder().setFrameId(2L).build(),
            2_000_000_000L,
            3_200_000_001L,
            4L,
        ))
    }

    @Test
    fun `camera freshness and bounded IMU window use normalized host time`() {
        val timeline = SensorTimeline(
            SensorTimelineLimits(cameraFreshnessNanos = 100L, imuFreshnessNanos = 100L),
        )
        val frame = FramePayload.newBuilder().setFrameId(1L).build()
        assertFalse(timeline.acceptCamera(frame, 100L, 201L, 4L))
        assertTrue(timeline.acceptCamera(frame, 150L, 200L, 4L))

        val readings = timeline.acceptImu(imuDelivery(listOf(150L, 190L, 210L), receiveNs = 220L))

        assertEquals(listOf(150L, 190L, 210L), readings.map { it.hostPoseTimestampNs })
        assertEquals(listOf(190L, 210L), timeline.snapshotAround(200L, 15L).imu.map { it.hostPoseTimestampNs })
        assertEquals(1L, timeline.snapshotAround(200L).staleCameraRejected)
    }

    @Test
    fun `audio and touch reject overflow without replacing ordered records`() {
        val timeline = SensorTimeline(SensorTimelineLimits(maximumAudioBlocks = 1, maximumTouchEvents = 1))
        assertTrue(timeline.acceptAudio(audioDelivery(1L)))
        assertFalse(timeline.acceptAudio(audioDelivery(2L)))
        assertEquals(1L, timeline.acceptTouch(touchDelivery(1L))?.event?.eventId)
        assertEquals(null, timeline.acceptTouch(touchDelivery(2L)))

        assertEquals(listOf(1L), timeline.drainAudio().map { it.block.chunkId })
        assertEquals(listOf(1L), timeline.drainTouch().map { it.event.eventId })
        val snapshot = timeline.snapshotAround(0L)
        assertEquals(1L, snapshot.audioOverflow)
        assertEquals(1L, snapshot.touchOverflow)
    }

    private fun imuDelivery(timestamps: List<Long>, receiveNs: Long): LiveSensorDelivery {
        val readings = timestamps.mapIndexed { index, timestamp ->
            ImuReading.newBuilder()
                .setSequenceId(index + 1L)
                .setPose(Pose.newBuilder().setMonotonicTimestampNs(timestamp))
                .setAngularVelocityMonotonicTimestampNs(timestamp)
                .setLinearAccelerationMonotonicTimestampNs(timestamp)
                .build()
        }
        val sensor = base().setImuBatch(
            ImuBatch.newBuilder().setLeaseId("lease").setBatchId(1L)
                .setCreatedMonotonicTimestampNs(timestamps.last()).addAllSamples(readings),
        ).build()
        return delivery(
            sensor,
            receiveNs,
            imu = timestamps.mapIndexed { index, timestamp ->
                NormalizedImuSampleTiming(index, normalized(timestamp), normalized(timestamp), normalized(timestamp))
            },
        )
    }

    private fun audioDelivery(id: Long): LiveSensorDelivery {
        val sensor = base().setMicrophoneChunk(
            MicrophoneChunk.newBuilder().setLeaseId("lease").setChunkId(id)
                .setCaptureMonotonicTimestampNs(id).setSampleRateHz(16_000).setChannelCount(1)
                .setEncoding(AudioSampleEncoding.AUDIO_SAMPLE_ENCODING_PCM_S16LE)
                .setAudioData(ByteString.copyFrom(byteArrayOf(1, 2))),
        ).build()
        return delivery(sensor, id + 1L, microphone = normalized(id))
    }

    private fun touchDelivery(id: Long): LiveSensorDelivery {
        val sensor = base().setTouchEvent(
            RokidTouchEvent.newBuilder().setEventId(id).setObservedMonotonicTimestampNs(id)
                .setSourceUptimeMs(id).setKey(RokidTouchKey.ROKID_TOUCH_KEY_SINGLE_TAP)
                .setAction(RokidTouchAction.ROKID_TOUCH_ACTION_DOWN).setScanCode(148),
        ).build()
        return delivery(sensor, id + 1L, touch = normalized(id))
    }

    private fun delivery(
        sensor: SensorStreamEnvelope,
        receiveNs: Long,
        imu: List<NormalizedImuSampleTiming> = emptyList(),
        microphone: NormalizedMonotonicTimestamp? = null,
        touch: NormalizedMonotonicTimestamp? = null,
    ) = LiveSensorDelivery(
        sensor,
        receiveNs,
        normalized(receiveNs),
        null,
        null,
        imu,
        microphone,
        touch,
    )

    private fun normalized(timestamp: Long) = NormalizedMonotonicTimestamp(
        timestamp,
        timestamp,
        4L,
        timestamp,
        0L,
        ClockNormalizationEvidence(1L, 0L, 8L, 4L, null, 1L),
    )

    private fun base() = SensorStreamEnvelope.newBuilder().setSessionId("session").setLeaseId("lease")
}
