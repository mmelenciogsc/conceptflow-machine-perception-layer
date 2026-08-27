// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import com.google.protobuf.ByteString
import org.conceptflow.mpl.v1.AudioSampleEncoding
import org.conceptflow.mpl.v1.CameraFrameChunk
import org.conceptflow.mpl.v1.ImuBatch
import org.conceptflow.mpl.v1.ImuReading
import org.conceptflow.mpl.v1.MicrophoneChunk
import org.conceptflow.mpl.v1.RokidTouchAction
import org.conceptflow.mpl.v1.RokidTouchEvent
import org.conceptflow.mpl.v1.RokidTouchKey
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.conceptflow.mpl.v1.SensorStreamKind

data class StreamPacketLimits(
    // 64 KiB keeps a 640x640 RGB frame to 19 records instead of 75 while remaining below the
    // receiver's independently enforced per-chunk maximum.
    val cameraChunkBytes: Int = 64 * 1_024,
    val maximumCameraBytes: Int = 2 * 1_024 * 1_024,
    val maximumImuSamplesPerBatch: Int = 64,
    val maximumMicrophoneChunkBytes: Int = 64 * 1_024,
) {
    init {
        require(cameraChunkBytes in 1_024..64 * 1_024)
        require(maximumCameraBytes in cameraChunkBytes..8 * 1_024 * 1_024)
        require(maximumImuSamplesPerBatch in 1..64)
        require(maximumMicrophoneChunkBytes in 256..256 * 1_024)
    }
}

/**
 * Creates bounded protocol packets but does not choose or authenticate a transport.
 * Envelope sequence/send time stay zero until [SensorStreamOutbox.takeNext]
 * establishes actual wire order.
 */
