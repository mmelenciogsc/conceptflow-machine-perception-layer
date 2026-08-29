// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class PrivateLanDiscoveryRole { ANDROID_ANNOUNCER, ROKID_LISTENER }

internal enum class PrivateRouteKind { WIFI_CLIENT, WIFI_HOTSPOT_DOWNSTREAM }

internal data class PrivateWifiRoute(
    val network: Network?,
    val address: Inet4Address,
    val prefixLength: Int,
    val interfaceName: String,
    val defaultGateway: Inet4Address? = null,
    val kind: PrivateRouteKind = PrivateRouteKind.WIFI_CLIENT,
)

internal enum class PrivateLanFallbackSource { CONFIGURED_ADDRESS, HOTSPOT_GATEWAY }

internal data class PrivateLanFallback(
    val address: InetAddress,
    val source: PrivateLanFallbackSource,
)

/**
 * Low-bandwidth private-LAN rendezvous for firmware pairs whose P2P discovery cannot interoperate.
 *
 * Announcements contain only a fixed protocol marker, version, configured service port, and an
 * opaque process-local sequence. They are not authentication: the existing pinned mutual-TLS
 * handshake remains mandatory before any sensor producer starts. The listener accepts only
 * private/link-local packet sources and, after a bounded discovery interval, falls back to either
 * its provisioned private address or the current private default gateway (the phone in hotspot
 * topology). Mutual TLS still determines whether that endpoint is the authorized Android Node.
 */
