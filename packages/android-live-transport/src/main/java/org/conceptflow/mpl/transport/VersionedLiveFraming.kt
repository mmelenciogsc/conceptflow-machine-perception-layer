// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import org.conceptflow.mpl.v1.LiveLinkEnvelope

/** Stable wire kinds; numeric values are protocol, not Kotlin enum ordinals. */
internal enum class LiveWireMessageType(val wireValue: Int, val streamId: Int) {
    CONTROL(1, 0),
    CAMERA_FRAME(2, 1),
    AUDIO_BLOCK(3, 2),
    IMU_BATCH(4, 3),
    TOUCH_EVENT(5, 4),
    ;

    companion object {
        fun fromWire(value: Int): LiveWireMessageType? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Versioned binary framing for the TLS data plane. Large camera/audio bytes are outside protobuf
 * metadata, while authentication and binding remain provided by the enclosing mutual-TLS lane.
 */
internal object VersionedLiveFraming {
    const val HEADER_BYTES = 38
    const val MAJOR_VERSION = 1
    const val MINOR_VERSION = 1
    private const val MAXIMUM_SESSION_BYTES = 128
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'F'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte())

    fun write(envelope: LiveLinkEnvelope, output: OutputStream, maxFrameBytes: Int) {
        require(maxFrameBytes > 0)
        val split = split(envelope)
        val session = envelope.sessionId.toByteArray(StandardCharsets.UTF_8)
        if (session.isEmpty() || session.size > MAXIMUM_SESSION_BYTES ||
            split.metadata.size + split.payload.size() > maxFrameBytes
        ) throw FramingException(FramingFailure.INVALID_LENGTH)
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(MAJOR_VERSION.toByte())
            .put(MINOR_VERSION.toByte())
            .put(split.type.wireValue.toByte())
            .put(0)
            .putInt(split.type.streamId)
            .putLong(envelope.laneSequenceId)
            .putLong(split.sourceTimestampNs)
            .putShort(session.size.toShort())
            .putInt(split.metadata.size)
            .putInt(split.payload.size())
            .array()
        output.write(header)
        output.write(session)
        output.write(split.metadata)
        split.payload.writeTo(output)
    }

    private fun split(envelope: LiveLinkEnvelope): SplitRecord {
        require(envelope.sessionId.isNotBlank() && envelope.laneSequenceId > 0L)
        val type: LiveWireMessageType
        val sourceTimestampNs: Long
        var payload = ByteString.EMPTY
        val metadata = when {
            envelope.hasControl() -> {
                type = LiveWireMessageType.CONTROL
                sourceTimestampNs = envelope.sentMonotonicTimestampNs
                envelope
            }
            envelope.sensor.hasCameraChunk() -> {
                type = LiveWireMessageType.CAMERA_FRAME
                sourceTimestampNs = envelope.sensor.cameraChunk.captureMonotonicTimestampNs
                payload = envelope.sensor.cameraChunk.chunkData
                envelope.toBuilder().setSensor(
                    envelope.sensor.toBuilder().setCameraChunk(
                        envelope.sensor.cameraChunk.toBuilder().clearChunkData(),
                    ),
                ).build()
            }
            envelope.sensor.hasMicrophoneChunk() -> {
                type = LiveWireMessageType.AUDIO_BLOCK
                sourceTimestampNs = envelope.sensor.microphoneChunk.captureMonotonicTimestampNs
                payload = envelope.sensor.microphoneChunk.audioData
                envelope.toBuilder().setSensor(
                    envelope.sensor.toBuilder().setMicrophoneChunk(
                        envelope.sensor.microphoneChunk.toBuilder().clearAudioData(),
                    ),
                ).build()
            }
            envelope.sensor.hasImuBatch() -> {
                type = LiveWireMessageType.IMU_BATCH
                sourceTimestampNs = envelope.sensor.imuBatch.createdMonotonicTimestampNs
                envelope
            }
            envelope.sensor.hasTouchEvent() -> {
                type = LiveWireMessageType.TOUCH_EVENT
                sourceTimestampNs = envelope.sensor.touchEvent.observedMonotonicTimestampNs
                envelope
            }
            else -> throw FramingException(FramingFailure.MALFORMED_PROTOBUF)
        }
        if (sourceTimestampNs <= 0L) throw FramingException(FramingFailure.MALFORMED_PROTOBUF)
        return SplitRecord(type, sourceTimestampNs, metadata.toByteArray(), payload)
    }

    private data class SplitRecord(
        val type: LiveWireMessageType,
        val sourceTimestampNs: Long,
        val metadata: ByteArray,
        val payload: ByteString,
    )

    internal fun magic(): ByteArray = MAGIC.copyOf()
}

/** Stateful parser: socket timeouts preserve every partially read header and body byte. */
internal class VersionedLiveFrameReader(
    private val input: InputStream,
    private val maxFrameBytes: Int,
) {
    private val header = ByteArray(VersionedLiveFraming.HEADER_BYTES)
    private var headerRead = 0
    private var parsed: ParsedHeader? = null
    private var session = ByteArray(0)
    private var sessionRead = 0
    private var metadata = ByteArray(0)
    private var metadataRead = 0
    private var payload = ByteArray(0)
    private var payloadRead = 0

    init {
        require(maxFrameBytes > 0)
    }

    fun readOrNull(): LiveLinkEnvelope? {
        try {
            while (headerRead < header.size) {
                val count = readSome(header, headerRead, header.size - headerRead)
                if (count == -1) {
                    if (headerRead == 0) return null
                    throw FramingException(FramingFailure.TRUNCATED_PREFIX)
                }
                headerRead += count
            }
            val current = parsed ?: parseHeader().also {
                parsed = it
                session = ByteArray(it.sessionBytes)
                metadata = ByteArray(it.metadataBytes)
                payload = ByteArray(it.payloadBytes)
            }
            readFully(session) { sessionRead = it }
            readFully(metadata) { metadataRead = it }
            readFully(payload) { payloadRead = it }
            val result = reconstruct(current)
            reset()
            return result
        } catch (error: java.net.SocketTimeoutException) {
            throw error
        } catch (error: FramingException) {
            reset()
            throw error
        } catch (error: EOFException) {
            reset()
            throw FramingException(FramingFailure.TRUNCATED_RECORD, error)
        } catch (error: InvalidProtocolBufferException) {
            reset()
            throw FramingException(FramingFailure.MALFORMED_PROTOBUF, error)
        } catch (error: CharacterCodingException) {
            reset()
            throw FramingException(FramingFailure.MALFORMED_PROTOBUF, error)
        }
    }

    private fun parseHeader(): ParsedHeader {
        if (!header.copyOfRange(0, 4).contentEquals(VersionedLiveFraming.magic())) {
            throw FramingException(FramingFailure.MALFORMED_PROTOBUF)
        }
        val source = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        source.position(4)
        val major = source.get().toInt() and 0xff
        val minor = source.get().toInt() and 0xff
        val type = LiveWireMessageType.fromWire(source.get().toInt() and 0xff)
            ?: throw FramingException(FramingFailure.MALFORMED_PROTOBUF)
        val flags = source.get().toInt() and 0xff
        val streamId = source.int
        val sequence = source.long
        val sourceTimestampNs = source.long
        val sessionBytes = source.short.toInt() and 0xffff
        val metadataBytes = source.int
        val payloadBytes = source.int
        if (major != VersionedLiveFraming.MAJOR_VERSION || minor > VersionedLiveFraming.MINOR_VERSION ||
            flags != 0 || streamId != type.streamId || sequence <= 0L || sourceTimestampNs <= 0L ||
            sessionBytes !in 1..128 || metadataBytes <= 0 || payloadBytes < 0 ||
            metadataBytes.toLong() + payloadBytes.toLong() > maxFrameBytes
        ) throw FramingException(FramingFailure.INVALID_LENGTH)
        return ParsedHeader(type, sequence, sourceTimestampNs, sessionBytes, metadataBytes, payloadBytes)
    }

    private fun reconstruct(header: ParsedHeader): LiveLinkEnvelope {
        val sessionId = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(session)).toString()
        if (sessionId.isBlank()) throw FramingException(FramingFailure.MALFORMED_PROTOBUF)
        var envelope = LiveLinkEnvelope.parseFrom(metadata)
        if (envelope.sessionId != sessionId || envelope.laneSequenceId != header.sequence) {
            throw FramingException(FramingFailure.MALFORMED_PROTOBUF)
        }
        envelope = when (header.type) {
            LiveWireMessageType.CONTROL -> {
                if (!envelope.hasControl() || payload.isNotEmpty()) malformed()
                envelope
            }
            LiveWireMessageType.CAMERA_FRAME -> {
                if (!envelope.hasSensor() || !envelope.sensor.hasCameraChunk() || payload.isEmpty() ||
                    !envelope.sensor.cameraChunk.chunkData.isEmpty
                ) malformed()
                envelope.toBuilder().setSensor(
                    envelope.sensor.toBuilder().setCameraChunk(
                        envelope.sensor.cameraChunk.toBuilder().setChunkData(ByteString.copyFrom(payload)),
                    ),
                ).build()
            }
            LiveWireMessageType.AUDIO_BLOCK -> {
                if (!envelope.hasSensor() || !envelope.sensor.hasMicrophoneChunk() || payload.isEmpty() ||
                    !envelope.sensor.microphoneChunk.audioData.isEmpty
                ) malformed()
                envelope.toBuilder().setSensor(
                    envelope.sensor.toBuilder().setMicrophoneChunk(
                        envelope.sensor.microphoneChunk.toBuilder().setAudioData(ByteString.copyFrom(payload)),
                    ),
                ).build()
            }
            LiveWireMessageType.IMU_BATCH -> {
                if (!envelope.hasSensor() || !envelope.sensor.hasImuBatch() || payload.isNotEmpty()) malformed()
                envelope
            }
            LiveWireMessageType.TOUCH_EVENT -> {
                if (!envelope.hasSensor() || !envelope.sensor.hasTouchEvent() || payload.isNotEmpty()) malformed()
                envelope
            }
        }
        if (sourceTimestamp(envelope) != header.sourceTimestampNs) malformed()
        return envelope
    }

