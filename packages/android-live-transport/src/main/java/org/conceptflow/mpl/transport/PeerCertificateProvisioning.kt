// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.io.ByteArrayInputStream
import java.security.interfaces.ECPublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/** Public-only certificate exchange helpers. Private-key encodings are always rejected. */
object PeerCertificateProvisioning {
    private const val MAX_CERTIFICATE_BYTES = 16 * 1024
    private const val PEM_BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val PEM_END = "-----END CERTIFICATE-----"

    fun decodeDer(der: ByteArray): X509Certificate {
        require(der.isNotEmpty() && der.size <= MAX_CERTIFICATE_BYTES) {
            "peer certificate is outside its size bound"
        }
        val certificate = try {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        } catch (error: Exception) {
            throw IllegalArgumentException("peer certificate is not a valid X.509 certificate", error)
        }
        require(certificate.encoded.contentEquals(der)) {
            "peer certificate input must contain exactly one canonical DER certificate"
        }
        validatePublicCertificate(certificate)
        return certificate
    }

    fun decodePem(pem: String): X509Certificate {
        require(!pem.contains("PRIVATE KEY", ignoreCase = true)) {
            "private key material is forbidden in peer provisioning"
        }
        val trimmed = pem.trim()
        require(trimmed.startsWith(PEM_BEGIN) && trimmed.endsWith(PEM_END)) {
            "peer certificate PEM framing is invalid"
        }
        val payload = trimmed.removePrefix(PEM_BEGIN).removeSuffix(PEM_END)
            .filterNot(Char::isWhitespace)
        require(payload.isNotEmpty()) { "peer certificate PEM is empty" }
        val der = try {
            Base64.getDecoder().decode(payload)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("peer certificate PEM payload is invalid", error)
        }
        return decodeDer(der)
    }

    fun encodePem(certificate: X509Certificate): String {
        validatePublicCertificate(certificate)
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded)
        return "$PEM_BEGIN\n$body\n$PEM_END\n"
    }

    fun exactPublicKeyPin(certificate: X509Certificate): TlsPeerPin {
        validatePublicCertificate(certificate)
        return TlsPeerPin.fromPublicKey(certificate)
    }

    private fun validatePublicCertificate(certificate: X509Certificate) {
        certificate.checkValidity()
        val publicKey = certificate.publicKey as? ECPublicKey
            ?: throw IllegalArgumentException("peer identity must use an EC public key")
        require(publicKey.params.curve.field.fieldSize == 256) {
            "peer identity must use a 256-bit P-256-compatible key"
        }
        require(certificate.sigAlgName.contains("SHA256", ignoreCase = true)) {
            "peer certificate must use a SHA-256 signature"
        }
    }
}
