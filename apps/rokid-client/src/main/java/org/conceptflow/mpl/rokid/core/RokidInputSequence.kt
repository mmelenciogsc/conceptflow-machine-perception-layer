// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

enum class RokidInputKey {
    PREAMBLE,
    SWIPE_FORWARD,
    SWIPE_BACKWARD,
    SINGLE_TAP,
    DOUBLE_TAP,
    UNRELATED,
}

enum class RokidInputAction {
    DOWN,
    UP,
    OTHER,
}

data class RokidInputEvent(
    val key: RokidInputKey,
    val action: RokidInputAction,
    val eventTimeMillis: Long,
    val repeatCount: Int = 0,
    val canceled: Boolean,
    val longPress: Boolean,
    val scanCode: Int,
    val device: RokidInputDeviceIdentity,
)

data class RokidInputDeviceIdentity(
    val deviceId: Int,
    val source: Int,
    val deviceSources: Int,
    val name: String,
    val isVirtual: Boolean,
    val vendorId: Int,
    val productId: Int,
)

fun interface RokidInputHardwarePolicy {
    fun accepts(event: RokidInputEvent): Boolean
}

class ExactRokidInputHardwarePolicy(
    private val expectedDeviceName: String,
    private val expectedSource: Int,
    private val scanCodeByKey: Map<RokidInputKey, Int>,
) : RokidInputHardwarePolicy {
    override fun accepts(event: RokidInputEvent): Boolean =
        event.device.name == expectedDeviceName &&
            !event.device.isVirtual &&
            event.device.source == expectedSource &&
            event.device.deviceSources == expectedSource &&
            scanCodeByKey[event.key] == event.scanCode
}

/**
 * Candidate Android translations of the verified raw PSOC scan codes. The device identity and
 * source come from dumpsys input; the complete mapping still needs an on-device onKeyEvent trace.
 */
object RokidCandidateInputProfile {
    const val DEVICE_NAME = "ROKID,PSOC-TP-R"
    const val SOURCE_KEYBOARD = 0x00000101
    const val TWO_FINGER_LONG_PRESS_ANDROID_KEY_CODE = 176
    const val TWO_FINGER_LONG_PRESS_SCAN_CODE = 149

    val keyByAndroidKeyCode = mapOf(
        83 to RokidInputKey.PREAMBLE,
        24 to RokidInputKey.SWIPE_FORWARD,
        25 to RokidInputKey.SWIPE_BACKWARD,
        186 to RokidInputKey.SINGLE_TAP,
        185 to RokidInputKey.DOUBLE_TAP,
    )

    val scanCodeByKey = mapOf(
        RokidInputKey.PREAMBLE to 204,
        RokidInputKey.SWIPE_FORWARD to 115,
        RokidInputKey.SWIPE_BACKWARD to 114,
        RokidInputKey.SINGLE_TAP to 148,
        RokidInputKey.DOUBLE_TAP to 400,
    )

    /**
     * Observe-only probe for the Settings key emitted by the RV203 touch controller. Physical
     * getevent tracing correlates scan code 149 / KEY_PROG2 with the two-finger-hold broadcast,
     * but the validated firmware does not deliver that key through AccessibilityService. Keeping
     * this probe allows a future firmware change to be detected without consuming the event.
     */
    fun isTwoFingerLongPressProbe(
        androidKeyCode: Int,
        scanCode: Int,
        device: RokidInputDeviceIdentity,
    ): Boolean =
        androidKeyCode == TWO_FINGER_LONG_PRESS_ANDROID_KEY_CODE &&
            scanCode == TWO_FINGER_LONG_PRESS_SCAN_CODE &&
            device.name == DEVICE_NAME &&
            !device.isVirtual &&
            device.source == SOURCE_KEYBOARD &&
            device.deviceSources == SOURCE_KEYBOARD
}

enum class RokidLocalControlCommand(val action: String) {
    ENABLE_NODE("org.conceptflow.mpl.rokid.internal.ACCESSIBILITY_ENABLE"),
    DISABLE_NODE("org.conceptflow.mpl.rokid.internal.ACCESSIBILITY_DISABLE"),
    MICROPHONE_START_INTENT("org.conceptflow.mpl.rokid.internal.ACCESSIBILITY_MIC_START"),
    MICROPHONE_STOP_INTENT("org.conceptflow.mpl.rokid.internal.ACCESSIBILITY_MIC_STOP"),
    ;

