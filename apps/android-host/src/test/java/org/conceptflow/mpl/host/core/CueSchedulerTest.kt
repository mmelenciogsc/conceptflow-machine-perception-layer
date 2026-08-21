// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.core

import org.conceptflow.mpl.v1.CueCancellation
import org.conceptflow.mpl.v1.PerceptionCue
import org.conceptflow.mpl.v1.Urgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CueSchedulerTest {
    @Test
    fun suppressesStaleLowConfidenceDuplicateAndDisallowedModality() {
        val scheduler = CueScheduler(
            CueSchedulingPolicy(
                minimumConfidence = 0.7,
                cooldownMillis = 100,
                modalities = CueModalityPolicy(allowEarcon = true, allowSpeech = false, allowHaptic = false),
            ),
        )
        val now = 2_000_000_000L
        assertDrop(CueDropReason.STALE, scheduler.submit(testCue("old", created = 1L, ttlMs = 1), now))
        assertDrop(CueDropReason.LOW_CONFIDENCE, scheduler.submit(testCue("uncertain", created = now, confidence = 0.6), now))
        assertTrue(scheduler.submit(testCue("first", created = now, description = "same"), now) is SchedulingDecision.Enqueued)
        assertDrop(
            CueDropReason.DEDUP_COOLDOWN,
            scheduler.submit(testCue("second", created = now, description = "same"), now + 1L),
        )

        val speechOnly = PerceptionCue.newBuilder(testCue("speech", created = now))
            .clearEarcon()
            .setSpeech(org.conceptflow.mpl.v1.Speech.newBuilder().setText("speech"))
            .build()
        assertDrop(CueDropReason.NO_ALLOWED_MODALITY, scheduler.submit(speechOnly, now))
    }

    @Test
    fun prioritizesBoundsAndPreemptsQueue() {
        val scheduler = CueScheduler(CueSchedulingPolicy(maximumQueueDepth = 2, preemptionPriorityDelta = 10))
        val now = 1_000L
        scheduler.submit(testCue("low", priority = 10, created = now), now)
        scheduler.submit(testCue("mid", priority = 30, created = now, description = "mid"), now)
        assertDrop(CueDropReason.CAPACITY, scheduler.submit(testCue("lowest", priority = 5, created = now), now))
        assertEquals("mid", scheduler.next(now)?.cueId)

        val high = testCue("critical", priority = 45, created = now, urgency = Urgency.URGENCY_CRITICAL)
        val decision = scheduler.submit(high, now)
        assertTrue(decision is SchedulingDecision.Preempted)
        assertEquals("critical", scheduler.activeCueId())
        assertTrue(scheduler.complete("critical"))
        assertEquals("low", scheduler.next(now)?.cueId)
    }

    @Test
    fun appliesVerbosityCancellationAndTtlAtDispatch() {
        val minimal = CueScheduler(CueSchedulingPolicy(verbosity = Verbosity.MINIMAL))
        val now = 5_000L
        assertDrop(CueDropReason.VERBOSITY, minimal.submit(testCue("routine", priority = 20, created = now), now))
        assertTrue(
            minimal.submit(testCue("urgent", priority = 20, created = now, urgency = Urgency.URGENCY_HIGH), now) is
                SchedulingDecision.Enqueued,
        )
        val cancel = PerceptionCue.newBuilder()
            .setCueId("cancel-message")
            .setCancel(CueCancellation.newBuilder().addCueIds("urgent").setReason("superseded"))
            .build()
        assertTrue(minimal.submit(cancel, now) is SchedulingDecision.Cancelled)
        assertNull(minimal.next(now))

        val expiry = CueScheduler(CueSchedulingPolicy())
        expiry.submit(testCue("short", created = now, ttlMs = 1), now)
        assertNull(expiry.next(now + 1_000_000L))
    }

    @Test
    fun cooldownCapacityDoesNotEvictLiveDeduplicationKeys() {
        val scheduler = CueScheduler(
            CueSchedulingPolicy(
                cooldownMillis = 1_000,
                maximumQueueDepth = 4,
                maximumCooldownEntries = 2,
            ),
        )
        val now = 10_000L
        assertTrue(scheduler.submit(testCue("one", created = now, description = "one"), now) is SchedulingDecision.Enqueued)
        assertTrue(scheduler.submit(testCue("two", created = now, description = "two"), now) is SchedulingDecision.Enqueued)

        assertDrop(
            CueDropReason.CAPACITY,
            scheduler.submit(testCue("three", created = now, description = "three"), now),
        )
        assertDrop(
            CueDropReason.DEDUP_COOLDOWN,
            scheduler.submit(testCue("duplicate", created = now, description = "one"), now + 1L),
        )
        assertEquals(2, scheduler.cooldownCount())
    }

    @Test
    fun expiredCooldownsArePrunedBeforeCapacityCheck() {
        val scheduler = CueScheduler(
            CueSchedulingPolicy(
                cooldownMillis = 100,
                maximumQueueDepth = 4,
                maximumCooldownEntries = 2,
            ),
        )
        val now = 10_000L
        assertTrue(scheduler.submit(testCue("one", created = now, description = "one"), now) is SchedulingDecision.Enqueued)
        assertTrue(scheduler.submit(testCue("two", created = now, description = "two"), now) is SchedulingDecision.Enqueued)
        assertEquals(2, scheduler.cooldownCount())

        val afterCooldown = now + 100_000_000L
        assertTrue(
            scheduler.submit(testCue("later", created = afterCooldown, description = "later"), afterCooldown) is
                SchedulingDecision.Enqueued,
        )
        assertEquals(1, scheduler.cooldownCount())
    }

    private fun assertDrop(reason: CueDropReason, decision: SchedulingDecision) {
        assertEquals(reason, (decision as SchedulingDecision.Dropped).reason)
    }
}
