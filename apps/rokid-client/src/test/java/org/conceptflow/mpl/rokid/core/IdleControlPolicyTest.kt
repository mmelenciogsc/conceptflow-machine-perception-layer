// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleControlPolicyTest {
    @Test
    fun persistedRecoveryDistinguishesSameBootFromKnownNewBoot() {
        assertEquals(
            IdleControlRecoveryAuthorization.SAME_BOOT,
            IdleControlPolicy.recoveryAuthorization(true, 84, 84),
        )
        assertEquals(
            IdleControlRecoveryAuthorization.PERSISTED_NEW_BOOT,
            IdleControlPolicy.recoveryAuthorization(true, 84, 85),
        )
        assertEquals(
            IdleControlRecoveryAuthorization.NOT_AUTHORIZED,
            IdleControlPolicy.recoveryAuthorization(false, 84, 85),
        )
        assertEquals(
            IdleControlRecoveryAuthorization.NOT_AUTHORIZED,
            IdleControlPolicy.recoveryAuthorization(true, null, 85),
        )
        assertEquals(
            IdleControlRecoveryAuthorization.NOT_AUTHORIZED,
            IdleControlPolicy.recoveryAuthorization(true, 84, null),
        )
    }

    @Test
    fun sameBootRecoveryRequiresAnExplicitArmFromTheCurrentBoot() {
        assertTrue(IdleControlPolicy.mayResumeSameBoot(true, 84, 84))
        assertFalse(IdleControlPolicy.mayResumeSameBoot(false, 84, 84))
        assertFalse(IdleControlPolicy.mayResumeSameBoot(true, 83, 84))
        assertFalse(IdleControlPolicy.mayResumeSameBoot(true, null, 84))
        assertFalse(IdleControlPolicy.mayResumeSameBoot(true, 84, null))
    }

    @Test
    fun armedStateCoversBothActiveRendezvousAndSensorOffCooldown() {
        // Neither a liveLinkRun nor a scheduled-retry flag is an input: both transient phases
        // preserve the same explicitly armed state.
        assertTrue(armedState())
    }

    @Test
    fun inactiveRestoreAndStoppingStatesAreNotArmed() {
        assertFalse(armedState(started = false))
        assertFalse(armedState(foreground = false))
        assertFalse(armedState(persisted = false))
        assertFalse(armedState(visible = false))
        assertFalse(armedState(stopping = true))
    }

    @Test
    fun idleArmAdmissionDistinguishesFreshIdempotentAndCollidingRuns() {
        assertEquals(
            IdleControlArmDecision.ARM,
            armDecision(hasRun = false),
        )
        assertEquals(
            IdleControlArmDecision.ALREADY_ARMED,
            armDecision(hasRun = true),
        )
        assertEquals(
            IdleControlArmDecision.REJECT_LIVE_COLLISION,
            armDecision(hasRun = true, managed = false),
        )
        assertEquals(
            IdleControlArmDecision.REJECT_LIVE_COLLISION,
            armDecision(hasRun = true, persisted = false),
        )
        assertEquals(
            IdleControlArmDecision.REJECT_LIVE_COLLISION,
            armDecision(hasRun = true, foreground = false),
        )
        assertEquals(
            IdleControlArmDecision.REJECT_LIVE_COLLISION,
            armDecision(hasRun = true, visible = false),
        )
    }

    @Test
    fun everyRestoreSourceKeepsOnlyAnEnabledIdleService() {
        IdleControlRestoreReason.entries.forEach { reason ->
            val enabled = IdleControlPolicy.restore(enabled = true, reason)
            assertTrue(enabled.keepIdleService)
            assertFalse(enabled.startCapture)

            val disabled = IdleControlPolicy.restore(enabled = false, reason)
            assertFalse(disabled.keepIdleService)
            assertFalse(disabled.startCapture)
        }
    }

    private fun armDecision(
        hasRun: Boolean,
        managed: Boolean = true,
        persisted: Boolean = true,
        foreground: Boolean = true,
        visible: Boolean = true,
    ) = IdleControlPolicy.armDecision(hasRun, managed, persisted, foreground, visible)

    private fun armedState(
        started: Boolean = true,
        foreground: Boolean = true,
        persisted: Boolean = true,
        visible: Boolean = true,
        stopping: Boolean = false,
    ) = IdleControlPolicy.isArmed(started, foreground, persisted, visible, stopping)
}
