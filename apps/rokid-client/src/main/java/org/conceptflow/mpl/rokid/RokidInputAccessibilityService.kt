// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import org.conceptflow.mpl.rokid.core.ExactRokidInputHardwarePolicy
import org.conceptflow.mpl.rokid.core.IdleControlPolicy
import org.conceptflow.mpl.rokid.core.IdleControlRecoveryAuthorization
import org.conceptflow.mpl.rokid.core.RokidCandidateInputProfile
import org.conceptflow.mpl.rokid.core.RokidInputAction
import org.conceptflow.mpl.rokid.core.RokidInputDispatchPolicy
import org.conceptflow.mpl.rokid.core.RokidInputDeviceIdentity
import org.conceptflow.mpl.rokid.core.RokidInputEvent
import org.conceptflow.mpl.rokid.core.RokidInputKey
import org.conceptflow.mpl.rokid.core.RokidInputSequenceStateMachine
import org.conceptflow.mpl.rokid.core.RokidLocalControlCommand
import org.conceptflow.mpl.rokid.core.RokidTouchEventHub
import org.conceptflow.mpl.rokid.core.uptimeMillisToElapsedRealtimeNanos
import java.util.concurrent.atomic.AtomicLong

class RokidInputAccessibilityService : AccessibilityService() {
    private val hardwarePolicy = ExactRokidInputHardwarePolicy(
        expectedDeviceName = RokidCandidateInputProfile.DEVICE_NAME,
        expectedSource = RokidCandidateInputProfile.SOURCE_KEYBOARD,
        scanCodeByKey = RokidCandidateInputProfile.scanCodeByKey,
    )
    private val sequence = RokidInputSequenceStateMachine(hardwarePolicy)
    private val commandGateStore by lazy { RokidInputCommandGateStore(this) }
    private var systemBroadcastObserver: RokidSystemBroadcastObserver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var runtimeBound = false
    private var runtimeBinding = false
    private var recoveryRequestInFlight = false

