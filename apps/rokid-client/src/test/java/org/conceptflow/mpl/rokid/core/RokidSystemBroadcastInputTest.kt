// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RokidSystemBroadcastInputTest {
    @Test
    fun documentedTouchActionsResolveExactly() {
        RokidSystemBroadcastInput.entries.forEach { input ->
            assertEquals(input, RokidSystemBroadcastInput.fromAction(input.action))
        }
        assertNull(
            RokidSystemBroadcastInput.fromAction(
                "com.android.action.ACTION_SPRITE_BUTTON_CLICK",
            ),
        )
        assertNull(RokidSystemBroadcastInput.fromAction(null))
    }

    @Test
    fun interceptionIsDisabledByDefault() {
        assertEquals(
            RokidBroadcastInterceptionDecision.OBSERVE_ONLY,
            RokidBroadcastInterceptionPolicy.decide(
                input = RokidSystemBroadcastInput.TWO_FINGER_DOUBLE_TAP,
                isOrderedBroadcast = true,
                validatedActions = setOf(RokidSystemBroadcastInput.TWO_FINGER_DOUBLE_TAP),
                interceptionEnabled = false,
            ),
        )
    }

    @Test
    fun interceptionRequiresAnOrderedPhysicallyValidatedAction() {
        assertEquals(
            RokidBroadcastInterceptionDecision.REJECT_NOT_ORDERED,
            RokidBroadcastInterceptionPolicy.decide(
                input = RokidSystemBroadcastInput.TWO_FINGER_DOUBLE_TAP,
                isOrderedBroadcast = false,
                validatedActions = setOf(RokidSystemBroadcastInput.TWO_FINGER_DOUBLE_TAP),
                interceptionEnabled = true,
            ),
        )
        assertEquals(
            RokidBroadcastInterceptionDecision.REJECT_NOT_VALIDATED,
            RokidBroadcastInterceptionPolicy.decide(
                input = RokidSystemBroadcastInput.TWO_FINGER_DOUBLE_TAP,
                isOrderedBroadcast = true,
                validatedActions = emptySet(),
                interceptionEnabled = true,
            ),
        )
        assertEquals(
            RokidBroadcastInterceptionDecision.ABORT_ORDERED_BROADCAST,
            RokidBroadcastInterceptionPolicy.decide(
                input = RokidSystemBroadcastInput.TWO_FINGER_DOUBLE_TAP,
                isOrderedBroadcast = true,
                validatedActions = setOf(RokidSystemBroadcastInput.TWO_FINGER_DOUBLE_TAP),
                interceptionEnabled = true,
            ),
        )
    }
}
