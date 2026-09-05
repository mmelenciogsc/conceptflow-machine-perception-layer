// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import org.conceptflow.mpl.rokid.core.IdleControlActivationCoordinator
import org.conceptflow.mpl.rokid.core.IdleControlActivationDecision
import org.conceptflow.mpl.rokid.core.RuntimeCommand
import org.conceptflow.mpl.rokid.core.RuntimeCommandAuthorization
import org.conceptflow.mpl.rokid.core.VisibleServiceActivation

class RokidCommandActivity : Activity(), android.content.ServiceConnection {
    private var runtime: RokidRuntimeService.RuntimeBinder? = null
    private var bound = false
    private var pendingCommand: RuntimeCommand? = null
    private var activityResumed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceActivation: IdleControlActivationCoordinator? = null
    private var activeVisibleService: VisibleServiceActivation? = null
    private var delayedActivation: Runnable? = null
    private var armedStateVerification: Runnable? = null
    private var cameraEligibilityGuard: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCommand = authorizedCommand(intent?.action)
        if (pendingCommand == null) {
            Log.w(TAG, "state=rejected reason=unknown_command")
            finishAndRemoveTask()
            return
        }
        val visibleService = VisibleServiceActivation.fromCommand(pendingCommand)
        if (visibleService != null) {
            prepareVisibleCameraEligibilityWindow()
            beginVisibleServiceActivation(visibleService)
            return
        }
        if (pendingCommand?.requiresVisibleWake == true) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (pendingCommand?.requiresVisibleWake == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        bindRuntimeService()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        serviceActivation?.let { handleActivationDecision(it.onResumed()) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val command = authorizedCommand(intent.action)
        if (command == null) {
            Log.w(TAG, "state=rejected reason=unknown_command")
            return
        }
        pendingCommand = command
        val visibleService = VisibleServiceActivation.fromCommand(command)
        if (visibleService != null) {
            prepareVisibleCameraEligibilityWindow()
            beginVisibleServiceActivation(visibleService)
        } else if (command.requiresVisibleWake) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            cancelVisibleServiceActivation()
            runtime?.let { dispatch(it, command) } ?: bindRuntimeService()
        } else {
            cancelVisibleServiceActivation()
            runtime?.let { dispatch(it, command) } ?: bindRuntimeService()
        }
    }

    /**
     * Keeps YodaOS's logical display awake while the nonvisual broker remains armed.
     *
     * This target revokes Camera2 access when the broker's UID becomes idle, even when the
     * associated service was originally promoted from a visible activity. The window is
     * transparent and input-pass-through after activation, so this flag preserves camera
     * eligibility without introducing a visual or touch surface. Android releases the flag when
     * the Activity is destroyed after idle control is disabled.
     */
    private fun prepareVisibleCameraEligibilityWindow() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val binder = service as? RokidRuntimeService.RuntimeBinder
        if (binder == null) {
            Log.e(TAG, "state=rejected reason=unexpected_service_binder")
            finishAndRemoveTask()
            return
        }
        runtime = binder
        bound = true
        if (serviceActivation != null) {
            verifyVisibleServiceArmedState()
        } else {
            pendingCommand?.let { dispatch(binder, it) }
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        runtime = null
        bound = false
        finishAndRemoveTask()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        serviceActivation?.let { handleActivationDecision(it.onWindowFocusChanged(hasFocus)) }
    }

    override fun onPause() {
        activityResumed = false
        serviceActivation?.let { coordinator ->
            handleActivationDecision(coordinator.onPaused())
            delayedActivation?.let(mainHandler::removeCallbacks)
            delayedActivation = null
        }
        super.onPause()
    }

    override fun onDestroy() {
        cameraEligibilityGuard?.let(mainHandler::removeCallbacks)
        cameraEligibilityGuard = null
        cancelVisibleServiceActivation()
        if (bound) {
            unbindService(this)
            bound = false
        }
        runtime = null
        super.onDestroy()
    }

