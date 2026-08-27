// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandedAudioPolicyTest {
    @Test
    fun activationAndForcedTestScriptsPreserveExactWordsOrderAndPause() {
        assertEquals(
            listOf(
                BrandedAudioStep.Tone(BrandedToneKind.READY),
                BrandedAudioStep.Speech("Concept flow."),
                BrandedAudioStep.Pause(650L),
                BrandedAudioStep.Speech("Machine Intelligence."),
                BrandedAudioStep.Pause(600L),
                BrandedAudioStep.Speech("Human Architecture."),
                BrandedAudioStep.Pause(700L),
                BrandedAudioStep.Tone(BrandedToneKind.STARTUP_SEPARATOR),
                BrandedAudioStep.Pause(600L),
                BrandedAudioStep.Speech("Machine Perception Layer."),
                BrandedAudioStep.Pause(600L),
                BrandedAudioStep.Speech("Map."),
                BrandedAudioStep.Pause(480L),
                BrandedAudioStep.Speech("Morph."),
                BrandedAudioStep.Pause(480L),
                BrandedAudioStep.Speech("Move."),
                BrandedAudioStep.Pause(900L),
                BrandedAudioStep.Speech("It's just supplemental awareness."),
            ),
            BrandedAudioScript.activationReady(
                includeBootBrandLine = true,
                includeProductLine = true,
            ),
        )
        assertEquals(
            listOf(
                BrandedAudioStep.Tone(BrandedToneKind.READY),
                BrandedAudioStep.Speech("Concept flow."),
                BrandedAudioStep.Pause(650L),
                BrandedAudioStep.Speech("Machine Intelligence."),
                BrandedAudioStep.Pause(600L),
                BrandedAudioStep.Speech("Human Architecture."),
                BrandedAudioStep.Pause(700L),
                BrandedAudioStep.Tone(BrandedToneKind.STARTUP_SEPARATOR),
                BrandedAudioStep.Pause(600L),
                BrandedAudioStep.Speech("Machine Perception Layer."),
                BrandedAudioStep.Pause(600L),
                BrandedAudioStep.Speech("Map."),
                BrandedAudioStep.Pause(480L),
                BrandedAudioStep.Speech("Morph."),
                BrandedAudioStep.Pause(480L),
                BrandedAudioStep.Speech("Move."),
                BrandedAudioStep.Pause(900L),
                BrandedAudioStep.Speech("It's just supplemental awareness."),
            ),
            BrandedAudioScript.fullBrandTest(),
        )
        assertEquals(
            listOf(BrandedAudioStep.Tone(BrandedToneKind.READY)),
            BrandedAudioScript.activationReady(
                includeBootBrandLine = false,
                includeProductLine = false,
            ),
        )
    }

    @Test
    fun processStartupCanBeClaimedOnlyOnce() {
        val gate = ProcessStartupAnnouncementGate()

        assertTrue(gate.claim())
        assertFalse(gate.claim())
    }

    @Test
    fun productLineUsesRollingThreeDayBoundaryAndSuppressesClockRollback() {
        val previous = 1_000_000L

        assertTrue(ProductLineRepeatPolicy.mayClaim(null, previous))
        assertFalse(ProductLineRepeatPolicy.mayClaim(previous, previous - 1L))
        assertFalse(
            ProductLineRepeatPolicy.mayClaim(
                previous,
                previous + PRODUCT_LINE_REPEAT_INTERVAL_MILLIS - 1L,
            ),
        )
        assertTrue(
            ProductLineRepeatPolicy.mayClaim(
                previous,
                previous + PRODUCT_LINE_REPEAT_INTERVAL_MILLIS,
            ),
        )
    }

    @Test
    fun bootBrandLineClaimsEachKnownBootOnlyOnceAndAllowsProcessFallback() {
        assertTrue(BootBrandLinePolicy.mayClaim(lastClaimedBootCount = null, currentBootCount = 41))
        assertFalse(BootBrandLinePolicy.mayClaim(lastClaimedBootCount = 41, currentBootCount = 41))
        assertTrue(BootBrandLinePolicy.mayClaim(lastClaimedBootCount = 41, currentBootCount = 42))
        assertTrue(BootBrandLinePolicy.mayClaim(lastClaimedBootCount = 41, currentBootCount = null))
    }

    @Test
    fun authenticatedSessionToneIsEmittedOnlyForIncreasingReadyCount() {
        val gate = AuthenticatedSessionToneGate()

        assertFalse(gate.observe(0L))
        assertTrue(gate.observe(1L))
        assertFalse(gate.observe(1L))
        assertFalse(gate.observe(0L))
        assertTrue(gate.observe(2L))
    }

    @Test
    fun voicePolicyPrefersDeterministicLocalEnglishCapabilityWithoutGenderGuessing() {
        val selected = SpeechVoicePolicy.selectPreferredEnglish(
            listOf(
                SpeechVoiceCapability("network-premium", "en-US", true, 500, 100),
                SpeechVoiceCapability("local-gb", "en-GB", false, 500, 100),
                SpeechVoiceCapability("local-us-z", "en-US", false, 400, 100),
                SpeechVoiceCapability("local-us-a", "en-US", false, 400, 100),
                SpeechVoiceCapability("local-es", "es-US", false, 500, 50),
            ),
        )

        assertEquals("local-us-a", selected?.name)
        assertEquals(
            "network-only",
            SpeechVoicePolicy.selectPreferredEnglish(
                listOf(SpeechVoiceCapability("network-only", "en-US", true, 500, 100)),
            )?.name,
        )
        assertNull(SpeechVoicePolicy.selectPreferredEnglish(emptyList()))
    }
}
