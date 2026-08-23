// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import com.google.protobuf.ByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import org.conceptflow.mpl.v1.CameraFrameChunk
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.LiveLinkEnvelope
import org.conceptflow.mpl.v1.LiveLinkKeepalive
import org.conceptflow.mpl.v1.LiveLinkControl
import org.conceptflow.mpl.v1.LiveTransportLane
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedProtobufFramingTest {
    @Test
    fun `round trips a bounded big endian protobuf record`() {
        val envelope = LiveLinkEnvelope.newBuilder()
            .setSessionId("session")
            .setLeaseId("lease")
            .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL)
            .setLaneSequenceId(1)
            .setControl(
                LiveLinkControl.newBuilder().setKeepalive(
                    LiveLinkKeepalive.newBuilder().setNonce(1).setSentMonotonicNs(100),
                ),
            )
            .build()
        val output = ByteArrayOutputStream()

        BoundedProtobufFraming.write(envelope, output, LiveLaneFrameLimits.REALTIME_CONTROL_MAX_BYTES)

        val bytes = output.toByteArray()
        val encodedSize = ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
        assertEquals(envelope.serializedSize, encodedSize)
        assertEquals(
            envelope,
            BoundedProtobufFraming.readOrNull(
                ByteArrayInputStream(bytes),
                LiveLinkEnvelope.parser(),
                LiveLaneFrameLimits.REALTIME_CONTROL_MAX_BYTES,
            ),
        )
        assertNull(
            BoundedProtobufFraming.readOrNull(
                ByteArrayInputStream(ByteArray(0)),
                LiveLinkEnvelope.parser(),
                LiveLaneFrameLimits.REALTIME_CONTROL_MAX_BYTES,
            ),
        )
    }

    @Test
    fun `rejects an oversized length before reading a payload`() {
        val oversized = LiveLaneFrameLimits.REALTIME_CONTROL_MAX_BYTES + 1
        val prefix = byteArrayOf(
            (oversized ushr 24).toByte(),
            (oversized ushr 16).toByte(),
            (oversized ushr 8).toByte(),
            oversized.toByte(),
        )

        val error = assertThrows(FramingException::class.java) {
            BoundedProtobufFraming.readOrNull(
                ByteArrayInputStream(prefix),
                LiveLinkEnvelope.parser(),
                LiveLaneFrameLimits.REALTIME_CONTROL_MAX_BYTES,
            )
        }
        assertEquals(FramingFailure.INVALID_LENGTH, error.failure)
    }

    @Test
    fun `rejects a truncated payload`() {
        val error = assertThrows(FramingException::class.java) {
            BoundedProtobufFraming.readOrNull(
                ByteArrayInputStream(byteArrayOf(0, 0, 0, 2, 8)),
                LiveLinkEnvelope.parser(),
                LiveLaneFrameLimits.REALTIME_CONTROL_MAX_BYTES,
            )
        }
        assertEquals(FramingFailure.TRUNCATED_RECORD, error.failure)
    }

    @Test
    fun `rejects shutdown in the middle of a length prefix`() {
        val error = assertThrows(FramingException::class.java) {
            BoundedProtobufFraming.readOrNull(
                ByteArrayInputStream(byteArrayOf(0, 0)),
                LiveLinkEnvelope.parser(),
                LiveLaneFrameLimits.REALTIME_CONTROL_MAX_BYTES,
            )
        }
        assertEquals(FramingFailure.TRUNCATED_PREFIX, error.failure)
    }

    @Test
    fun `accepts orderly shutdown only at a complete record boundary`() {
        val first = envelope(sequence = 1, nonce = 11)
        val second = envelope(sequence = 2, nonce = 12)
        val output = ByteArrayOutputStream().also {
            BoundedProtobufFraming.write(first, it, LiveLaneFrameLimits.REALTIME_CONTROL_MAX_BYTES)
            BoundedProtobufFraming.write(second, it, LiveLaneFrameLimits.REALTIME_CONTROL_MAX_BYTES)
        }
        val input = ByteArrayInputStream(output.toByteArray())

        assertEquals(
            first,
            BoundedProtobufFraming.readOrNull(
                input,
                LiveLinkEnvelope.parser(),
                LiveLaneFrameLimits.REALTIME_CONTROL_MAX_BYTES,
            ),
        )
        assertEquals(
            second,
            BoundedProtobufFraming.readOrNull(
                input,
                LiveLinkEnvelope.parser(),
                LiveLaneFrameLimits.REALTIME_CONTROL_MAX_BYTES,
            ),
        )
        assertNull(
            BoundedProtobufFraming.readOrNull(
                input,
                LiveLinkEnvelope.parser(),
                LiveLaneFrameLimits.REALTIME_CONTROL_MAX_BYTES,
            ),
        )
    }

    @Test
    fun `rejects malformed protobuf bytes`() {
        val error = assertThrows(FramingException::class.java) {
            BoundedProtobufFraming.readOrNull(
                ByteArrayInputStream(byteArrayOf(0, 0, 0, 1, 0x80.toByte())),
                LiveLinkEnvelope.parser(),
                LiveLaneFrameLimits.REALTIME_CONTROL_MAX_BYTES,
            )
        }
        assertEquals(FramingFailure.MALFORMED_PROTOBUF, error.failure)
    }

    @Test
    fun `stateful reader resumes a fragmented record after socket timeouts`() {
        val expected = envelope(sequence = 9, nonce = 91)
        val encoded = ByteArrayOutputStream().also {
            BoundedProtobufFraming.write(expected, it, LiveLaneFrameLimits.REALTIME_CONTROL_MAX_BYTES)
        }.toByteArray()
        val input = FragmentingTimeoutInputStream(encoded, maximumFragmentBytes = 1, timeoutEveryCalls = 3)
        val reader = BoundedProtobufRecordReader(
            input,
            LiveLinkEnvelope.parser(),
            LiveLaneFrameLimits.REALTIME_CONTROL_MAX_BYTES,
        )
        var actual: LiveLinkEnvelope? = null
        var timeouts = 0
        while (actual == null) {
            try {
                actual = reader.readOrNull()
            } catch (_: SocketTimeoutException) {
                timeouts++
            }
        }

        assertTrue(timeouts > 0)
        assertEquals(expected, actual)
        assertNull(readThroughTimeouts(reader))
    }

    @Test
    fun `stateful reader preserves 544 sustained camera records across partial reads and timeouts`() {
        val payload = ByteString.copyFrom(ByteArray(CAMERA_PAYLOAD_BYTES) { (it % 251).toByte() })
        val expected = (0 until SUSTAINED_CAMERA_RECORDS).map { index -> cameraEnvelope(index, payload) }
        val encoded = ByteArrayOutputStream().also { output ->
            expected.forEach { envelope ->
                BoundedProtobufFraming.write(envelope, output, LiveLaneFrameLimits.CAMERA_MAX_BYTES)
            }
        }.toByteArray()
        val input = FragmentingTimeoutInputStream(encoded, maximumFragmentBytes = 2_048, timeoutEveryCalls = 11)
        val reader = BoundedProtobufRecordReader(
            input,
            LiveLinkEnvelope.parser(),
            LiveLaneFrameLimits.CAMERA_MAX_BYTES,
        )

        expected.forEach { envelope -> assertEquals(envelope, readThroughTimeouts(reader)) }
        assertNull(readThroughTimeouts(reader))
        assertTrue(input.timeoutsThrown > 0)
    }

    private fun readThroughTimeouts(reader: BoundedProtobufRecordReader<LiveLinkEnvelope>): LiveLinkEnvelope? {
        while (true) {
            try {
                return reader.readOrNull()
            } catch (_: SocketTimeoutException) {
                // A socket poll timeout is not a framing boundary; retry on the same stateful reader.
            }
        }
    }

    private fun cameraEnvelope(index: Int, payload: ByteString): LiveLinkEnvelope {
        val chunkIndex = index % CAMERA_CHUNKS_PER_FRAME
        val frameId = index / CAMERA_CHUNKS_PER_FRAME + 1L
        val chunk = CameraFrameChunk.newBuilder()
            .setFrameId(frameId)
            .setChunkIndex(chunkIndex)
            .setChunkCount(CAMERA_CHUNKS_PER_FRAME)
            .setTotalPayloadBytes((CAMERA_CHUNKS_PER_FRAME * CAMERA_PAYLOAD_BYTES).toLong())
            .setChunkData(payload)
        if (chunkIndex == 0) {
            chunk.frameMetadata = FramePayload.newBuilder()
                .setSessionId("session")
                .setStreamId("camera")
                .setFrameId(frameId)
                .setCaptureMonotonicTimestampNs(frameId)
                .build()
        }
        return LiveLinkEnvelope.newBuilder()
            .setSessionId("session")
            .setLeaseId("lease")
            .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA)
            .setLaneSequenceId(index + 1L)
            .setSensor(
                SensorStreamEnvelope.newBuilder()
                    .setSessionId("session")
                    .setLeaseId("lease")
                    .setCameraChunk(chunk),
            )
            .build()
    }

    private fun envelope(sequence: Long, nonce: Long): LiveLinkEnvelope = LiveLinkEnvelope.newBuilder()
        .setSessionId("session")
        .setLeaseId("lease")
        .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL)
        .setLaneSequenceId(sequence)
        .setControl(
            LiveLinkControl.newBuilder().setKeepalive(
                LiveLinkKeepalive.newBuilder().setNonce(nonce).setSentMonotonicNs(100),
            ),
        )
        .build()

    private class FragmentingTimeoutInputStream(
        private val source: ByteArray,
        private val maximumFragmentBytes: Int,
        private val timeoutEveryCalls: Int,
    ) : InputStream() {
        private var position = 0
        private var readCalls = 0
        var timeoutsThrown = 0
            private set

        override fun read(): Int {
            val single = ByteArray(1)
            val count = read(single, 0, 1)
            return if (count == -1) -1 else single[0].toInt() and 0xff
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset + length <= target.size)
            if (length == 0) return 0
            readCalls++
            if (readCalls % timeoutEveryCalls == 0) {
                timeoutsThrown++
                throw SocketTimeoutException("synthetic poll timeout")
            }
            if (position == source.size) return -1
            val count = minOf(length, maximumFragmentBytes, source.size - position)
            source.copyInto(target, offset, position, position + count)
            position += count
            return count
        }
    }

    private companion object {
        const val CAMERA_PAYLOAD_BYTES = 16 * 1_024
        const val CAMERA_CHUNKS_PER_FRAME = 68
        const val SUSTAINED_CAMERA_RECORDS = 544
    }
}
