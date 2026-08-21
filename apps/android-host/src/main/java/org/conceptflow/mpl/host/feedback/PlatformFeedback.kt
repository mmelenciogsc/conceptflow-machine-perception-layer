// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityManager
import org.conceptflow.mpl.v1.HapticPattern
import org.conceptflow.mpl.v1.PerceptionCue
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

fun interface HostAudioFeedback {
    fun play(cue: PerceptionCue): Boolean
}

fun interface HostHapticFeedback {
    fun play(cue: PerceptionCue): Boolean
}

interface HostSpeechFeedback : AutoCloseable {
    enum class Outcome { SPOKEN, SUPPRESSED_FOR_ACCESSIBILITY, UNAVAILABLE }
    fun speak(text: String, interrupt: Boolean = true): Outcome
}

class PlatformHostAudioFeedback : HostAudioFeedback, AutoCloseable {
    private val tone = ToneGenerator(AudioManager.STREAM_ACCESSIBILITY, 55)

    override fun play(cue: PerceptionCue): Boolean =
        cue.hasEarcon() && tone.startTone(ToneGenerator.TONE_PROP_BEEP, 100)

    override fun close() = tone.release()
}

class PlatformHostHapticFeedback(context: Context) : HostHapticFeedback {
    private val vibrator = context.getSystemService(Vibrator::class.java)

    override fun play(cue: PerceptionCue): Boolean {
        if (!cue.hasHaptic() || vibrator?.hasVibrator() != true) return false
        val strength = (cue.haptic.intensity.coerceIn(0f, 1f) * 140f).toInt().coerceIn(1, 140)
        val duration = cue.haptic.durationMs.toLong().coerceIn(20L, 500L)
        val effect = if (cue.haptic.pattern == HapticPattern.HAPTIC_PATTERN_DOUBLE_PULSE) {
            VibrationEffect.createWaveform(
                longArrayOf(0L, duration / 2L, 60L, duration / 2L),
                intArrayOf(0, strength, 0, strength),
                -1,
            )
        } else {
            VibrationEffect.createOneShot(duration, strength)
        }
        vibrator.vibrate(effect)
        return true
    }
}

class AccessibilityAwareSpeechFeedback(context: Context) : HostSpeechFeedback {
    private val accessibility = context.getSystemService(AccessibilityManager::class.java)
    private val utteranceIds = AtomicLong(0L)
    @Volatile
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.also { it.language = Locale.getDefault() }

    override fun speak(text: String, interrupt: Boolean): HostSpeechFeedback.Outcome {
        if (accessibility?.isEnabled == true) return HostSpeechFeedback.Outcome.SUPPRESSED_FOR_ACCESSIBILITY
        if (!ready || text.isBlank()) return HostSpeechFeedback.Outcome.UNAVAILABLE
        val queueMode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = tts.speak(text, queueMode, null, "conceptflow-host-${utteranceIds.incrementAndGet()}")
        return if (result == TextToSpeech.SUCCESS) {
            HostSpeechFeedback.Outcome.SPOKEN
        } else {
            HostSpeechFeedback.Outcome.UNAVAILABLE
        }
    }

    override fun close() {
        tts.stop()
        tts.shutdown()
    }
}

class HostCueDispatcher(
    private val audio: HostAudioFeedback,
    private val haptics: HostHapticFeedback,
    private val speech: HostSpeechFeedback,
    private val accessibleText: ((String) -> Unit)? = null,
) {
    fun dispatch(cue: PerceptionCue): Boolean {
        var rendered = false
        if (cue.hasEarcon()) rendered = runCatching { audio.play(cue) }.getOrDefault(false) || rendered
        if (cue.hasHaptic()) rendered = runCatching { haptics.play(cue) }.getOrDefault(false) || rendered
        if (cue.hasSpeech()) {
            val outcome = runCatching { speech.speak(cue.speech.text, cue.speech.interrupt) }
                .getOrDefault(HostSpeechFeedback.Outcome.UNAVAILABLE)
            if (outcome == HostSpeechFeedback.Outcome.SPOKEN) {
                rendered = true
            } else {
                val text = boundedAccessibleText(cue)
                val sink = accessibleText
                if (text != null && sink != null) {
                    runCatching { sink(text) }.onSuccess { rendered = true }
                }
            }
        }
        return rendered
    }

    private fun boundedAccessibleText(cue: PerceptionCue): String? {
        val source = cue.speech.text.takeIf { it.isNotBlank() } ?: cue.description
        val normalized = source
            .filter { !it.isISOControl() || it.isWhitespace() }
            .trim()
            .replace(Regex("\\s+"), " ")
        return normalized.take(MAX_ACCESSIBLE_CUE_TEXT_CHARS).takeIf { it.isNotBlank() }
    }

    private companion object {
        const val MAX_ACCESSIBLE_CUE_TEXT_CHARS = 512
    }
}
