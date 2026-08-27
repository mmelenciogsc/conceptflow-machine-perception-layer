// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import kotlin.math.abs

data class DepthProfileRouterConfig(
    val shadowComparisonCooldownNanos: Long = 5_000_000_000L,
    val maximumShadowComparisonsPerTransition: Int = 2,
) {
    init {
        require(shadowComparisonCooldownNanos > 0L)
        require(maximumShadowComparisonsPerTransition in 1..8)
    }
}

data class TimestampedDepthProfileDecision(
    val frameId: Long,
    val frameTimestampNanos: Long,
    val sceneState: SceneEnvironmentState,
    val selectedEnvironment: DepthEnvironment?,
    val selectedProfile: MachineVisionModelProfile?,
    val confidence: Double,
    val uncertaintyMultiplier: Double,
    val shadowComparisonRecommended: Boolean,
    val reason: String,
) {
    init {
        require(frameId > 0L && frameTimestampNanos >= 0L)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(uncertaintyMultiplier.isFinite() && uncertaintyMultiplier >= 1.0)
        require((selectedEnvironment == null) == (selectedProfile == null))
        require(selectedProfile?.depthEnvironment == selectedEnvironment)
    }
}

/** Maps fused scene state to one timestamp-correlated metric-depth profile. */
class DepthProfileRouter(
    private val selector: DepthProfileSelector = DepthProfileSelector(),
    private val config: DepthProfileRouterConfig = DepthProfileRouterConfig(),
) {
    private var mode = EnvironmentSelectionMode.AUTOMATIC
    private var lastSceneState = SceneEnvironmentState.UNKNOWN
    private var lastShadowComparisonNanos = Long.MIN_VALUE
    private var shadowComparisonsInTransition = 0

    @Synchronized
    fun setMode(value: EnvironmentSelectionMode) {
        if (mode == value) return
        mode = value
        selector.reset()
        lastSceneState = SceneEnvironmentState.UNKNOWN
        lastShadowComparisonNanos = Long.MIN_VALUE
        shadowComparisonsInTransition = 0
    }

    @Synchronized
    fun mode(): EnvironmentSelectionMode = mode

    @Synchronized
    fun route(
        frameId: Long,
        frameTimestampNanos: Long,
        nowNanos: Long,
        evidence: EnvironmentEvidence?,
        bothProfilesAvailable: Boolean,
    ): TimestampedDepthProfileDecision {
        require(frameId > 0L && frameTimestampNanos >= 0L && nowNanos >= frameTimestampNanos)
        manualEnvironment()?.let { environment ->
            return decision(
                frameId,
                frameTimestampNanos,
                environment.toSceneState(),
                environment,
                1.0,
                false,
                "manual_override",
            )
        }
        val automatic = selector.evaluate(evidence, nowNanos)
        val enteredTransition = automatic.sceneState == SceneEnvironmentState.TRANSITION &&
            lastSceneState != SceneEnvironmentState.TRANSITION
        if (enteredTransition) shadowComparisonsInTransition = 0
        if (automatic.sceneState != SceneEnvironmentState.TRANSITION) shadowComparisonsInTransition = 0
        val cooldownPassed = lastShadowComparisonNanos == Long.MIN_VALUE ||
            nowNanos - lastShadowComparisonNanos >= config.shadowComparisonCooldownNanos
        val shadow = automatic.sceneState == SceneEnvironmentState.TRANSITION &&
            bothProfilesAvailable && cooldownPassed &&
            shadowComparisonsInTransition < config.maximumShadowComparisonsPerTransition
        if (shadow) {
            lastShadowComparisonNanos = nowNanos
            shadowComparisonsInTransition += 1
        }
        lastSceneState = automatic.sceneState
        val uncertaintyMultiplier = when (automatic.sceneState) {
            SceneEnvironmentState.INDOOR, SceneEnvironmentState.OUTDOOR -> 1.0
            SceneEnvironmentState.TRANSITION -> 1.5
            SceneEnvironmentState.UNKNOWN -> if (automatic.environment == null) 3.0 else 2.0
        }
        return decision(
            frameId,
            frameTimestampNanos,
            automatic.sceneState,
            automatic.environment,
            automatic.confidence,
            shadow,
            automatic.reason,
            uncertaintyMultiplier,
        )
    }

    private fun manualEnvironment(): DepthEnvironment? = when (mode) {
        EnvironmentSelectionMode.AUTOMATIC -> null
        EnvironmentSelectionMode.FORCE_INDOOR -> DepthEnvironment.INDOOR
        EnvironmentSelectionMode.FORCE_OUTDOOR -> DepthEnvironment.OUTDOOR
    }

    private fun decision(
        frameId: Long,
        frameTimestampNanos: Long,
        sceneState: SceneEnvironmentState,
        environment: DepthEnvironment?,
        confidence: Double,
        shadow: Boolean,
        reason: String,
        uncertaintyMultiplier: Double = 1.0,
    ) = TimestampedDepthProfileDecision(
        frameId = frameId,
        frameTimestampNanos = frameTimestampNanos,
        sceneState = sceneState,
        selectedEnvironment = environment,
        selectedProfile = environment?.let(MachineVisionModelProfiles::depth),
        confidence = confidence,
        uncertaintyMultiplier = uncertaintyMultiplier,
        shadowComparisonRecommended = shadow,
        reason = reason,
    )
}

