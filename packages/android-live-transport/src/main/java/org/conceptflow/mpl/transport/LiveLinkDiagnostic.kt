// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException

/** Bounded diagnostics safe for aggregate logs: values contain no peer or session material. */
enum class LiveLinkDiagnosticCode {
    LEASE_EXPIRED,
    PROTOCOL_REJECTED,
    PROTOCOL_BINDING_MISMATCH,
    PROTOCOL_UNSUPPORTED_LANE,
    PROTOCOL_PAYLOAD_LANE_MISMATCH,
    PROTOCOL_MALFORMED_CONTROL,
    PROTOCOL_CAMERA_LANE_UNAUTHENTICATED,
    PROTOCOL_SEQUENCE_REPLAY_OR_GAP,
    PROTOCOL_SEQUENCE_EXHAUSTED,
    FRAMING_INVALID_LENGTH,
    FRAMING_MALFORMED_PROTOBUF,
    FRAMING_TRUNCATED_PREFIX,
    FRAMING_TRUNCATED_RECORD,
    SENSOR_ENVELOPE_REJECTED,
    SENSOR_LANE_REJECTED,
    SENSOR_SEND_TIMESTAMP_REJECTED,
    SENSOR_CAMERA_TIMESTAMP_REJECTED,
    SENSOR_IMU_BATCH_TIMESTAMP_REJECTED,
    SENSOR_IMU_SAMPLE_TIMESTAMP_REJECTED,
    SENSOR_TOUCH_OVERFLOW,
    CLOCK_SYNC_REJECTED,
    CAMERA_TICKET_REJECTED,
    TLS_LOCAL_IDENTITY_UNAVAILABLE,
    TLS_KEY_TYPE_UNSUPPORTED,
    TLS_KEY_ALIAS_NOT_SELECTED,
    TLS_PEER_CERTIFICATE_MISSING,
    TLS_PEER_CERTIFICATE_EXPIRED,
    TLS_PEER_CERTIFICATE_NOT_YET_VALID,
    TLS_PEER_PIN_MISMATCH,
    TLS_PEER_UNVERIFIED,
    TLS_PROTOCOL_REJECTED,
    TLS_CERTIFICATE_REJECTED,
    TLS_HANDSHAKE_REJECTED,
    SOCKET_TIMEOUT,
    NETWORK_IO,
    INTERNAL_FAILURE,
}

internal class LiveLinkSecurityDiagnosticException(
    val diagnosticCode: LiveLinkDiagnosticCode,
    cause: Throwable? = null,
) : java.security.GeneralSecurityException(diagnosticCode.name, cause)

/** Carries only a fixed code; payload values and exception text never enter observable diagnostics. */
internal class LiveSensorValidationException(
    val diagnosticCode: LiveLinkDiagnosticCode,
) : IllegalArgumentException(diagnosticCode.name)

internal class PeerCertificateMissingException : CertificateException("peer certificate is missing")
internal class PeerPinMismatchException : CertificateException("peer certificate pin mismatch")

