// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveLinkDiagnosticTest {
    @Test
    fun `typed identity selection diagnostic wins over generic handshake wrapper`() {
        val error = SSLHandshakeException("synthetic").apply {
            initCause(
                LiveLinkSecurityDiagnosticException(
                    LiveLinkDiagnosticCode.TLS_KEY_ALIAS_NOT_SELECTED,
                ),
            )
        }

        assertEquals(
            LiveLinkDiagnosticCode.TLS_KEY_ALIAS_NOT_SELECTED,
            classifyDiagnostic(error),
        )
    }

    @Test
    fun `certificate and pin failures map to bounded categorical codes`() {
        assertEquals(
            LiveLinkDiagnosticCode.TLS_PEER_CERTIFICATE_EXPIRED,
            classifyDiagnostic(SSLHandshakeException("synthetic").apply {
                initCause(CertificateExpiredException())
            }),
        )
        assertEquals(
            LiveLinkDiagnosticCode.TLS_PEER_PIN_MISMATCH,
            classifyDiagnostic(PeerPinMismatchException()),
        )
    }

    @Test
    fun `diagnostic enum cannot contain runtime details`() {
        LiveLinkDiagnosticCode.entries.forEach { code ->
            assertFalse(code.name.contains('/'))
            assertFalse(code.name.contains(':'))
            assertFalse(code.name.contains('.'))
            assertFalse(code.name.any(Char::isLowerCase))
        }
    }

    @Test
    fun `generic Android SSL failure remains a TLS category rather than internal`() {
        assertEquals(
            LiveLinkDiagnosticCode.TLS_HANDSHAKE_REJECTED,
            classifyDiagnostic(SSLException("synthetic")),
        )
    }

    @Test
    fun `generic argument rejection is protocol failure rather than opaque internal failure`() {
        assertEquals(
            LiveLinkDiagnosticCode.PROTOCOL_REJECTED,
            classifyDiagnostic(IllegalArgumentException("sensitive runtime detail")),
        )
    }

    @Test
    fun `every lane protocol failure retains its exact privacy-safe category`() {
        val expected = mapOf(
            LaneProtocolFailure.BINDING_MISMATCH to LiveLinkDiagnosticCode.PROTOCOL_BINDING_MISMATCH,
            LaneProtocolFailure.UNSUPPORTED_LANE to LiveLinkDiagnosticCode.PROTOCOL_UNSUPPORTED_LANE,
            LaneProtocolFailure.PAYLOAD_LANE_MISMATCH to
                LiveLinkDiagnosticCode.PROTOCOL_PAYLOAD_LANE_MISMATCH,
            LaneProtocolFailure.MALFORMED_CONTROL to LiveLinkDiagnosticCode.PROTOCOL_MALFORMED_CONTROL,
            LaneProtocolFailure.CAMERA_LANE_UNAUTHENTICATED to
                LiveLinkDiagnosticCode.PROTOCOL_CAMERA_LANE_UNAUTHENTICATED,
            LaneProtocolFailure.SEQUENCE_REPLAY_OR_GAP to
                LiveLinkDiagnosticCode.PROTOCOL_SEQUENCE_REPLAY_OR_GAP,
            LaneProtocolFailure.SEQUENCE_EXHAUSTED to LiveLinkDiagnosticCode.PROTOCOL_SEQUENCE_EXHAUSTED,
        )

        assertEquals(LaneProtocolFailure.entries.toSet(), expected.keys)
        expected.forEach { (failure, diagnostic) ->
            assertEquals(diagnostic, classifyDiagnostic(LaneProtocolException(failure)))
            assertEquals(
                LiveLinkDisconnectReason.PROTOCOL,
                classifyDisconnect(LaneProtocolException(failure)),
            )
        }
    }

    @Test
    fun `every framing failure retains its exact privacy-safe diagnostic category`() {
        val expected = mapOf(
            FramingFailure.INVALID_LENGTH to LiveLinkDiagnosticCode.FRAMING_INVALID_LENGTH,
            FramingFailure.MALFORMED_PROTOBUF to LiveLinkDiagnosticCode.FRAMING_MALFORMED_PROTOBUF,
            FramingFailure.TRUNCATED_PREFIX to LiveLinkDiagnosticCode.FRAMING_TRUNCATED_PREFIX,
            FramingFailure.TRUNCATED_RECORD to LiveLinkDiagnosticCode.FRAMING_TRUNCATED_RECORD,
        )

        assertEquals(FramingFailure.entries.toSet(), expected.keys)
        expected.forEach { (failure, diagnostic) ->
            val error = FramingException(failure)
            assertEquals(diagnostic, classifyDiagnostic(error))
            assertEquals(
                if (failure == FramingFailure.TRUNCATED_PREFIX || failure == FramingFailure.TRUNCATED_RECORD) {
                    LiveLinkDisconnectReason.NETWORK
                } else {
                    LiveLinkDisconnectReason.PROTOCOL
                },
                classifyDisconnect(error),
            )
        }
    }
}