data class DepthProfileQuality(
    val frameId: Long,
    val environment: DepthEnvironment,
    val completedMonotonicTimestampNanos: Long,
    val validSampleFraction: Double,
    val temporalMedianErrorMeters: Double,
    val calibrationMedianErrorMeters: Double,
    val medianUncertaintyMeters: Double,
) {
    init {
        require(frameId > 0L && completedMonotonicTimestampNanos >= 0L)
        require(validSampleFraction.isFinite() && validSampleFraction in 0.0..1.0)
        require(temporalMedianErrorMeters.isFinite() && temporalMedianErrorMeters >= 0.0)
        require(calibrationMedianErrorMeters.isFinite() && calibrationMedianErrorMeters >= 0.0)
        require(medianUncertaintyMeters.isFinite() && medianUncertaintyMeters >= 0.0)
    }
}

/** Selects an ambiguity-only shadow result; it does not redefine scene truth. */
class DepthShadowComparator(
    private val minimumScoreAdvantage: Double = 0.05,
    private val maximumCompletionSkewNanos: Long = 500_000_000L,
) {
    init {
        require(minimumScoreAdvantage.isFinite() && minimumScoreAdvantage > 0.0)
        require(maximumCompletionSkewNanos >= 0L)
    }

    fun choose(first: DepthProfileQuality, second: DepthProfileQuality): DepthEnvironment? {
        if (first.frameId != second.frameId || first.environment == second.environment) return null
        if (absoluteDifference(
                first.completedMonotonicTimestampNanos,
                second.completedMonotonicTimestampNanos,
            ) > maximumCompletionSkewNanos
        ) {
            return null
        }
        val firstScore = score(first)
        val secondScore = score(second)
        if (abs(firstScore - secondScore) < minimumScoreAdvantage) return null
        return if (firstScore < secondScore) first.environment else second.environment
    }

    private fun score(value: DepthProfileQuality): Double =
        value.temporalMedianErrorMeters * 0.50 +
            value.calibrationMedianErrorMeters * 0.30 +
            value.medianUncertaintyMeters * 0.20 +
            (1.0 - value.validSampleFraction) * 2.0

    private fun absoluteDifference(first: Long, second: Long): Long =
        if (first >= second) first - second else second - first
}

class EnvironmentDepthCoordinator(
    private val sceneClassifier: CameraSceneClassifier = BviSemanticSceneClassifier(),
    private val buffer: EnvironmentEvidenceBuffer = EnvironmentEvidenceBuffer(),
    private val fusion: EnvironmentEvidenceFusion = EnvironmentEvidenceFusion(),
    private val router: DepthProfileRouter = DepthProfileRouter(),
) {
    fun setMode(mode: EnvironmentSelectionMode) {
        if (router.mode() == mode) return
        buffer.clear()
        router.setMode(mode)
    }

    fun mode(): EnvironmentSelectionMode = router.mode()

    fun updateGnss(sample: GnssQualitySample): Boolean =
        GnssOutdoorEvidenceInterpreter.interpret(sample)?.let(buffer::update) ?: false

    fun routeFrame(
        frame: VisionFrame,
        semanticDetections: List<SceneSemanticDetection>,
        dedicatedVisualSignal: EnvironmentSignal? = null,
        nowNanos: Long,
        bothProfilesAvailable: Boolean,
    ): TimestampedDepthProfileDecision {
        sceneClassifier.classify(
            frame.frameId,
            frame.captureMonotonicTimestampNanos,
            semanticDetections,
        )?.let(buffer::update)
        dedicatedVisualSignal?.let {
            require(it.family == EnvironmentSignalFamily.CAMERA ||
                it.family == EnvironmentSignalFamily.VLM_CAMERA)
            val originatingFrameId = requireNotNull(it.originatingFrameId)
            require(
                if (it.family == EnvironmentSignalFamily.CAMERA) {
                    originatingFrameId == frame.frameId
                } else {
                    originatingFrameId <= frame.frameId
                },
            )
            require(it.timestampNanos <= frame.captureMonotonicTimestampNanos)
            buffer.update(it)
        }
        val evidence = fusion.fuse(frame.captureMonotonicTimestampNanos, buffer.snapshot())
        return router.route(
            frame.frameId,
            frame.captureMonotonicTimestampNanos,
            nowNanos,
            evidence,
            bothProfilesAvailable,
        )
    }
}

private fun DepthEnvironment.toSceneState(): SceneEnvironmentState = when (this) {
    DepthEnvironment.INDOOR -> SceneEnvironmentState.INDOOR
    DepthEnvironment.OUTDOOR -> SceneEnvironmentState.OUTDOOR
}
