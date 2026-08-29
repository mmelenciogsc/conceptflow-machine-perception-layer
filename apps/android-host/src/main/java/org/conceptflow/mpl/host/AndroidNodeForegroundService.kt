// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet
import org.conceptflow.mpl.androidhost.LauncherActivity
import org.conceptflow.mpl.host.focus.SpatialFocusCommand
import org.conceptflow.mpl.host.focus.SpatialFocusState
import org.conceptflow.mpl.host.vision.EnvironmentSelectionMode
import org.conceptflow.mpl.host.vision.GnssQualitySample
import org.conceptflow.mpl.transport.AndroidWifiDirectGroupRecovery
import org.conceptflow.mpl.transport.WifiDirectRecoveryBand
import org.conceptflow.mpl.transport.WifiDirectRecoveryMode
import org.conceptflow.mpl.transport.WifiDirectGroupRecoveryOutcome
import org.conceptflow.mpl.transport.WifiDirectGroupRecoveryResult

/**
 * Process-local observable state for the foreground Android Node runtime.
 *
 * The service, rather than an Activity, owns the network listener and inference resources. Activity
 * recreation and display sleep therefore cannot silently close the authenticated listener.
 */
object AndroidNodeRuntimeState {
    private val observers = CopyOnWriteArraySet<(LiveMachineVisionStatus?) -> Unit>()
    private val focusObservers = CopyOnWriteArraySet<(SpatialFocusState?) -> Unit>()

    @Volatile
    private var latest: LiveMachineVisionStatus? = null
    @Volatile private var latestFocus: SpatialFocusState? = null

    fun current(): LiveMachineVisionStatus? = latest

    fun addObserver(observer: (LiveMachineVisionStatus?) -> Unit) {
        observers += observer
        observer(latest)
    }

    fun removeObserver(observer: (LiveMachineVisionStatus?) -> Unit) {
        observers -= observer
    }

    fun currentFocus(): SpatialFocusState? = latestFocus

    fun addFocusObserver(observer: (SpatialFocusState?) -> Unit) {
        focusObservers += observer
        observer(latestFocus)
    }

    fun removeFocusObserver(observer: (SpatialFocusState?) -> Unit) {
        focusObservers -= observer
    }

    internal fun publish(status: LiveMachineVisionStatus?) {
        latest = status
        observers.forEach { observer -> runCatching { observer(status) } }
    }

    internal fun publishFocus(state: SpatialFocusState?) {
        latestFocus = state
        focusObservers.forEach { observer -> runCatching { observer(state) } }
    }
}

data class AndroidNodeWifiDirectRecoverySnapshot(
    val running: Boolean,
    val result: WifiDirectGroupRecoveryResult? = null,
) {
    fun accessibleSummary(): String = when {
        running -> "wifi_direct_recovery_running"
        result == null -> "wifi_direct_recovery_idle"
        else -> "wifi_direct_recovery_${result.outcome.name.lowercase()}_" +
            "removed_existing_${result.removedExistingGroup}"
    }
}

object AndroidNodeWifiDirectRecoveryState {
    @Volatile
    private var latest = AndroidNodeWifiDirectRecoverySnapshot(running = false)

    fun current(): AndroidNodeWifiDirectRecoverySnapshot = latest

    internal fun begin() {
        latest = AndroidNodeWifiDirectRecoverySnapshot(running = true)
    }

    internal fun complete(result: WifiDirectGroupRecoveryResult) {
        latest = AndroidNodeWifiDirectRecoverySnapshot(running = false, result = result)
    }
}

/** Persistent, explicitly started owner of the Android Node listener and QNN sessions. */
class AndroidNodeForegroundService : Service() {
    private var controller: LiveMachineVisionController? = null
    private var wifiDirectRecovery: AndroidWifiDirectGroupRecovery? = null
    private lateinit var desiredStateStore: AndroidNodeDesiredStateStore

