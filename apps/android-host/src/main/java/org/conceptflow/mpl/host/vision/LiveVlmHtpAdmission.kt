// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

enum class LocalVlmHtpWorkKind {
    PREWARM,
    ENVIRONMENT_CLASSIFICATION,
    FOCUSED_OBJECT_VQA,
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
 * starve admitted inference; only direct motion/occlusion/approach evidence interrupts that window.
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
        if (qnnReason in interruptingReasonsFor(work.kind)) {
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
        val BACKGROUND_WORK_INTERRUPTING_REASONS = setOf(
            SemanticDepthRefreshReason.MOTION,
            SemanticDepthRefreshReason.OCCLUSION,
            SemanticDepthRefreshReason.RAPID_APPROACH,
        )

        // An explicit user investigation is answered from its bounded captured frame and outranks
        // ordinary motion or semantic-track occlusion refreshes. Rapid-approach evidence still
        // interrupts it. The hard completion timeout above remains authoritative for every kind.
        val FOCUSED_VQA_INTERRUPTING_REASONS = setOf(
            SemanticDepthRefreshReason.RAPID_APPROACH,
        )

        fun interruptingReasonsFor(kind: LocalVlmHtpWorkKind): Set<SemanticDepthRefreshReason> =
            if (kind == LocalVlmHtpWorkKind.FOCUSED_OBJECT_VQA) {
                FOCUSED_VQA_INTERRUPTING_REASONS
            } else {
                BACKGROUND_WORK_INTERRUPTING_REASONS
            }
    }
}

enum class LocalVlmWorkLane { PREWARM, DRAIN }

/** Prevents a cancelled lazy job's stale completion from clearing a newer lane owner. */
class GenerationScopedVlmWorkGate {
    private var generation = 0L
    private var active: LocalVlmWorkLane? = null

    @Synchronized
    fun begin(lane: LocalVlmWorkLane): Long? {
        if (active != null) return null
        active = lane
        return generation
    }

    @Synchronized
    fun finish(lane: LocalVlmWorkLane, ownerGeneration: Long): Boolean {
        if (ownerGeneration != generation) return false
        if (active != lane) return false
        active = null
        return true
    }

    @Synchronized
    fun isCurrent(lane: LocalVlmWorkLane, ownerGeneration: Long): Boolean =
        ownerGeneration == generation && lane == active

    @Synchronized
    fun cancelAll() {
        generation = if (generation == Long.MAX_VALUE) 0L else generation + 1L
        active = null
    }

    @Synchronized fun isActive(lane: LocalVlmWorkLane): Boolean = lane == active
}
