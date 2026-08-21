// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.feedback

data class HapticCapabilities(
    val apiLevel: Int,
    val actuatorCount: Int,
    val amplitudeControl: Boolean,
    val clickPrimitive: Boolean,
    val quickRisePrimitive: Boolean,
    val clickEffect: Boolean,
    val doubleClickEffect: Boolean,
    val tickEffect: Boolean,
) {
    init {
        require(apiLevel >= 0)
        require(actuatorCount >= 0)
    }
}

enum class HapticPlanKind { PRIMITIVE, PREDEFINED, WAVEFORM }

enum class PrimitiveKind { CLICK, QUICK_RISE }

enum class PredefinedKind { CLICK, DOUBLE_CLICK, TICK }

data class PrimitiveStep(val kind: PrimitiveKind, val scale: Float, val delayMs: Int) {
    init {
        require(scale.isFinite() && scale in 0f..1f)
        require(delayMs in 0..500)
    }
}

data class WaveformStep(val durationMs: Int, val amplitude: Int) {
    init {
        require(durationMs in 1..500)
        require(amplitude in 0..255)
    }
}

data class HapticPlan(
    val kind: HapticPlanKind,
    val durationMs: Int,
    val intensity: Float,
    val primitiveSteps: List<PrimitiveStep> = emptyList(),
    val predefined: PredefinedKind? = null,
    val waveformSteps: List<WaveformStep> = emptyList(),
    val spatiallyLocalized: Boolean = false,
) {
    init {
        require(durationMs in 20..500)
        require(intensity.isFinite() && intensity in 0f..1f)
        require(!spatiallyLocalized) { "The default-actuator adapter cannot claim spatial haptics" }
        require(waveformSteps.size <= 5)
        require(
            when (kind) {
                HapticPlanKind.PRIMITIVE -> primitiveSteps.isNotEmpty() && predefined == null && waveformSteps.isEmpty()
                HapticPlanKind.PREDEFINED -> primitiveSteps.isEmpty() && predefined != null && waveformSteps.isEmpty()
                HapticPlanKind.WAVEFORM -> primitiveSteps.isEmpty() && predefined == null && waveformSteps.isNotEmpty()
            },
        )
    }
}

object HapticPlanner {
    const val PATTERN_UNSPECIFIED = 0
    const val PATTERN_PULSE = 1
    const val PATTERN_DOUBLE_PULSE = 2
    const val PATTERN_RAMP = 3

    fun plan(
        pattern: Int,
        intensity: Float,
        durationMs: Int,
        capabilities: HapticCapabilities,
    ): HapticPlan? {
        if (capabilities.actuatorCount <= 0 || !intensity.isFinite()) return null
        val boundedIntensity = intensity.coerceIn(0f, 1f)
        if (boundedIntensity == 0f || pattern == PATTERN_UNSPECIFIED) return null
        val boundedDuration = durationMs.coerceIn(20, 500)
        primitive(pattern, boundedIntensity, boundedDuration, capabilities)?.let { return it }
        predefined(pattern, boundedIntensity, boundedDuration, capabilities)?.let { return it }
        return waveform(pattern, boundedIntensity, boundedDuration, capabilities.amplitudeControl)
    }

    private fun primitive(
        pattern: Int,
        intensity: Float,
        durationMs: Int,
        capabilities: HapticCapabilities,
    ): HapticPlan? {
        if (capabilities.apiLevel < 30) return null
        val steps = when (pattern) {
            PATTERN_PULSE -> if (capabilities.clickPrimitive) listOf(PrimitiveStep(PrimitiveKind.CLICK, intensity, 0)) else null
            PATTERN_DOUBLE_PULSE -> if (capabilities.clickPrimitive) {
                listOf(
                    PrimitiveStep(PrimitiveKind.CLICK, intensity, 0),
                    PrimitiveStep(PrimitiveKind.CLICK, intensity, (durationMs / 3).coerceIn(30, 100)),
                )
            } else null
            PATTERN_RAMP -> if (capabilities.quickRisePrimitive) listOf(PrimitiveStep(PrimitiveKind.QUICK_RISE, intensity, 0)) else null
            else -> null
        } ?: return null
        return HapticPlan(HapticPlanKind.PRIMITIVE, durationMs, intensity, primitiveSteps = steps)
    }

    private fun predefined(
        pattern: Int,
        intensity: Float,
        durationMs: Int,
        capabilities: HapticCapabilities,
    ): HapticPlan? {
        if (capabilities.apiLevel < 29) return null
        val effect = when (pattern) {
            PATTERN_PULSE -> when {
                capabilities.clickEffect -> PredefinedKind.CLICK
                capabilities.tickEffect -> PredefinedKind.TICK
                else -> null
            }
            PATTERN_DOUBLE_PULSE -> if (capabilities.doubleClickEffect) PredefinedKind.DOUBLE_CLICK else null
            PATTERN_RAMP -> if (capabilities.tickEffect) PredefinedKind.TICK else null
            else -> null
        } ?: return null
        return HapticPlan(HapticPlanKind.PREDEFINED, durationMs, intensity, predefined = effect)
    }

    private fun waveform(pattern: Int, intensity: Float, durationMs: Int, amplitudeControl: Boolean): HapticPlan? {
        val amplitude = if (amplitudeControl) (intensity * 255f).toInt().coerceIn(1, 255) else 255
        val steps = when (pattern) {
            PATTERN_PULSE -> listOf(WaveformStep(durationMs.coerceAtMost(120), amplitude))
            PATTERN_DOUBLE_PULSE -> {
                val pulse = (durationMs / 3).coerceIn(20, 80)
                listOf(WaveformStep(pulse, amplitude), WaveformStep(50, 0), WaveformStep(pulse, amplitude))
            }
            PATTERN_RAMP -> {
                val segment = (durationMs.coerceAtMost(240) / 4).coerceAtLeast(5)
                listOf(0.25f, 0.50f, 0.75f, 1f).map { scale ->
                    WaveformStep(segment, (amplitude * scale).toInt().coerceIn(1, 255))
                }
            }
            else -> return null
        }
        return HapticPlan(HapticPlanKind.WAVEFORM, durationMs, intensity, waveformSteps = steps)
    }
}
