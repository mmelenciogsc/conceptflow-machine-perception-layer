// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.conceptflow.mpl.v1.LiveTransportLane

/** Fixed, privacy-safe framing failures. No payload bytes, lengths, or peer data are exposed. */
enum class FramingFailure {
    INVALID_LENGTH,
    MALFORMED_PROTOBUF,
    TRUNCATED_PREFIX,
    TRUNCATED_RECORD,
}

class FramingException(
    val failure: FramingFailure,
    cause: Throwable? = null,
) : IOException(failure.name, cause)

object LiveLaneFrameLimits {
    const val REALTIME_CONTROL_MAX_BYTES: Int = 64 * 1024
    const val CAMERA_MAX_BYTES: Int = 96 * 1024

    fun forLane(lane: LiveTransportLane): Int = when (lane) {
        LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL -> REALTIME_CONTROL_MAX_BYTES
        LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA -> CAMERA_MAX_BYTES
        else -> throw IllegalArgumentException("unsupported transport lane")
    }
}

/** Strict four-byte, big-endian, positive-length protobuf framing. */
object BoundedProtobufFraming {
    fun write(message: MessageLite, output: OutputStream, maxFrameBytes: Int) {
        require(maxFrameBytes > 0) { "maxFrameBytes must be positive" }
        val size = message.serializedSize
        if (size <= 0 || size > maxFrameBytes) {
            throw FramingException(FramingFailure.INVALID_LENGTH)
        }
        output.write((size ushr 24) and 0xff)
        output.write((size ushr 16) and 0xff)
        output.write((size ushr 8) and 0xff)
        output.write(size and 0xff)
        message.writeTo(output)
    }

    fun <T : MessageLite> readOrNull(
        input: InputStream,
        parser: Parser<T>,
        maxFrameBytes: Int,
    ): T? = BoundedProtobufRecordReader(input, parser, maxFrameBytes).readOrNull()
}

/**
 * Stateful reader for one stream. If a socket timeout interrupts a prefix or payload, the next
 * call resumes at the exact byte offset instead of treating remaining payload bytes as a prefix.
 */
internal class BoundedProtobufRecordReader<T : MessageLite>(
    private val input: InputStream,
    private val parser: Parser<T>,
    private val maxFrameBytes: Int,
) {
    private val prefix = ByteArray(LENGTH_PREFIX_BYTES)
    private var prefixBytesRead = 0
    private var payload: ByteArray? = null
    private var payloadBytesRead = 0

    init {
        require(maxFrameBytes > 0) { "maxFrameBytes must be positive" }
    }

    fun readOrNull(): T? {
        while (prefixBytesRead < prefix.size) {
            val count = readSome(prefix, prefixBytesRead, prefix.size - prefixBytesRead)
            if (count == -1) {
                if (prefixBytesRead == 0) return null
                reset()
                throw FramingException(FramingFailure.TRUNCATED_PREFIX)
            }
            prefixBytesRead += count
        }

        val record = payload ?: allocatePayload().also { payload = it }
        while (payloadBytesRead < record.size) {
            val count = readSome(record, payloadBytesRead, record.size - payloadBytesRead)
            if (count == -1) {
                reset()
                throw FramingException(FramingFailure.TRUNCATED_RECORD)
            }
            payloadBytesRead += count
        }

        reset()
        return try {
            parser.parseFrom(record)
        } catch (error: InvalidProtocolBufferException) {
            throw FramingException(FramingFailure.MALFORMED_PROTOBUF, error)
        }
    }

    private fun allocatePayload(): ByteArray {
        val unsignedLength =
            ((prefix[0].toLong() and 0xffL) shl 24) or
                ((prefix[1].toLong() and 0xffL) shl 16) or
                ((prefix[2].toLong() and 0xffL) shl 8) or
                (prefix[3].toLong() and 0xffL)
        if (unsignedLength == 0L || unsignedLength > maxFrameBytes.toLong()) {
            reset()
            throw FramingException(FramingFailure.INVALID_LENGTH)
        }
        return ByteArray(unsignedLength.toInt())
    }

    /** InputStream permits zero-byte reads; force bounded forward progress without busy looping. */
    private fun readSome(target: ByteArray, offset: Int, length: Int): Int {
        val count = input.read(target, offset, length)
        if (count != 0) return count
        val next = input.read()
        if (next == -1) return -1
        target[offset] = next.toByte()
        return 1
    }

    private fun reset() {
        prefixBytesRead = 0
        payload = null
        payloadBytesRead = 0
    }

    private companion object {
        const val LENGTH_PREFIX_BYTES = 4
    }
}