    private fun dispatch(binder: RokidRuntimeService.RuntimeBinder, command: RuntimeCommand) {
        pendingCommand = null
        if (command == RuntimeCommand.ENABLE_IDLE_CONTROL) {
            beginVisibleServiceActivation(VisibleServiceActivation.IDLE_CONTROL)
            return
        }
        cancelVisibleServiceActivation()
        binder.execute(command) {
            runOnUiThread {
                finishAndRemoveTask()
            }
        }
    }

    private fun beginVisibleServiceActivation(activation: VisibleServiceActivation) {
        cancelVisibleServiceActivation()
        pendingCommand = activation.command
        activeVisibleService = activation
        serviceActivation = IdleControlActivationCoordinator().also { coordinator ->
            handleActivationDecision(coordinator.onWindowFocusChanged(hasWindowFocus()))
            if (activityResumed) handleActivationDecision(coordinator.onResumed())
        }
    }

    private fun handleActivationDecision(decision: IdleControlActivationDecision) {
        when (decision) {
            IdleControlActivationDecision.SCHEDULE_START -> scheduleVisibleActivityStart()
            IdleControlActivationDecision.START_SERVICE_AND_BIND -> startAndBindVisibleService()
            IdleControlActivationDecision.RETRY_ARMED_STATE -> scheduleArmedStateVerification()
            IdleControlActivationDecision.COMPLETE -> completeVisibleServiceActivation()
            IdleControlActivationDecision.FAILED_CLOSED ->
                failVisibleServiceSetup("service_not_armed")
            IdleControlActivationDecision.WAIT_FOR_VISIBILITY -> Unit
        }
    }

    private fun scheduleVisibleActivityStart() {
        delayedActivation?.let(mainHandler::removeCallbacks)
        val activation = Runnable {
            delayedActivation = null
            serviceActivation?.let { handleActivationDecision(it.onActivationDelayElapsed()) }
        }
        delayedActivation = activation
        mainHandler.postDelayed(activation, VISIBLE_ACTIVITY_SETTLE_MS)
    }

    private fun startAndBindVisibleService() {
        val activation = activeVisibleService ?: run {
            failVisibleServiceSetup("missing_activation")
            return
        }
        val serviceIntent = Intent(this, RokidRuntimeService::class.java)
            .setAction(activation.command.action)
        val started = runCatching { startForegroundService(serviceIntent) }
        if (started.isFailure) {
            Log.e(
                TAG,
                "state=${activation.logState} reason=visible_activity_service_start_rejected",
            )
            failVisibleServiceSetup("service_start_rejected")
            return
        }
        if (!bound && !bindRuntimeService()) return
        verifyVisibleServiceArmedState()
    }

    private fun verifyVisibleServiceArmedState() {
        if (armedStateVerification != null) return
        val verification = object : Runnable {
            override fun run() {
                armedStateVerification = null
                val coordinator = serviceActivation ?: return
                val armed = when (activeVisibleService) {
                    VisibleServiceActivation.IDLE_CONTROL,
                    VisibleServiceActivation.SAME_BOOT_RECOVERY,
                    VisibleServiceActivation.PERSISTED_BOOT_RECOVERY,
                    -> runtime?.isIdleControlArmed() == true
                    null -> false
                }
                handleActivationDecision(coordinator.observeArmedState(armed))
            }
        }
        armedStateVerification = verification
        mainHandler.post(verification)
    }

    private fun scheduleArmedStateVerification() {
        if (armedStateVerification != null) return
        val verification = Runnable {
            armedStateVerification = null
            verifyVisibleServiceArmedState()
        }
        armedStateVerification = verification
        mainHandler.postDelayed(verification, ARM_VERIFICATION_RETRY_MS)
    }

