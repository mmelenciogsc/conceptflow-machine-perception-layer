// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.math.BigInteger
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date
import android.security.keystore.KeyProperties
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AliasConstrainedKeyManagerTest {
    @Test
    fun `TLS selection cannot escape the configured identity alias`() {
        val first = keyPair()
        val second = keyPair()
        val delegate = FakeKeyManager(mapOf("selected" to first, "other" to second))
        val constrained = AliasConstrainedKeyManager(delegate, "selected")

        assertArrayEquals(arrayOf("selected"), constrained.getClientAliases("EC", null))
        assertArrayEquals(arrayOf("selected"), constrained.getServerAliases("EC", null))
        assertEquals("selected", constrained.chooseClientAlias(arrayOf("EC"), null, null))
        assertEquals("selected", constrained.chooseServerAlias("EC", null, null))
        assertEquals("selected", constrained.chooseClientAlias(arrayOf("EC_EC"), null, null))
        assertEquals("selected", constrained.chooseEngineServerAlias("ECDSA", null, null))
        assertEquals(first.private, constrained.getPrivateKey("selected"))
        assertNull(constrained.getPrivateKey("other"))
        assertNull(constrained.getCertificateChain("other"))
        assertNull(constrained.chooseClientAlias(arrayOf("RSA"), null, null))
        assertEquals(
            LiveLinkDiagnosticCode.TLS_KEY_TYPE_UNSUPPORTED,
            constrained.consumeHandshakeDiagnostic(),
        )
    }

    @Test
    fun `configured EC key proves signing compatibility before TLS use`() {
        val pair = keyPair()
        AndroidKeystoreTlsIdentity.verifySigningIdentity(
            pair.private,
            FakeCertificate(pair.public),
            java.security.SecureRandom(),
        )
    }

    @Test
    fun `TLS 1_3 Android EC authorization requires raw and SHA256 digest modes`() {
        val purposes = KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        assertTrue(
            AndroidKeystoreTlsIdentity.isTls13EcAuthorizationCompatible(
                "EC",
                purposes,
                setOf(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256),
            ),
        )
        assertFalse(
            AndroidKeystoreTlsIdentity.isTls13EcAuthorizationCompatible(
                "EC",
                purposes,
                setOf(KeyProperties.DIGEST_SHA256),
            ),
        )
        assertFalse(
            AndroidKeystoreTlsIdentity.isTls13EcAuthorizationCompatible(
                "EC",
                KeyProperties.PURPOSE_VERIFY,
                setOf(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256),
            ),
        )
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private class FakeKeyManager(private val pairs: Map<String, KeyPair>) : X509KeyManager {
        private val certificates: Map<String, Array<X509Certificate>> = pairs.mapValues { (_, pair) ->
            arrayOf<X509Certificate>(FakeCertificate(pair.public))
        }

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            if (keyType == "EC") pairs.keys.toTypedArray() else null

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String? = getClientAliases(keyType?.firstOrNull(), issuers)?.firstOrNull()

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            getClientAliases(keyType, issuers)

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String? = getServerAliases(keyType, issuers)?.firstOrNull()

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = certificates[alias]
        override fun getPrivateKey(alias: String?): PrivateKey? = pairs[alias]?.private
    }

    @Suppress("DEPRECATION")
    private class FakeCertificate(private val key: PublicKey) : X509Certificate() {
        override fun checkValidity() = Unit
        override fun checkValidity(date: Date?) = Unit
        override fun getVersion(): Int = 3
        override fun getSerialNumber(): BigInteger = BigInteger.ONE
        override fun getIssuerDN(): Principal = X500Principal("CN=Test")
        override fun getSubjectDN(): Principal = X500Principal("CN=Test")
        override fun getNotBefore(): Date = Date(0)
        override fun getNotAfter(): Date = Date(Long.MAX_VALUE)
        override fun getTBSCertificate(): ByteArray = byteArrayOf(1)
        override fun getSignature(): ByteArray = byteArrayOf(1)
        override fun getSigAlgName(): String = "SHA256withECDSA"
        override fun getSigAlgOID(): String = "1.2.840.10045.4.3.2"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints(): Int = -1
        override fun getEncoded(): ByteArray = byteArrayOf(1)
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun toString(): String = "FakeCertificate(<redacted>)"
        override fun getPublicKey(): PublicKey = key
        override fun getCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getExtensionValue(oid: String?): ByteArray? = null
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun hasUnsupportedCriticalExtension(): Boolean = false
    }
}
