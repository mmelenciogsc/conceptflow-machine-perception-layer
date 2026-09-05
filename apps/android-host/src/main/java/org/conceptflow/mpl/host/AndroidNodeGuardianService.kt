// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.conceptflow.mpl.androidhost.LauncherActivity
import org.conceptflow.mpl.host.R

/**
 * Tiny process-level guardian for the foreground sensor owner.
 *
 * It owns no sensor, model, network, Unity, or FMOD resource. Its sole job is to retain a Binder
 * death boundary outside the memory-heavy Android Node process and restore that process only while
 * the user's durable enabled state remains true. This compensates for the observed HyperOS build
 * not honoring START_STICKY after a LOW_MEMORY process termination.
 */
class AndroidNodeGuardianService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var desiredStateStore: AndroidNodeDesiredStateStore
    private var bound = false
    private var binding = false
    private var stopping = false
    private var recoveryScheduled = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binding = false
            bound = service != null
            recoveryScheduled = false
            if (service == null) handleMainProcessLoss("null_binder")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleMainProcessLoss("disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            handleMainProcessLoss("binding_died")
        }

        override fun onNullBinding(name: ComponentName?) {
            handleMainProcessLoss("null_binding")
        }
    }

    override fun onCreate() {
        super.onCreate()
        desiredStateStore = AndroidNodeDesiredStateStore(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            unbindMain()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val desiredState = desiredStateStore.read()
        if (!desiredState.enabled) {
            stopping = true
            unbindMain()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        startForegroundServiceState()
        // A null intent is Android restoring this sticky guardian after its own process loss.
        // ACTION_START comes from an already-running Android Node and must not recurse.
        if (intent == null) {
            AndroidNodeForegroundService.restoreFromGuardian(this)
            scheduleBind()
        } else {
            bindMain()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        unbindMain()
        super.onDestroy()
    }

    private fun handleMainProcessLoss(reason: String) {
        if (stopping || recoveryScheduled) return
        unbindMain()
        if (!desiredStateStore.read().enabled) {
            stopping = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        recoveryScheduled = true
        Log.w(TAG, "state=guardian main_loss=$reason restore=scheduled")
        runCatching { AndroidNodeForegroundService.restoreFromGuardian(this) }
            .onFailure { Log.e(TAG, "state=guardian restore=failed") }
        scheduleBind()
    }

    private fun scheduleBind() {
        handler.removeCallbacks(bindRunnable)
        handler.postDelayed(bindRunnable, AndroidNodeGuardianRecoveryPolicy.REBIND_DELAY_MILLIS)
    }

    private val bindRunnable = Runnable {
        if (stopping) return@Runnable
        bindMain()
    }

    private fun bindMain() {
        if (bound || binding || stopping) return
        binding = true
        val accepted = runCatching {
            bindService(
                Intent(this, AndroidNodeForegroundService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!accepted) {
            binding = false
            recoveryScheduled = false
            scheduleBind()
        }
    }

    private fun unbindMain() {
        if (bound || binding) runCatching { unbindService(connection) }
        bound = false
        binding = false
    }

    private fun startForegroundServiceState() {
        val notification = notification()
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

    private fun notification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LauncherActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentTitle(getString(R.string.android_node_guardian_notification_title))
            .setContentText(getString(R.string.android_node_guardian_notification_text))
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.android_node_guardian_notification_title),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = getString(R.string.android_node_guardian_notification_text)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    companion object {
        private const val TAG = "ConceptFlowGuardian"
        private const val ACTION_START = "org.conceptflow.mpl.host.action.START_ANDROID_NODE_GUARDIAN"
        private const val ACTION_STOP = "org.conceptflow.mpl.host.action.STOP_ANDROID_NODE_GUARDIAN"
        private const val NOTIFICATION_CHANNEL_ID = "conceptflow_android_node_guardian"
        private const val NOTIFICATION_ID = 2302

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AndroidNodeGuardianService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AndroidNodeGuardianService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}

internal object AndroidNodeGuardianRecoveryPolicy {
    const val REBIND_DELAY_MILLIS = 1_000L
}