    private fun sourceTimestamp(envelope: LiveLinkEnvelope): Long = when {
        envelope.hasControl() -> envelope.sentMonotonicTimestampNs
        envelope.sensor.hasCameraChunk() -> envelope.sensor.cameraChunk.captureMonotonicTimestampNs
        envelope.sensor.hasMicrophoneChunk() -> envelope.sensor.microphoneChunk.captureMonotonicTimestampNs
        envelope.sensor.hasImuBatch() -> envelope.sensor.imuBatch.createdMonotonicTimestampNs
        envelope.sensor.hasTouchEvent() -> envelope.sensor.touchEvent.observedMonotonicTimestampNs
        else -> malformed()
    }

    private fun readFully(target: ByteArray, update: (Int) -> Unit) {
        var offset = when (target) {
            session -> sessionRead
            metadata -> metadataRead
            else -> payloadRead
        }
        while (offset < target.size) {
            val count = readSome(target, offset, target.size - offset)
            if (count == -1) throw EOFException()
            offset += count
            update(offset)
        }
    }

    private fun readSome(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val count = input.read(target, offset, length)
        if (count != 0) return count
        val next = input.read()
        if (next == -1) return -1
        target[offset] = next.toByte()
        return 1
    }

    private fun reset() {
        headerRead = 0
        parsed = null
        session = ByteArray(0)
        sessionRead = 0
        metadata = ByteArray(0)
        metadataRead = 0
        payload = ByteArray(0)
        payloadRead = 0
    }

    private fun malformed(): Nothing = throw FramingException(FramingFailure.MALFORMED_PROTOBUF)

    private data class ParsedHeader(
        val type: LiveWireMessageType,
        val sequence: Long,
        val sourceTimestampNs: Long,
        val sessionBytes: Int,
        val metadataBytes: Int,
        val payloadBytes: Int,
    )
}
