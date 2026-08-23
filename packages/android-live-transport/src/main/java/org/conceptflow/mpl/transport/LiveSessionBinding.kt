// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKey
import org.conceptflow.mpl.v1.LiveTransportLane

class LiveSessionBinding(
    val sessionId: String,
    val leaseId: String,
    connectionNonce: ByteArray,
) {
    private val nonce = connectionNonce.copyOf()

    init {
        requireBoundedIdentifier(sessionId, "sessionId")
        requireBoundedIdentifier(leaseId, "leaseId")
        require(nonce.size == CONNECTION_NONCE_BYTES) { "connection nonce must be 32 bytes" }
    }

    val connectionNonce: ByteArray
        get() = nonce.copyOf()

    internal fun canonicalBytes(lane: LiveTransportLane): ByteArray {
        requireSupportedLane(lane)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(lane.number)
            writeBoundedUtf8(data, sessionId)
            writeBoundedUtf8(data, leaseId)
            data.writeInt(nonce.size)
            data.write(nonce)
        }
        return output.toByteArray()
    }

    fun matches(sessionId: String, leaseId: String, connectionNonce: ByteArray): Boolean =
        this.sessionId == sessionId &&
            this.leaseId == leaseId &&
            MessageDigest.isEqual(nonce, connectionNonce)

    override fun equals(other: Any?): Boolean =
        other is LiveSessionBinding &&
            sessionId == other.sessionId &&
            leaseId == other.leaseId &&
            nonce.contentEquals(other.nonce)

    override fun hashCode(): Int = 31 * (31 * sessionId.hashCode() + leaseId.hashCode()) + nonce.contentHashCode()

    override fun toString(): String =
        "LiveSessionBinding(sessionId=<redacted>,leaseId=<redacted>,connectionNonce=<redacted>)"

    companion object {
        const val CONNECTION_NONCE_BYTES: Int = 32
        private const val MAX_IDENTIFIER_BYTES = 256

        private fun requireBoundedIdentifier(value: String, label: String) {
            val encoded = value.toByteArray(StandardCharsets.UTF_8)
            require(value.isNotBlank() && encoded.size <= MAX_IDENTIFIER_BYTES) {
                "$label must be non-blank and at most $MAX_IDENTIFIER_BYTES UTF-8 bytes"
            }
        }

        private fun writeBoundedUtf8(output: DataOutputStream, value: String) {
            val encoded = value.toByteArray(StandardCharsets.UTF_8)
            output.writeInt(encoded.size)
            output.write(encoded)
        }

        internal fun requireSupportedLane(lane: LiveTransportLane) {
            require(
                lane == LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL ||
                    lane == LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA,
            ) { "unsupported transport lane" }
        }
    }
}

enum class CameraTicketFailure {
    MALFORMED,
    AUTHENTICATION_FAILED,
    EXPIRED,
    REPLAYED,
    CAPACITY_EXHAUSTED,
}

class CameraTicketException(val failure: CameraTicketFailure) :
    SecurityException("camera lane ticket rejected: $failure")

