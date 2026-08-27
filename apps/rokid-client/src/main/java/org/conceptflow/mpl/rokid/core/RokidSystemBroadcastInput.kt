// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

/**
 * Touch-panel broadcasts documented by Rokid's bare-metal YodaOS-Sprite guide.
 *
 * Temple-button actions are intentionally excluded: photo, video, power, and pairing behavior
 * remain owned by the operating system.
 */
enum class RokidSystemBroadcastInput(val action: String) {
    TWO_FINGER_SINGLE_TAP("com.android.action.ACTION_TWO_FINGER_SINGLE_TAP"),
    TWO_FINGER_DOUBLE_TAP("com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP"),
    TWO_FINGER_SWIPE_FORWARD("com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"),
    TWO_FINGER_SWIPE_BACK("com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"),
    TWO_FINGER_LONG_PRESS("com.android.action.ACTION_SETTINGS_KEY"),
    ONE_FINGER_LONG_PRESS("com.android.action.ACTION_AI_START"),
    ;

    companion object {
        private val byAction = entries.associateBy(RokidSystemBroadcastInput::action)

        fun fromAction(action: String?): RokidSystemBroadcastInput? = byAction[action]
    }
}

enum class RokidBroadcastInterceptionDecision {
    OBSERVE_ONLY,
    REJECT_NOT_ORDERED,
    REJECT_NOT_VALIDATED,
    ABORT_ORDERED_BROADCAST,
}

/** Fail-closed policy: an action must be individually validated on this firmware and ordered. */
object RokidBroadcastInterceptionPolicy {
    fun decide(
        input: RokidSystemBroadcastInput,
        isOrderedBroadcast: Boolean,
        validatedActions: Set<RokidSystemBroadcastInput>,
        interceptionEnabled: Boolean,
    ): RokidBroadcastInterceptionDecision {
        if (!interceptionEnabled) return RokidBroadcastInterceptionDecision.OBSERVE_ONLY
        if (!isOrderedBroadcast) return RokidBroadcastInterceptionDecision.REJECT_NOT_ORDERED
        if (input !in validatedActions) {
            return RokidBroadcastInterceptionDecision.REJECT_NOT_VALIDATED
        }
        return RokidBroadcastInterceptionDecision.ABORT_ORDERED_BROADCAST
    }
}
