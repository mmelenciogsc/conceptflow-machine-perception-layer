// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager

/** Prevents JSSE from selecting any Android-Keystore identity except the configured app alias. */
internal class AliasConstrainedKeyManager(
    private val delegate: X509KeyManager,
    private val alias: String,
) : X509ExtendedKeyManager() {
    private val certificateChain = delegate.getCertificateChain(alias)?.copyOf()
    private val privateKey = delegate.getPrivateKey(alias)
    private val selectionDiagnostic = ThreadLocal<LiveLinkDiagnosticCode?>()

    init {
        if (alias.isBlank() || privateKey == null || certificateChain.isNullOrEmpty()) {
            throw LiveLinkSecurityDiagnosticException(
                LiveLinkDiagnosticCode.TLS_LOCAL_IDENTITY_UNAVAILABLE,
            )
        }
        if (!privateKey.algorithm.equals("EC", ignoreCase = true) ||
            certificateChain.first().publicKey.algorithm?.equals("EC", ignoreCase = true) != true
        ) {
            throw LiveLinkSecurityDiagnosticException(LiveLinkDiagnosticCode.TLS_KEY_TYPE_UNSUPPORTED)
        }
    }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        selectAlias(listOfNotNull(keyType), issuers)?.let { arrayOf(it) }

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = selectAlias(keyType.orEmpty().asIterable(), issuers)

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        selectAlias(listOfNotNull(keyType), issuers)?.let { arrayOf(it) }

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = selectAlias(listOfNotNull(keyType), issuers)

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = chooseClientAlias(keyType, issuers, null)

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = chooseServerAlias(keyType, issuers, null)

    override fun getCertificateChain(requestedAlias: String?): Array<X509Certificate>? =
        if (requestedAlias == alias) {
            certificateChain?.copyOf()
        } else {
            selectionDiagnostic.set(LiveLinkDiagnosticCode.TLS_KEY_ALIAS_NOT_SELECTED)
            null
        }

    override fun getPrivateKey(requestedAlias: String?): PrivateKey? =
        if (requestedAlias == alias) {
            privateKey
        } else {
            selectionDiagnostic.set(LiveLinkDiagnosticCode.TLS_KEY_ALIAS_NOT_SELECTED)
            null
        }

    private fun selectAlias(keyTypes: Iterable<String>, issuers: Array<out Principal>?): String? {
        if (keyTypes.none(::supportsEcKeyType)) {
            selectionDiagnostic.set(LiveLinkDiagnosticCode.TLS_KEY_TYPE_UNSUPPORTED)
            return null
        }
        if (!supportsIssuer(issuers)) {
            selectionDiagnostic.set(LiveLinkDiagnosticCode.TLS_KEY_ALIAS_NOT_SELECTED)
            return null
        }
        selectionDiagnostic.remove()
        return alias
    }

    internal fun beginHandshakeDiagnostic() = selectionDiagnostic.remove()

    internal fun consumeHandshakeDiagnostic(): LiveLinkDiagnosticCode? =
        selectionDiagnostic.get().also { selectionDiagnostic.remove() }

    private fun supportsEcKeyType(keyType: String?): Boolean {
        val normalized = keyType?.uppercase(java.util.Locale.ROOT) ?: return false
        return normalized == "EC" || normalized == "ECDSA" || normalized.startsWith("EC_")
    }

    private fun supportsIssuer(issuers: Array<out Principal>?): Boolean {
        if (issuers.isNullOrEmpty()) return true
        val issuer = certificateChain?.firstOrNull()?.issuerX500Principal ?: return false
        return issuers.any { it == issuer }
    }
}

internal fun aliasConstrainedKeyManagers(
    keyStore: KeyStore,
    alias: String,
    keyPassword: CharArray? = null,
): Array<KeyManager> {
    val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    factory.init(keyStore, keyPassword)
    val constrained = factory.keyManagers.filterIsInstance<X509KeyManager>().map {
        AliasConstrainedKeyManager(it, alias) as KeyManager
    }
    require(constrained.isNotEmpty()) { "no X.509 key manager is available" }
    return constrained.toTypedArray()
}
