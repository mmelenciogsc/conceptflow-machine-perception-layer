// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticPlannerTest {
    @Test
    fun prefersSupportedPrimitives() {
        val pulse = HapticPlanner.plan(1, 0.6f, 80, capabilities(clickPrimitive = true))!!
        val ramp = HapticPlanner.plan(3, 0.6f, 80, capabilities(quickRisePrimitive = true))!!
        assertEquals(HapticPlanKind.PRIMITIVE, pulse.kind)
        assertEquals(PrimitiveKind.CLICK, pulse.primitiveSteps.single().kind)
        assertEquals(PrimitiveKind.QUICK_RISE, ramp.primitiveSteps.single().kind)
    }

    @Test
    fun usesSupportedPredefinedEffectBeforeWaveform() {
        val plan = HapticPlanner.plan(2, 0.5f, 120, capabilities(doubleClickEffect = true))!!
        assertEquals(HapticPlanKind.PREDEFINED, plan.kind)
        assertEquals(PredefinedKind.DOUBLE_CLICK, plan.predefined)
    }

    @Test
    fun waveformFallbackIsShortBoundedAndNonSpatial() {
        val single = HapticPlanner.plan(2, 0.5f, 500, capabilities(apiLevel = 29))!!
        val multiple = HapticPlanner.plan(3, 0.5f, 500, capabilities(apiLevel = 29, actuatorCount = 3))!!
        assertEquals(HapticPlanKind.WAVEFORM, single.kind)
        assertTrue(single.waveformSteps.size <= 5)
        assertTrue(single.waveformSteps.sumOf { it.durationMs } <= 500)
        assertFalse(single.spatiallyLocalized)
        assertFalse(multiple.spatiallyLocalized)
    }

    @Test
    fun boundsDurationAndIntensity() {
        val plan = HapticPlanner.plan(1, 2f, 1_000, capabilities(apiLevel = 29))!!
        assertEquals(500, plan.durationMs)
        assertEquals(1f, plan.intensity)
        assertEquals(255, plan.waveformSteps.single().amplitude)
        assertTrue(plan.waveformSteps.single().durationMs <= 120)
    }

    @Test
    fun rejectsAbsentActuatorZeroNonFiniteAndUnknownPatterns() {
        assertNull(HapticPlanner.plan(1, 1f, 80, capabilities(actuatorCount = 0)))
        assertNull(HapticPlanner.plan(1, 0f, 80, capabilities()))
        assertNull(HapticPlanner.plan(1, Float.NaN, 80, capabilities()))
        assertNull(HapticPlanner.plan(0, 1f, 80, capabilities()))
        assertNull(HapticPlanner.plan(99, 1f, 80, capabilities()))
    }

    private fun capabilities(
        apiLevel: Int = 30,
        actuatorCount: Int = 1,
        clickPrimitive: Boolean = false,
        quickRisePrimitive: Boolean = false,
        doubleClickEffect: Boolean = false,
    ) = HapticCapabilities(
        apiLevel,
        actuatorCount,
        amplitudeControl = true,
        clickPrimitive,
        quickRisePrimitive,
        clickEffect = false,
        doubleClickEffect,
        tickEffect = false,
    )
}