    private fun completeVisibleServiceActivation() {
        val activation = activeVisibleService ?: run {
            failVisibleServiceSetup("missing_activation")
            return
        }
        if (activation == VisibleServiceActivation.IDLE_CONTROL) {
            val binder = runtime
            if (binder == null || !binder.completeIdleControlEnable()) {
                failVisibleServiceSetup("armed_state_changed")
                return
            }
        }
        cancelVisibleServiceActivation()
        pendingCommand = null
        when (activation) {
            VisibleServiceActivation.IDLE_CONTROL -> Log.i(
                TAG,
                "state=idle_control_enable_complete capture_authority=poco_authenticated_session",
            )
            VisibleServiceActivation.SAME_BOOT_RECOVERY -> Log.i(
                TAG,
                "state=same_boot_recovery_broker result=activation_completed",
            )
            VisibleServiceActivation.PERSISTED_BOOT_RECOVERY -> Log.i(
                TAG,
                "state=persisted_boot_recovery_broker result=activation_completed",
            )
        }
        keepCameraEligibilityVisible()
    }

    private fun keepCameraEligibilityVisible() {
        if (cameraEligibilityGuard != null) return
        // Apply pass-through only after the visible broker has gained focus and completed the
        // foreground-service handshake; applying FLAG_NOT_FOCUSABLE earlier prevents activation.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        lateinit var guard: Runnable
        guard = Runnable {
            if (runtime?.isIdleControlArmed() == true) {
                mainHandler.postDelayed(guard, CAMERA_ELIGIBILITY_POLL_MS)
            } else {
                cameraEligibilityGuard = null
                finishAndRemoveTask()
            }
        }
        cameraEligibilityGuard = guard
        mainHandler.postDelayed(guard, CAMERA_ELIGIBILITY_POLL_MS)
        Log.i(TAG, "state=camera_eligibility_guard result=active input_passthrough=true")
    }

    private fun bindRuntimeService(): Boolean {
        if (bound) return true
        val connected = bindService(
            Intent(this, RokidRuntimeService::class.java),
            this,
            Context.BIND_AUTO_CREATE,
        )
        if (!connected) {
            Log.e(TAG, "state=rejected reason=service_bind_failed")
            if (serviceActivation != null) {
                failVisibleServiceSetup("service_bind_failed")
            } else {
                finishAndRemoveTask()
            }
        }
        return connected
    }

    private fun cancelVisibleServiceActivation() {
        delayedActivation?.let(mainHandler::removeCallbacks)
        delayedActivation = null
        armedStateVerification?.let(mainHandler::removeCallbacks)
        armedStateVerification = null
        serviceActivation = null
        activeVisibleService = null
    }

    private fun failVisibleServiceSetup(reason: String) {
        val logState = activeVisibleService?.logState ?: "visible_service_activity_unavailable"
        cancelVisibleServiceActivation()
        Log.e(TAG, "state=$logState reason=$reason")
        finishAndRemoveTask()
    }

    private val VisibleServiceActivation.logState: String
        get() = when (this) {
            VisibleServiceActivation.IDLE_CONTROL -> "idle_control_activity_unavailable"
            VisibleServiceActivation.SAME_BOOT_RECOVERY -> "same_boot_recovery_activity_unavailable"
            VisibleServiceActivation.PERSISTED_BOOT_RECOVERY ->
                "persisted_boot_recovery_activity_unavailable"
        }

    private fun authorizedCommand(action: String?): RuntimeCommand? {
        val command = RuntimeCommand.fromAction(action) ?: return null
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!RuntimeCommandAuthorization.isAllowed(command, debuggable)) {
            Log.w(TAG, "state=rejected reason=debug_only_command")
            return null
        }
        return command
    }

    private val RuntimeCommand.requiresVisibleWake: Boolean
        get() = this != RuntimeCommand.DISABLE_IDLE_CONTROL &&
            this != RuntimeCommand.ENABLE_VALIDATED_GESTURE_COMMANDS &&
            this != RuntimeCommand.DISABLE_GESTURE_COMMANDS &&
            this != RuntimeCommand.ENABLE_LEGACY_SENSOR_SPOOL &&
            this != RuntimeCommand.DISABLE_LEGACY_SENSOR_SPOOL

    companion object {
        private const val TAG = "ConceptFlowRokid"
        private const val VISIBLE_ACTIVITY_SETTLE_MS = 150L
        private const val ARM_VERIFICATION_RETRY_MS = 150L
        private const val CAMERA_ELIGIBILITY_POLL_MS = 1_000L
    }
}