    override fun onCreate() {
        super.onCreate()
        instance = this
        desiredStateStore = AndroidNodeDesiredStateStore(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            val desiredState = desiredStateStore.read()
            if (!desiredState.enabled) {
                Log.i(TAG, "state=android_node_service restore=skipped reason=not_enabled")
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            startForegroundServiceState(getString(R.string.android_node_starting))
            startNode(desiredState.environmentMode)
            Log.i(TAG, "state=android_node_service restore=started")
            return START_STICKY
        }
        return when (intent?.action) {
            ACTION_START -> {
                val environmentMode = intent.environmentMode()
                if (!desiredStateStore.enable(environmentMode)) {
                    Log.e(TAG, "state=android_node_service persistence=failed operation=enable")
                }
                startForegroundServiceState(getString(R.string.android_node_starting))
                startNode(environmentMode)
                START_STICKY
            }
            ACTION_STOP -> {
                if (!desiredStateStore.disable()) {
                    Log.e(TAG, "state=android_node_service persistence=failed operation=disable")
                }
                stopNode()
                START_NOT_STICKY
            }
            ACTION_REQUEST_MICROPHONE -> {
                restoreControllerIfDesired()
                controller?.requestMicrophone()
                restartDisposition()
            }
            ACTION_REQUEST_AMBIENT_PROFILE -> {
                restoreControllerIfDesired()
                controller?.requestAmbientSoundProfile()
                restartDisposition()
            }
            ACTION_PLAY_BRAND_SEQUENCE -> {
                restoreControllerIfDesired()
                controller?.playRokidBrandSequence()
                restartDisposition()
            }
            ACTION_FOCUS_COMMAND -> {
                restoreControllerIfDesired()
                intent.focusCommand()?.let { controller?.handleFocusCommand(it) }
                restartDisposition()
            }
            ACTION_RECOVER_WIFI_DIRECT -> {
                startForegroundServiceState(getString(R.string.android_node_starting))
                recoverWifiDirectGroup(
                    preferredBand = intent.wifiDirectRecoveryBand(),
                    recoveryMode = intent.wifiDirectRecoveryMode(),
                )
                START_STICKY
            }
            else -> {
                Log.w(TAG, "state=android_node_service action=rejected")
                stopSelfResult(startId)
                START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val recovery = wifiDirectRecovery
        wifiDirectRecovery = null
        recovery?.close()
        controller?.close()
        controller = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    @Synchronized
    private fun startNode(environmentMode: EnvironmentSelectionMode) {
        if (wifiDirectRecovery != null) return
        val existing = controller
        if (existing?.snapshot()?.phase in ACTIVE_NODE_PHASES) {
            existing?.snapshot()?.let(AndroidNodeRuntimeState::publish)
            return
        }
        existing?.close()
        val replacement = LiveMachineVisionController(
            context = applicationContext,
            onFocusState = AndroidNodeRuntimeState::publishFocus,
            onStatus = { status ->
                AndroidNodeRuntimeState.publish(status)
                updateNotification(status.accessibleSummary())
            },
        )
        controller = replacement
        runCatching {
            replacement.start(
                LiveMachineVisionTestSpec(
                    environmentMode = environmentMode,
                    runMode = LiveMachineVisionRunMode.PERSISTENT_NODE,
                ),
            )
        }.onFailure {
            Log.e(TAG, "state=android_node_service start=failed")
            replacement.close()
        }
    }

    @Synchronized
    private fun stopNode() {
        val recovery = wifiDirectRecovery
        wifiDirectRecovery = null
        recovery?.close()
        controller?.close()
        controller = null
        AndroidNodeRuntimeState.publishFocus(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Synchronized
    private fun recoverWifiDirectGroup(
        preferredBand: WifiDirectRecoveryBand,
        recoveryMode: WifiDirectRecoveryMode,
    ) {
        if (wifiDirectRecovery != null) {
            Log.i(TAG, "state=wifi_direct_recovery result=already_running")
            return
        }
        controller?.close()
        controller = null
        AndroidNodeRuntimeState.publishFocus(null)
        AndroidNodeWifiDirectRecoveryState.begin()
        updateNotification(getString(R.string.android_node_wifi_direct_recovery))

        lateinit var recovery: AndroidWifiDirectGroupRecovery
        recovery = AndroidWifiDirectGroupRecovery(
            applicationContext,
            preferredBand,
            recoveryMode,
        ) { result ->
            synchronized(this) {
                if (wifiDirectRecovery === recovery) {
                    wifiDirectRecovery = null
                    AndroidNodeWifiDirectRecoveryState.complete(result)
                    Log.i(
                        TAG,
                        "state=wifi_direct_recovery result=${result.outcome.name.lowercase()} " +
                            "removed_existing=${result.removedExistingGroup}",
                    )
                    val desiredState = desiredStateStore.read()
                    if (desiredState.enabled) {
                        updateNotification(getString(R.string.android_node_wifi_direct_recovery_complete))
                        startNode(desiredState.environmentMode)
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        wifiDirectRecovery = recovery
        runCatching { recovery.start() }.onFailure {
            wifiDirectRecovery = null
            recovery.close()
            val result = WifiDirectGroupRecoveryResult(
                WifiDirectGroupRecoveryOutcome.VALIDATION_FAILED,
                removedExistingGroup = false,
            )
            AndroidNodeWifiDirectRecoveryState.complete(result)
            Log.e(TAG, "state=wifi_direct_recovery result=start_failed")
            val desiredState = desiredStateStore.read()
            if (desiredState.enabled) startNode(desiredState.environmentMode)
        }
    }

    private fun startForegroundServiceState(summary: String) {
        val notification = notification(summary)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun restartDisposition(): Int =
        if (desiredStateStore.read().enabled) START_STICKY else START_NOT_STICKY

    private fun restoreControllerIfDesired() {
        if (controller != null) return
        val desiredState = desiredStateStore.read()
        if (!desiredState.enabled) return
        startForegroundServiceState(getString(R.string.android_node_starting))
        startNode(desiredState.environmentMode)
    }

    private fun updateNotification(summary: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(summary),
        )
    }

    private fun notification(summary: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LauncherActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AndroidNodeForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentTitle(getString(R.string.android_node_notification_title))
            .setContentText(summary.take(MAX_NOTIFICATION_SUMMARY_CHARACTERS))
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.android_node_notification_stop), stopIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.android_node_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.android_node_notification_description)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun Intent.environmentMode(): EnvironmentSelectionMode =
        getStringExtra(EXTRA_ENVIRONMENT_MODE)
            ?.let { value -> runCatching { EnvironmentSelectionMode.valueOf(value) }.getOrNull() }
            ?: EnvironmentSelectionMode.AUTOMATIC

    private fun Intent.focusCommand(): SpatialFocusCommand? =
        getStringExtra(EXTRA_FOCUS_COMMAND)
            ?.let { value -> runCatching { SpatialFocusCommand.valueOf(value) }.getOrNull() }

    private fun Intent.wifiDirectRecoveryBand(): WifiDirectRecoveryBand =
        getStringExtra(EXTRA_WIFI_DIRECT_RECOVERY_BAND)
            ?.let { value -> runCatching { WifiDirectRecoveryBand.valueOf(value) }.getOrNull() }
            ?: WifiDirectRecoveryBand.TWO_GHZ

    private fun Intent.wifiDirectRecoveryMode(): WifiDirectRecoveryMode =
        getStringExtra(EXTRA_WIFI_DIRECT_RECOVERY_MODE)
            ?.let { value -> runCatching { WifiDirectRecoveryMode.valueOf(value) }.getOrNull() }
            ?: WifiDirectRecoveryMode.RECREATE_AUTONOMOUS

    companion object {
        private const val TAG = "ConceptFlowHost"
        private const val NOTIFICATION_CHANNEL_ID = "conceptflow_android_node"
        private const val NOTIFICATION_ID = 2301
        private const val MAX_NOTIFICATION_SUMMARY_CHARACTERS = 512
        private const val ACTION_START = "org.conceptflow.mpl.host.action.START_ANDROID_NODE"
        private const val ACTION_STOP = "org.conceptflow.mpl.host.action.STOP_ANDROID_NODE"
        private const val ACTION_REQUEST_MICROPHONE =
            "org.conceptflow.mpl.host.action.REQUEST_ROKID_MICROPHONE"
        private const val ACTION_REQUEST_AMBIENT_PROFILE =
            "org.conceptflow.mpl.host.action.REQUEST_AMBIENT_PROFILE"
        private const val ACTION_PLAY_BRAND_SEQUENCE =
            "org.conceptflow.mpl.host.action.PLAY_ROKID_BRAND_SEQUENCE"
        private const val ACTION_FOCUS_COMMAND = "org.conceptflow.mpl.host.action.FOCUS_COMMAND"
        private const val ACTION_RECOVER_WIFI_DIRECT =
            "org.conceptflow.mpl.host.action.RECOVER_WIFI_DIRECT"
        private const val EXTRA_ENVIRONMENT_MODE = "environment_mode"
        private const val EXTRA_FOCUS_COMMAND = "focus_command"
        private const val EXTRA_WIFI_DIRECT_RECOVERY_BAND = "wifi_direct_recovery_band"
        private const val EXTRA_WIFI_DIRECT_RECOVERY_MODE = "wifi_direct_recovery_mode"
        private const val MAXIMUM_FOCUS_COMMAND_SEQUENCE = 6
        private val ACTIVE_NODE_PHASES = setOf(
            LiveMachineVisionPhase.OPENING_QNN_HTP,
            LiveMachineVisionPhase.LISTENING,
            LiveMachineVisionPhase.STREAMING,
        )

        @Volatile
        private var instance: AndroidNodeForegroundService? = null

        fun start(context: Context, environmentMode: EnvironmentSelectionMode) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AndroidNodeForegroundService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_ENVIRONMENT_MODE, environmentMode.name),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AndroidNodeForegroundService::class.java).setAction(ACTION_STOP),
            )
        }

        fun requestMicrophone(context: Context) {
            context.startService(
                Intent(context, AndroidNodeForegroundService::class.java)
                    .setAction(ACTION_REQUEST_MICROPHONE),
            )
        }

        fun requestAmbientProfile(context: Context) {
            context.startService(
                Intent(context, AndroidNodeForegroundService::class.java)
                    .setAction(ACTION_REQUEST_AMBIENT_PROFILE),
            )
        }

        fun playBrandSequence(context: Context) {
            context.startService(
                Intent(context, AndroidNodeForegroundService::class.java)
                    .setAction(ACTION_PLAY_BRAND_SEQUENCE),
            )
        }

        fun focusCommand(context: Context, command: SpatialFocusCommand) {
            context.startService(
                Intent(context, AndroidNodeForegroundService::class.java)
                    .setAction(ACTION_FOCUS_COMMAND)
                    .putExtra(EXTRA_FOCUS_COMMAND, command.name),
            )
        }

        fun recoverWifiDirect(
            context: Context,
            preferredBand: WifiDirectRecoveryBand = WifiDirectRecoveryBand.TWO_GHZ,
            recoveryMode: WifiDirectRecoveryMode = WifiDirectRecoveryMode.RECREATE_AUTONOMOUS,
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AndroidNodeForegroundService::class.java)
                    .setAction(ACTION_RECOVER_WIFI_DIRECT)
                    .putExtra(EXTRA_WIFI_DIRECT_RECOVERY_BAND, preferredBand.name)
                    .putExtra(EXTRA_WIFI_DIRECT_RECOVERY_MODE, recoveryMode.name),
            )
        }

        /** Synchronous shell-only provider hook; the controller and focus manager are synchronized. */
        internal fun focusCommandSequenceNow(
            commands: List<SpatialFocusCommand>,
        ): SpatialFocusState? {
            require(commands.isNotEmpty() && commands.size <= MAXIMUM_FOCUS_COMMAND_SEQUENCE)
            val activeController = instance?.controller ?: return null
            var state: SpatialFocusState? = null
            for (command in commands) {
                state = activeController.handleFocusCommand(command) ?: return null
            }
            return state
        }

        fun offerGnss(sample: GnssQualitySample): Boolean =
            instance?.controller?.updateGnss(sample) ?: false
    }
}

internal data class AndroidNodeDesiredState(
    val enabled: Boolean,
    val environmentMode: EnvironmentSelectionMode,
)

internal object AndroidNodeRestartPolicy {
    fun restore(enabled: Boolean, serializedEnvironmentMode: String?): AndroidNodeDesiredState =
        AndroidNodeDesiredState(
            enabled = enabled,
            environmentMode = serializedEnvironmentMode
                ?.let { value -> runCatching { EnvironmentSelectionMode.valueOf(value) }.getOrNull() }
                ?: EnvironmentSelectionMode.AUTOMATIC,
        )
}

internal class AndroidNodeDesiredStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun enable(environmentMode: EnvironmentSelectionMode): Boolean =
        preferences.edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_ENVIRONMENT_MODE, environmentMode.name)
            .commit()

    fun disable(): Boolean =
        preferences.edit()
            .putBoolean(KEY_ENABLED, false)
            .remove(KEY_ENVIRONMENT_MODE)
            .commit()

    fun read(): AndroidNodeDesiredState = AndroidNodeRestartPolicy.restore(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        serializedEnvironmentMode = preferences.getString(KEY_ENVIRONMENT_MODE, null),
    )

    private companion object {
        const val PREFERENCES_NAME = "android_node_runtime"
        const val KEY_ENABLED = "enabled"
        const val KEY_ENVIRONMENT_MODE = "environment_mode"
    }
}
