// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

enum class IdleControlActivationDecision {
    WAIT_FOR_VISIBILITY,
    SCHEDULE_START,
    START_SERVICE_AND_BIND,
    RETRY_ARMED_STATE,
    COMPLETE,
    FAILED_CLOSED,
}

enum class VisibleServiceActivation(val command: RuntimeCommand) {
    IDLE_CONTROL(RuntimeCommand.ENABLE_IDLE_CONTROL),
    SAME_BOOT_RECOVERY(RuntimeCommand.RECOVER_SAME_BOOT),
    PERSISTED_BOOT_RECOVERY(RuntimeCommand.RECOVER_PERSISTED_BOOT),
    ;

    companion object {
        fun fromCommand(command: RuntimeCommand?): VisibleServiceActivation? =
            entries.firstOrNull { it.command == command }
    }
}

/**
 * Pure state machine that prevents a background Activity from attempting a YodaOS service start.
 * The delayed start is valid only while the Activity remains resumed and window-focused, and service
 * promotion must become observable within a bounded number of verification attempts.
 */
class IdleControlActivationCoordinator(
    private val maximumVerificationAttempts: Int = DEFAULT_MAXIMUM_VERIFICATION_ATTEMPTS,
) {
    private var resumed = false
    private var focused = false
    private var startScheduled = false
    private var startIssued = false
    private var terminal = false
    private var verificationAttempts = 0

    init {
        require(maximumVerificationAttempts > 0)
    }

    fun onResumed(): IdleControlActivationDecision {
        resumed = true
        return visibilityDecision()
    }

    fun onPaused(): IdleControlActivationDecision {
        resumed = false
        if (!startIssued) startScheduled = false
        return IdleControlActivationDecision.WAIT_FOR_VISIBILITY
    }

    fun onWindowFocusChanged(hasFocus: Boolean): IdleControlActivationDecision {
        focused = hasFocus
        if (!hasFocus && !startIssued) startScheduled = false
        return visibilityDecision()
    }

    fun onActivationDelayElapsed(): IdleControlActivationDecision {
        if (terminal || startIssued || !startScheduled || !resumed || !focused) {
            startScheduled = false
            return IdleControlActivationDecision.WAIT_FOR_VISIBILITY
        }
        startScheduled = false
        startIssued = true
        return IdleControlActivationDecision.START_SERVICE_AND_BIND
    }

    fun observeArmedState(armed: Boolean): IdleControlActivationDecision {
        if (terminal) return IdleControlActivationDecision.FAILED_CLOSED
        check(startIssued) { "service state cannot be observed before activation starts" }
        if (armed) {
            terminal = true
            return IdleControlActivationDecision.COMPLETE
        }
        verificationAttempts += 1
        if (verificationAttempts < maximumVerificationAttempts) {
            return IdleControlActivationDecision.RETRY_ARMED_STATE
        }
        terminal = true
        return IdleControlActivationDecision.FAILED_CLOSED
    }

    private fun visibilityDecision(): IdleControlActivationDecision {
        if (terminal || startIssued || startScheduled || !resumed || !focused) {
            return IdleControlActivationDecision.WAIT_FOR_VISIBILITY
        }
        startScheduled = true
        return IdleControlActivationDecision.SCHEDULE_START
    }

    companion object {
        const val DEFAULT_MAXIMUM_VERIFICATION_ATTEMPTS = 10
    }
}
