// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.feedback

import org.conceptflow.mpl.v1.PerceptionCue
import org.conceptflow.mpl.v1.Speech
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostCueDispatcherTest {
    @Test
    fun speechOnlyCueIsDeliveredOnlyWhenSpeechActuallyStarts() {
        val cue = speechCue()
        val spoken = HostCueDispatcher(
            audio = HostAudioFeedback { false },
            haptics = HostHapticFeedback { false },
            speech = FakeSpeechFeedback(HostSpeechFeedback.Outcome.SPOKEN),
        )
        val unavailable = HostCueDispatcher(
            audio = HostAudioFeedback { false },
            haptics = HostHapticFeedback { false },
            speech = FakeSpeechFeedback(HostSpeechFeedback.Outcome.UNAVAILABLE),
        )

        assertTrue(spoken.dispatch(cue))
        assertFalse(unavailable.dispatch(cue))
    }

    @Test
    fun suppressedSpeechIsDeliveredAsBoundedAccessibleText() {
        val exposed = mutableListOf<String>()
        val dispatcher = HostCueDispatcher(
            audio = HostAudioFeedback { false },
            haptics = HostHapticFeedback { false },
            speech = FakeSpeechFeedback(HostSpeechFeedback.Outcome.SUPPRESSED_FOR_ACCESSIBILITY),
            accessibleText = exposed::add,
        )

        assertTrue(dispatcher.dispatch(speechCue()))
        assertEquals(listOf("Door ahead"), exposed)
    }

    @Test
    fun unavailableSpeechInMixedCueStillExposesEquivalentText() {
        val exposed = mutableListOf<String>()
        val cue = speechCue().toBuilder()
            .setDescription("Fallback description")
            .setSpeech(Speech.newBuilder().setText("  Door\n\tahead  ").setInterrupt(true))
            .build()
        val dispatcher = HostCueDispatcher(
            audio = HostAudioFeedback { true },
            haptics = HostHapticFeedback { false },
            speech = FakeSpeechFeedback(HostSpeechFeedback.Outcome.UNAVAILABLE),
            accessibleText = exposed::add,
        )

        assertTrue(dispatcher.dispatch(cue))
        assertEquals(listOf("Door ahead"), exposed)
    }

    @Test
    fun unavailableSpeechFallbackIsBoundedAndUsesDescriptionWhenSpeechIsBlank() {
        val exposed = mutableListOf<String>()
        val cue = speechCue().toBuilder()
            .setDescription("x".repeat(600))
            .setSpeech(Speech.newBuilder().setText("  \n "))
            .build()
        val dispatcher = HostCueDispatcher(
            audio = HostAudioFeedback { false },
            haptics = HostHapticFeedback { false },
            speech = FakeSpeechFeedback(HostSpeechFeedback.Outcome.UNAVAILABLE),
            accessibleText = exposed::add,
        )

        assertTrue(dispatcher.dispatch(cue))
        assertEquals(512, exposed.single().length)
    }

    @Test
    fun modalityFreeCueIsNotReportedAsDelivered() {
        val dispatcher = HostCueDispatcher(
            audio = HostAudioFeedback { true },
            haptics = HostHapticFeedback { true },
            speech = FakeSpeechFeedback(HostSpeechFeedback.Outcome.SPOKEN),
        )

        assertFalse(dispatcher.dispatch(PerceptionCue.getDefaultInstance()))
    }

    private fun speechCue(): PerceptionCue = PerceptionCue.newBuilder()
        .setCueId("speech")
        .setSpeech(Speech.newBuilder().setText("Door ahead").setInterrupt(true))
        .build()

    private class FakeSpeechFeedback(private val outcome: HostSpeechFeedback.Outcome) : HostSpeechFeedback {
        override fun speak(text: String, interrupt: Boolean): HostSpeechFeedback.Outcome = outcome
        override fun close() = Unit
    }
}
