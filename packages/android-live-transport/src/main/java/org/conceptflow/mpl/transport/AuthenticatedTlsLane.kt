// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.io.Closeable
import javax.net.ssl.SSLSocket
import org.conceptflow.mpl.v1.LiveLinkEnvelope
import org.conceptflow.mpl.v1.LiveTransportLane

/** One authenticated TLS socket dedicated to exactly one live transport lane. */
class AuthenticatedTlsLane internal constructor(
    val lane: LiveTransportLane,
    internal val socket: SSLSocket,
) : Closeable {
    private val maxFrameBytes = LiveLaneFrameLimits.forLane(lane)
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val reader = BoundedProtobufRecordReader(input, LiveLinkEnvelope.parser(), maxFrameBytes)

    init {
        if (lane == LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL) {
            socket.tcpNoDelay = true
        }
    }

    @Synchronized
    fun write(envelope: LiveLinkEnvelope) {
        if (envelope.lane != lane) throw LaneProtocolException(LaneProtocolFailure.PAYLOAD_LANE_MISMATCH)
        BoundedProtobufFraming.write(envelope, output, maxFrameBytes)
        output.flush()
    }

    fun readOrNull(): LiveLinkEnvelope? {
        val envelope = reader.readOrNull() ?: return null
        if (envelope.lane != lane) throw LaneProtocolException(LaneProtocolFailure.PAYLOAD_LANE_MISMATCH)
        return envelope
    }

    override fun close() = socket.close()

    override fun toString(): String = "AuthenticatedTlsLane(lane=$lane)"
}

/** Enforces that realtime/control and camera are physically separate TLS sockets. */
class AuthenticatedTlsLanePair(
    val realtimeControl: AuthenticatedTlsLane,
    val camera: AuthenticatedTlsLane,
) : Closeable {
    init {
        require(realtimeControl.lane == LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL) {
            "realtimeControl has the wrong lane"
        }
        require(camera.lane == LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA) {
            "camera has the wrong lane"
        }
        require(realtimeControl.socket !== camera.socket) { "live lanes must use independent TLS sockets" }
    }

    override fun close() {
        runCatching { camera.close() }
        realtimeControl.close()
    }
}