    companion object {
        fun fromAction(action: String?): RokidLocalControlCommand? =
            entries.firstOrNull { it.action == action }
    }
}

object RokidInputDispatchPolicy {
    fun dispatchIfEnabled(
        commandsEnabled: Boolean,
        command: RokidLocalControlCommand,
        dispatch: (RokidLocalControlCommand) -> Unit,
    ): Boolean {
        if (!commandsEnabled) return false
        dispatch(command)
        return true
    }
}

fun interface RokidMicrophoneIntentHandler {
    /** Returns true only when the active controller queues the session-bound intent. */
    fun handle(command: RokidLocalControlCommand): Boolean
}

/**
 * Bounded observer-only grammar for the physically observed Android translation of the PSOC
 * mapping. The raw double-tap terminator is consumed by YodaOS before AccessibilityService
 * delivery, so its two closely timed post-swipe preambles are the app-visible completion signal.
 * Android key consumption is deliberately outside this pure state machine.
 */
class RokidInputSequenceStateMachine(
    private val hardwarePolicy: RokidInputHardwarePolicy,
    private val preambleTimeoutMillis: Long = 900L,
    private val followupTimeoutMillis: Long = 1_600L,
    private val repeatedSwipeCollapseMillis: Long = 350L,
    private val quickTapMaximumMillis: Long = 500L,
    private val keyHoldMaximumMillis: Long = 1_200L,
) {
    private var lastEventTimeMillis = -1L
    private var downKey: RokidInputKey? = null
    private var downTimeMillis = 0L
    private var preambleTimeMillis: Long? = null
    private var pendingSwipe: RokidInputKey? = null
    private var pendingSwipeTimeMillis = 0L
    private var repeatedSwipeUntilMillis = 0L
    private var followupPreambleCount = 0
    private var lastFollowupPreambleTimeMillis = 0L
    private var sequenceDevice: RokidInputDeviceIdentity? = null

    init {
        require(preambleTimeoutMillis > 0L)
        require(followupTimeoutMillis > 0L)
        require(repeatedSwipeCollapseMillis >= 0L)
        require(quickTapMaximumMillis > 0L)
        require(keyHoldMaximumMillis >= quickTapMaximumMillis)
    }

    @Synchronized
    fun observe(event: RokidInputEvent, nodeActive: Boolean): RokidLocalControlCommand? {
        if (!hardwarePolicy.accepts(event)) {
            reset()
            return null
        }
        if (event.eventTimeMillis < 0L || event.eventTimeMillis < lastEventTimeMillis) {
            reset()
            lastEventTimeMillis = event.eventTimeMillis.coerceAtLeast(-1L)
            return null
        }
        lastEventTimeMillis = event.eventTimeMillis
        expireSequence(event.eventTimeMillis)
        val expectedDevice = sequenceDevice
        if (expectedDevice != null && event.device != expectedDevice) {
            reset()
            return null
        }
        if (sequenceDevice == null) sequenceDevice = event.device
        if (event.canceled || event.longPress) {
            reset()
            return null
        }
        if (event.key == RokidInputKey.UNRELATED || event.action == RokidInputAction.OTHER) {
            resetSequenceAndKey()
            return null
        }
        return when (event.action) {
            RokidInputAction.DOWN -> onDown(event)
            RokidInputAction.UP -> onUp(event, nodeActive)
            RokidInputAction.OTHER -> null
        }
    }

    @Synchronized
    fun reset() {
        lastEventTimeMillis = -1L
        resetSequenceAndKey()
    }

    private fun onDown(event: RokidInputEvent): RokidLocalControlCommand? {
        if (event.repeatCount != 0 || downKey != null) {
            resetSequenceAndKey()
            return null
        }
        downKey = event.key
        downTimeMillis = event.eventTimeMillis
        return null
    }

    private fun onUp(event: RokidInputEvent, nodeActive: Boolean): RokidLocalControlCommand? {
        val pressedKey = downKey
        val heldMillis = event.eventTimeMillis - downTimeMillis
        downKey = null
        if (event.repeatCount != 0 || pressedKey != event.key || heldMillis < 0L) {
            resetSequence()
            return null
        }
        return onCompletedKey(event.key, event.eventTimeMillis, heldMillis, nodeActive)
    }

    private fun onCompletedKey(
        key: RokidInputKey,
        eventTimeMillis: Long,
        heldMillis: Long,
        nodeActive: Boolean,
    ): RokidLocalControlCommand? {
        if (heldMillis > keyHoldMaximumMillis) {
            resetSequence()
            return null
        }
        return when (key) {
            RokidInputKey.PREAMBLE -> completePreamble(eventTimeMillis)
            RokidInputKey.SWIPE_FORWARD,
            RokidInputKey.SWIPE_BACKWARD,
            -> completeSwipe(key, eventTimeMillis)
            RokidInputKey.DOUBLE_TAP -> {
                resetSequence()
                null
            }
            RokidInputKey.SINGLE_TAP -> completeFollowup(
                eventTimeMillis,
                heldMillis,
                nodeActive,
            )
            RokidInputKey.UNRELATED -> null
        }
    }

    private fun completePreamble(eventTimeMillis: Long): RokidLocalControlCommand? {
        if (pendingSwipe == null) {
            if (preambleTimeMillis != null) {
                resetSequence()
                return null
            }
            preambleTimeMillis = eventTimeMillis
            return null
        }
        val previousStepTime = if (followupPreambleCount == 0) {
            pendingSwipeTimeMillis
        } else {
            lastFollowupPreambleTimeMillis
        }
        if (
            followupPreambleCount >= 2 ||
            eventTimeMillis - pendingSwipeTimeMillis > followupTimeoutMillis ||
            eventTimeMillis - previousStepTime > preambleTimeoutMillis
        ) {
            resetSequence()
            return null
        }
        followupPreambleCount += 1
        lastFollowupPreambleTimeMillis = eventTimeMillis
        if (followupPreambleCount != 2) return null
        val swipe = pendingSwipe
        resetSequence()
        return when (swipe) {
            RokidInputKey.SWIPE_FORWARD -> RokidLocalControlCommand.ENABLE_NODE
            RokidInputKey.SWIPE_BACKWARD -> RokidLocalControlCommand.DISABLE_NODE
            else -> null
        }
    }

    private fun completeSwipe(key: RokidInputKey, eventTimeMillis: Long): RokidLocalControlCommand? {
        val preamble = preambleTimeMillis
        if (preamble != null && eventTimeMillis - preamble <= preambleTimeoutMillis) {
            pendingSwipe = key
            pendingSwipeTimeMillis = eventTimeMillis
            repeatedSwipeUntilMillis = eventTimeMillis + repeatedSwipeCollapseMillis
            preambleTimeMillis = null
            return null
        }
        if (
            pendingSwipe == key &&
            followupPreambleCount == 0 &&
            eventTimeMillis <= repeatedSwipeUntilMillis
        ) return null
        resetSequence()
        return null
    }

    private fun completeFollowup(
        eventTimeMillis: Long,
        heldMillis: Long,
        nodeActive: Boolean,
    ): RokidLocalControlCommand? {
        val swipe = pendingSwipe
        val valid = heldMillis <= quickTapMaximumMillis &&
            swipe != null &&
            followupPreambleCount == 1 &&
            eventTimeMillis - lastFollowupPreambleTimeMillis <= preambleTimeoutMillis &&
            eventTimeMillis - pendingSwipeTimeMillis <= followupTimeoutMillis
        resetSequence()
        if (!valid) return null
        return when {
            nodeActive && swipe == RokidInputKey.SWIPE_FORWARD ->
                RokidLocalControlCommand.MICROPHONE_START_INTENT
            nodeActive && swipe == RokidInputKey.SWIPE_BACKWARD ->
                RokidLocalControlCommand.MICROPHONE_STOP_INTENT
            else -> null
        }
    }

    private fun expireSequence(nowMillis: Long) {
        val preamble = preambleTimeMillis
        if (preamble != null && nowMillis - preamble > preambleTimeoutMillis) {
            resetSequence()
        }
        if (pendingSwipe != null && nowMillis - pendingSwipeTimeMillis > followupTimeoutMillis) {
            resetSequence()
            return
        }
        if (
            followupPreambleCount > 0 &&
            nowMillis - lastFollowupPreambleTimeMillis > preambleTimeoutMillis
        ) {
            resetSequence()
        }
    }

    private fun resetSequenceAndKey() {
        downKey = null
        downTimeMillis = 0L
        resetSequence()
    }

    private fun resetSequence() {
        preambleTimeMillis = null
        pendingSwipe = null
        pendingSwipeTimeMillis = 0L
        repeatedSwipeUntilMillis = 0L
        followupPreambleCount = 0
        lastFollowupPreambleTimeMillis = 0L
        sequenceDevice = null
    }
}
