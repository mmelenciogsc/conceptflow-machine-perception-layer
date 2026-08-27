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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class WifiDirectNodeRole { ANDROID_GROUP_OWNER, ROKID_CLIENT }

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

    private val retry = Runnable {
        if (closed.get() || !radioAvailable()) return@Runnable
        when (role) {
            WifiDirectNodeRole.ANDROID_GROUP_OWNER -> ensureOwnerGroup()
            WifiDirectNodeRole.ROKID_CLIENT -> ensureClientDiscovery()
        }
    }

    private val connectionTimeout = Runnable {
        if (closed.get() || role != WifiDirectNodeRole.ROKID_CLIENT) return@Runnable
        if (status().phase == WifiDirectPhase.CONNECTING && endpointSnapshot() == null) {
            Log.w(TAG, "state=wifi_direct_connection_formation_timeout role=rokid_client")
            resetClientDiscovery()
        }
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
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (role == WifiDirectNodeRole.ROKID_CLIENT && endpointSnapshot() == null) {
                        ensureClientDiscovery()
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
        mainHandler.removeCallbacks(retry)
        mainHandler.removeCallbacks(connectionTimeout)
        stateLock.withLock {
            endpoint = null
            phase = WifiDirectPhase.CLOSED
            stateChanged.signalAll()
        }
        mainHandler.post {
            if (::channel.isInitialized) {
                runCatching {
                    serviceRequest?.let { request ->
                        manager.removeServiceRequest(channel, request, noOpAction())
                    }
                    if (role == WifiDirectNodeRole.ANDROID_GROUP_OWNER) {
                        manager.clearLocalServices(channel, noOpAction())
                    }
                    manager.stopPeerDiscovery(channel, noOpAction())
                }
            }
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
            channel = manager.initialize(appContext, Looper.getMainLooper()) {
                if (!closed.get()) {
                    ownerServiceRegistered = false
                    serviceRequest = null
                    clearEndpoint(
                        if (radioAvailable()) WifiDirectPhase.STARTING else WifiDirectPhase.WAITING_FOR_RADIO,
                    )
                    scheduleRetry(immediate = false)
                }
            }
            if (!radioAvailable()) {
                clearEndpoint(WifiDirectPhase.WAITING_FOR_RADIO)
                Log.i(TAG, "state=wifi_direct_waiting_for_radio role=${role.name.lowercase()} source=startup")
            } else {
                when (role) {
                    WifiDirectNodeRole.ANDROID_GROUP_OWNER -> prepareOwner()
                    WifiDirectNodeRole.ROKID_CLIENT -> prepareClient()
                }
            }
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
                updatePhase(WifiDirectPhase.CONNECTING)
                runAuthorizedP2p {
                    manager.createGroup(
                        channel,
                        action(operation = "create_group", onSuccess = ::requestConnectionInfo),
                    )
                }
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
            runAuthorizedP2p {
                manager.discoverServices(
                    channel,
                    action(
                        operation = "discover_services",
                        onSuccess = ::markDiscoveringUnlessConnecting,
                        onFailure = ::resetClientDiscovery,
                    ),
                )
            }
            return
        }
        val request = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)
        serviceRequest = request
        runAuthorizedP2p {
            manager.addServiceRequest(
                channel,
                request,
                action(
                    operation = "add_service_request",
                    onSuccess = {
                        runAuthorizedP2p {
                            manager.discoverServices(
                                channel,
                                action(
                                    operation = "discover_services",
                                    onSuccess = ::markDiscoveringUnlessConnecting,
                                    onFailure = ::resetClientDiscovery,
                                ),
                            )
                        }
                    },
                    onFailure = ::resetClientDiscovery,
                ),
            )
        }
    }

    private fun connectToOwner(device: WifiP2pDevice) {
        if (closed.get() || !radioAvailable() || endpointSnapshot() != null || device.deviceAddress.isNullOrBlank()) {
            return
        }
        if (connectingDeviceAddress == device.deviceAddress) return
        mainHandler.removeCallbacks(retry)
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
        if (closed.get() || !radioAvailable() || !::channel.isInitialized) return
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
            clearEndpoint(
                WifiDirectPhase.CONNECTING,
            )
            scheduleRetry(immediate = false)
            return
        }
        mainHandler.removeCallbacks(connectionTimeout)
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
        runAuthorizedP2p {
            manager.stopPeerDiscovery(channel, noOpAction())
        }
        Log.i(TAG, "state=wifi_direct_group_ready role=${role.name.lowercase()} endpoint=private_redacted")
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
                manager.removeServiceRequest(
                    channel,
                    staleRequest,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = scheduleRetry(immediate = false)
                        override fun onFailure(reason: Int) = scheduleRetry(immediate = false)
                    },
                )
            }
        }
        if (staleRequest == null && operationStarted) {
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
            val wasAvailable = radioAvailable(wifiRadioEnabled, p2pRadioEnabled)
            wifiEnabled?.let { wifiRadioEnabled = it }
            p2pEnabled?.let { p2pRadioEnabled = it }
            val isAvailable = radioAvailable(wifiRadioEnabled, p2pRadioEnabled)
            wasAvailable to isAvailable
        }
        val wasAvailable = transition.first
        val isAvailable = transition.second
        if (!isAvailable) {
            mainHandler.removeCallbacks(retry)
            mainHandler.removeCallbacks(connectionTimeout)
            connectingDeviceAddress = null
            serviceRequest = null
            ownerServiceRegistered = false
            clearEndpoint(WifiDirectPhase.WAITING_FOR_RADIO)
            if (wasAvailable) {
                Log.w(
                    TAG,
                    "state=wifi_direct_waiting_for_radio role=${role.name.lowercase()} source=$source",
                )
            }
            return
        }
        if (!wasAvailable && started.get() && ::channel.isInitialized) {
            retryIndex = 0
            clearEndpoint(WifiDirectPhase.STARTING)
            Log.i(TAG, "state=wifi_direct_radio_restored role=${role.name.lowercase()} source=$source")
            when (role) {
                WifiDirectNodeRole.ANDROID_GROUP_OWNER -> prepareOwner()
                WifiDirectNodeRole.ROKID_CLIENT -> prepareClient()
            }
        }
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
        if (closed.get() || !radioAvailable()) return false
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
        val RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 15_000L)

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

        private const val TAG = "ConceptFlowP2p"
    }
}
