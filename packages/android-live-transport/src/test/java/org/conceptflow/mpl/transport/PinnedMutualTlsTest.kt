// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedMutualTlsTest {
    @Test
    fun `exact certificate and public key pins accept only the configured peer`() {
        val first = TestCertificate(byteArrayOf(1, 2, 3), publicKey())
        val second = TestCertificate(byteArrayOf(4, 5, 6), publicKey())

        assertTrue(TlsPeerPin.fromCertificate(first).matches(first))
        assertFalse(TlsPeerPin.fromCertificate(first).matches(second))
        assertTrue(TlsPeerPin.fromPublicKey(first).matches(first))
        assertFalse(TlsPeerPin.fromPublicKey(first).matches(second))

        ExactPinTrustManager(listOf(TlsPeerPin.fromPublicKey(first)))
            .checkServerTrusted(arrayOf(first), "EC")
        assertThrows(CertificateException::class.java) {
            ExactPinTrustManager(listOf(TlsPeerPin.fromPublicKey(first)))
                .checkClientTrusted(arrayOf(second), "EC")
        }
    }

    @Test
    fun `pin text is redacted and certificate validity is enforced`() {
        val expired = TestCertificate(byteArrayOf(7, 8, 9), publicKey(), expired = true)
        val pin = TlsPeerPin.fromCertificate(expired)

        assertFalse(pin.toString().contains("070809"))
        assertThrows(CertificateExpiredException::class.java) {
            ExactPinTrustManager(listOf(pin)).checkServerTrusted(arrayOf(expired), "EC")
        }
    }

    private fun publicKey(): PublicKey = KeyPairGenerator.getInstance("EC").apply {
        initialize(256)
    }.generateKeyPair().public

    @Suppress("DEPRECATION")
    private class TestCertificate(
        private val certificateBytes: ByteArray,
        private val key: PublicKey,
        private val expired: Boolean = false,
    ) : X509Certificate() {
        override fun checkValidity() {
            if (expired) throw CertificateExpiredException()
        }

        override fun checkValidity(date: Date?) = checkValidity()
        override fun getVersion(): Int = 3
        override fun getSerialNumber(): BigInteger = BigInteger.ONE
        override fun getIssuerDN(): Principal = X500Principal("CN=Test Issuer")
        override fun getSubjectDN(): Principal = X500Principal("CN=Test Subject")
        override fun getNotBefore(): Date = Date(0)
        override fun getNotAfter(): Date = Date(Long.MAX_VALUE)
        override fun getTBSCertificate(): ByteArray = certificateBytes.copyOf()
        override fun getSignature(): ByteArray = byteArrayOf(1)
        override fun getSigAlgName(): String = "NONE"
        override fun getSigAlgOID(): String = "0.0"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints(): Int = -1
        override fun getEncoded(): ByteArray = certificateBytes.copyOf()
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun toString(): String = "TestCertificate(<redacted>)"
        override fun getPublicKey(): PublicKey = key
        override fun getCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getExtensionValue(oid: String?): ByteArray? = null
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun hasUnsupportedCriticalExtension(): Boolean = false
    }
}
