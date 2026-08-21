// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.v1.Direction
import org.conceptflow.mpl.v1.CueCancellation
import org.conceptflow.mpl.v1.CueSupersession
import org.conceptflow.mpl.v1.Earcon
import org.conceptflow.mpl.v1.Haptic
import org.conceptflow.mpl.v1.HapticPattern
import org.conceptflow.mpl.v1.PerceptionCue
import org.conceptflow.mpl.v1.Speech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CueRendererTest {
    @Test
    fun rendersInspectableStereoBalanceAndOptionalHaptic() {
        val audio = mutableListOf<StereoBalance>()
        var haptics = 0
        val renderer = InspectableCueRenderer(
            clock = MonotonicClock { 1_000_000_000L },
            audio = AudioCueOutput { _, _, _, balance ->
                audio += balance
                true
            },
            haptics = HapticCueOutput {
                haptics += 1
                true
            },
        )

        val event = renderer.render(envelope(cue("left", 2L, 900_000_000L, Direction.DIRECTION_LEFT)))

        assertEquals(RenderDisposition.RENDERED, event.disposition)
        assertTrue(event.audioPlayed)
        assertTrue(audio.single().left > audio.single().right)
        assertEquals(1, haptics)
        assertEquals(event, renderer.snapshot().single())
    }

    @Test
    fun suppressesExpiredDuplicateAndOlderFrameCues() {
        var now = 2_000_000_000L
        val renderer = InspectableCueRenderer(
            MonotonicClock { now },
            audio = AudioCueOutput { _, _, _, _ -> true },
        )
        assertEquals(
            RenderDisposition.STALE,
            renderer.render(
                envelope(cue("expired", 1L, 1_000_000_000L, Direction.DIRECTION_AHEAD, ttlMs = 100)),
            ).disposition,
        )
        val current = cue("current", 10L, now, Direction.DIRECTION_RIGHT)
        assertEquals(RenderDisposition.RENDERED, renderer.render(envelope(current)).disposition)
        assertEquals(RenderDisposition.DUPLICATE, renderer.render(envelope(current)).disposition)
        now += 1L
        assertEquals(
            RenderDisposition.STALE,
            renderer.render(envelope(cue("older", 9L, now, Direction.DIRECTION_LEFT))).disposition,
        )
    }

    @Test
    fun reportsUnsupportedFilteredControlAndUnavailableOutputsTruthfully() {
        val speechOnly = PerceptionCue.newBuilder(cue("speech", 1L, 100L, Direction.DIRECTION_AHEAD))
            .clearEarcon()
            .clearHaptic()
            .setSpeech(Speech.newBuilder().setText("Ahead"))
            .build()
        val modalityFree = speechOnly.toBuilder().clearSpeech().setCueId("empty").build()
        val cancellation = PerceptionCue.newBuilder()
            .setCancel(CueCancellation.newBuilder().addCueIds("old-cue"))
            .build()
        val supersession = PerceptionCue.newBuilder()
            .setSupersede(CueSupersession.newBuilder().addCueIds("older-cue"))
            .build()
        val unavailable = InspectableCueRenderer(
            clock = MonotonicClock { 100L },
            audio = AudioCueOutput { _, _, _, _ -> false },
            haptics = HapticCueOutput { false },
        )

        assertEquals(
            RenderDisposition.NO_RENDERABLE_MODALITY,
            unavailable.render(envelope(speechOnly)).disposition,
        )
        assertEquals(
            RenderDisposition.NO_RENDERABLE_MODALITY,
            unavailable.render(envelope(modalityFree)).disposition,
        )
        assertEquals(RenderDisposition.CONTROL_ONLY, unavailable.render(envelope(cancellation)).disposition)
        assertEquals(RenderDisposition.CONTROL_ONLY, unavailable.render(envelope(supersession)).disposition)
        assertEquals(
            RenderDisposition.OUTPUT_UNAVAILABLE,
            unavailable.render(envelope(cue("unavailable", 2L, 100L, Direction.DIRECTION_LEFT))).disposition,
        )

        val filtered = InspectableCueRenderer(
            clock = MonotonicClock { 100L },
            audio = AudioCueOutput { _, _, _, _ -> true },
            haptics = HapticCueOutput { true },
            outputPolicy = CueOutputPolicy(allowEarcon = false, allowHaptic = false),
        )
        assertEquals(
            RenderDisposition.NO_RENDERABLE_MODALITY,
            filtered.render(envelope(cue("filtered", 3L, 100L, Direction.DIRECTION_LEFT))).disposition,
        )
        assertFalse(filtered.snapshot().single().audioPlayed)
    }

    @Test
    fun freshnessAndDeduplicationArePartitionedBySessionAndStream() {
        val renderer = InspectableCueRenderer(
            clock = MonotonicClock { 100L },
            audio = AudioCueOutput { _, _, _, _ -> true },
            historyCapacity = 3,
        )
        val high = cue("repeated", 10L, 100L, Direction.DIRECTION_LEFT)

        assertEquals(RenderDisposition.RENDERED, renderer.render(envelope(high, "one", "camera")).disposition)
        assertEquals(RenderDisposition.DUPLICATE, renderer.render(envelope(high, "one", "camera")).disposition)
        assertEquals(
            RenderDisposition.STALE,
            renderer.render(envelope(cue("older", 9L, 100L, Direction.DIRECTION_LEFT), "one", "camera")).disposition,
        )
        assertEquals(
            RenderDisposition.RENDERED,
            renderer.render(envelope(cue("repeated", 1L, 100L, Direction.DIRECTION_LEFT), "two", "camera")).disposition,
        )
        assertEquals(
            RenderDisposition.RENDERED,
            renderer.render(envelope(cue("repeated", 1L, 100L, Direction.DIRECTION_LEFT), "one", "depth")).disposition,
        )
        assertEquals(3, renderer.historyCount())
    }

    @Test
    fun sessionHistoryEvictionIsDeterministicAndBounded() {
        val renderer = InspectableCueRenderer(
            clock = MonotonicClock { 100L },
            audio = AudioCueOutput { _, _, _, _ -> true },
            historyCapacity = 2,
        )
        renderer.render(envelope(cue("same", 10L, 100L, Direction.DIRECTION_LEFT), "one", "camera"))
        renderer.render(envelope(cue("two", 10L, 100L, Direction.DIRECTION_LEFT), "two", "camera"))
        renderer.render(envelope(cue("three", 10L, 100L, Direction.DIRECTION_LEFT), "three", "camera"))
        assertEquals(2, renderer.historyCount())

        assertEquals(
            RenderDisposition.RENDERED,
            renderer.render(
                envelope(cue("same", 1L, 100L, Direction.DIRECTION_LEFT), "one", "camera"),
            ).disposition,
        )
        assertEquals(2, renderer.historyCount())
    }

    private fun cue(
        id: String,
        frameId: Long,
        created: Long,
        direction: Direction,
        ttlMs: Int = 1_000,
    ): PerceptionCue = PerceptionCue.newBuilder()
        .setCueId(id)
        .setFrameId(frameId)
        .setCreatedMonotonicTimestampNs(created)
        .setTtlMs(ttlMs)
        .setDescription(id)
        .setConfidence(0.9)
        .setDirection(direction)
        .setEarcon(Earcon.newBuilder().setEarconId("tone").setGain(0.5f).setPitch(1f))
        .setHaptic(
            Haptic.newBuilder()
                .setPattern(HapticPattern.HAPTIC_PATTERN_PULSE)
                .setIntensity(0.3f)
                .setDurationMs(50),
        )
        .build()

    private fun envelope(
        cue: PerceptionCue,
        sessionId: String = "session",
        streamId: String = "camera",
    ): CueEnvelope = CueEnvelope(sessionId, streamId, cue)
}
