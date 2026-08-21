// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.feedback

import android.content.Context
import android.annotation.TargetApi
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityManager
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
    private val applicationContext = context.applicationContext
    private val vibratorManager: VibratorManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        applicationContext.getSystemService(VibratorManager::class.java)
    } else {
        null
    }
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        applicationContext.getSystemService(Vibrator::class.java)
    }

    override fun play(cue: PerceptionCue): Boolean {
        val target = vibrator ?: return false
        if (!cue.hasHaptic() || !target.hasVibrator()) return false
        val plan = HapticPlanner.plan(
            cue.haptic.pattern.number,
            cue.haptic.intensity,
            cue.haptic.durationMs,
            capabilities(target),
        ) ?: return false
        return runCatching {
            target.vibrate(effect(plan))
            true
        }.getOrDefault(false)
    }

    private fun capabilities(vibrator: Vibrator): HapticCapabilities {
        val api30Capabilities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            capabilitiesAtLeastApi30(vibrator)
        } else {
            Api30HapticCapabilities(booleanArrayOf(false, false), intArrayOf(0, 0, 0))
        }
        val count = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibratorManager?.vibratorIds?.size ?: 0
        } else {
            1
        }
        return HapticCapabilities(
            apiLevel = Build.VERSION.SDK_INT,
            actuatorCount = count,
            amplitudeControl = vibrator.hasAmplitudeControl(),
            clickPrimitive = api30Capabilities.primitives.getOrElse(0) { false },
            quickRisePrimitive = api30Capabilities.primitives.getOrElse(1) { false },
            clickEffect = api30Capabilities.effectSupported(0),
            doubleClickEffect = api30Capabilities.effectSupported(1),
            tickEffect = api30Capabilities.effectSupported(2),
        )
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun capabilitiesAtLeastApi30(vibrator: Vibrator): Api30HapticCapabilities = Api30HapticCapabilities(
        primitives = runCatching {
            vibrator.arePrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
            )
        }.getOrDefault(booleanArrayOf(false, false)),
        effects = runCatching {
            vibrator.areEffectsSupported(
                VibrationEffect.EFFECT_CLICK,
                VibrationEffect.EFFECT_DOUBLE_CLICK,
                VibrationEffect.EFFECT_TICK,
            )
        }.getOrDefault(intArrayOf(0, 0, 0)),
    )

    private fun effect(plan: HapticPlan): VibrationEffect = when (plan.kind) {
        HapticPlanKind.PRIMITIVE -> {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "primitive plan requires API 30" }
            primitiveEffectAtLeastApi30(plan)
        }
        HapticPlanKind.PREDEFINED -> VibrationEffect.createPredefined(
            when (plan.predefined) {
                PredefinedKind.CLICK -> VibrationEffect.EFFECT_CLICK
                PredefinedKind.DOUBLE_CLICK -> VibrationEffect.EFFECT_DOUBLE_CLICK
                PredefinedKind.TICK -> VibrationEffect.EFFECT_TICK
                null -> error("predefined plan requires an effect")
            },
        )
        HapticPlanKind.WAVEFORM -> VibrationEffect.createWaveform(
            plan.waveformSteps.map { it.durationMs.toLong() }.toLongArray(),
            plan.waveformSteps.map { it.amplitude }.toIntArray(),
            -1,
        )
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun primitiveEffectAtLeastApi30(plan: HapticPlan): VibrationEffect {
        val composition = VibrationEffect.startComposition()
        plan.primitiveSteps.forEach { step ->
            val primitive = when (step.kind) {
                PrimitiveKind.CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
                PrimitiveKind.QUICK_RISE -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
            }
            composition.addPrimitive(primitive, step.scale, step.delayMs)
        }
        return composition.compose()
    }

    private data class Api30HapticCapabilities(
        val primitives: BooleanArray,
        val effects: IntArray,
    ) {
        fun effectSupported(index: Int): Boolean =
            effects.getOrNull(index) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
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
