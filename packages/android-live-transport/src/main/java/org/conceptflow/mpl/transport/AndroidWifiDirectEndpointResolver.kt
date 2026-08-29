// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class WifiDirectNodeRole { ANDROID_GROUP_OWNER, ROKID_CLIENT }

internal enum class WifiDirectInitializationAction { PREPARE_NOW, DEFER_TO_RETRY }

internal enum class WifiDirectPeerFallbackPhase {
    IDLE,
    STARTING_PEER_DISCOVERY,
    DISCOVERING_PEERS,
    REQUESTING_PEERS,
}

internal enum class WifiDirectOwnerVisibilityStrategy { PLATFORM_LISTEN, PEER_DISCOVERY }

enum class WifiDirectPhase {
    IDLE,
    STARTING,
    WAITING_FOR_RADIO,
    DISCOVERING,
    CONNECTING,
    GROUP_READY,
    CLOSED,
}

data class WifiDirectStatus(
    val phase: WifiDirectPhase,
    val retries: Long,
    val lastFailureReason: Int?,
    val lastFailureOperation: String? = null,
    val radioAvailable: Boolean = true,
)

/**
 * Owns Android Wi-Fi Direct group management while the existing pinned mutual-TLS protocol owns
 * peer authentication. DNS-SD discovery is only rendezvous; a spoofed advertisement cannot pass
 * the certificate pin on either data lane.
 */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission") // Every P2P operation passes runAuthorizedP2p's runtime guard.