internal fun classifyDiagnostic(error: Throwable): LiveLinkDiagnosticCode {
    val chain = generateSequence(error) { it.cause }.take(MAXIMUM_CAUSE_DEPTH).toList()
    chain.filterIsInstance<LiveLinkSecurityDiagnosticException>().firstOrNull()?.let {
        return it.diagnosticCode
    }
    chain.filterIsInstance<LiveSensorValidationException>().firstOrNull()?.let {
        return it.diagnosticCode
    }
    chain.filterIsInstance<LaneProtocolException>().firstOrNull()?.let {
        return it.failure.toDiagnosticCode()
    }
    chain.filterIsInstance<FramingException>().firstOrNull()?.let {
        return it.failure.toDiagnosticCode()
    }
    return when {
        chain.any { it is LeaseExpiredException } -> LiveLinkDiagnosticCode.LEASE_EXPIRED
        chain.any { it is CameraTicketException } -> LiveLinkDiagnosticCode.CAMERA_TICKET_REJECTED
        chain.any { it is ClockSyncException } -> LiveLinkDiagnosticCode.CLOCK_SYNC_REJECTED
        chain.any { it is PeerCertificateMissingException } -> LiveLinkDiagnosticCode.TLS_PEER_CERTIFICATE_MISSING
        chain.any { it is PeerPinMismatchException } -> LiveLinkDiagnosticCode.TLS_PEER_PIN_MISMATCH
        chain.any { it is CertificateExpiredException } -> LiveLinkDiagnosticCode.TLS_PEER_CERTIFICATE_EXPIRED
        chain.any { it is CertificateNotYetValidException } -> {
            LiveLinkDiagnosticCode.TLS_PEER_CERTIFICATE_NOT_YET_VALID
        }
        chain.any { it is SSLPeerUnverifiedException } -> LiveLinkDiagnosticCode.TLS_PEER_UNVERIFIED
        chain.any { it is SSLProtocolException } -> LiveLinkDiagnosticCode.TLS_PROTOCOL_REJECTED
        chain.any { it is InvalidKeyException || it is UnrecoverableKeyException || it is KeyStoreException } -> {
            LiveLinkDiagnosticCode.TLS_LOCAL_IDENTITY_UNAVAILABLE
        }
        chain.any { it is CertificateException } -> LiveLinkDiagnosticCode.TLS_CERTIFICATE_REJECTED
        chain.any { it is SSLHandshakeException } -> LiveLinkDiagnosticCode.TLS_HANDSHAKE_REJECTED
        chain.any { it is SSLException } -> LiveLinkDiagnosticCode.TLS_HANDSHAKE_REJECTED
        chain.any { it is java.net.SocketTimeoutException } -> LiveLinkDiagnosticCode.SOCKET_TIMEOUT
        chain.any { it is java.io.IOException } -> LiveLinkDiagnosticCode.NETWORK_IO
        chain.any { it is IllegalArgumentException } -> LiveLinkDiagnosticCode.PROTOCOL_REJECTED
        else -> LiveLinkDiagnosticCode.INTERNAL_FAILURE
    }
}

private fun LaneProtocolFailure.toDiagnosticCode(): LiveLinkDiagnosticCode = when (this) {
    LaneProtocolFailure.BINDING_MISMATCH -> LiveLinkDiagnosticCode.PROTOCOL_BINDING_MISMATCH
    LaneProtocolFailure.UNSUPPORTED_LANE -> LiveLinkDiagnosticCode.PROTOCOL_UNSUPPORTED_LANE
    LaneProtocolFailure.PAYLOAD_LANE_MISMATCH -> LiveLinkDiagnosticCode.PROTOCOL_PAYLOAD_LANE_MISMATCH
    LaneProtocolFailure.MALFORMED_CONTROL -> LiveLinkDiagnosticCode.PROTOCOL_MALFORMED_CONTROL
    LaneProtocolFailure.CAMERA_LANE_UNAUTHENTICATED ->
        LiveLinkDiagnosticCode.PROTOCOL_CAMERA_LANE_UNAUTHENTICATED
    LaneProtocolFailure.SEQUENCE_REPLAY_OR_GAP ->
        LiveLinkDiagnosticCode.PROTOCOL_SEQUENCE_REPLAY_OR_GAP
    LaneProtocolFailure.SEQUENCE_EXHAUSTED -> LiveLinkDiagnosticCode.PROTOCOL_SEQUENCE_EXHAUSTED
}

private fun FramingFailure.toDiagnosticCode(): LiveLinkDiagnosticCode = when (this) {
    FramingFailure.INVALID_LENGTH -> LiveLinkDiagnosticCode.FRAMING_INVALID_LENGTH
    FramingFailure.MALFORMED_PROTOBUF -> LiveLinkDiagnosticCode.FRAMING_MALFORMED_PROTOBUF
    FramingFailure.TRUNCATED_PREFIX -> LiveLinkDiagnosticCode.FRAMING_TRUNCATED_PREFIX
    FramingFailure.TRUNCATED_RECORD -> LiveLinkDiagnosticCode.FRAMING_TRUNCATED_RECORD
}

private const val MAXIMUM_CAUSE_DEPTH = 8
const val LIVE_LINK_DIAGNOSTIC_SCHEMA_VERSION = 4