class SensorStreamPacketizer(
    private val clock: MonotonicClock,
    private val limits: StreamPacketLimits = StreamPacketLimits(),
) {
    @Synchronized
    fun camera(lease: ActiveStreamLease, frame: org.conceptflow.mpl.v1.FramePayload): List<SensorStreamEnvelope> {
        val now = clock.nowNanos()
        if (!lease.permits(SensorStreamKind.SENSOR_STREAM_KIND_CAMERA, now)) return emptyList()
        val payload = frame.frameData
        if (payload.isEmpty || payload.size() > limits.maximumCameraBytes ||
            payload.size().toLong() != frame.image.payloadBytes
        ) {
            return emptyList()
        }
        val chunkCount = (payload.size() + limits.cameraChunkBytes - 1) / limits.cameraChunkBytes
        // The Camera2 source has a local capture session. The authenticated live-link session is
        // authoritative on the wire, including the metadata repeated in the first chunk.
        val metadata = frame.toBuilder()
            .setSessionId(lease.sessionId)
            .clearFrameData()
            .build()
        return List(chunkCount) { chunkIndex ->
            val start = chunkIndex * limits.cameraChunkBytes
            val end = minOf(payload.size(), start + limits.cameraChunkBytes)
            val chunk = CameraFrameChunk.newBuilder()
                .setFrameId(frame.frameId)
                .setChunkIndex(chunkIndex)
                .setChunkCount(chunkCount)
                .setTotalPayloadBytes(payload.size().toLong())
                .setCaptureMonotonicTimestampNs(frame.captureMonotonicTimestampNs)
                .setChunkData(payload.substring(start, end))
                .apply { if (chunkIndex == 0) frameMetadata = metadata }
                .build()
            envelope(lease).setCameraChunk(chunk).build()
        }
    }

    @Synchronized
    fun imu(lease: ActiveStreamLease, batch: ImuTransmissionBatch): SensorStreamEnvelope? {
        val now = clock.nowNanos()
        if (!lease.permits(SensorStreamKind.SENSOR_STREAM_KIND_IMU, now) ||
            batch.samples.size > limits.maximumImuSamplesPerBatch
        ) {
            return null
        }
        val payload = ImuBatch.newBuilder()
            .setLeaseId(lease.leaseId)
            .setBatchId(batch.batchId)
            .setCreatedMonotonicTimestampNs(batch.createdMonotonicTimestampNanos)
            .addAllSamples(batch.samples.map(::imuReading))
            .build()
        return envelope(lease).setImuBatch(payload).build()
    }

    @Synchronized
    fun microphone(lease: ActiveStreamLease, chunk: PcmAudioChunk): SensorStreamEnvelope? {
        val now = clock.nowNanos()
        if (!lease.permits(SensorStreamKind.SENSOR_STREAM_KIND_MICROPHONE, now) ||
            chunk.pcm16LittleEndian.isEmpty() ||
            chunk.pcm16LittleEndian.size > limits.maximumMicrophoneChunkBytes
        ) {
            return null
        }
        val payload = MicrophoneChunk.newBuilder()
            .setLeaseId(lease.leaseId)
            .setChunkId(chunk.chunkId)
            .setCaptureMonotonicTimestampNs(chunk.captureMonotonicTimestampNs)
            .setSampleRateHz(chunk.sampleRateHz)
            .setChannelCount(chunk.channelCount)
            .setEncoding(AudioSampleEncoding.AUDIO_SAMPLE_ENCODING_PCM_S16LE)
            .setAudioData(ByteString.copyFrom(chunk.pcm16LittleEndian))
            .build()
        return envelope(lease).setMicrophoneChunk(payload).build()
    }

    @Synchronized
    fun touch(
        lease: ActiveStreamLease,
        eventId: Long,
        observedMonotonicTimestampNs: Long,
        event: RokidInputEvent,
    ): SensorStreamEnvelope? {
        val now = clock.nowNanos()
        if (!lease.permits(SensorStreamKind.SENSOR_STREAM_KIND_TOUCH, now) ||
            eventId <= 0L || observedMonotonicTimestampNs <= 0L || event.eventTimeMillis < 0L
        ) return null
        val key = when (event.key) {
            RokidInputKey.PREAMBLE -> RokidTouchKey.ROKID_TOUCH_KEY_PREAMBLE
            RokidInputKey.SWIPE_FORWARD -> RokidTouchKey.ROKID_TOUCH_KEY_SWIPE_FORWARD
            RokidInputKey.SWIPE_BACKWARD -> RokidTouchKey.ROKID_TOUCH_KEY_SWIPE_BACKWARD
            RokidInputKey.SINGLE_TAP -> RokidTouchKey.ROKID_TOUCH_KEY_SINGLE_TAP
            RokidInputKey.DOUBLE_TAP -> RokidTouchKey.ROKID_TOUCH_KEY_DOUBLE_TAP
            RokidInputKey.UNRELATED -> return null
        }
        val action = when (event.action) {
            RokidInputAction.DOWN -> RokidTouchAction.ROKID_TOUCH_ACTION_DOWN
            RokidInputAction.UP -> RokidTouchAction.ROKID_TOUCH_ACTION_UP
            RokidInputAction.OTHER -> return null
        }
        val payload = RokidTouchEvent.newBuilder()
            .setEventId(eventId)
            .setObservedMonotonicTimestampNs(observedMonotonicTimestampNs)
            .setSourceUptimeMs(event.eventTimeMillis)
            .setKey(key)
            .setAction(action)
            .setRepeatCount(event.repeatCount)
            .setCanceled(event.canceled)
            .setLongPress(event.longPress)
            .setScanCode(event.scanCode)
            .build()
        return envelope(lease).setTouchEvent(payload).build()
    }

    @Synchronized
    fun systemTouch(
        lease: ActiveStreamLease,
        eventId: Long,
        event: RokidSystemTouchEvent,
    ): SensorStreamEnvelope? {
        val now = clock.nowNanos()
        if (!lease.permits(SensorStreamKind.SENSOR_STREAM_KIND_TOUCH, now) || eventId <= 0L) {
            return null
        }
        if (event.input != RokidSystemBroadcastInput.TWO_FINGER_LONG_PRESS) return null
        val payload = RokidTouchEvent.newBuilder()
            .setEventId(eventId)
            .setObservedMonotonicTimestampNs(event.observedMonotonicTimestampNs)
            .setSourceUptimeMs(event.sourceUptimeMillis)
            .setKey(RokidTouchKey.ROKID_TOUCH_KEY_TWO_FINGER_LONG_PRESS)
            .setAction(RokidTouchAction.ROKID_TOUCH_ACTION_TRIGGERED)
            .setRepeatCount(0)
            .setCanceled(false)
            .setLongPress(true)
            // Physically correlated raw PSOC event: Linux KEY_PROG2 / scan code 149.
            .setScanCode(RokidCandidateInputProfile.TWO_FINGER_LONG_PRESS_SCAN_CODE)
            .build()
        return envelope(lease).setTouchEvent(payload).build()
    }

    private fun envelope(lease: ActiveStreamLease): SensorStreamEnvelope.Builder =
        SensorStreamEnvelope.newBuilder()
            .setSessionId(lease.sessionId)
            .setLeaseId(lease.leaseId)

    private fun imuReading(sample: ImuSample): ImuReading = ImuReading.newBuilder()
        .setSequenceId(sample.sequenceId)
        .setPose(sample.pose)
        .setAngularVelocityRadiansPerSecond(sample.angularVelocityRadiansPerSecond)
        .setLinearAccelerationMetersPerSecondSquared(sample.linearAccelerationMetersPerSecondSquared)
        .setOrientationAccuracy(sample.orientationAccuracy)
        .setAngularVelocityMonotonicTimestampNs(sample.angularVelocityTimestampNanos)
        .setLinearAccelerationMonotonicTimestampNs(sample.linearAccelerationTimestampNanos)
        .build()
}
