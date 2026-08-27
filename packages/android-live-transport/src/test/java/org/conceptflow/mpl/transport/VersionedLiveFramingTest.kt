// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import com.google.protobuf.ByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.conceptflow.mpl.v1.CameraFrameChunk
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.LiveLinkEnvelope
import org.conceptflow.mpl.v1.LiveTransportLane
import org.conceptflow.mpl.v1.SensorStreamEnvelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionedLiveFramingTest {
    @Test
    fun `camera bytes round trip outside protobuf metadata with explicit header`() {
        val expected = cameraEnvelope(ByteArray(16_384) { (it % 251).toByte() })
        val output = ByteArrayOutputStream()

        VersionedLiveFraming.write(expected, output, LiveLaneFrameLimits.CAMERA_MAX_BYTES)

        val wire = output.toByteArray()
        assertArrayEquals(byteArrayOf(0x43, 0x46, 0x4d, 0x50), wire.copyOfRange(0, 4))
        val header = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN)
        header.position(30)
        val metadataBytes = header.int
        val payloadBytes = header.int
        assertTrue(metadataBytes < expected.serializedSize)
        assertEquals(16_384, payloadBytes)
        assertEquals(
            expected,
            VersionedLiveFrameReader(ByteArrayInputStream(wire), LiveLaneFrameLimits.CAMERA_MAX_BYTES)
                .readOrNull(),
        )
    }

    @Test
    fun `fragmentation and socket timeouts preserve parser state`() {
        val expected = cameraEnvelope(ByteArray(8_192) { 7 })
        val wire = ByteArrayOutputStream().also {
            VersionedLiveFraming.write(expected, it, LiveLaneFrameLimits.CAMERA_MAX_BYTES)
        }.toByteArray()
        val reader = VersionedLiveFrameReader(
            TimeoutFragmentInput(wire, maximumRead = 97, timeoutEvery = 5),
            LiveLaneFrameLimits.CAMERA_MAX_BYTES,
        )
        var actual: LiveLinkEnvelope? = null
        var timeouts = 0
        while (actual == null) {
            try {
                actual = reader.readOrNull()
            } catch (_: SocketTimeoutException) {
                timeouts += 1
            }
        }
        assertTrue(timeouts > 0)
        assertEquals(expected, actual)
        var terminal: LiveLinkEnvelope? = actual
        while (terminal != null) {
            try {
                terminal = reader.readOrNull()
            } catch (_: SocketTimeoutException) {
                // A timeout is not an EOF boundary.
            }
        }
        assertNull(terminal)
    }

    @Test
    fun `bad magic is rejected before allocation`() {
        val wire = ByteArrayOutputStream().also {
            VersionedLiveFraming.write(cameraEnvelope(byteArrayOf(1)), it, LiveLaneFrameLimits.CAMERA_MAX_BYTES)
        }.toByteArray()
        wire[0] = 0
        assertThrows(FramingException::class.java) {
            VersionedLiveFrameReader(ByteArrayInputStream(wire), LiveLaneFrameLimits.CAMERA_MAX_BYTES)
                .readOrNull()
        }
    }

    private fun cameraEnvelope(payload: ByteArray): LiveLinkEnvelope {
        val captureNs = 8_000_000_000L
        val chunk = CameraFrameChunk.newBuilder()
            .setFrameMetadata(
                FramePayload.newBuilder()
                    .setSessionId("session")
                    .setStreamId("camera")
                    .setFrameId(7L)
                    .setCaptureMonotonicTimestampNs(captureNs),
            )
            .setFrameId(7L)
            .setChunkIndex(0)
            .setChunkCount(1)
            .setTotalPayloadBytes(payload.size.toLong())
            .setCaptureMonotonicTimestampNs(captureNs)
            .setChunkData(ByteString.copyFrom(payload))
        return LiveLinkEnvelope.newBuilder()
            .setSessionId("session")
            .setLeaseId("lease")
            .setLane(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA)
            .setLaneSequenceId(1L)
            .setSentMonotonicTimestampNs(captureNs + 1L)
            .setSensor(
                SensorStreamEnvelope.newBuilder()
                    .setSessionId("session")
                    .setLeaseId("lease")
                    .setCameraChunk(chunk),
            )
            .build()
    }

    private class TimeoutFragmentInput(
        private val bytes: ByteArray,
        private val maximumRead: Int,
        private val timeoutEvery: Int,
    ) : InputStream() {
        private var position = 0
        private var calls = 0

        override fun read(): Int {
            if (position == bytes.size) return -1
            return bytes[position++].toInt() and 0xff
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            calls += 1
            if (calls % timeoutEvery == 0) throw SocketTimeoutException("synthetic")
            if (position == bytes.size) return -1
            val count = minOf(length, maximumRead, bytes.size - position)
            bytes.copyInto(target, offset, position, position + count)
            position += count
            return count
        }
    }
}
