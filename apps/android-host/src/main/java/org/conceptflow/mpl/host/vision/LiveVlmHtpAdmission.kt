// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

enum class LocalVlmHtpWorkKind {
    PREWARM,
    ENVIRONMENT_CLASSIFICATION,
}

data class LocalVlmHtpWorkState(
    val kind: LocalVlmHtpWorkKind,
    val startedMonotonicNanos: Long,
) {
    init {
        require(startedMonotonicNanos >= 0L)
    }
}

enum class LiveVlmQnnAdmissionDecision {
    RUN_QNN,
    DEFER_QNN_FOR_VLM,
    CANCEL_VLM_FOR_URGENT_QNN,
    CANCEL_VLM_AFTER_TIMEOUT,
}

/**
 * Gives sparse VLM work one bounded completion window. Routine age/confidence refresh cannot
 * starve classification; only direct motion/occlusion/approach evidence interrupts that window.
 * This state contains no image, label, location, or device identity.
 */
class LiveVlmHtpAdmissionGate(
    private val maximumVlmWindowNanos: Long = 8_500_000_000L,
) {
    private var activeWork: LocalVlmHtpWorkState? = null

    init {
        require(maximumVlmWindowNanos in 1_000_000_000L..30_000_000_000L)
    }

    @Synchronized
    fun observe(work: LocalVlmHtpWorkState?) {
        activeWork = work
    }

    @Synchronized
    fun decide(nowNanos: Long, qnnReason: SemanticDepthRefreshReason): LiveVlmQnnAdmissionDecision {
        require(nowNanos >= 0L)
        val work = activeWork ?: return LiveVlmQnnAdmissionDecision.RUN_QNN
        val elapsed = (nowNanos - work.startedMonotonicNanos).coerceAtLeast(0L)
        if (elapsed >= maximumVlmWindowNanos) {
            activeWork = null
            return LiveVlmQnnAdmissionDecision.CANCEL_VLM_AFTER_TIMEOUT
        }
        if (qnnReason in INTERRUPTING_REASONS) {
            activeWork = null
            return LiveVlmQnnAdmissionDecision.CANCEL_VLM_FOR_URGENT_QNN
        }
        return LiveVlmQnnAdmissionDecision.DEFER_QNN_FOR_VLM
    }

    @Synchronized
    fun hasActiveWork(): Boolean = activeWork != null

    @Synchronized
    fun reset() {
        activeWork = null
    }

    private companion object {
        val INTERRUPTING_REASONS = setOf(
            SemanticDepthRefreshReason.MOTION,
            SemanticDepthRefreshReason.OCCLUSION,
            SemanticDepthRefreshReason.RAPID_APPROACH,
        )
    }
}

enum class LocalVlmWorkLane { PREWARM, DRAIN }

/** Prevents a cancelled lazy job's stale completion from clearing a newer lane owner. */
class GenerationScopedVlmWorkGate {
    private var generation = 0L
    private val active = mutableSetOf<LocalVlmWorkLane>()

    @Synchronized
    fun begin(lane: LocalVlmWorkLane): Long? {
        if (!active.add(lane)) return null
        return generation
    }

    @Synchronized
    fun finish(lane: LocalVlmWorkLane, ownerGeneration: Long): Boolean {
        if (ownerGeneration != generation) return false
        return active.remove(lane)
    }

    @Synchronized
    fun isCurrent(lane: LocalVlmWorkLane, ownerGeneration: Long): Boolean =
        ownerGeneration == generation && lane in active

    @Synchronized
    fun cancelAll() {
        generation = if (generation == Long.MAX_VALUE) 0L else generation + 1L
        active.clear()
    }

    @Synchronized fun isActive(lane: LocalVlmWorkLane): Boolean = lane in active
}
