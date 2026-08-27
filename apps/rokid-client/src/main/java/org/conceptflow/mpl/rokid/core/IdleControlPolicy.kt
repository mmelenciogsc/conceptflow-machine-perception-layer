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

data class IdleControlRestoreDecision(
    val keepIdleService: Boolean,
    val startCapture: Boolean,
)

/** Fail-closed restore policy: persistence may restore idle presence, never sensor capture. */
object IdleControlPolicy {
    /** A process death may resume an explicit arm only in the same OS boot. */
    fun mayResumeSameBoot(
        enabled: Boolean,
        armedBootCount: Int?,
        currentBootCount: Int?,
    ): Boolean = enabled && armedBootCount != null && currentBootCount != null &&
        armedBootCount >= 0 && armedBootCount == currentBootCount

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
