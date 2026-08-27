// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

data class EnvironmentEvidence(
    val timestampNanos: Long,
    val indoorProbability: Double,
    val outdoorProbability: Double,
    val independentSignalCount: Int,
    val hasPrimaryVisualSignal: Boolean = false,
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
    val consecutiveEvidenceRequired: Int = 2,
    val minimumHoldNanos: Long = 10_000_000_000L,
    // The fusion layer already applies modality-specific freshness (2 s semantic camera,
    // 20 s VLM camera, 15 s GNSS). This outer bound must admit the slowest accepted modality.
    val maximumEvidenceAgeNanos: Long = 20_000_000_000L,
    val maximumProfileReuseNanos: Long = 90_000_000_000L,
    val singleVisualEnterProbability: Double = 0.88,
) {
    init {
        require(enterProbability in 0.5..1.0)
        require(minimumProbabilityMargin in 0.0..1.0)
        require(consecutiveEvidenceRequired in 1..20)
        require(minimumHoldNanos >= 0L)
        require(maximumEvidenceAgeNanos > 0L)
        require(maximumProfileReuseNanos >= maximumEvidenceAgeNanos)
        require(maximumProfileReuseNanos >= minimumHoldNanos)
        require(singleVisualEnterProbability in enterProbability..1.0)
    }
}

data class DepthProfileDecision(
    val environment: DepthEnvironment?,
    val changed: Boolean,
    val reason: String,
    val sceneState: SceneEnvironmentState = environment.toSceneState(),
    val confidence: Double = 0.0,
    val evidenceTimestampNanos: Long? = null,
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
    private var lastConfirmedNanos = Long.MIN_VALUE
    private var lastEvidenceTimestampNanos = Long.MIN_VALUE
    private var lastSceneState = SceneEnvironmentState.UNKNOWN
    private var lastConfidence = 0.0

    @Synchronized
    fun evaluate(evidence: EnvironmentEvidence?, nowNanos: Long): DepthProfileDecision {
        require(nowNanos >= 0L)
        if (evidence == null) {
            resetCandidate()
            return holdOrExpire(
                nowNanos,
                "no_environment_evidence",
                SceneEnvironmentState.UNKNOWN,
                0.0,
                null,
            )
        }
        val age = nowNanos - evidence.timestampNanos
        if (age < 0L || age > config.maximumEvidenceAgeNanos) {
            resetCandidate()
            return holdOrExpire(nowNanos, "stale_or_future_evidence", SceneEnvironmentState.UNKNOWN, 0.0, null)
        }
        if (evidence.timestampNanos <= lastEvidenceTimestampNanos) {
            return holdOrExpire(
                nowNanos,
                "duplicate_or_out_of_order_evidence",
                lastSceneState,
                lastConfidence,
                evidence.timestampNanos,
            )
        }
        lastEvidenceTimestampNanos = evidence.timestampNanos
        val confidence = maxOf(evidence.indoorProbability, evidence.outdoorProbability)
        val proposed = classify(evidence)
        if (proposed == null) {
            resetCandidate()
            val state = if (
                evidence.independentSignalCount > 0 &&
                kotlin.math.abs(evidence.indoorProbability - evidence.outdoorProbability) < config.minimumProbabilityMargin
            ) {
                SceneEnvironmentState.TRANSITION
            } else {
                SceneEnvironmentState.UNKNOWN
            }
            return holdOrExpire(
                nowNanos,
                "insufficient_environment_evidence",
                state,
                confidence,
                evidence.timestampNanos,
            )
        }
        if (proposed == current) {
            resetCandidate()
            lastConfirmedNanos = nowNanos
            lastSceneState = proposed.toSceneState()
            lastConfidence = confidence
            return DepthProfileDecision(
                current,
                false,
                "profile_unchanged",
                lastSceneState,
                confidence,
                evidence.timestampNanos,
            )
        }
        if (current != null && lastSwitchNanos != Long.MIN_VALUE &&
            nowNanos - lastSwitchNanos < config.minimumHoldNanos
        ) {
            resetCandidate()
            return holdOrExpire(
                nowNanos,
                "minimum_hold_active",
                SceneEnvironmentState.TRANSITION,
                confidence,
                evidence.timestampNanos,
            )
        }
        if (candidate == proposed) {
            candidateCount += 1
        } else {
            candidate = proposed
            candidateCount = 1
        }
        if (candidateCount < config.consecutiveEvidenceRequired) {
            return holdOrExpire(
                nowNanos,
                "environment_evidence_pending",
                SceneEnvironmentState.TRANSITION,
                confidence,
                evidence.timestampNanos,
            )
        }
        current = proposed
        lastSwitchNanos = nowNanos
        lastConfirmedNanos = nowNanos
        lastSceneState = proposed.toSceneState()
        lastConfidence = confidence
        resetCandidate()
        return DepthProfileDecision(
            current,
            true,
            "profile_switched",
            lastSceneState,
            confidence,
            evidence.timestampNanos,
        )
    }

    @Synchronized
    fun current(): DepthEnvironment? = current

    @Synchronized
    fun reset() {
        current = null
        lastSwitchNanos = Long.MIN_VALUE
        lastConfirmedNanos = Long.MIN_VALUE
        lastEvidenceTimestampNanos = Long.MIN_VALUE
        lastSceneState = SceneEnvironmentState.UNKNOWN
        lastConfidence = 0.0
        resetCandidate()
    }

    private fun classify(evidence: EnvironmentEvidence): DepthEnvironment? {
        val strongestProbability = maxOf(evidence.indoorProbability, evidence.outdoorProbability)
        val hasQuorum = evidence.independentSignalCount >= 2 ||
            evidence.hasPrimaryVisualSignal && strongestProbability >= config.singleVisualEnterProbability
        if (!hasQuorum) return null
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

    private fun holdOrExpire(
        nowNanos: Long,
        reason: String,
        state: SceneEnvironmentState,
        confidence: Double,
        evidenceTimestampNanos: Long?,
    ): DepthProfileDecision {
        if (current != null && lastConfirmedNanos != Long.MIN_VALUE &&
            nowNanos - lastConfirmedNanos > config.maximumProfileReuseNanos
        ) {
            current = null
            lastSwitchNanos = Long.MIN_VALUE
            lastSceneState = SceneEnvironmentState.UNKNOWN
            lastConfidence = 0.0
            resetCandidate()
            return DepthProfileDecision(null, true, "profile_expired")
        }
        lastSceneState = state
        lastConfidence = confidence
        return DepthProfileDecision(current, false, reason, state, confidence, evidenceTimestampNanos)
    }
}

private fun DepthEnvironment?.toSceneState(): SceneEnvironmentState = when (this) {
    DepthEnvironment.INDOOR -> SceneEnvironmentState.INDOOR
    DepthEnvironment.OUTDOOR -> SceneEnvironmentState.OUTDOOR
    null -> SceneEnvironmentState.UNKNOWN
}