class AndroidPrivateLanDiscoveryEndpointResolver(
    context: Context,
    private val role: PrivateLanDiscoveryRole,
    private val configuredFallbackAddress: InetAddress,
    private val realtimePort: Int,
) : LiveLinkEndpointResolver, Closeable {
    private val appContext = context.applicationContext
    private val connectivityManager =
        requireNotNull(appContext.getSystemService(ConnectivityManager::class.java))
    private val wifiManager = requireNotNull(appContext.getSystemService(WifiManager::class.java))
    private val hotspotInterfaces = HotspotInterfaceSource.create(
        appContext,
        enabled = role == PrivateLanDiscoveryRole.ANDROID_ANNOUNCER,
    )
    private val closed = AtomicBoolean(false)
    private val announcementSequence = AtomicLong(0L)
    private val announcerLock = Any()

    private val activeSockets = Collections.synchronizedSet(mutableSetOf<DatagramSocket>())
    @Volatile
    private var announcerThread: Thread? = null

    override fun awaitAddress(timeoutMillis: Long): InetAddress {
        require(timeoutMillis in 1L..MAXIMUM_RESOLUTION_TIMEOUT_MILLIS)
        check(!closed.get()) { "private-LAN resolver is closed" }
        return when (role) {
            PrivateLanDiscoveryRole.ANDROID_ANNOUNCER -> {
                val routes = awaitAnnouncementRoutes(timeoutMillis)
                startAnnouncer(routes)
                IPV4_ANY
            }
            PrivateLanDiscoveryRole.ROKID_LISTENER -> resolveListenerAddress(timeoutMillis)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeActiveSockets()
        announcerThread?.interrupt()
        announcerThread = null
        hotspotInterfaces.close()
    }

    private fun awaitAnnouncementRoutes(timeoutMillis: Long): List<PrivateWifiRoute> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!closed.get()) {
            currentAnnouncementRoutes().takeIf(List<PrivateWifiRoute>::isNotEmpty)?.let { return it }
            if (System.nanoTime() >= deadline) {
                throw SocketTimeoutException("private Wi-Fi or hotspot route was not ready")
            }
            Thread.sleep(ROUTE_POLL_MILLIS)
        }
        throw IllegalStateException("private-LAN resolver is closed")
    }

    private fun currentPrivateWifiRoute(): PrivateWifiRoute? {
        val active = connectivityManager.activeNetwork
        val candidates = buildList {
            if (active != null) add(active)
            connectivityManager.allNetworks.forEach { network -> if (network != active) add(network) }
        }
        for (network in candidates) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) continue
            val linkProperties = connectivityManager.getLinkProperties(network) ?: continue
            val linkAddress = linkProperties.linkAddresses.firstOrNull { link ->
                val address = link.address
                address is Inet4Address && isPrivateOrLinkLocal(address)
            } ?: continue
            val defaultGateway = linkProperties.routes.asSequence()
                .filter { it.isDefaultRoute }
                .mapNotNull { it.gateway as? Inet4Address }
                .firstOrNull(::isPrivateOrLinkLocal)
            return PrivateWifiRoute(
                network = network,
                address = linkAddress.address as Inet4Address,
                prefixLength = linkAddress.prefixLength,
                interfaceName = linkProperties.interfaceName ?: continue,
                defaultGateway = defaultGateway,
            )
        }
        return null
    }

    private fun currentAnnouncementRoutes(): List<PrivateWifiRoute> {
        val hotspotRoutes = hotspotInterfaces.wifiInterfaceNames()
            .asSequence()
            .mapNotNull { name -> runCatching { NetworkInterface.getByName(name) }.getOrNull() }
            .flatMap(::routesForHotspotInterface)
            .distinctBy(::routeKey)
            .toList()
        if (hotspotRoutes.isNotEmpty()) return hotspotRoutes
        return listOfNotNull(currentPrivateWifiRoute())
    }

    private fun routesForHotspotInterface(networkInterface: NetworkInterface): Sequence<PrivateWifiRoute> {
        val eligible = runCatching {
            networkInterface.isUp && !networkInterface.isLoopback
        }.getOrDefault(false)
        if (!eligible) return emptySequence()
        return networkInterface.interfaceAddresses.asSequence().mapNotNull { interfaceAddress ->
            val address = interfaceAddress.address as? Inet4Address ?: return@mapNotNull null
            val prefixLength = interfaceAddress.networkPrefixLength.toInt()
            if (!isPrivateOrLinkLocal(address) || prefixLength !in 1..32) return@mapNotNull null
            PrivateWifiRoute(
                network = null,
                address = address,
                prefixLength = prefixLength,
                interfaceName = networkInterface.name,
                kind = PrivateRouteKind.WIFI_HOTSPOT_DOWNSTREAM,
            )
        }
    }

    private fun startAnnouncer(initialRoutes: List<PrivateWifiRoute>) = synchronized(announcerLock) {
        if (announcerThread?.isAlive == true || closed.get()) return@synchronized
        Log.i(TAG, "state=private_lan_announcement result=started")
        announcerThread = Thread(
            {
                var routes = initialRoutes
                var sockets = emptyList<RouteSocket>()
                try {
                    // awaitAddress returns immediately after this thread starts so the caller can
                    // bind both TLS listeners. Delay the first best-effort beacon to avoid sending
                    // a valid rendezvous address before those listener sockets exist.
                    Thread.sleep(ANNOUNCEMENT_START_DELAY_MILLIS)
                    while (!closed.get()) {
                        val refreshed = currentAnnouncementRoutes()
                        if (refreshed.map(::routeKey) != routes.map(::routeKey) || sockets.isEmpty()) {
                            sockets.forEach { it.socket.close() }
                            activeSockets.removeAll(sockets.map(RouteSocket::socket).toSet())
                            routes = refreshed
                            sockets = routes.mapNotNull(::openAnnouncementSocket)
                            activeSockets.addAll(sockets.map(RouteSocket::socket))
                            if (sockets.isNotEmpty()) {
                                val routeKind = if (routes.any {
                                        it.kind == PrivateRouteKind.WIFI_HOTSPOT_DOWNSTREAM
                                    }
                                ) {
                                    "hotspot"
                                } else {
                                    "wifi"
                                }
                                Log.i(
                                    TAG,
                                    "state=private_lan_announcement route=$routeKind " +
                                        "interfaces=${sockets.size}",
                                )
                            }
                        }
                        val payload = encodeBeacon(realtimePort, announcementSequence.incrementAndGet())
                        var rebuildSockets = false
                        sockets.forEach { routeSocket ->
                            val sent = runCatching {
                                routeSocket.destinations.forEach { destination ->
                                    routeSocket.socket.send(
                                        DatagramPacket(
                                            payload,
                                            payload.size,
                                            destination,
                                            DISCOVERY_PORT,
                                        ),
                                    )
                                }
                            }.isSuccess
                            if (!sent) rebuildSockets = true
                        }
                        if (rebuildSockets) {
                            sockets.forEach { it.socket.close() }
                            activeSockets.removeAll(sockets.map(RouteSocket::socket).toSet())
                            sockets = emptyList()
                            Log.w(TAG, "state=private_lan_announcement result=route_refresh")
                        }
                        Thread.sleep(ANNOUNCEMENT_INTERVAL_MILLIS)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: SocketException) {
                    if (!closed.get()) Log.w(TAG, "state=private_lan_announcement socket=closed")
                } catch (_: Exception) {
                    if (!closed.get()) Log.w(TAG, "state=private_lan_announcement result=failed")
                } finally {
                    sockets.forEach { it.socket.close() }
                    activeSockets.removeAll(sockets.map(RouteSocket::socket).toSet())
                }
            },
            "mpl-lan-announcer",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun openAnnouncementSocket(route: PrivateWifiRoute): RouteSocket? = runCatching {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            route.network?.bindSocket(this)
            bind(InetSocketAddress(route.address, 0))
        }
        RouteSocket(socket, broadcastDestinations(route.address, route.prefixLength))
    }.getOrNull()

    private fun resolveListenerAddress(timeoutMillis: Long): InetAddress {
        val route = currentPrivateWifiRoute()
        val discovered = discoverAnnouncer(
            timeoutMillis.coerceAtMost(DISCOVERY_BEFORE_STATIC_FALLBACK_MILLIS),
            route,
        )
        if (discovered != null) return discovered
        val fallback = selectFallback(
            configuredFallbackAddress,
            route?.address,
            route?.prefixLength,
            route?.defaultGateway,
        )
        Log.i(
            TAG,
            "state=private_lan_rendezvous result=${when (fallback.source) {
                PrivateLanFallbackSource.CONFIGURED_ADDRESS -> "static_fallback"
                PrivateLanFallbackSource.HOTSPOT_GATEWAY -> "gateway_fallback"
            }}",
        )
        return fallback.address
    }

    private fun discoverAnnouncer(timeoutMillis: Long, route: PrivateWifiRoute?): InetAddress? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val receiveBuffer = ByteArray(MAXIMUM_BEACON_BYTES)
        val multicastLock = runCatching {
            wifiManager.createMulticastLock("conceptflow-mpl-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        return try {
            MulticastSocket(null).use { socket ->
                activeSockets.add(socket)
                socket.reuseAddress = true
                route?.network?.bindSocket(socket)
                socket.bind(InetSocketAddress(IPV4_ANY, DISCOVERY_PORT))
                route?.interfaceName?.let(NetworkInterface::getByName)?.let { networkInterface ->
                    socket.joinGroup(InetSocketAddress(IPV4_MULTICAST_GROUP, DISCOVERY_PORT), networkInterface)
                }
                while (!closed.get()) {
                    val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                    if (remainingMillis <= 0L) return@use null
                    socket.soTimeout = remainingMillis.coerceAtMost(RECEIVE_POLL_MILLIS).toInt()
                    val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (isPrivateOrLinkLocal(packet.address) &&
                        decodeBeacon(packet.data, packet.offset, packet.length, realtimePort)
                    ) {
                        Log.i(TAG, "state=private_lan_rendezvous result=discovered")
                        return@use packet.address
                    }
                }
                null
            }
        } finally {
            activeSockets.removeIf { it.isClosed }
            if (multicastLock?.isHeld == true) multicastLock.release()
        }
    }

    private fun closeActiveSockets() {
        val snapshot = synchronized(activeSockets) { activeSockets.toList() }
        snapshot.forEach(DatagramSocket::close)
        activeSockets.clear()
    }

    private data class RouteSocket(
        val socket: DatagramSocket,
        val destinations: List<InetAddress>,
    )

    internal companion object {
        const val DISCOVERY_PORT = 39_430
        const val DISCOVERY_BEFORE_STATIC_FALLBACK_MILLIS = 8_000L
        const val ANNOUNCEMENT_START_DELAY_MILLIS = 250L
        const val ANNOUNCEMENT_INTERVAL_MILLIS = 1_000L
        const val RECEIVE_POLL_MILLIS = 1_000L
        const val ROUTE_POLL_MILLIS = 250L
        const val MAXIMUM_RESOLUTION_TIMEOUT_MILLIS = 180_000L
        const val MAXIMUM_BEACON_BYTES = 64
        private const val PROTOCOL_VERSION: Byte = 1
        private const val BEACON_BYTES = 19
        private const val TAG = "ConceptFlowLanDiscovery"
        private val MAGIC = byteArrayOf(0x43, 0x46, 0x4d, 0x50, 0x4c, 0x4c, 0x41, 0x4e)
        private val IPV4_ANY = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))
        private val IPV4_LIMITED_BROADCAST = InetAddress.getByAddress(
            byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()),
        )
        private val IPV4_MULTICAST_GROUP = InetAddress.getByAddress(
            byteArrayOf(239.toByte(), 255.toByte(), 77, 77),
        )

        fun encodeBeacon(realtimePort: Int, sequence: Long): ByteArray {
            require(realtimePort in 1..65_535)
            require(sequence > 0L)
            return ByteBuffer.allocate(BEACON_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC)
                .put(PROTOCOL_VERSION)
                .putShort(realtimePort.toShort())
                .putLong(sequence)
                .array()
        }

        fun decodeBeacon(
            bytes: ByteArray,
            offset: Int,
            length: Int,
            expectedRealtimePort: Int,
        ): Boolean {
            if (offset < 0 || length != BEACON_BYTES || offset + length > bytes.size) return false
            val input = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.BIG_ENDIAN)
            for (expected in MAGIC) if (input.get() != expected) return false
            if (input.get() != PROTOCOL_VERSION) return false
            if (input.short.toInt().and(0xffff) != expectedRealtimePort) return false
            return input.long > 0L
        }

        fun broadcastDestinations(address: Inet4Address, prefixLength: Int): List<InetAddress> {
            require(prefixLength in 1..32)
            val octets = address.address
            val numeric = octets.fold(0) { value, octet -> value shl 8 or octet.toInt().and(0xff) }
            val mask = if (prefixLength == 32) -1 else -1 shl (32 - prefixLength)
            val directed = numeric or mask.inv()
            val directedBytes = byteArrayOf(
                (directed ushr 24).toByte(),
                (directed ushr 16).toByte(),
                (directed ushr 8).toByte(),
                directed.toByte(),
            )
            val directedAddress = InetAddress.getByAddress(directedBytes)
            return listOf(IPV4_MULTICAST_GROUP, IPV4_LIMITED_BROADCAST, directedAddress).distinct()
        }

        fun selectFallback(
            configuredAddress: InetAddress,
            clientAddress: Inet4Address?,
            clientPrefixLength: Int?,
            defaultGateway: Inet4Address?,
        ): PrivateLanFallback {
            if (clientAddress != null && clientPrefixLength != null && clientPrefixLength in 1..32 &&
                configuredAddress is Inet4Address &&
                sameSubnet(configuredAddress, clientAddress, clientPrefixLength)
            ) {
                return PrivateLanFallback(
                    configuredAddress,
                    PrivateLanFallbackSource.CONFIGURED_ADDRESS,
                )
            }
            if (defaultGateway != null && isPrivateOrLinkLocal(defaultGateway)) {
                return PrivateLanFallback(defaultGateway, PrivateLanFallbackSource.HOTSPOT_GATEWAY)
            }
            return PrivateLanFallback(configuredAddress, PrivateLanFallbackSource.CONFIGURED_ADDRESS)
        }

        fun sameSubnet(first: Inet4Address, second: Inet4Address, prefixLength: Int): Boolean {
            require(prefixLength in 1..32)
            val firstValue = ipv4Value(first)
            val secondValue = ipv4Value(second)
            val mask = if (prefixLength == 32) -1 else -1 shl (32 - prefixLength)
            return firstValue and mask == secondValue and mask
        }

        private fun routeKey(route: PrivateWifiRoute): String =
            "${route.kind}:${route.interfaceName}:${route.address.hostAddress}/${route.prefixLength}"

        private fun ipv4Value(address: Inet4Address): Int = address.address.fold(0) { value, octet ->
            value shl 8 or octet.toInt().and(0xff)
        }

        fun isPrivateOrLinkLocal(address: InetAddress): Boolean =
            address is Inet4Address && (address.isSiteLocalAddress || address.isLinkLocalAddress)
    }
}
