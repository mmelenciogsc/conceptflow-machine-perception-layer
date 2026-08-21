// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.core

import org.conceptflow.mpl.v1.PerceptionCue
import org.conceptflow.mpl.v1.Urgency

enum class Verbosity {
    MINIMAL,
    STANDARD,
    DETAILED,
}

data class CueModalityPolicy(
    val allowEarcon: Boolean = true,
    val allowSpeech: Boolean = true,
    val allowHaptic: Boolean = true,
)

data class CueSchedulingPolicy(
    val minimumConfidence: Double = 0.55,
    val cooldownMillis: Long = 900L,
    val maximumQueueDepth: Int = 8,
    val preemptionPriorityDelta: Int = 10,
    val verbosity: Verbosity = Verbosity.STANDARD,
    val modalities: CueModalityPolicy = CueModalityPolicy(),
    val maximumCooldownEntries: Int = 1_024,
) {
    init {
        require(minimumConfidence in 0.0..1.0)
        require(cooldownMillis in 0L..60_000L)
        require(maximumQueueDepth in 1..64)
        require(maximumCooldownEntries in 1..65_536)
        require(preemptionPriorityDelta in 1..100)
    }
}

enum class CueDropReason {
    INVALID,
    STALE,
    LOW_CONFIDENCE,
    VERBOSITY,
    NO_ALLOWED_MODALITY,
    DEDUP_COOLDOWN,
    CAPACITY,
}

sealed interface SchedulingDecision {
    data class Enqueued(val cue: PerceptionCue) : SchedulingDecision
    data class Preempted(val previousCueId: String, val cue: PerceptionCue) : SchedulingDecision
    data class Cancelled(val cueIds: Set<String>) : SchedulingDecision
    data class Dropped(val reason: CueDropReason) : SchedulingDecision
}

class CueScheduler(private val policy: CueSchedulingPolicy) {
    private val queued = mutableListOf<PerceptionCue>()
    private val cooldownUntil = LinkedHashMap<String, Long>()
    private var active: PerceptionCue? = null

    @Synchronized
    fun submit(cue: PerceptionCue, nowNanos: Long): SchedulingDecision {
        val cancellationIds = buildSet {
            if (cue.hasCancel()) addAll(cue.cancel.cueIdsList)
            if (cue.hasSupersede()) addAll(cue.supersede.cueIdsList)
        }.filter { it.isNotBlank() }.toSet()
        if (cancellationIds.isNotEmpty()) cancelIds(cancellationIds)
        if (!cue.hasEarcon() && !cue.hasSpeech() && !cue.hasHaptic()) {
            return if (cancellationIds.isNotEmpty()) {
                SchedulingDecision.Cancelled(cancellationIds)
            } else {
                SchedulingDecision.Dropped(CueDropReason.NO_ALLOWED_MODALITY)
            }
        }
        if (cue.cueId.isBlank() || cue.ttlMs == 0 || cue.confidence !in 0.0..1.0 ||
            cue.createdMonotonicTimestampNs > nowNanos
        ) {
            return SchedulingDecision.Dropped(CueDropReason.INVALID)
        }
        if (isExpired(cue, nowNanos)) return SchedulingDecision.Dropped(CueDropReason.STALE)
        if (cue.confidence < policy.minimumConfidence) {
            return SchedulingDecision.Dropped(CueDropReason.LOW_CONFIDENCE)
        }
        if (policy.verbosity == Verbosity.MINIMAL && cue.urgency.number < Urgency.URGENCY_HIGH.number && cue.priority < 70) {
            return SchedulingDecision.Dropped(CueDropReason.VERBOSITY)
        }

        val filtered = filterModalities(cue)
            ?: return SchedulingDecision.Dropped(CueDropReason.NO_ALLOWED_MODALITY)
        val key = semanticKey(filtered)
        pruneCooldowns(nowNanos)
        if ((cooldownUntil[key] ?: Long.MIN_VALUE) > nowNanos) {
            return SchedulingDecision.Dropped(CueDropReason.DEDUP_COOLDOWN)
        }
        if (key !in cooldownUntil && cooldownUntil.size == policy.maximumCooldownEntries) {
            return SchedulingDecision.Dropped(CueDropReason.CAPACITY)
        }

        val current = active
        if (current != null && shouldPreempt(current, filtered)) {
            active = filtered
            cooldownUntil[key] = nowNanos + policy.cooldownMillis * 1_000_000L
            return SchedulingDecision.Preempted(current.cueId, filtered)
        }

        if (queued.size == policy.maximumQueueDepth) {
            val worst = queued.minWithOrNull(cueComparator)
            if (worst == null || cueComparator.compare(filtered, worst) <= 0) {
                return SchedulingDecision.Dropped(CueDropReason.CAPACITY)
            }
            queued.remove(worst)
        }
        queued += filtered
        cooldownUntil[key] = nowNanos + policy.cooldownMillis * 1_000_000L
        return SchedulingDecision.Enqueued(filtered)
    }

    @Synchronized
    fun next(nowNanos: Long): PerceptionCue? {
        if (active?.let { !isExpired(it, nowNanos) } == true) return null
        active = null
        queued.removeAll { isExpired(it, nowNanos) }
        val next = queued.maxWithOrNull(cueComparator) ?: return null
        queued.remove(next)
        active = next
        return next
    }

    @Synchronized
    fun complete(cueId: String): Boolean {
        if (active?.cueId != cueId) return false
        active = null
        return true
    }

    @Synchronized
    fun cancel(cueIds: Set<String>): Int = cancelIds(cueIds)

    @Synchronized
    fun queuedCount(): Int = queued.size

    @Synchronized
    fun activeCueId(): String? = active?.cueId

    @Synchronized
    fun cooldownCount(): Int = cooldownUntil.size

    private fun filterModalities(cue: PerceptionCue): PerceptionCue? {
        val builder = cue.toBuilder()
        if (!policy.modalities.allowEarcon) builder.clearEarcon()
        if (!policy.modalities.allowSpeech) builder.clearSpeech()
        if (!policy.modalities.allowHaptic) builder.clearHaptic()
        val filtered = builder.build()
        return filtered.takeIf { it.hasEarcon() || it.hasSpeech() || it.hasHaptic() }
    }

    private fun shouldPreempt(current: PerceptionCue, incoming: PerceptionCue): Boolean =
        incoming.priority >= current.priority + policy.preemptionPriorityDelta ||
            incoming.urgency.number > current.urgency.number

    private fun cancelIds(ids: Set<String>): Int {
        var removed = queued.count { it.cueId in ids }
        queued.removeAll { it.cueId in ids }
        if (active?.cueId in ids) {
            active = null
            removed += 1
        }
        return removed
    }

    private fun isExpired(cue: PerceptionCue, nowNanos: Long): Boolean =
        nowNanos - cue.createdMonotonicTimestampNs >= cue.ttlMs.toLong() * 1_000_000L

    private fun pruneCooldowns(nowNanos: Long) {
        val iterator = cooldownUntil.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value <= nowNanos) iterator.remove()
        }
    }

    private fun semanticKey(cue: PerceptionCue): String = listOf(
        cue.category.number,
        cue.direction.number,
        cue.description.trim().lowercase(),
    ).joinToString("|")

    private companion object {
        val cueComparator = compareBy<PerceptionCue>({ it.priority }, { it.urgency.number })
            .thenByDescending { it.createdMonotonicTimestampNs }
    }
}