class AndroidWifiDirectEndpointResolver(
    context: Context,
    private val role: WifiDirectNodeRole,
) : LiveLinkEndpointResolver, Closeable {
    private val appContext = context.applicationContext
    private val manager = requireNotNull(appContext.getSystemService(WifiP2pManager::class.java)) {
        "Wi-Fi Direct service is unavailable"
    }
    private val wifiManager = requireNotNull(appContext.getSystemService(WifiManager::class.java)) {
        "Wi-Fi service is unavailable"
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = ReentrantLock()
    private val stateChanged = stateLock.newCondition()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private lateinit var channel: WifiP2pManager.Channel
    private var channelReady = false
    private val channelEpoch = AtomicLong(0L)
    private var receiverRegistered = false
    private var endpoint: InetAddress? = null
    private var phase = WifiDirectPhase.IDLE
    private var retries = 0L
    private var lastFailureReason: Int? = null
    private var lastFailureOperation: String? = null
    private var wifiRadioEnabled = wifiManager.isWifiEnabled
    private var p2pRadioEnabled: Boolean? = null
    private var retryIndex = 0
    private var connectingDeviceAddress: String? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private var ownerServiceRegistered = false
    private var silentDiscoveryRestarts = 0
    private var peerFallbackAttempts = 0
    private var peerFallbackPhase = WifiDirectPeerFallbackPhase.IDLE
    private var peerFallbackEpoch = 0L
    private var ownerVisibilityActive = false
    private var ownerVisibilityEpoch = 0L
    private var ownerVisibilityDiscoveryAttempts = 0
    private var ownerClientObserved = false
    private var ownerVisibilityWindowConsumed = false
    private var ownerListeningRequested = false
    private var ownerListeningStartAttempts = 0
    private var ownerPeerDiscoveryRequested = false

    private val retry = Runnable {
        if (closed.get() || !channelReady || !radioAvailable()) return@Runnable
        when (role) {
            WifiDirectNodeRole.ANDROID_GROUP_OWNER -> ensureOwnerGroup()
            WifiDirectNodeRole.ROKID_CLIENT -> ensureClientDiscovery()
        }
    }

    private val channelRestart = Runnable { initializeChannel() }

    private val discoveryWatchdog = Runnable {
        val current = status()
        if (!discoveryWatchdogAllowed(
                role,
                current.phase,
                endpointSnapshot() != null,
                silentDiscoveryRestarts,
            )
        ) {
            if (current.phase == WifiDirectPhase.DISCOVERING &&
                silentDiscoveryRestarts >= MAXIMUM_SILENT_DISCOVERY_RESTARTS
            ) {
                Log.w(TAG, "state=wifi_direct_discovery_watchdog_exhausted role=rokid_client")
            }
            return@Runnable
        }
        silentDiscoveryRestarts += 1
        Log.w(
            TAG,
            "state=wifi_direct_discovery_silent role=rokid_client " +
                "restart=$silentDiscoveryRestarts/$MAXIMUM_SILENT_DISCOVERY_RESTARTS",
        )
        if (peerFallbackAllowed(
                role,
                current.phase,
                endpointSnapshot() != null,
                silentDiscoveryRestarts,
                peerFallbackAttempts,
                peerFallbackPhase,
            )
        ) {
            startPeerFallbackScan()
        } else {
            resetClientDiscovery()
        }
    }

    private val peerFallbackTimeout = Runnable {
        if (peerFallbackPhase == WifiDirectPeerFallbackPhase.IDLE || closed.get()) return@Runnable
        Log.w(TAG, "state=wifi_direct_peer_fallback_timeout role=rokid_client")
        clearPeerFallbackState()
        resetClientDiscovery()
    }

    private val peerSnapshotDelay = Runnable { requestPeerSnapshot() }

    private val connectionTimeout = Runnable {
        if (closed.get() || role != WifiDirectNodeRole.ROKID_CLIENT) return@Runnable
        if (status().phase == WifiDirectPhase.CONNECTING && endpointSnapshot() == null) {
            Log.w(TAG, "state=wifi_direct_connection_formation_timeout role=rokid_client")
            resetClientDiscovery()
        }
    }

    private val ownerVisibilityTimeout = Runnable {
        if (!ownerVisibilityCallbackAllowed(
                closed.get(),
                channelReady,
                ownerVisibilityEpoch,
                channelEpoch.get(),
                ownerVisibilityActive,
            )
        ) return@Runnable
        stopOwnerVisibility("timeout", observedClients = 0)
    }

    private val ownerVisibilityRetry = Runnable { attemptOwnerVisibilityDiscovery() }

    private val ownerListeningRetry = Runnable { attemptOwnerListening() }

    private val ownerMembershipPoll = Runnable { requestOwnerGroupMembership() }

    private val ownerVisibilityRestart = Runnable {
        if (closed.get() || role != WifiDirectNodeRole.ANDROID_GROUP_OWNER ||
            ownerClientObserved || status().phase !in OWNER_RENDEZVOUS_PHASES
        ) return@Runnable
        ownerVisibilityWindowConsumed = false
        startOwnerVisibilityWindow()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED,
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    updateRadioState(p2pEnabled = enabled, source = "p2p")
                }
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                    if (state == WifiManager.WIFI_STATE_ENABLED || state == WifiManager.WIFI_STATE_DISABLED) {
                        updateRadioState(
                            wifiEnabled = state == WifiManager.WIFI_STATE_ENABLED,
                            source = "wifi",
                        )
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    requestConnectionInfo()
                    if (ownerVisibilityActive) requestOwnerGroupMembership()
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (role == WifiDirectNodeRole.ROKID_CLIENT && endpointSnapshot() == null) {
                        if (peerFallbackPhase == WifiDirectPeerFallbackPhase.DISCOVERING_PEERS) {
                            requestPeerSnapshot()
                        } else if (peerFallbackPhase == WifiDirectPeerFallbackPhase.IDLE) {
                            ensureClientDiscovery()
                        }
                    }
                }
            }
        }
    }

    override fun awaitAddress(timeoutMillis: Long): InetAddress {
        require(timeoutMillis in 1L..MAXIMUM_RESOLUTION_TIMEOUT_MILLIS)
        requireRequiredPermission()
        startOnce()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        stateLock.lockInterruptibly()
        try {
            while (true) {
                endpoint?.let { return it }
                check(!closed.get()) { "Wi-Fi Direct resolver is closed" }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) throw SocketTimeoutException("Wi-Fi Direct group was not ready")
                stateChanged.awaitNanos(remaining)
            }
        } finally {
            stateLock.unlock()
        }
    }

    fun status(): WifiDirectStatus = stateLock.withLock {
        WifiDirectStatus(
            phase = phase,
            retries = retries,
            lastFailureReason = lastFailureReason,
            lastFailureOperation = lastFailureOperation,
            radioAvailable = wifiRadioEnabled && p2pRadioEnabled != false,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val stopListeningOnClose = ownerListeningCleanupRequired(
            Build.VERSION.SDK_INT,
            ownerListeningRequested,
        )
        mainHandler.removeCallbacks(retry)
        mainHandler.removeCallbacks(connectionTimeout)
        mainHandler.removeCallbacks(channelRestart)
        mainHandler.removeCallbacks(discoveryWatchdog)
        mainHandler.removeCallbacks(peerFallbackTimeout)
        mainHandler.removeCallbacks(peerSnapshotDelay)
        peerFallbackPhase = WifiDirectPeerFallbackPhase.IDLE
        clearOwnerVisibilityState()
        channelEpoch.incrementAndGet()
        stateLock.withLock {
            endpoint = null
            phase = WifiDirectPhase.CLOSED
            stateChanged.signalAll()
        }
        mainHandler.post {
            if (::channel.isInitialized && channelReady) {
                runCatching {
                    serviceRequest?.let { request ->
                        manager.removeServiceRequest(channel, request, noOpAction())
                    }
                    if (role == WifiDirectNodeRole.ANDROID_GROUP_OWNER) {
                        manager.clearLocalServices(channel, noOpAction())
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && stopListeningOnClose) {
                        manager.stopListening(channel, noOpAction())
                    }
                    manager.stopPeerDiscovery(channel, noOpAction())
                }
            }
            channelReady = false
            if (receiverRegistered) {
                runCatching { appContext.unregisterReceiver(receiver) }
                receiverRegistered = false
            }
        }
    }

    private fun startOnce() {
        if (!started.compareAndSet(false, true)) return
        updatePhase(WifiDirectPhase.STARTING)
        mainHandler.post {
            if (closed.get()) return@post
            registerReceiver()
            initializeChannel()
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun initializeChannel() {
        if (closed.get()) return
        mainHandler.removeCallbacks(channelRestart)
        channelReady = false
        val epoch = channelEpoch.incrementAndGet()
        val initializedChannel = try {
            manager.initialize(appContext, Looper.getMainLooper()) {
                handleChannelDisconnect(epoch)
            }
        } catch (_: RuntimeException) {
            null
        }
        if (initializedChannel == null) {
            if (!closed.get() && epoch == channelEpoch.get()) {
                recordFailure("initialize_channel", WifiP2pManager.ERROR)
                clearEndpoint(
                    if (radioAvailable()) WifiDirectPhase.STARTING else WifiDirectPhase.WAITING_FOR_RADIO,
                )
                scheduleChannelRestart()
            }
            return
        }
        if (closed.get() || epoch != channelEpoch.get()) return
        channel = initializedChannel
        channelReady = true
        ownerServiceRegistered = false
        serviceRequest = null
        connectingDeviceAddress = null
        silentDiscoveryRestarts = 0
        peerFallbackAttempts = 0
        clearPeerFallbackState()
        clearOwnerVisibilityState()
        ownerClientObserved = false
        ownerVisibilityWindowConsumed = false
        if (!radioAvailable()) {
            clearEndpoint(WifiDirectPhase.WAITING_FOR_RADIO)
            Log.i(TAG, "state=wifi_direct_waiting_for_radio role=${role.name.lowercase()} source=startup")
            return
        }
        clearEndpoint(WifiDirectPhase.STARTING)
        when (initializationAction(role)) {
            WifiDirectInitializationAction.PREPARE_NOW -> when (role) {
                WifiDirectNodeRole.ANDROID_GROUP_OWNER -> prepareOwner()
                WifiDirectNodeRole.ROKID_CLIENT -> prepareClient()
            }
            // AOSP/vendor Channel setup finishes on the supplied Looper. Do not issue DNS-SD or
            // group commands in the same callback turn as initialize: API-32 YodaOS can silently
            // drop them while bringing P2P up.
            WifiDirectInitializationAction.DEFER_TO_RETRY -> scheduleRetry(immediate = false)
        }
    }

    private fun handleChannelDisconnect(epoch: Long) {
        if (!shouldReinitializeChannel(closed.get(), epoch, channelEpoch.get())) return
        channelReady = false
        channelEpoch.incrementAndGet()
        mainHandler.removeCallbacks(retry)
        mainHandler.removeCallbacks(connectionTimeout)
        mainHandler.removeCallbacks(discoveryWatchdog)
        mainHandler.removeCallbacks(peerFallbackTimeout)
        mainHandler.removeCallbacks(peerSnapshotDelay)
        peerFallbackPhase = WifiDirectPeerFallbackPhase.IDLE
        peerFallbackEpoch = 0L
        clearOwnerVisibilityState()
        ownerClientObserved = false
        ownerVisibilityWindowConsumed = false
        ownerServiceRegistered = false
        serviceRequest = null
        connectingDeviceAddress = null
        recordFailure("channel_disconnected", WifiP2pManager.ERROR)
        clearEndpoint(
            if (radioAvailable()) WifiDirectPhase.STARTING else WifiDirectPhase.WAITING_FOR_RADIO,
        )
        scheduleChannelRestart()
    }

    private fun scheduleChannelRestart() {
        if (closed.get()) return
        mainHandler.removeCallbacks(channelRestart)
        mainHandler.postDelayed(channelRestart, CHANNEL_REINITIALIZATION_DELAY_MILLIS)
    }

    private fun prepareOwner() {
        if (closed.get() || !radioAvailable()) return
        if (ownerServiceRegistered) {
            ensureOwnerGroup()
            return
        }
        val service = WifiP2pDnsSdServiceInfo.newInstance(
            SERVICE_INSTANCE,
            SERVICE_TYPE,
            mapOf("schema" to "1", "role" to "android-node"),
        )
        runAuthorizedP2p {
            manager.addLocalService(
                channel,
                service,
                action(
                    operation = "add_local_service",
                    onSuccess = {
                        ownerServiceRegistered = true
                        ensureOwnerGroup()
                    },
                ),
            )
        }
    }

    private fun ensureOwnerGroup() {
        if (closed.get() || !radioAvailable()) return
        runAuthorizedP2p {
            manager.requestGroupInfo(channel) { group ->
                if (closed.get()) return@requestGroupInfo
                if (group != null) {
                    if (group.isGroupOwner) {
                        requestConnectionInfo()
                    } else {
                        recordFailure("owner_group_role", WifiP2pManager.BUSY)
                    }
                    return@requestGroupInfo
                }
                // Advertise first and let the Rokid initiate ordinary group negotiation. A
                // pre-created autonomous GO was accepted by both frameworks but was invisible to
                // this firmware pair on both tested bands. The client advertises GO intent zero;
                // the negotiated group is then verified below before its address is accepted.
                updatePhase(WifiDirectPhase.DISCOVERING)
                startOwnerVisibilityWindow()
            }
        }
    }

    private fun prepareClient() {
        if (closed.get() || !radioAvailable()) return
        manager.setDnsSdResponseListeners(
            channel,
            { instanceName, registrationType, device ->
                if (matchesService(instanceName, registrationType)) connectToOwner(device)
            },
            { _, _, _ -> Unit },
        )
        ensureClientDiscovery()
    }

    private fun ensureClientDiscovery() {
        if (closed.get() || !radioAvailable() || endpointSnapshot() != null) return
        if (!discoveryAllowed(status().phase)) return
        requestConnectionInfo()
        if (serviceRequest != null) {
            discoverClientServices()
            return
        }
        val request = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)
        serviceRequest = request
        val operationStarted = runAuthorizedP2p {
            manager.addServiceRequest(
                channel,
                request,
                action(
                    operation = "add_service_request",
                    onSuccess = ::discoverClientServices,
                    onFailure = ::resetClientDiscovery,
                ),
            )
        }
        if (operationStarted) scheduleDiscoveryWatchdog()
    }

    private fun discoverClientServices() {
        if (closed.get() || role != WifiDirectNodeRole.ROKID_CLIENT ||
            !channelReady || !radioAvailable() || endpointSnapshot() != null
        ) return
        val operationStarted = runAuthorizedP2p {
            manager.discoverServices(
                channel,
                action(
                    operation = "discover_services",
                    onSuccess = {
                        markDiscoveringUnlessConnecting()
                        scheduleDiscoveryWatchdog()
                    },
                    onFailure = ::resetClientDiscovery,
                ),
            )
        }
        if (operationStarted) scheduleDiscoveryWatchdog()
    }

    private fun scheduleDiscoveryWatchdog() {
        mainHandler.removeCallbacks(discoveryWatchdog)
        if (discoveryWatchdogAllowed(
                role,
                status().phase,
                endpointSnapshot() != null,
                silentDiscoveryRestarts,
            )
        ) {
            mainHandler.postDelayed(discoveryWatchdog, DISCOVERY_WATCHDOG_MILLIS)
        }
    }

    private fun startPeerFallbackScan() {
        if (!channelReady || !radioAvailable() || closed.get() ||
            peerFallbackPhase != WifiDirectPeerFallbackPhase.IDLE
        ) return
        peerFallbackAttempts += 1
        peerFallbackEpoch = channelEpoch.get()
        // stopPeerDiscovery can emit PEERS_CHANGED with an empty cache before discoverPeers is
        // accepted. Keep that stale broadcast ineligible until discoverPeers reports success.
        peerFallbackPhase = WifiDirectPeerFallbackPhase.STARTING_PEER_DISCOVERY
        mainHandler.removeCallbacks(peerSnapshotDelay)
        mainHandler.removeCallbacks(peerFallbackTimeout)
        mainHandler.postDelayed(peerFallbackTimeout, PEER_FALLBACK_TIMEOUT_MILLIS)
        val operationStarted = runAuthorizedP2p {
            manager.stopPeerDiscovery(channel, noOpAction())
            manager.discoverPeers(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!peerFallbackCallbackAllowed(
                                closed.get(),
                                channelReady,
                                peerFallbackEpoch,
                                channelEpoch.get(),
                                peerFallbackPhase,
                                WifiDirectPeerFallbackPhase.STARTING_PEER_DISCOVERY,
                            )
                        ) return
                        peerFallbackPhase = WifiDirectPeerFallbackPhase.DISCOVERING_PEERS
                        mainHandler.removeCallbacks(peerSnapshotDelay)
                        mainHandler.postDelayed(peerSnapshotDelay, PEER_DISCOVERY_SETTLE_MILLIS)
                    }

                    override fun onFailure(reason: Int) {
                        if (!peerFallbackCallbackAllowed(
                                closed.get(),
                                channelReady,
                                peerFallbackEpoch,
                                channelEpoch.get(),
                                peerFallbackPhase,
                                WifiDirectPeerFallbackPhase.STARTING_PEER_DISCOVERY,
                            )
                        ) return
                        recordFailure("discover_peers_fallback", reason)
                        clearPeerFallbackState()
                        resetClientDiscovery()
                    }
                },
            )
        }
        if (!operationStarted) {
            clearPeerFallbackState()
        }
    }

    private fun requestPeerSnapshot() {
        if (!peerFallbackCallbackAllowed(
                closed.get(),
                channelReady,
                peerFallbackEpoch,
                channelEpoch.get(),
                peerFallbackPhase,
                WifiDirectPeerFallbackPhase.DISCOVERING_PEERS,
            )
        ) return
        val callbackEpoch = peerFallbackEpoch
        peerFallbackPhase = WifiDirectPeerFallbackPhase.REQUESTING_PEERS
        mainHandler.removeCallbacks(peerSnapshotDelay)
        val operationStarted = runAuthorizedP2p {
            manager.requestPeers(channel) { peerList ->
                if (!peerFallbackCallbackAllowed(
                        closed.get(),
                        channelReady,
                        callbackEpoch,
                        channelEpoch.get(),
                        peerFallbackPhase,
                        WifiDirectPeerFallbackPhase.REQUESTING_PEERS,
                    )
                ) return@requestPeers
                val peers = peerList?.deviceList?.toList().orEmpty()
                val ownerStates = peers.map { it.isGroupOwner }
                val ownerCandidates = ownerStates.count { it }
                val candidateIndex = rendezvousCandidateIndex(ownerStates)
                val candidate = candidateIndex?.let(peers::get)
                clearPeerFallbackState()
                if (candidate == null || candidate.deviceAddress.isNullOrBlank()) {
                    Log.w(
                        TAG,
                        "state=wifi_direct_peer_fallback_no_candidate role=rokid_client " +
                            "visible_peers=${peers.size} owner_candidates=$ownerCandidates",
                    )
                    resetClientDiscovery()
                    return@requestPeers
                }
                Log.i(
                    TAG,
                    "state=wifi_direct_peer_fallback_candidate_selected role=rokid_client " +
                        "visible_peers=${peers.size} owner_candidates=$ownerCandidates",
                )
                connectToOwner(candidate)
            }
        }
        if (!operationStarted) {
            clearPeerFallbackState()
        }
    }

    private fun clearPeerFallbackState() {
        mainHandler.removeCallbacks(peerSnapshotDelay)
        mainHandler.removeCallbacks(peerFallbackTimeout)
        peerFallbackPhase = WifiDirectPeerFallbackPhase.IDLE
        peerFallbackEpoch = 0L
    }

    private fun connectToOwner(device: WifiP2pDevice) {
        if (closed.get() || !radioAvailable() || endpointSnapshot() != null || device.deviceAddress.isNullOrBlank()) {
            return
        }
        if (connectingDeviceAddress == device.deviceAddress) return
        mainHandler.removeCallbacks(retry)
        mainHandler.removeCallbacks(discoveryWatchdog)
        clearPeerFallbackState()
        connectingDeviceAddress = device.deviceAddress
        updatePhase(WifiDirectPhase.CONNECTING)
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = CLIENT_GROUP_OWNER_INTENT
            wps.setup = WpsInfo.PBC
        }
        runAuthorizedP2p {
            manager.connect(
                channel,
                config,
                action(
                    operation = "connect",
                    onSuccess = {
                        // Action success means negotiation started, not that the group already exists.
                        // Wait for WIFI_P2P_CONNECTION_CHANGED_ACTION and retain an explicit watchdog
                        // for vendors that omit the terminal broadcast.
                        mainHandler.removeCallbacks(connectionTimeout)
                        mainHandler.postDelayed(connectionTimeout, CONNECTION_FORMATION_TIMEOUT_MILLIS)
                    },
                    onFailure = ::resetClientDiscovery,
                ),
            )
        }
    }

    private fun requestConnectionInfo() {
        if (closed.get() || !radioAvailable() || !channelReady) return
        runAuthorizedP2p {
            manager.requestConnectionInfo(channel, ::acceptConnectionInfo)
        }
    }

    private fun acceptConnectionInfo(info: WifiP2pInfo) {
        if (!radioAvailable()) return
        if (!info.groupFormed || info.groupOwnerAddress == null) {
            if (role == WifiDirectNodeRole.ROKID_CLIENT) {
                when (status().phase) {
                    WifiDirectPhase.CONNECTING -> return
                    WifiDirectPhase.GROUP_READY -> {
                        resetClientDiscovery()
                        return
                    }
                    else -> {
                        clearEndpoint(WifiDirectPhase.DISCOVERING)
                        return
                    }
                }
            }
            if (status().phase == WifiDirectPhase.GROUP_READY) {
                stopOwnerVisibility("group_lost", observedClients = 0)
            }
            ownerClientObserved = false
            clearEndpoint(WifiDirectPhase.DISCOVERING)
            if (!ownerVisibilityActive) {
                ownerVisibilityWindowConsumed = false
                startOwnerVisibilityWindow()
            }
            return
        }
        mainHandler.removeCallbacks(connectionTimeout)
        mainHandler.removeCallbacks(discoveryWatchdog)
        clearPeerFallbackState()
        silentDiscoveryRestarts = 0
        peerFallbackAttempts = 0
        if (role == WifiDirectNodeRole.ANDROID_GROUP_OWNER && !info.isGroupOwner) {
            recordFailure("owner_connection_role", WifiP2pManager.ERROR)
            return
        }
        if (role == WifiDirectNodeRole.ROKID_CLIENT && info.isGroupOwner) {
            recordFailure("client_connection_role", WifiP2pManager.ERROR)
            return
        }
        val address = info.groupOwnerAddress
        if (!address.isSiteLocalAddress && !address.isLinkLocalAddress) {
            recordFailure("group_owner_address", WifiP2pManager.ERROR)
            return
        }
        connectingDeviceAddress = null
        retryIndex = 0
        stateLock.withLock {
            endpoint = address
            phase = WifiDirectPhase.GROUP_READY
            lastFailureReason = null
            lastFailureOperation = null
            stateChanged.signalAll()
        }
        if (role == WifiDirectNodeRole.ANDROID_GROUP_OWNER) {
            startOwnerVisibilityWindow()
        } else {
            runAuthorizedP2p {
                manager.stopPeerDiscovery(channel, noOpAction())
            }
        }
        Log.i(TAG, "state=wifi_direct_group_ready role=${role.name.lowercase()} endpoint=private_redacted")
    }

    /**
     * Before group formation, this makes the phone discoverable for ordinary negotiated P2P.
     * For a retained group, it also lets a late-arriving client discover the owner. Keep this
     * activity inside recurring bounded windows; mTLS still authenticates the eventual peer.
     */
    private fun startOwnerVisibilityWindow() {
        if (!ownerVisibilityWindowAllowed(
                role,
                status().phase,
                endpointSnapshot() != null,
                ownerVisibilityActive,
                ownerClientObserved,
                ownerVisibilityWindowConsumed,
            )
        ) return
        ownerVisibilityActive = true
        ownerVisibilityWindowConsumed = true
        ownerVisibilityEpoch = channelEpoch.get()
        ownerVisibilityDiscoveryAttempts = 0
        mainHandler.removeCallbacks(ownerVisibilityTimeout)
        mainHandler.postDelayed(ownerVisibilityTimeout, OWNER_VISIBILITY_WINDOW_MILLIS)
        when (ownerVisibilityStrategy(Build.VERSION.SDK_INT)) {
            WifiDirectOwnerVisibilityStrategy.PLATFORM_LISTEN -> attemptOwnerListening()
            WifiDirectOwnerVisibilityStrategy.PEER_DISCOVERY -> attemptOwnerVisibilityDiscovery()
        }
        requestOwnerGroupMembership()
    }

    /** API 33 added periodic social-channel listen state for an already formed group owner. */
    private fun attemptOwnerListening() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!ownerListeningAttemptAllowed(
                Build.VERSION.SDK_INT,
                ownerVisibilityActive,
                ownerListeningRequested,
                ownerListeningStartAttempts,
            ) ||
            !ownerVisibilityCallbackAllowed(
                closed.get(),
                channelReady,
                ownerVisibilityEpoch,
                channelEpoch.get(),
                ownerVisibilityActive,
            )
        ) return
        val callbackEpoch = ownerVisibilityEpoch
        ownerListeningStartAttempts += 1
        // Treat dispatch as active until a definitive failure. This guarantees that timeout,
        // membership, and close still issue stopListening if the vendor omits the start callback.
        ownerListeningRequested = true
        val operationStarted = runAuthorizedP2p {
            manager.startListening(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!ownerVisibilityCallbackAllowed(
                                closed.get(),
                                channelReady,
                                callbackEpoch,
                                channelEpoch.get(),
                                ownerVisibilityActive,
                            )
                        ) return
                        Log.i(
                            TAG,
                            "state=wifi_direct_owner_listen_active role=android_group_owner " +
                                "attempt=$ownerListeningStartAttempts/" +
                                "$MAXIMUM_OWNER_LISTEN_START_ATTEMPTS",
                        )
                    }

                    override fun onFailure(reason: Int) {
                        if (!ownerVisibilityCallbackAllowed(
                                closed.get(),
                                channelReady,
                                callbackEpoch,
                                channelEpoch.get(),
                                ownerVisibilityActive,
                            )
                        ) return
                        ownerListeningRequested = false
                        recordFailure("owner_start_listening", reason)
                        if (ownerListeningStartAttempts < MAXIMUM_OWNER_LISTEN_START_ATTEMPTS) {
                            mainHandler.removeCallbacks(ownerListeningRetry)
                            mainHandler.postDelayed(ownerListeningRetry, OWNER_VISIBILITY_RETRY_MILLIS)
                        } else {
                            // Some Android 13+ vendor stacks expose startListening but cannot hold
                            // it for an autonomous group. Fall back only after bounded failures;
                            // never run discovery concurrently with a successful listen request.
                            attemptOwnerVisibilityDiscovery()
                        }
                    }
                },
            )
        }
        if (!operationStarted) ownerListeningRequested = false
    }

    private fun attemptOwnerVisibilityDiscovery() {
        if (!ownerVisibilityCallbackAllowed(
                closed.get(),
                channelReady,
                ownerVisibilityEpoch,
                channelEpoch.get(),
                ownerVisibilityActive,
            ) || ownerVisibilityDiscoveryAttempts >= MAXIMUM_OWNER_VISIBILITY_DISCOVERY_ATTEMPTS
        ) return
        val callbackEpoch = ownerVisibilityEpoch
        ownerVisibilityDiscoveryAttempts += 1
        ownerPeerDiscoveryRequested = true
        val operationStarted = runAuthorizedP2p {
            manager.discoverPeers(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!ownerVisibilityCallbackAllowed(
                                closed.get(),
                                channelReady,
                                callbackEpoch,
                                channelEpoch.get(),
                                ownerVisibilityActive,
                            )
                        ) return
                        Log.i(
                            TAG,
                            "state=wifi_direct_owner_visibility_active role=android_group_owner " +
                                "attempt=$ownerVisibilityDiscoveryAttempts/" +
                                "$MAXIMUM_OWNER_VISIBILITY_DISCOVERY_ATTEMPTS",
                        )
                    }

                    override fun onFailure(reason: Int) {
                        if (!ownerVisibilityCallbackAllowed(
                                closed.get(),
                                channelReady,
                                callbackEpoch,
                                channelEpoch.get(),
                                ownerVisibilityActive,
                            )
                        ) return
                        ownerPeerDiscoveryRequested = false
                        recordFailure("owner_discover_peers", reason)
                        if (ownerVisibilityDiscoveryAttempts < MAXIMUM_OWNER_VISIBILITY_DISCOVERY_ATTEMPTS) {
                            mainHandler.removeCallbacks(ownerVisibilityRetry)
                            mainHandler.postDelayed(ownerVisibilityRetry, OWNER_VISIBILITY_RETRY_MILLIS)
                        }
                    }
                },
            )
        }
        if (!operationStarted) {
            ownerPeerDiscoveryRequested = false
            mainHandler.removeCallbacks(ownerVisibilityRetry)
        }
    }

    private fun requestOwnerGroupMembership() {
        if (!ownerVisibilityCallbackAllowed(
                closed.get(),
                channelReady,
                ownerVisibilityEpoch,
                channelEpoch.get(),
                ownerVisibilityActive,
            )
        ) return
        val callbackEpoch = ownerVisibilityEpoch
        runAuthorizedP2p {
            manager.requestGroupInfo(channel) { group ->
                if (!ownerVisibilityCallbackAllowed(
                        closed.get(),
                        channelReady,
                        callbackEpoch,
                        channelEpoch.get(),
                        ownerVisibilityActive,
                    )
                ) return@requestGroupInfo
                val observedClients = group?.clientList?.size ?: 0
                if (group?.isGroupOwner == true && observedClients > 0) {
                    ownerClientObserved = true
                    requestConnectionInfo()
                    stopOwnerVisibility("client_joined", observedClients)
                } else {
                    mainHandler.removeCallbacks(ownerMembershipPoll)
                    mainHandler.postDelayed(ownerMembershipPoll, OWNER_MEMBERSHIP_POLL_MILLIS)
                }
            }
        }
    }

    private fun stopOwnerVisibility(reason: String, observedClients: Int) {
        if (!ownerVisibilityActive) return
        val stopListening = ownerListeningCleanupRequired(Build.VERSION.SDK_INT, ownerListeningRequested)
        val stopPeerDiscovery = ownerPeerDiscoveryRequested
        val restartAfterPause = ownerVisibilityRestartAllowed(
            closed = closed.get(),
            role = role,
            phase = status().phase,
            clientObserved = ownerClientObserved || observedClients > 0,
            reason = reason,
        )
        clearOwnerVisibilityState()
        runAuthorizedP2p {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && stopListening) {
                manager.stopListening(channel, noOpAction())
            }
            if (stopPeerDiscovery) manager.stopPeerDiscovery(channel, noOpAction())
        }
        if (restartAfterPause) {
            mainHandler.postDelayed(ownerVisibilityRestart, OWNER_VISIBILITY_PAUSE_MILLIS)
        }
        Log.i(
            TAG,
            "state=wifi_direct_owner_visibility_stopped role=android_group_owner " +
                "reason=$reason observed_clients=$observedClients",
        )
    }

    private fun clearOwnerVisibilityState() {
        mainHandler.removeCallbacks(ownerVisibilityTimeout)
        mainHandler.removeCallbacks(ownerVisibilityRetry)
        mainHandler.removeCallbacks(ownerListeningRetry)
        mainHandler.removeCallbacks(ownerMembershipPoll)
        mainHandler.removeCallbacks(ownerVisibilityRestart)
        ownerVisibilityActive = false
        ownerVisibilityEpoch = 0L
        ownerVisibilityDiscoveryAttempts = 0
        ownerListeningRequested = false
        ownerListeningStartAttempts = 0
        ownerPeerDiscoveryRequested = false
    }

    private fun action(
        operation: String,
        onSuccess: () -> Unit = {},
        onFailure: (() -> Unit)? = null,
    ): WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = onSuccess.invoke()
        override fun onFailure(reason: Int) {
            recordFailure(operation, reason)
            onFailure?.invoke() ?: scheduleRetry(immediate = false)
        }
    }

    private fun noOpAction(): WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = Unit
        override fun onFailure(reason: Int) = Unit
    }

    private fun recordFailure(operation: String, reason: Int) {
        stateLock.withLock {
            retries = Math.addExact(retries, 1L)
            lastFailureReason = reason
            lastFailureOperation = operation
            stateChanged.signalAll()
        }
        Log.w(
            TAG,
            "state=wifi_direct_operation_failed role=${role.name.lowercase()} " +
                "operation=$operation reason=${failureReasonName(reason)}($reason)",
        )
    }

    private fun scheduleRetry(immediate: Boolean) {
        if (closed.get() || !radioAvailable()) return
        mainHandler.removeCallbacks(retry)
        val delay = if (immediate) 0L else RETRY_DELAYS_MILLIS[retryIndex.coerceAtMost(RETRY_DELAYS_MILLIS.lastIndex)]
        if (!immediate && retryIndex < RETRY_DELAYS_MILLIS.lastIndex) retryIndex += 1
        mainHandler.postDelayed(retry, delay)
    }

    private fun resetClientDiscovery() {
        if (closed.get() || role != WifiDirectNodeRole.ROKID_CLIENT) return
        mainHandler.removeCallbacks(discoveryWatchdog)
        clearPeerFallbackState()
        if (!radioAvailable()) {
            serviceRequest = null
            clearEndpoint(WifiDirectPhase.WAITING_FOR_RADIO)
            return
        }
        mainHandler.removeCallbacks(connectionTimeout)
        connectingDeviceAddress = null
        clearEndpoint(WifiDirectPhase.DISCOVERING)
        val staleRequest = serviceRequest
        serviceRequest = null
        val operationStarted = runAuthorizedP2p {
            manager.cancelConnect(channel, noOpAction())
            manager.stopPeerDiscovery(channel, noOpAction())
            if (staleRequest != null) {
                manager.removeServiceRequest(channel, staleRequest, noOpAction())
            }
        }
        // Do not depend exclusively on a vendor action callback: the same cold P2P state that
        // dropped addServiceRequest can also omit a cleanup callback. Channel ordering keeps the
        // delayed retry behind the cleanup messages when the service is responsive.
        if (operationStarted) {
            scheduleRetry(immediate = false)
        }
    }

    private fun markDiscoveringUnlessConnecting() {
        if (discoveryAllowed(status().phase)) {
            updatePhase(WifiDirectPhase.DISCOVERING)
        }
    }

    private fun clearEndpoint(nextPhase: WifiDirectPhase) {
        connectingDeviceAddress = null
        stateLock.withLock {
            endpoint = null
            phase = nextPhase
            stateChanged.signalAll()
        }
    }

    private fun updatePhase(nextPhase: WifiDirectPhase) {
        stateLock.withLock {
            phase = nextPhase
            stateChanged.signalAll()
        }
    }

    private fun endpointSnapshot(): InetAddress? = stateLock.withLock { endpoint }

    private fun radioAvailable(): Boolean = stateLock.withLock {
        radioAvailable(wifiRadioEnabled, p2pRadioEnabled)
    }

    /**
     * Wi-Fi/P2P enablement is owned by Android/YodaOS, not this unprivileged application. Pausing
     * here prevents a disabled vendor stack from being hammered with BUSY operations. The same
     * resolver resumes discovery when the platform reports both layers available again.
     */
    private fun updateRadioState(
        wifiEnabled: Boolean? = null,
        p2pEnabled: Boolean? = null,
        source: String,
    ) {
        val transition = stateLock.withLock {
            val previousWifiEnabled = wifiRadioEnabled
            val previousP2pEnabled = p2pRadioEnabled
            val wasAvailable = radioAvailable(wifiRadioEnabled, p2pRadioEnabled)
            wifiEnabled?.let { wifiRadioEnabled = it }
            p2pEnabled?.let { p2pRadioEnabled = it }
            val isAvailable = radioAvailable(wifiRadioEnabled, p2pRadioEnabled)
            RadioTransition(
                wasAvailable = wasAvailable,
                isAvailable = isAvailable,
                preparationRequired = preparationRequiredAfterRadioUpdate(
                    previousWifiEnabled,
                    previousP2pEnabled,
                    wifiRadioEnabled,
                    p2pRadioEnabled,
                ),
                p2pAvailabilityConfirmed = previousP2pEnabled == null && p2pRadioEnabled == true,
                previousPhase = phase,
            )
        }
        val wasAvailable = transition.wasAvailable
        val isAvailable = transition.isAvailable
        if (!isAvailable) {
            mainHandler.removeCallbacks(retry)
            mainHandler.removeCallbacks(connectionTimeout)
            mainHandler.removeCallbacks(discoveryWatchdog)
            clearPeerFallbackState()
            clearOwnerVisibilityState()
            ownerClientObserved = false
            ownerVisibilityWindowConsumed = false
            connectingDeviceAddress = null
            serviceRequest = null
            ownerServiceRegistered = false
            silentDiscoveryRestarts = 0
            peerFallbackAttempts = 0
            clearEndpoint(WifiDirectPhase.WAITING_FOR_RADIO)
            if (wasAvailable) {
                Log.w(
                    TAG,
                    "state=wifi_direct_waiting_for_radio role=${role.name.lowercase()} source=$source",
                )
            }
            return
        }
        val preparationStillNeeded = !transition.p2pAvailabilityConfirmed ||
            transition.previousPhase == WifiDirectPhase.IDLE ||
            transition.previousPhase == WifiDirectPhase.STARTING ||
            transition.previousPhase == WifiDirectPhase.WAITING_FOR_RADIO
        val roleNeedsPreparation = !transition.wasAvailable || transition.p2pAvailabilityConfirmed
        if (transition.preparationRequired && roleNeedsPreparation && preparationStillNeeded &&
            started.get() && channelReady
        ) {
            mainHandler.removeCallbacks(retry)
            retryIndex = 0
            Log.i(TAG, "state=wifi_direct_radio_restored role=${role.name.lowercase()} source=$source")
            when (role) {
                WifiDirectNodeRole.ANDROID_GROUP_OWNER -> {
                    if (transition.p2pAvailabilityConfirmed &&
                        transition.previousPhase == WifiDirectPhase.STARTING
                    ) {
                        resetOwnerPreparation()
                    } else {
                        clearEndpoint(WifiDirectPhase.STARTING)
                        prepareOwner()
                    }
                }
                WifiDirectNodeRole.ROKID_CLIENT -> {
                    // If the optimistic unknown-state attempt already allocated a request but
                    // never received an action callback, remove it in channel order before a
                    // fresh bounded retry. A successful discovery has already left STARTING and
                    // does not need to be torn down merely because the first state broadcast
                    // confirmed that P2P was enabled.
                    if (transition.p2pAvailabilityConfirmed &&
                        transition.previousPhase == WifiDirectPhase.STARTING && serviceRequest != null
                    ) {
                        resetClientDiscovery()
                    } else {
                        clearEndpoint(WifiDirectPhase.STARTING)
                        prepareClient()
                    }
                }
            }
        }
    }

    private fun resetOwnerPreparation() {
        if (closed.get() || role != WifiDirectNodeRole.ANDROID_GROUP_OWNER || !radioAvailable()) return
        ownerServiceRegistered = false
        clearEndpoint(WifiDirectPhase.STARTING)
        val operationStarted = runAuthorizedP2p {
            manager.clearLocalServices(channel, noOpAction())
        }
        if (operationStarted) scheduleRetry(immediate = false)
    }

    private fun requireRequiredPermission() {
        check(appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            "Wi-Fi Direct feature is unavailable"
        }
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        check(appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            "Wi-Fi Direct runtime permission is missing"
        }
    }

    /** Runtime permissions can be revoked after awaitAddress's initial validation. */
    private inline fun runAuthorizedP2p(operation: () -> Unit): Boolean {
        if (closed.get() || !channelReady || !radioAvailable()) return false
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            recordPermissionLoss()
            return false
        }
        return try {
            operation()
            true
        } catch (_: SecurityException) {
            recordPermissionLoss()
            false
        }
    }

    private fun recordPermissionLoss() {
        recordFailure("permission", WifiP2pManager.ERROR)
        Log.w(TAG, "state=wifi_direct_permission_unavailable role=${role.name.lowercase()}")
        scheduleRetry(immediate = false)
    }

    internal companion object {
        const val SERVICE_INSTANCE = "CONCEPTFlow-MPL-AndroidNode"
        const val SERVICE_TYPE = "_cf-mpl._tcp"
        const val CLIENT_GROUP_OWNER_INTENT = 0
        const val MAXIMUM_RESOLUTION_TIMEOUT_MILLIS = 180_000L
        const val CONNECTION_FORMATION_TIMEOUT_MILLIS = 70_000L
        const val CHANNEL_REINITIALIZATION_DELAY_MILLIS = 1_000L
        const val DISCOVERY_WATCHDOG_MILLIS = 15_000L
        const val MAXIMUM_SILENT_DISCOVERY_RESTARTS = 6
        const val PEER_FALLBACK_AFTER_SILENT_RESTARTS = 3
        const val MAXIMUM_PEER_FALLBACK_ATTEMPTS = 2
        const val PEER_DISCOVERY_SETTLE_MILLIS = 5_000L
        const val PEER_FALLBACK_TIMEOUT_MILLIS = 12_000L
        const val OWNER_VISIBILITY_WINDOW_MILLIS = 90_000L
        const val OWNER_MEMBERSHIP_POLL_MILLIS = 3_000L
        const val OWNER_VISIBILITY_RETRY_MILLIS = 3_000L
        const val OWNER_VISIBILITY_PAUSE_MILLIS = 15_000L
        const val MAXIMUM_OWNER_VISIBILITY_DISCOVERY_ATTEMPTS = 3
        const val MAXIMUM_OWNER_LISTEN_START_ATTEMPTS = 3
        val RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 15_000L)
        private val OWNER_RENDEZVOUS_PHASES = setOf(
            WifiDirectPhase.DISCOVERING,
            WifiDirectPhase.GROUP_READY,
        )

        fun matchesService(instanceName: String?, registrationType: String?): Boolean =
            instanceName == SERVICE_INSTANCE &&
                registrationType?.removeSuffix(".")?.removeSuffix(".local")?.removeSuffix(".") == SERVICE_TYPE

        fun discoveryAllowed(phase: WifiDirectPhase): Boolean = when (phase) {
            WifiDirectPhase.IDLE,
            WifiDirectPhase.STARTING,
            WifiDirectPhase.DISCOVERING,
            -> true
            WifiDirectPhase.WAITING_FOR_RADIO,
            WifiDirectPhase.CONNECTING,
            WifiDirectPhase.GROUP_READY,
            WifiDirectPhase.CLOSED,
            -> false
        }

        fun failureReasonName(reason: Int): String = when (reason) {
            WifiP2pManager.ERROR -> "ERROR"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "BUSY"
            else -> "UNKNOWN"
        }

        fun radioAvailable(wifiEnabled: Boolean, p2pEnabled: Boolean?): Boolean =
            wifiEnabled && p2pEnabled != false

        fun initializationAction(role: WifiDirectNodeRole): WifiDirectInitializationAction =
            when (role) {
                WifiDirectNodeRole.ANDROID_GROUP_OWNER,
                WifiDirectNodeRole.ROKID_CLIENT,
                -> WifiDirectInitializationAction.DEFER_TO_RETRY
            }

        /**
         * Unknown P2P state is optimistic for an already-enabled stack, but its first explicit
         * enabled report is still a readiness edge: an initialization-time request may have been
         * dropped while a vendor stack was disabled or still setting up its client channel.
         */
        fun preparationRequiredAfterRadioUpdate(
            previousWifiEnabled: Boolean,
            previousP2pEnabled: Boolean?,
            currentWifiEnabled: Boolean,
            currentP2pEnabled: Boolean?,
        ): Boolean {
            val wasAvailable = radioAvailable(previousWifiEnabled, previousP2pEnabled)
            val isAvailable = radioAvailable(currentWifiEnabled, currentP2pEnabled)
            return isAvailable &&
                (!wasAvailable || previousP2pEnabled == null && currentP2pEnabled == true)
        }

        fun shouldReinitializeChannel(closed: Boolean, callbackEpoch: Long, currentEpoch: Long): Boolean =
            !closed && callbackEpoch > 0L && callbackEpoch == currentEpoch

        fun discoveryWatchdogAllowed(
            role: WifiDirectNodeRole,
            phase: WifiDirectPhase,
            hasEndpoint: Boolean,
            completedRestarts: Int,
        ): Boolean = role == WifiDirectNodeRole.ROKID_CLIENT &&
            (phase == WifiDirectPhase.STARTING || phase == WifiDirectPhase.DISCOVERING) &&
            !hasEndpoint &&
            completedRestarts in 0 until MAXIMUM_SILENT_DISCOVERY_RESTARTS

        fun peerFallbackAllowed(
            role: WifiDirectNodeRole,
            phase: WifiDirectPhase,
            hasEndpoint: Boolean,
            completedSilentRestarts: Int,
            completedFallbackAttempts: Int,
            fallbackPhase: WifiDirectPeerFallbackPhase,
        ): Boolean = role == WifiDirectNodeRole.ROKID_CLIENT &&
            (phase == WifiDirectPhase.STARTING || phase == WifiDirectPhase.DISCOVERING) &&
            !hasEndpoint && fallbackPhase == WifiDirectPeerFallbackPhase.IDLE &&
            completedSilentRestarts >= PEER_FALLBACK_AFTER_SILENT_RESTARTS &&
            completedFallbackAttempts in 0 until MAXIMUM_PEER_FALLBACK_ATTEMPTS

        fun uniqueGroupOwnerIndex(groupOwnerStates: List<Boolean>): Int? {
            var selected: Int? = null
            groupOwnerStates.forEachIndexed { index, isGroupOwner ->
                if (!isGroupOwner) return@forEachIndexed
                if (selected != null) return null
                selected = index
            }
            return selected
        }

        /**
         * Vendor peer metadata can lag behind the independently formed group state. Prefer one
         * explicitly reported owner. When none is reported, a sole visible peer is an unambiguous
         * rendezvous candidate; pinned mutual TLS still authenticates it before any stream flows.
         */
        fun rendezvousCandidateIndex(groupOwnerStates: List<Boolean>): Int? {
            val reportedOwner = uniqueGroupOwnerIndex(groupOwnerStates)
            if (reportedOwner != null) return reportedOwner
            if (groupOwnerStates.count { it } > 1) return null
            return if (groupOwnerStates.size == 1) 0 else null
        }

        fun peerFallbackCallbackAllowed(
            closed: Boolean,
            channelReady: Boolean,
            callbackEpoch: Long,
            currentEpoch: Long,
            currentPhase: WifiDirectPeerFallbackPhase,
            expectedPhase: WifiDirectPeerFallbackPhase,
        ): Boolean = !closed && channelReady && currentPhase == expectedPhase &&
            callbackEpoch > 0L && callbackEpoch == currentEpoch

        fun ownerVisibilityWindowAllowed(
            role: WifiDirectNodeRole,
            phase: WifiDirectPhase,
            hasEndpoint: Boolean,
            alreadyActive: Boolean,
            clientAlreadyObserved: Boolean,
            windowAlreadyConsumed: Boolean,
        ): Boolean = role == WifiDirectNodeRole.ANDROID_GROUP_OWNER &&
            ((phase == WifiDirectPhase.DISCOVERING && !hasEndpoint) ||
                (phase == WifiDirectPhase.GROUP_READY && hasEndpoint)) && !alreadyActive &&
            !clientAlreadyObserved && !windowAlreadyConsumed

        fun ownerVisibilityCallbackAllowed(
            closed: Boolean,
            channelReady: Boolean,
            callbackEpoch: Long,
            currentEpoch: Long,
            active: Boolean,
        ): Boolean = !closed && channelReady && active && callbackEpoch > 0L && callbackEpoch == currentEpoch

        fun ownerListeningAvailable(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU

        fun ownerVisibilityStrategy(sdkInt: Int): WifiDirectOwnerVisibilityStrategy =
            if (ownerListeningAvailable(sdkInt)) {
                WifiDirectOwnerVisibilityStrategy.PLATFORM_LISTEN
            } else {
                WifiDirectOwnerVisibilityStrategy.PEER_DISCOVERY
            }

        fun ownerListeningAttemptAllowed(
            sdkInt: Int,
            windowActive: Boolean,
            startRequested: Boolean,
            completedAttempts: Int,
        ): Boolean = ownerListeningAvailable(sdkInt) && windowActive && !startRequested &&
            completedAttempts in 0 until MAXIMUM_OWNER_LISTEN_START_ATTEMPTS

        fun ownerListeningCleanupRequired(sdkInt: Int, startRequested: Boolean): Boolean =
            ownerListeningAvailable(sdkInt) && startRequested

        fun ownerVisibilityRestartAllowed(
            closed: Boolean,
            role: WifiDirectNodeRole,
            phase: WifiDirectPhase,
            clientObserved: Boolean,
            reason: String,
        ): Boolean = !closed && role == WifiDirectNodeRole.ANDROID_GROUP_OWNER &&
            phase in OWNER_RENDEZVOUS_PHASES && !clientObserved && reason == "timeout"

        private const val TAG = "ConceptFlowP2p"
    }

    private data class RadioTransition(
        val wasAvailable: Boolean,
        val isAvailable: Boolean,
        val preparationRequired: Boolean,
        val p2pAvailabilityConfirmed: Boolean,
        val previousPhase: WifiDirectPhase,
    )
}
