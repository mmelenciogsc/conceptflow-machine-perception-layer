// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.v1.Direction
import org.conceptflow.mpl.v1.Haptic
import org.conceptflow.mpl.v1.PerceptionCue
import java.util.ArrayDeque

enum class RenderDisposition {
    RENDERED,
    STALE,
    DUPLICATE,
    INVALID,
    CONTROL_ONLY,
    NO_RENDERABLE_MODALITY,
    OUTPUT_UNAVAILABLE,
}

data class StereoBalance(val left: Float, val right: Float)

data class CueRenderEvent(
    val sessionId: String,
    val streamId: String,
    val cueId: String,
    val frameId: Long,
    val disposition: RenderDisposition,
    val balance: StereoBalance? = null,
    val audioPlayed: Boolean = false,
    val hapticPlayed: Boolean = false,
)

fun interface AudioCueOutput {
    fun play(earconId: String, gain: Float, pitch: Float, balance: StereoBalance): Boolean
}

fun interface HapticCueOutput {
    fun play(haptic: Haptic): Boolean
}

interface CueRenderer {
    fun render(envelope: CueEnvelope): CueRenderEvent
}

data class CueOutputPolicy(
    val allowEarcon: Boolean = true,
    val allowHaptic: Boolean = true,
)

class InspectableCueRenderer(
    private val clock: MonotonicClock,
    private val audio: AudioCueOutput? = null,
    private val haptics: HapticCueOutput? = null,
    private val eventCapacity: Int = 64,
    private val historyCapacity: Int = 64,
    private val cueIdsPerHistory: Int = 128,
    private val outputPolicy: CueOutputPolicy = CueOutputPolicy(),
) : CueRenderer {
    private val events = ArrayDeque<CueRenderEvent>()
    private val histories = LinkedHashMap<CueStreamKey, CueHistory>()

    init {
        require(eventCapacity in 1..1024)
        require(historyCapacity in 1..1024)
        require(cueIdsPerHistory in 1..4096)
    }

    @Synchronized
    override fun render(envelope: CueEnvelope): CueRenderEvent {
        val cue = envelope.cue
        val now = clock.nowNanos()
        val key = CueStreamKey(envelope.sessionId, envelope.streamId)
        val history = touchHistory(key)
        val controlIds = buildSet {
            if (cue.hasCancel()) addAll(cue.cancel.cueIdsList.filter(String::isNotBlank))
            if (cue.hasSupersede()) addAll(cue.supersede.cueIdsList.filter(String::isNotBlank))
        }
        val hasControl = cue.hasCancel() || cue.hasSupersede()
        val hasEarcon = outputPolicy.allowEarcon && cue.hasEarcon()
        val hasHaptic = outputPolicy.allowHaptic && cue.hasHaptic()
        val hasRenderableModality = hasEarcon || hasHaptic
        val disposition = when {
            envelope.sessionId.isBlank() || envelope.streamId.isBlank() -> RenderDisposition.INVALID
            hasControl && controlIds.isEmpty() -> RenderDisposition.INVALID
            !hasRenderableModality && hasControl -> RenderDisposition.CONTROL_ONLY
            !hasRenderableModality -> RenderDisposition.NO_RENDERABLE_MODALITY
            cue.cueId.isBlank() || cue.ttlMs == 0 || cue.confidence !in 0.0..1.0 -> RenderDisposition.INVALID
            cue.createdMonotonicTimestampNs > now -> RenderDisposition.INVALID
            now - cue.createdMonotonicTimestampNs >= cue.ttlMs.toLong() * 1_000_000L -> RenderDisposition.STALE
            history != null && cue.frameId != 0L && cue.frameId < history.lastRenderedFrameId -> RenderDisposition.STALE
            history != null && cue.cueId in history.renderedCueIds -> RenderDisposition.DUPLICATE
            else -> RenderDisposition.RENDERED
        }

        var balance: StereoBalance? = null
        var audioPlayed = false
        var hapticPlayed = false
        var finalDisposition = disposition
        if (finalDisposition == RenderDisposition.RENDERED) {
            if (hasEarcon) {
                val requestedBalance = balanceFor(cue.direction)
                audioPlayed = runCatching {
                    audio?.play(
                        cue.earcon.earconId,
                        cue.earcon.gain.coerceIn(0f, 0.75f),
                        cue.earcon.pitch.coerceIn(0.5f, 2f),
                        requestedBalance,
                    ) == true
                }.getOrDefault(false)
                if (audioPlayed) balance = requestedBalance
            }
            if (hasHaptic) {
                hapticPlayed = runCatching { haptics?.play(cue.haptic) == true }.getOrDefault(false)
            }
            if (audioPlayed || hapticPlayed) {
                recordRendered(key, cue)
            } else {
                finalDisposition = RenderDisposition.OUTPUT_UNAVAILABLE
            }
        }

        return CueRenderEvent(
            sessionId = envelope.sessionId,
            streamId = envelope.streamId,
            cueId = cue.cueId,
            frameId = cue.frameId,
            disposition = finalDisposition,
            balance = balance,
            audioPlayed = audioPlayed,
            hapticPlayed = hapticPlayed,
        ).also {
            events.addLast(it)
            while (events.size > eventCapacity) events.removeFirst()
        }
    }

    @Synchronized
    fun snapshot(): List<CueRenderEvent> = events.toList()

    @Synchronized
    fun historyCount(): Int = histories.size

    private fun touchHistory(key: CueStreamKey): CueHistory? {
        val history = histories.remove(key) ?: return null
        histories[key] = history
        return history
    }

    private fun recordRendered(key: CueStreamKey, cue: PerceptionCue) {
        val history = histories.remove(key) ?: CueHistory()
        history.renderedCueIds += cue.cueId
        while (history.renderedCueIds.size > cueIdsPerHistory) {
            history.renderedCueIds.remove(history.renderedCueIds.first())
        }
        history.lastRenderedFrameId = maxOf(history.lastRenderedFrameId, cue.frameId)
        histories[key] = history
        while (histories.size > historyCapacity) histories.remove(histories.keys.first())
    }

    companion object {
        fun balanceFor(direction: Direction): StereoBalance = when (direction) {
            Direction.DIRECTION_LEFT -> StereoBalance(left = 0.72f, right = 0.18f)
            Direction.DIRECTION_RIGHT -> StereoBalance(left = 0.18f, right = 0.72f)
            else -> StereoBalance(left = 0.52f, right = 0.52f)
        }
    }
}

private data class CueStreamKey(val sessionId: String, val streamId: String)

private data class CueHistory(
    var lastRenderedFrameId: Long = 0L,
    val renderedCueIds: LinkedHashSet<String> = LinkedHashSet(),
)
