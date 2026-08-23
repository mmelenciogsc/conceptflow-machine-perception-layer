// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import android.annotation.SuppressLint
import java.net.Socket
import java.security.KeyManagementException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.conceptflow.mpl.v1.LiveTransportLane

enum class TlsPeerPinType {
    CERTIFICATE_SHA256,
    PUBLIC_KEY_SHA256,
}

/** Runtime-supplied exact pin. Its textual representation is deliberately redacted. */
class TlsPeerPin private constructor(
    val type: TlsPeerPinType,
    digest: ByteArray,
) {
    private val digest = digest.copyOf()

    init {
        require(this.digest.size == SHA256_BYTES) { "a TLS peer pin must be a SHA-256 digest" }
    }

    fun matches(certificate: X509Certificate): Boolean {
        val encoded = when (type) {
            TlsPeerPinType.CERTIFICATE_SHA256 -> certificate.encoded
            TlsPeerPinType.PUBLIC_KEY_SHA256 -> certificate.publicKey.encoded
        }
        return MessageDigest.isEqual(digest, sha256(encoded))
    }

    override fun equals(other: Any?): Boolean =
        other is TlsPeerPin && type == other.type && digest.contentEquals(other.digest)

    override fun hashCode(): Int = 31 * type.hashCode() + digest.contentHashCode()

    override fun toString(): String = "TlsPeerPin(type=$type,digest=<redacted>)"

    companion object {
        private const val SHA256_BYTES = 32

        fun certificateSha256(digest: ByteArray): TlsPeerPin =
            TlsPeerPin(TlsPeerPinType.CERTIFICATE_SHA256, digest)

        fun publicKeySha256(digest: ByteArray): TlsPeerPin =
            TlsPeerPin(TlsPeerPinType.PUBLIC_KEY_SHA256, digest)

        fun fromCertificate(certificate: X509Certificate): TlsPeerPin =
            certificateSha256(sha256(certificate.encoded))

        fun fromPublicKey(certificate: X509Certificate): TlsPeerPin =
            publicKeySha256(sha256(certificate.publicKey.encoded))

        private fun sha256(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

@SuppressLint("CustomX509TrustManager") // The exact runtime pin is the deliberately configured trust root.
class ExactPinTrustManager(
    peerPins: Collection<TlsPeerPin>,
) : X509TrustManager {
    private val peerPins = peerPins.toSet()

    init {
        require(this.peerPins.isNotEmpty()) { "at least one runtime peer pin is required" }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    fun verify(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw PeerCertificateMissingException()
        leaf.checkValidity()
        if (peerPins.none { it.matches(leaf) }) {
            throw PeerPinMismatchException()
        }
    }
}

/**
 * TLS 1.3-only mutual-authentication context. Local identity key managers and
 * exact peer pins are supplied by the caller; this library embeds no identity.
 */
class PinnedMutualTls private constructor(
    private val context: SSLContext,
    private val trustManager: ExactPinTrustManager,
    private val identityManagers: List<AliasConstrainedKeyManager>,
) {
    private fun wrapConnectedClient(rawSocket: Socket, peerHost: String, peerPort: Int): SSLSocket {
        require(rawSocket.isConnected) { "raw socket must already be connected" }
        val socket = context.socketFactory.createSocket(rawSocket, peerHost, peerPort, true) as SSLSocket
        configureClient(socket)
        return socket
    }

    fun createUnboundServerSocket(): SSLServerSocket =
        (context.serverSocketFactory.createServerSocket() as SSLServerSocket).also(::configureServer)

    private fun authenticateClient(socket: SSLSocket) {
        identityManagers.forEach(AliasConstrainedKeyManager::beginHandshakeDiagnostic)
        configureClient(socket)
        try {
            socket.startHandshake()
            verifyNegotiatedPeer(socket)
        } catch (error: Exception) {
            throw withIdentityDiagnostic(error)
        }
    }

    private fun authenticateServer(socket: SSLSocket) {
        identityManagers.forEach(AliasConstrainedKeyManager::beginHandshakeDiagnostic)
        socket.useClientMode = false
        socket.needClientAuth = true
        requireTls13(socket)
        try {
            socket.startHandshake()
            verifyNegotiatedPeer(socket)
        } catch (error: Exception) {
            throw withIdentityDiagnostic(error)
        }
    }

    fun openClientLane(
        rawSocket: Socket,
        peerHost: String,
        peerPort: Int,
        lane: LiveTransportLane,
    ): AuthenticatedTlsLane {
        val socket = wrapConnectedClient(rawSocket, peerHost, peerPort)
        return try {
            authenticateClient(socket)
            AuthenticatedTlsLane(lane, socket)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    fun openServerLane(socket: SSLSocket, lane: LiveTransportLane): AuthenticatedTlsLane {
        return try {
            authenticateServer(socket)
            AuthenticatedTlsLane(lane, socket)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun configureClient(socket: SSLSocket) {
        socket.useClientMode = true
        requireTls13(socket)
    }

    private fun configureServer(socket: SSLServerSocket) {
        socket.useClientMode = false
        socket.needClientAuth = true
        val supported = socket.supportedProtocols.toSet()
        if (TLS_1_3 !in supported) throw KeyManagementException("TLS 1.3 is unavailable")
        socket.enabledProtocols = arrayOf(TLS_1_3)
    }

    private fun requireTls13(socket: SSLSocket) {
        val supported = socket.supportedProtocols.toSet()
        if (TLS_1_3 !in supported) throw KeyManagementException("TLS 1.3 is unavailable")
        socket.enabledProtocols = arrayOf(TLS_1_3)
    }

    private fun verifyNegotiatedPeer(socket: SSLSocket) {
        val certificates = socket.session.peerCertificates
        val chain = certificates.map {
            it as? X509Certificate ?: throw CertificateException("peer certificate is not X.509")
        }.toTypedArray()
        trustManager.verify(chain)
    }

    private fun withIdentityDiagnostic(error: Exception): Exception {
        val diagnostic = identityManagers.firstNotNullOfOrNull {
            it.consumeHandshakeDiagnostic()
        } ?: return error
        return LiveLinkSecurityDiagnosticException(diagnostic, error)
    }

    companion object {
        private const val TLS_1_3 = "TLSv1.3"

        fun create(
            keyManagers: Array<out KeyManager>,
            peerPins: Collection<TlsPeerPin>,
            secureRandom: SecureRandom = SecureRandom(),
        ): PinnedMutualTls {
            require(keyManagers.isNotEmpty()) { "a local TLS identity is required" }
            val trustManager = ExactPinTrustManager(peerPins)
            val context = SSLContext.getInstance("TLS")
            context.init(keyManagers.map { it }.toTypedArray(), arrayOf<TrustManager>(trustManager), secureRandom)
            return PinnedMutualTls(
                context,
                trustManager,
                keyManagers.filterIsInstance<AliasConstrainedKeyManager>(),
            )
        }

        fun keyManagers(
            identityKeyStore: KeyStore,
            keyPassword: CharArray? = null,
        ): Array<KeyManager> {
            val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            factory.init(identityKeyStore, keyPassword)
            return factory.keyManagers
        }
    }
}
