// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class IdleControlActivationCoordinatorTest {
    @Test
    fun visibleServiceRoutingIncludesExplicitAndBothRecoveryModes() {
        assertEquals(
            VisibleServiceActivation.IDLE_CONTROL,
            VisibleServiceActivation.fromCommand(RuntimeCommand.ENABLE_IDLE_CONTROL),
        )
        assertEquals(
            VisibleServiceActivation.SAME_BOOT_RECOVERY,
            VisibleServiceActivation.fromCommand(RuntimeCommand.RECOVER_SAME_BOOT),
        )
        assertEquals(
            VisibleServiceActivation.PERSISTED_BOOT_RECOVERY,
            VisibleServiceActivation.fromCommand(RuntimeCommand.RECOVER_PERSISTED_BOOT),
        )
        assertEquals(null, VisibleServiceActivation.fromCommand(RuntimeCommand.PLAY_FULL_BRAND_TEST))
        assertEquals(null, VisibleServiceActivation.fromCommand(RuntimeCommand.STOP))
        assertEquals(null, VisibleServiceActivation.fromCommand(null))
    }

    @Test
    fun everyVisibleServiceWaitsForForegroundEligibilityAndArmedVerification() {
        VisibleServiceActivation.entries.forEach {
            val coordinator = IdleControlActivationCoordinator()

            assertEquals(
                IdleControlActivationDecision.WAIT_FOR_VISIBILITY,
                coordinator.onResumed(),
            )
            assertEquals(
                IdleControlActivationDecision.SCHEDULE_START,
                coordinator.onWindowFocusChanged(true),
            )
            assertEquals(
                IdleControlActivationDecision.START_SERVICE_AND_BIND,
                coordinator.onActivationDelayElapsed(),
            )
            assertEquals(
                IdleControlActivationDecision.COMPLETE,
                coordinator.observeArmedState(true),
            )
        }
    }

    @Test
    fun waitsForBothResumeAndWindowFocusBeforeScheduling() {
        val coordinator = IdleControlActivationCoordinator()

        assertEquals(
            IdleControlActivationDecision.WAIT_FOR_VISIBILITY,
            coordinator.onWindowFocusChanged(true),
        )
        assertEquals(IdleControlActivationDecision.SCHEDULE_START, coordinator.onResumed())
        assertEquals(
            IdleControlActivationDecision.START_SERVICE_AND_BIND,
            coordinator.onActivationDelayElapsed(),
        )
    }

    @Test
    fun focusLossInvalidatesDelayAndRegainSchedulesAgain() {
        val coordinator = IdleControlActivationCoordinator()
        coordinator.onResumed()
        assertEquals(
            IdleControlActivationDecision.SCHEDULE_START,
            coordinator.onWindowFocusChanged(true),
        )
        assertEquals(
            IdleControlActivationDecision.WAIT_FOR_VISIBILITY,
            coordinator.onWindowFocusChanged(false),
        )
        assertEquals(
            IdleControlActivationDecision.WAIT_FOR_VISIBILITY,
            coordinator.onActivationDelayElapsed(),
        )
        assertEquals(
            IdleControlActivationDecision.SCHEDULE_START,
            coordinator.onWindowFocusChanged(true),
        )
    }

    @Test
    fun pauseInvalidatesDelay() {
        val coordinator = IdleControlActivationCoordinator()
        coordinator.onWindowFocusChanged(true)
        assertEquals(IdleControlActivationDecision.SCHEDULE_START, coordinator.onResumed())
        coordinator.onPaused()

        assertEquals(
            IdleControlActivationDecision.WAIT_FOR_VISIBILITY,
            coordinator.onActivationDelayElapsed(),
        )
    }

    @Test
    fun startsOnlyOnceAndCompletesWhenArmed() {
        val coordinator = IdleControlActivationCoordinator()
        coordinator.onResumed()
        coordinator.onWindowFocusChanged(true)
        assertEquals(
            IdleControlActivationDecision.START_SERVICE_AND_BIND,
            coordinator.onActivationDelayElapsed(),
        )
        assertEquals(
            IdleControlActivationDecision.WAIT_FOR_VISIBILITY,
            coordinator.onActivationDelayElapsed(),
        )
        assertEquals(
            IdleControlActivationDecision.RETRY_ARMED_STATE,
            coordinator.observeArmedState(false),
        )
        assertEquals(
            IdleControlActivationDecision.COMPLETE,
            coordinator.observeArmedState(true),
        )
    }

    @Test
    fun failsClosedAfterBoundedVerificationAttempts() {
        val coordinator = IdleControlActivationCoordinator(maximumVerificationAttempts = 2)
        coordinator.onResumed()
        coordinator.onWindowFocusChanged(true)
        coordinator.onActivationDelayElapsed()

        assertEquals(
            IdleControlActivationDecision.RETRY_ARMED_STATE,
            coordinator.observeArmedState(false),
        )
        assertEquals(
            IdleControlActivationDecision.FAILED_CLOSED,
            coordinator.observeArmedState(false),
        )
    }
}
