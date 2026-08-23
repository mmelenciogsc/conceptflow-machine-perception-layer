// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuSignalReadinessTest {
    @Test
    fun `rotation arriving first cannot emit incomplete sample`() {
        val readiness = ImuSignalReadiness()

        assertFalse(readiness.canAssembleAt(100L))
        assertTrue(readiness.acceptAngularVelocity(80L))
        assertFalse(readiness.canAssembleAt(100L))
        assertTrue(readiness.acceptLinearAcceleration(90L))
        assertTrue(readiness.canAssembleAt(100L))
    }

    @Test
    fun `all callback orderings fail closed until both components precede orientation`() {
        val permutations = listOf(
            listOf(Signal.ORIENTATION, Signal.ANGULAR, Signal.LINEAR),
            listOf(Signal.ORIENTATION, Signal.LINEAR, Signal.ANGULAR),
            listOf(Signal.ANGULAR, Signal.ORIENTATION, Signal.LINEAR),
            listOf(Signal.LINEAR, Signal.ORIENTATION, Signal.ANGULAR),
            listOf(Signal.ANGULAR, Signal.LINEAR, Signal.ORIENTATION),
            listOf(Signal.LINEAR, Signal.ANGULAR, Signal.ORIENTATION),
        )

        permutations.forEach { ordering ->
            val readiness = ImuSignalReadiness()
            var emittedBeforeReady = false
            ordering.forEach { signal ->
                when (signal) {
                    Signal.ANGULAR -> readiness.acceptAngularVelocity(80L)
                    Signal.LINEAR -> readiness.acceptLinearAcceleration(90L)
                    Signal.ORIENTATION -> emittedBeforeReady = readiness.canAssembleAt(100L)
                }
            }
            val orientationWasLast = ordering.last() == Signal.ORIENTATION
            assertTrue(emittedBeforeReady == orientationWasLast)
            assertTrue(readiness.canAssembleAt(110L))
        }
    }

    @Test
    fun `newer component callback does not attach to older orientation`() {
        val readiness = ImuSignalReadiness()
        readiness.acceptAngularVelocity(120L)
        readiness.acceptLinearAcceleration(90L)

        assertFalse(readiness.canAssembleAt(100L))
        assertTrue(readiness.canAssembleAt(130L))
        assertFalse(readiness.acceptAngularVelocity(110L))
        assertTrue(readiness.canAssembleAt(130L))
    }

    @Test
    fun `reset makes a restarted source wait for fresh components`() {
        val readiness = ImuSignalReadiness()
        readiness.acceptAngularVelocity(80L)
        readiness.acceptLinearAcceleration(90L)
        assertTrue(readiness.canAssembleAt(100L))

        readiness.reset()

        assertFalse(readiness.canAssembleAt(110L))
    }

    private enum class Signal { ANGULAR, LINEAR, ORIENTATION }
}