/** Host-side authority for opaque, single-use camera-lane tickets. */
class CameraLaneTicketAuthority(
    private val hmacKey: SecretKey,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val maxLifetimeNs: Long = DEFAULT_MAX_LIFETIME_NS,
    private val maxConsumedTickets: Int = DEFAULT_MAX_CONSUMED_TICKETS,
) {
    private val consumed = LinkedHashMap<TicketDigest, Long>()

    init {
        require(maxLifetimeNs > 0) { "maxLifetimeNs must be positive" }
        require(maxConsumedTickets > 0) { "maxConsumedTickets must be positive" }
        newMac()
    }

    @Synchronized
    fun issue(binding: LiveSessionBinding, nowNs: Long, lifetimeNs: Long): ByteArray {
        require(nowNs >= 0) { "nowNs must be non-negative" }
        require(lifetimeNs in 1..maxLifetimeNs) { "ticket lifetime is outside the configured bound" }
        val expiresNs = try {
            Math.addExact(nowNs, lifetimeNs)
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("ticket expiry overflows monotonic time", error)
        }
        val ticketNonce = ByteArray(TICKET_NONCE_BYTES).also(secureRandom::nextBytes)
        val unsigned = unsignedTicket(expiresNs, ticketNonce)
        val tag = tag(binding, expiresNs, ticketNonce)
        return unsigned + tag
    }

    @Synchronized
    fun consume(ticket: ByteArray, binding: LiveSessionBinding, nowNs: Long) {
        if (nowNs < 0 || ticket.size != TICKET_BYTES || ticket[0] != VERSION) {
            throw CameraTicketException(CameraTicketFailure.MALFORMED)
        }
        val expiresNs = readLong(ticket, 1)
        if (expiresNs < 0) throw CameraTicketException(CameraTicketFailure.MALFORMED)
        val ticketNonce = ticket.copyOfRange(1 + Long.SIZE_BYTES, UNSIGNED_TICKET_BYTES)
        val actualTag = ticket.copyOfRange(UNSIGNED_TICKET_BYTES, TICKET_BYTES)
        val expectedTag = tag(binding, expiresNs, ticketNonce)
        if (!MessageDigest.isEqual(expectedTag, actualTag)) {
            throw CameraTicketException(CameraTicketFailure.AUTHENTICATION_FAILED)
        }
        if (expiresNs < nowNs) throw CameraTicketException(CameraTicketFailure.EXPIRED)

        pruneExpired(nowNs)
        val digest = TicketDigest(MessageDigest.getInstance("SHA-256").digest(ticket))
        if (consumed.containsKey(digest)) throw CameraTicketException(CameraTicketFailure.REPLAYED)
        if (consumed.size >= maxConsumedTickets) {
            throw CameraTicketException(CameraTicketFailure.CAPACITY_EXHAUSTED)
        }
        consumed[digest] = expiresNs
    }

    @Synchronized
    fun reset() {
        consumed.clear()
    }

    override fun toString(): String = "CameraLaneTicketAuthority(key=<redacted>,consumed=${consumed.size})"

    private fun pruneExpired(nowNs: Long) {
        val iterator = consumed.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < nowNs) iterator.remove()
        }
    }

    private fun tag(binding: LiveSessionBinding, expiresNs: Long, ticketNonce: ByteArray): ByteArray {
        val mac = newMac()
        mac.update(PURPOSE)
        mac.update(binding.canonicalBytes(LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA))
        mac.update(unsignedTicket(expiresNs, ticketNonce))
        return mac.doFinal()
    }

    private fun newMac(): Mac = Mac.getInstance(HMAC_ALGORITHM).also { it.init(hmacKey) }

    private fun unsignedTicket(expiresNs: Long, ticketNonce: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(UNSIGNED_TICKET_BYTES)
        DataOutputStream(output).use { data ->
            data.writeByte(VERSION.toInt())
            data.writeLong(expiresNs)
            data.write(ticketNonce)
        }
        return output.toByteArray()
    }

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(Long.SIZE_BYTES) { index ->
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
        }
        return value
    }

    private class TicketDigest(private val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is TicketDigest && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val TICKET_NONCE_BYTES = 16
        private const val HMAC_BYTES = 32
        private const val UNSIGNED_TICKET_BYTES = 1 + Long.SIZE_BYTES + TICKET_NONCE_BYTES
        private const val TICKET_BYTES = UNSIGNED_TICKET_BYTES + HMAC_BYTES
        private const val DEFAULT_MAX_LIFETIME_NS = 30_000_000_000L
        private const val DEFAULT_MAX_CONSUMED_TICKETS = 64
        private const val VERSION: Byte = 1
        private val PURPOSE = "CONCEPTFLOW_CAMERA_LANE_TICKET_V1".toByteArray(StandardCharsets.US_ASCII)
    }
}
