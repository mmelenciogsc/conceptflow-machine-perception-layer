// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Date
import javax.net.ssl.KeyManager
import javax.security.auth.x500.X500Principal

data class AndroidTlsIdentity(
    val alias: String,
    val publicCertificateDer: ByteArray,
    val keyManagers: Array<KeyManager>,
) {
    override fun toString(): String =
        "AndroidTlsIdentity(alias=<redacted>,publicCertificate=<public-redacted>,keyManagers=${keyManagers.size})"
}

/** Creates a non-exportable P-256 application identity in Android Keystore. */
class AndroidKeystoreTlsIdentity(
    private val clock: Clock = Clock.systemUTC(),
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun ensure(alias: String): AndroidTlsIdentity {
        require(alias.matches(Regex("[A-Za-z0-9._-]{1,96}"))) { "identity alias is invalid" }
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (!keyStore.containsAlias(alias)) {
            generate(alias)
        } else if (!hasRequiredTls13Authorization(keyStore, alias)) {
            // Migration is intentionally destructive only for this app-owned alias. A new public
            // certificate changes the exact peer pin, so provisioning must export/pair it again.
            keyStore.deleteEntry(alias)
            generate(alias)
        }
        val certificate = keyStore.getCertificate(alias) as? X509Certificate
            ?: throw IllegalStateException("Android Keystore identity has no X.509 certificate")
        certificate.checkValidity(Date.from(clock.instant()))
        PeerCertificateProvisioning.exactPublicKeyPin(certificate)
        val privateKey = keyStore.getKey(alias, null) as? java.security.PrivateKey
            ?: throw LiveLinkSecurityDiagnosticException(
                LiveLinkDiagnosticCode.TLS_LOCAL_IDENTITY_UNAVAILABLE,
            )
        verifySigningIdentity(privateKey, certificate, secureRandom)
        return AndroidTlsIdentity(
            alias = alias,
            publicCertificateDer = certificate.encoded.copyOf(),
            keyManagers = aliasConstrainedKeyManagers(keyStore, alias),
        )
    }

    private fun generate(alias: String) {
        val now = clock.instant()
        val serialBytes = ByteArray(16).also(secureRandom::nextBytes)
        serialBytes[0] = (serialBytes[0].toInt() and 0x7f).toByte()
        val serial = BigInteger(1, serialBytes).max(BigInteger.ONE)
        val specification = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec(P256_CURVE))
            // Android Conscrypt's TLS 1.3 ECDSA CertificateVerify path can pass a pre-hashed
            // transcript through NONEwithECDSA. SHA-256 remains authorized for certificate and
            // direct challenge signing; both authorizations are required for API 32 through 36.
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
            )
            .setCertificateSubject(X500Principal(CERTIFICATE_SUBJECT))
            .setCertificateSerialNumber(serial)
            .setCertificateNotBefore(Date.from(now.minus(CLOCK_SKEW_ALLOWANCE)))
            .setCertificateNotAfter(Date.from(now.plus(CERTIFICATE_LIFETIME)))
            .setUserAuthenticationRequired(false)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE).run {
            initialize(specification, secureRandom)
            generateKeyPair()
        }
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val P256_CURVE = "secp256r1"
        private const val CERTIFICATE_SUBJECT = "CN=CONCEPTFlow Machine Perception Local Node"
        private val CLOCK_SKEW_ALLOWANCE: Duration = Duration.ofMinutes(10)
        private val CERTIFICATE_LIFETIME: Duration = Duration.ofDays(3650)
        internal val REQUIRED_TLS_DIGESTS = setOf(
            KeyProperties.DIGEST_NONE,
            KeyProperties.DIGEST_SHA256,
        )

        internal fun isTls13EcAuthorizationCompatible(
            keyAlgorithm: String,
            purposes: Int,
            digests: Set<String>,
        ): Boolean = keyAlgorithm.equals(KeyProperties.KEY_ALGORITHM_EC, ignoreCase = true) &&
            purposes and KeyProperties.PURPOSE_SIGN != 0 &&
            purposes and KeyProperties.PURPOSE_VERIFY != 0 &&
            digests.containsAll(REQUIRED_TLS_DIGESTS)

        private fun hasRequiredTls13Authorization(keyStore: KeyStore, alias: String): Boolean {
            return try {
                val privateKey = keyStore.getKey(alias, null) as? PrivateKey ?: return false
                val keyInfo = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEY_STORE)
                    .getKeySpec(privateKey, KeyInfo::class.java)
                isTls13EcAuthorizationCompatible(
                    privateKey.algorithm,
                    keyInfo.purposes,
                    keyInfo.digests.toSet(),
                )
            } catch (_: Exception) {
                false
            }
        }

        internal fun verifySigningIdentity(
            privateKey: java.security.PrivateKey,
            certificate: X509Certificate,
            secureRandom: SecureRandom,
        ) {
            try {
                val challenge = ByteArray(32).also(secureRandom::nextBytes)
                verifySignatureRoundTrip("SHA256withECDSA", challenge, privateKey, certificate, secureRandom)
                // This is the exact provider operation used by Android Conscrypt for TLS 1.3.
                verifySignatureRoundTrip("NONEwithECDSA", challenge, privateKey, certificate, secureRandom)
            } catch (error: LiveLinkSecurityDiagnosticException) {
                throw error
            } catch (error: Exception) {
                throw LiveLinkSecurityDiagnosticException(
                    LiveLinkDiagnosticCode.TLS_LOCAL_IDENTITY_UNAVAILABLE,
                    error,
                )
            }
        }

        private fun verifySignatureRoundTrip(
            algorithm: String,
            input: ByteArray,
            privateKey: PrivateKey,
            certificate: X509Certificate,
            secureRandom: SecureRandom,
        ) {
            val signatureBytes = Signature.getInstance(algorithm).run {
                initSign(privateKey, secureRandom)
                update(input)
                sign()
            }
            val verified = Signature.getInstance(algorithm).run {
                initVerify(certificate.publicKey)
                update(input)
                verify(signatureBytes)
            }
            if (!verified) {
                throw LiveLinkSecurityDiagnosticException(
                    LiveLinkDiagnosticCode.TLS_LOCAL_IDENTITY_UNAVAILABLE,
                )
            }
        }
    }
}
