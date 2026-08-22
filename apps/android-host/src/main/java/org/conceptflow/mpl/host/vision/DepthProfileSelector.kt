// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

data class EnvironmentEvidence(
    val timestampNanos: Long,
    val indoorProbability: Double,
    val outdoorProbability: Double,
    val independentSignalCount: Int,
) {
    init {
        require(timestampNanos >= 0L)
        require(indoorProbability.isFinite() && indoorProbability in 0.0..1.0)
        require(outdoorProbability.isFinite() && outdoorProbability in 0.0..1.0)
        require(independentSignalCount in 0..16)
    }
}

data class DepthProfileSelectorConfig(
    val enterProbability: Double = 0.72,
    val minimumProbabilityMargin: Double = 0.18,
    val consecutiveEvidenceRequired: Int = 3,
    val minimumHoldNanos: Long = 10_000_000_000L,
    val maximumEvidenceAgeNanos: Long = 2_000_000_000L,
) {
    init {
        require(enterProbability in 0.5..1.0)
        require(minimumProbabilityMargin in 0.0..1.0)
        require(consecutiveEvidenceRequired in 1..20)
        require(minimumHoldNanos >= 0L)
        require(maximumEvidenceAgeNanos > 0L)
    }
}

data class DepthProfileDecision(
    val environment: DepthEnvironment?,
    val changed: Boolean,
    val reason: String,
)

/**
 * Stateful indoor/outdoor selection with evidence quorum and hysteresis.
 * Unknown evidence never forces a model switch.
 */
class DepthProfileSelector(
    private val config: DepthProfileSelectorConfig = DepthProfileSelectorConfig(),
) {
    private var current: DepthEnvironment? = null
    private var candidate: DepthEnvironment? = null
    private var candidateCount = 0
    private var lastSwitchNanos = Long.MIN_VALUE

    @Synchronized
    fun evaluate(evidence: EnvironmentEvidence, nowNanos: Long): DepthProfileDecision {
        require(nowNanos >= 0L)
        val age = nowNanos - evidence.timestampNanos
        if (age < 0L || age > config.maximumEvidenceAgeNanos) {
            resetCandidate()
            return DepthProfileDecision(current, false, "stale_or_future_evidence")
        }
        val proposed = classify(evidence)
        if (proposed == null) {
            resetCandidate()
            return DepthProfileDecision(current, false, "insufficient_environment_evidence")
        }
        if (proposed == current) {
            resetCandidate()
            return DepthProfileDecision(current, false, "profile_unchanged")
        }
        if (current != null && lastSwitchNanos != Long.MIN_VALUE &&
            nowNanos - lastSwitchNanos < config.minimumHoldNanos
        ) {
            resetCandidate()
            return DepthProfileDecision(current, false, "minimum_hold_active")
        }
        if (candidate == proposed) {
            candidateCount += 1
        } else {
            candidate = proposed
            candidateCount = 1
        }
        if (candidateCount < config.consecutiveEvidenceRequired) {
            return DepthProfileDecision(current, false, "environment_evidence_pending")
        }
        current = proposed
        lastSwitchNanos = nowNanos
        resetCandidate()
        return DepthProfileDecision(current, true, "profile_switched")
    }

    @Synchronized
    fun current(): DepthEnvironment? = current

    @Synchronized
    fun reset() {
        current = null
        lastSwitchNanos = Long.MIN_VALUE
        resetCandidate()
    }

    private fun classify(evidence: EnvironmentEvidence): DepthEnvironment? {
        if (evidence.independentSignalCount < 2) return null
        val delta = evidence.indoorProbability - evidence.outdoorProbability
        return when {
            evidence.indoorProbability >= config.enterProbability && delta >= config.minimumProbabilityMargin -> {
                DepthEnvironment.INDOOR
            }
            evidence.outdoorProbability >= config.enterProbability && -delta >= config.minimumProbabilityMargin -> {
                DepthEnvironment.OUTDOOR
            }
            else -> null
        }
    }

    private fun resetCandidate() {
        candidate = null
        candidateCount = 0
    }
}
