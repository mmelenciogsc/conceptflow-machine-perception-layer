// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.io.InputStream
import java.net.InetAddress
import java.net.Inet6Address
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Properties

enum class LiveLinkEndpointRole {
    POCO_HOST,
    ROKID_CLIENT,
}

/**
 * Validated app-private live-link configuration. The peer certificate is public material, while
 * the local private key remains non-exportable in Android Keystore under [identityAlias].
 *
 * This type deliberately redacts its string representation because an address is operational
 * metadata even though it is not an authentication credential.
 */
class LiveLinkPrivateConfig private constructor(
    val role: LiveLinkEndpointRole,
    val address: InetAddress,
    val realtimePort: Int,
    val cameraPort: Int,
    val identityAlias: String,
    val peerCertificate: X509Certificate,
    val connectTimeoutMs: Int,
    val socketReadTimeoutMs: Int,
    val cameraTicketLifetimeMs: Int,
) {
    override fun toString(): String =
        "LiveLinkPrivateConfig(role=$role,address=<redacted>,ports=<redacted>,identityAlias=<redacted>,peerCertificate=<public-redacted>)"

    companion object {
        private const val MAX_CONFIG_BYTES = 48 * 1024
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65_535
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        private const val DEFAULT_READ_TIMEOUT_MS = 4_000
        private const val DEFAULT_TICKET_LIFETIME_MS = 10_000
        private val REQUIRED_KEYS = setOf(
            "schema_version",
            "address",
            "realtime_port",
            "camera_port",
            "identity_alias",
            "peer_certificate_der_base64",
        )
        private val OPTIONAL_KEYS = setOf(
            "connect_timeout_ms",
            "socket_read_timeout_ms",
            "camera_ticket_lifetime_ms",
        )

        fun parse(input: InputStream, role: LiveLinkEndpointRole): LiveLinkPrivateConfig {
            val bytes = readBounded(input, MAX_CONFIG_BYTES)
            val properties = Properties().apply {
                bytes.inputStream().reader(StandardCharsets.UTF_8).use(::load)
            }
            val keys = properties.stringPropertyNames()
            val missing = REQUIRED_KEYS - keys
            require(missing.isEmpty()) { "live-link configuration is incomplete" }
            require((keys - REQUIRED_KEYS - OPTIONAL_KEYS).isEmpty()) {
                "live-link configuration contains unsupported fields"
            }
            require(properties.required("schema_version") == "1") {
                "unsupported live-link configuration version"
            }

            val address = parsePrivateIpLiteral(properties.required("address"))
            val realtimePort = properties.boundedInt("realtime_port", MIN_PORT, MAX_PORT)
            val cameraPort = properties.boundedInt("camera_port", MIN_PORT, MAX_PORT)
            require(realtimePort != cameraPort) { "the two transport lanes require different ports" }
            val alias = properties.required("identity_alias")
            require(alias.matches(Regex("[A-Za-z0-9._-]{1,96}"))) { "identity alias is invalid" }

            val certificateBytes = try {
                Base64.getDecoder().decode(properties.required("peer_certificate_der_base64"))
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("peer certificate is not valid base64", error)
            }
            val peerCertificate = PeerCertificateProvisioning.decodeDer(certificateBytes)
            return LiveLinkPrivateConfig(
                role = role,
                address = address,
                realtimePort = realtimePort,
                cameraPort = cameraPort,
                identityAlias = alias,
                peerCertificate = peerCertificate,
                connectTimeoutMs = properties.optionalBoundedInt(
                    "connect_timeout_ms",
                    250,
                    30_000,
                    DEFAULT_CONNECT_TIMEOUT_MS,
                ),
                socketReadTimeoutMs = properties.optionalBoundedInt(
                    "socket_read_timeout_ms",
                    500,
                    30_000,
                    DEFAULT_READ_TIMEOUT_MS,
                ),
                cameraTicketLifetimeMs = properties.optionalBoundedInt(
                    "camera_ticket_lifetime_ms",
                    1_000,
                    30_000,
                    DEFAULT_TICKET_LIFETIME_MS,
                ),
            )
        }

        internal fun parsePrivateIpLiteral(value: String): InetAddress {
            if (':' in value) return parsePrivateIpv6(value)
            val pieces = value.split('.')
            require(pieces.size == 4 && pieces.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                    (part.length == 1 || part[0] != '0') && part.toIntOrNull() in 0..255
            }) { "address must be a canonical IPv4 literal" }
            val octets = pieces.map(String::toInt)
            val private = octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 169 && octets[1] == 254)
            require(private) { "address must be RFC1918 or IPv4 link-local" }
            return InetAddress.getByAddress(octets.map(Int::toByte).toByteArray())
        }

        private fun parsePrivateIpv6(value: String): InetAddress {
            require(value.isNotBlank() && value.length <= 64) { "address must be a bounded IP literal" }
            val pieces = value.split('%', limit = 2)
            require(pieces[0].matches(Regex("[0-9A-Fa-f:]+"))) {
                "address must be a numeric IPv6 literal"
            }
            if (pieces.size == 2) {
                require(pieces[1].matches(Regex("[1-9][0-9]{0,9}"))) {
                    "IPv6 scope must be a numeric interface index"
                }
            }
            val parsed = try {
                InetAddress.getByName(value)
            } catch (error: Exception) {
                throw IllegalArgumentException("address is not a valid IPv6 literal", error)
            }
            require(parsed is Inet6Address) { "address must be an IPv6 literal" }
            val first = parsed.address[0].toInt() and 0xff
            val second = parsed.address[1].toInt() and 0xff
            val uniqueLocal = first and 0xfe == 0xfc
            val linkLocal = first == 0xfe && second and 0xc0 == 0x80
            require(uniqueLocal || linkLocal) {
                "address must be IPv6 unique-local or link-local"
            }
            return parsed
        }

        private fun readBounded(input: InputStream, maximumBytes: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maximumBytes) { "live-link configuration exceeds its size bound" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        private fun Properties.required(name: String): String =
            getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("live-link configuration is incomplete")

        private fun Properties.boundedInt(name: String, minimum: Int, maximum: Int): Int {
            val parsed = required(name).toIntOrNull()
                ?: throw IllegalArgumentException("$name must be an integer")
            require(parsed in minimum..maximum) { "$name is outside its configured bound" }
            return parsed
        }

        private fun Properties.optionalBoundedInt(
            name: String,
            minimum: Int,
            maximum: Int,
            fallback: Int,
        ): Int = if (containsKey(name)) boundedInt(name, minimum, maximum) else fallback
    }
}
