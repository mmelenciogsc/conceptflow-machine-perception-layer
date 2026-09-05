// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

enum class IdleControlRestoreReason {
    BOOT_COMPLETED,
    PACKAGE_REPLACED,
    STICKY_RESTART,
}

enum class IdleControlArmDecision {
    ARM,
    ALREADY_ARMED,
    REJECT_LIVE_COLLISION,
}

enum class IdleControlRecoveryAuthorization {
    NOT_AUTHORIZED,
    SAME_BOOT,
    PERSISTED_NEW_BOOT,
}

data class IdleControlRestoreDecision(
    val keepIdleService: Boolean,
    val startCapture: Boolean,
)

/** Fail-closed restore policy: persistence may restore idle presence, never sensor capture. */
object IdleControlPolicy {
    /**
     * The persisted enabled bit records an explicit user choice. A matching boot count authorizes
     * ordinary process recovery; a different known boot count authorizes reconstruction through
     * the system-bound visible broker. Unknown clocks fail closed.
     */
    fun recoveryAuthorization(
        enabled: Boolean,
        armedBootCount: Int?,
        currentBootCount: Int?,
    ): IdleControlRecoveryAuthorization = when {
        !enabled || armedBootCount == null || armedBootCount < 0 ||
            currentBootCount == null || currentBootCount < 0 ->
            IdleControlRecoveryAuthorization.NOT_AUTHORIZED
        armedBootCount == currentBootCount -> IdleControlRecoveryAuthorization.SAME_BOOT
        else -> IdleControlRecoveryAuthorization.PERSISTED_NEW_BOOT
    }

    /** A process death may resume an explicit arm only in the same OS boot. */
    fun mayResumeSameBoot(
        enabled: Boolean,
        armedBootCount: Int?,
        currentBootCount: Int?,
    ): Boolean = recoveryAuthorization(enabled, armedBootCount, currentBootCount) ==
        IdleControlRecoveryAuthorization.SAME_BOOT

    fun isArmed(
        startedIdleEstablished: Boolean,
        foregroundEstablished: Boolean,
        persistedEnabled: Boolean,
        visibleArmEligible: Boolean,
        serviceStopping: Boolean,
    ): Boolean = startedIdleEstablished &&
        foregroundEstablished &&
        persistedEnabled &&
        visibleArmEligible &&
        !serviceStopping

    fun armDecision(
        hasActiveLiveLink: Boolean,
        activeRunIsManagedStandby: Boolean,
        persistedEnabled: Boolean,
        foregroundEstablished: Boolean,
        visibleArmEligible: Boolean,
    ): IdleControlArmDecision = when {
        !hasActiveLiveLink -> IdleControlArmDecision.ARM
        activeRunIsManagedStandby && persistedEnabled && foregroundEstablished && visibleArmEligible ->
            IdleControlArmDecision.ALREADY_ARMED
        else -> IdleControlArmDecision.REJECT_LIVE_COLLISION
    }

    fun restore(enabled: Boolean, reason: IdleControlRestoreReason): IdleControlRestoreDecision = when (reason) {
        IdleControlRestoreReason.BOOT_COMPLETED,
        IdleControlRestoreReason.PACKAGE_REPLACED,
        IdleControlRestoreReason.STICKY_RESTART -> IdleControlRestoreDecision(
            keepIdleService = enabled,
            startCapture = false,
        )
    }
}
