// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.random.Random
import org.conceptflow.mpl.transport.LiveLinkDisconnectReason

enum class RendezvousTerminalDecision {
    RETRY_IMMEDIATELY,
    RETRY_AFTER_COOLDOWN,
    FAIL_CLOSED,
    STOP,
}

enum class RendezvousAlarmDecision {
    START_EPOCH,
    IGNORE_ACTIVE_EPOCH,
    REJECT,
}

enum class RendezvousAlarmPrecision {
    EXACT_ALLOW_IDLE,
    INEXACT_ALLOW_IDLE,
}

/**
 * Authorizes a one-shot cooldown alarm without turning process recreation into capture authority.
 * A live epoch wins over a stale duplicate alarm; otherwise both the process and generation
 * capabilities must still match the explicitly armed service instance.
 */
object RendezvousAlarmPolicy {
    fun shouldConsumeScheduledAlarm(
        processCapabilityMatches: Boolean,
        generationMatches: Boolean,
    ): Boolean = processCapabilityMatches && generationMatches

    fun decide(
        liveEpochActive: Boolean,
        processCapabilityMatches: Boolean,
        generationMatches: Boolean,
        idleEnabled: Boolean,
        visibleArmEligible: Boolean,
        serviceStopping: Boolean,
    ): RendezvousAlarmDecision = when {
        liveEpochActive -> RendezvousAlarmDecision.IGNORE_ACTIVE_EPOCH
        !shouldConsumeScheduledAlarm(processCapabilityMatches, generationMatches) || !idleEnabled ||
            !visibleArmEligible || serviceStopping -> RendezvousAlarmDecision.REJECT
        else -> RendezvousAlarmDecision.START_EPOCH
    }

    /** Android 12/API 31 introduced exact-alarm special access. */
    fun precision(apiLevel: Int, exactAlarmAccess: Boolean): RendezvousAlarmPrecision =
        if (apiLevel < 31 || exactAlarmAccess) {
            RendezvousAlarmPrecision.EXACT_ALLOW_IDLE
        } else {
            RendezvousAlarmPrecision.INEXACT_ALLOW_IDLE
        }
}

/** Pure restart policy for sensor-off outbound rendezvous epochs. */
object RokidRendezvousPolicy {
    fun afterTerminal(
        stopReason: LiveLinkCaptureStopReason?,
        disconnectReason: LiveLinkDisconnectReason?,
        idleEnabled: Boolean,
        visibleArmEligible: Boolean,
    ): RendezvousTerminalDecision {
        if (!idleEnabled || !visibleArmEligible) return RendezvousTerminalDecision.STOP
        return when (stopReason) {
            LiveLinkCaptureStopReason.TIME_LIMIT_REACHED,
            LiveLinkCaptureStopReason.LEASE_EXPIRED,
            LiveLinkCaptureStopReason.REMOTE_COMPLETED,
            -> RendezvousTerminalDecision.RETRY_IMMEDIATELY
            LiveLinkCaptureStopReason.RENDEZVOUS_TIMEOUT ->
                RendezvousTerminalDecision.RETRY_AFTER_COOLDOWN
            LiveLinkCaptureStopReason.RETRY_LIMIT_REACHED -> when (disconnectReason) {
                LiveLinkDisconnectReason.NETWORK,
                LiveLinkDisconnectReason.TIMEOUT,
                -> RendezvousTerminalDecision.RETRY_AFTER_COOLDOWN
                else -> RendezvousTerminalDecision.FAIL_CLOSED
            }
            else -> RendezvousTerminalDecision.FAIL_CLOSED
        }
    }
}

/** Invalidates callbacks from every earlier service-owned rendezvous epoch. */
class RendezvousGeneration {
    private var value = 0L

    fun next(): Long {
        value = Math.addExact(value, 1L)
        return value
    }

    fun invalidate(): Long = next()

    fun isCurrent(candidate: Long): Boolean = candidate == value
}

/** One-shot total handshake guard; authentication permanently disarms it for this run. */
class PreAuthenticationRendezvousDeadlineGate {
    private var waiting = false

    fun begin() {
        check(!waiting)
        waiting = true
    }

    /** Returns true when a scheduled expiry callback should be removed. */
    fun observe(sessionsReady: Long): Boolean {
        if (!waiting || sessionsReady <= 0L) return false
        waiting = false
        return true
    }

    fun expireIfWaiting(sessionsReady: Long, state: LiveLinkCaptureState): Boolean {
        if (!waiting || sessionsReady > 0L || state == LiveLinkCaptureState.STOPPED) return false
        waiting = false
        return true
    }

    fun cancel() {
        waiting = false
    }

    companion object {
        // A first Android Wi-Fi Direct DNS-SD discovery plus the platform-owned consent and
        // group-formation sequence can exceed one minute on the target firmware. This bounded
        // lease remains sensor-off and is released immediately after authentication.
        const val DEFAULT_TIMEOUT_MILLIS = 180_000L
        const val WAKE_RELEASE_GUARD_MILLIS = 2_000L
        const val MAXIMUM_WAKE_LEASE_MILLIS = DEFAULT_TIMEOUT_MILLIS + WAKE_RELEASE_GUARD_MILLIS
    }
}

/** Bounded sensor-off retry cadence preserved across failed rendezvous epochs. */
class RendezvousBackoff(
    private val delaysMillis: List<Long> = listOf(15_000L, 30_000L, 60_000L),
    private val jitterRatio: Double = 0.10,
    private val jitterUnitSample: () -> Double = { Random.Default.nextDouble() },
) {
    private var failedEpochs = 0

    init {
        require(delaysMillis.isNotEmpty() && delaysMillis.all { it > 0L })
        require(delaysMillis.zipWithNext().all { (previous, next) -> next >= previous })
        require(jitterRatio in 0.0..0.10)
    }

    val maximumPossibleDelayMillis: Long
        get() = ceil(delaysMillis.last() * (1.0 + jitterRatio)).toLong()

    fun nextDelayMillis(): Long {
        val baseDelay = delaysMillis[failedEpochs.coerceAtMost(delaysMillis.lastIndex)]
        if (failedEpochs < delaysMillis.lastIndex) failedEpochs += 1
        val sample = jitterUnitSample()
        require(sample.isFinite() && sample in 0.0..1.0)
        val minimum = floor(baseDelay * (1.0 - jitterRatio)).toLong()
        val maximum = ceil(baseDelay * (1.0 + jitterRatio)).toLong()
        val multiplier = 1.0 + ((sample * 2.0) - 1.0) * jitterRatio
        return (baseDelay * multiplier).roundToLong().coerceIn(minimum, maximum)
    }

    fun resetAfterAuthenticatedSession() {
        failedEpochs = 0
    }
}