    private val runtimeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            runtimeBinding = false
            val runtime = service as? RokidRuntimeService.RuntimeBinder
            runtimeBound = runtime != null
            if (runtime == null || !runtime.isIdleControlArmed()) requestRecoveryIfArmed()
        }

        override fun onServiceDisconnected(name: ComponentName?) = handleRuntimeLoss("disconnected")

        override fun onBindingDied(name: ComponentName?) = handleRuntimeLoss("binding_died")

        override fun onNullBinding(name: ComponentName?) = handleRuntimeLoss("null_binding")
    }

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        systemBroadcastObserver?.close()
        systemBroadcastObserver = RokidSystemBroadcastObserver(this)
        bindRuntimeObserver()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode.toRokidInputKey()
        val device = event.toDeviceIdentity()
        val inputEvent = RokidInputEvent(
            key = key,
            action = event.action.toRokidInputAction(),
            eventTimeMillis = event.eventTime,
            repeatCount = event.repeatCount,
            canceled = event.isCanceled,
            longPress = event.isLongPress,
            scanCode = event.scanCode,
            device = device,
        )
        val allowlisted = hardwarePolicy.accepts(inputEvent)
        val twoFingerLongPressProbe = RokidCandidateInputProfile.isTwoFingerLongPressProbe(
            androidKeyCode = event.keyCode,
            scanCode = event.scanCode,
            device = device,
        )
        if (key != RokidInputKey.UNRELATED) {
            logCandidateEvent(event, allowlisted, mapping = "existing_candidate")
        } else if (twoFingerLongPressProbe) {
            logCandidateEvent(event, allowlisted = false, mapping = "two_finger_long_press_probe")
        }
        if (allowlisted) {
            RokidTouchEventHub.publish(
                inputEvent,
                uptimeMillisToElapsedRealtimeNanos(
                    eventUptimeMillis = event.eventTime,
                    receiptUptimeMillis = SystemClock.uptimeMillis(),
                    receiptElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                ),
            )
        }
        val nodeActive = IdleControlModeStore(this).isEnabled()
        val command = sequence.observe(
            inputEvent,
            nodeActive = nodeActive,
        )
        if (command != null) {
            val dispatched = RokidInputDispatchPolicy.dispatchIfEnabled(
                commandsEnabled = commandGateStore.isEnabled(),
                command = command,
            ) { dispatch(it, nodeActive, event.eventTime) }
            if (!dispatched) {
                Log.i(
                    TAG,
                    "state=input_sequence command=${command.name.lowercase()} result=observe_only",
                )
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        sequence.reset()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (runtimeBound || runtimeBinding) runCatching { unbindService(runtimeConnection) }
        runtimeBound = false
        runtimeBinding = false
        systemBroadcastObserver?.close()
        systemBroadcastObserver = null
        sequence.reset()
        super.onDestroy()
    }

    private fun requestRecoveryIfArmed() {
        if (recoveryRequestInFlight) return
        val store = IdleControlModeStore(this)
        val currentBootCount = runCatching {
            Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()
        val authorization = IdleControlPolicy.recoveryAuthorization(
            enabled = store.isEnabled(),
            armedBootCount = store.armedBootCount(),
            currentBootCount = currentBootCount,
        )
        val action = when (authorization) {
            IdleControlRecoveryAuthorization.SAME_BOOT ->
                RokidRuntimeService.ACTION_RECOVER_SAME_BOOT
            IdleControlRecoveryAuthorization.PERSISTED_NEW_BOOT ->
                RokidRuntimeService.ACTION_RECOVER_PERSISTED_BOOT
            IdleControlRecoveryAuthorization.NOT_AUTHORIZED -> {
                Log.i(TAG, "state=system_bound_recovery result=not_authorized")
                return
            }
        }
        recoveryRequestInFlight = true
        runCatching {
            // YodaOS rejects Service.startForeground() when a killed process is recreated only in
            // the background, even though this system-bound accessibility service is restarted.
            // Re-enter through the same short-lived nonvisual Activity broker used for explicit
            // arming so the runtime receives valid foreground-start eligibility. The runtime
            // independently rechecks the same-boot capability before opening the network.
            startActivity(
                Intent(this, RokidCommandActivity::class.java)
                    .setAction(action)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
        }.onSuccess {
            Log.i(
                TAG,
                "state=system_bound_recovery authorization=${authorization.name.lowercase()} " +
                    "result=visible_broker_requested",
            )
        }.onFailure {
            recoveryRequestInFlight = false
            Log.w(
                TAG,
                "state=system_bound_recovery authorization=${authorization.name.lowercase()} " +
                    "result=rejected",
            )
        }
        mainHandler.postDelayed(
            { recoveryRequestInFlight = false },
            RECOVERY_REQUEST_DEDUPLICATION_MILLIS,
        )
    }

    private fun handleRuntimeLoss(reason: String) {
        runtimeBound = false
        runtimeBinding = false
        Log.w(TAG, "state=runtime_observer main_loss=$reason recovery=scheduled")
        mainHandler.removeCallbacks(runtimeRebind)
        mainHandler.postDelayed(runtimeRebind, RUNTIME_REBIND_DELAY_MILLIS)
    }

    private val runtimeRebind = Runnable { bindRuntimeObserver() }

    private fun bindRuntimeObserver() {
        if (runtimeBound || runtimeBinding) return
        runtimeBinding = true
        val accepted = runCatching {
            bindService(
                Intent(this, RokidRuntimeService::class.java),
                runtimeConnection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!accepted) {
            runtimeBinding = false
            mainHandler.removeCallbacks(runtimeRebind)
            mainHandler.postDelayed(runtimeRebind, RUNTIME_REBIND_DELAY_MILLIS)
        }
    }

    private fun dispatch(
        command: RokidLocalControlCommand,
        nodeActive: Boolean,
        eventTimeMillis: Long,
    ) {
        if (command == RokidLocalControlCommand.DISABLE_NODE && !nodeActive) {
            Log.i(TAG, "state=local_control_dispatch command=disable_node result=already_disabled")
            return
        }
        val observedMonotonicNs = uptimeMillisToElapsedRealtimeNanos(
            eventUptimeMillis = eventTimeMillis,
            receiptUptimeMillis = SystemClock.uptimeMillis(),
            receiptElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
        )
        val intent = Intent(this, RokidRuntimeService::class.java)
            .setAction(command.action)
            .putExtra(RokidRuntimeService.EXTRA_GESTURE_OBSERVED_MONOTONIC_NS, observedMonotonicNs)
        val result = runCatching {
            startForegroundService(intent)
        }
        if (result.isSuccess) {
            Log.i(
                TAG,
                "state=local_control_dispatch command=${command.name.lowercase()} " +
                    "result=requested physical_validation=required",
            )
        } else {
            Log.w(
                TAG,
                "state=local_control_dispatch command=${command.name.lowercase()} result=rejected",
            )
        }
    }

    private fun logCandidateEvent(
        event: KeyEvent,
        allowlisted: Boolean,
        mapping: String,
    ) {
        val device = event.device
        Log.i(
            TAG,
            "state=input_candidate mapping=$mapping sequence=${candidateEvents.incrementAndGet()} " +
                "key_code=${event.keyCode} action=${event.action.toDiagnosticAction()} " +
                "repeat=${event.repeatCount} canceled=${event.isCanceled} " +
                "long_press=${event.isLongPress} scan_code=${event.scanCode} " +
                "device_id=${event.deviceId} " +
                "vendor_id=${device?.vendorId ?: -1} product_id=${device?.productId ?: -1} " +
                "source=${event.source} allowlisted=$allowlisted",
        )
    }

    private fun KeyEvent.toDeviceIdentity(): RokidInputDeviceIdentity = RokidInputDeviceIdentity(
        deviceId = deviceId,
        source = source,
        deviceSources = device?.sources ?: 0,
        name = device?.name.orEmpty(),
        isVirtual = device?.isVirtual ?: true,
        vendorId = device?.vendorId ?: -1,
        productId = device?.productId ?: -1,
    )

    private fun Int.toRokidInputKey(): RokidInputKey =
        RokidCandidateInputProfile.keyByAndroidKeyCode[this] ?: RokidInputKey.UNRELATED

    private fun Int.toRokidInputAction(): RokidInputAction = when (this) {
        KeyEvent.ACTION_DOWN -> RokidInputAction.DOWN
        KeyEvent.ACTION_UP -> RokidInputAction.UP
        else -> RokidInputAction.OTHER
    }

    private fun Int.toDiagnosticAction(): String = when (this) {
        KeyEvent.ACTION_DOWN -> "down"
        KeyEvent.ACTION_UP -> "up"
        else -> "other"
    }

    companion object {
        private const val TAG = "ConceptFlowRokidInput"
        private const val RUNTIME_REBIND_DELAY_MILLIS = 1_000L
        private const val RECOVERY_REQUEST_DEDUPLICATION_MILLIS = 10_000L
        private val candidateEvents = AtomicLong(0L)
    }
}
