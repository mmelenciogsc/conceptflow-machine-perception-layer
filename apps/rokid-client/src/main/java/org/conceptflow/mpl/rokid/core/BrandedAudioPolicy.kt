// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import java.util.concurrent.atomic.AtomicBoolean

const val PRODUCT_LINE_REPEAT_INTERVAL_MILLIS = 3L * 24L * 60L * 60L * 1_000L

enum class BrandedToneKind {
    READY,
    STARTUP_SEPARATOR,
    AUTHENTICATED_CONNECTION,
}

sealed interface BrandedAudioStep {
    data class Tone(val kind: BrandedToneKind) : BrandedAudioStep
    data class Speech(val text: String) : BrandedAudioStep
    data class Pause(val durationMillis: Long) : BrandedAudioStep
}

object BrandedAudioScript {
    const val BRAND_LINE = "CONCEPTFlow. Machine Intelligence. Human Architecture."
    const val PRODUCT_LINE = "Machine Perception Layer. Map. Morph. Move."
    const val SUPPLEMENTAL_AWARENESS = "It's just supplemental awareness."
    const val CONCEPTFLOW_SPOKEN = "Concept flow."
    const val MACHINE_INTELLIGENCE_SPOKEN = "Machine Intelligence."
    const val HUMAN_ARCHITECTURE_SPOKEN = "Human Architecture."
    const val MACHINE_PERCEPTION_LAYER_SPOKEN = "Machine Perception Layer."
    const val MAP_SPOKEN = "Map."
    const val MORPH_SPOKEN = "Morph."
    const val MOVE_SPOKEN = "Move."
    const val AFTER_CONCEPTFLOW_MILLIS = 650L
    const val BETWEEN_PARENT_BRAND_PHRASES_MILLIS = 600L
    const val AFTER_PARENT_BRAND_MILLIS = 700L
    const val AFTER_SEPARATOR_TONE_MILLIS = 600L
    const val AFTER_PRODUCT_NAME_MILLIS = 600L
    const val BETWEEN_PRODUCT_PRINCIPLES_MILLIS = 480L
    const val BEFORE_SUPPLEMENTAL_AWARENESS_MILLIS = 900L

    fun activationReady(
        includeBootBrandLine: Boolean,
        includeProductLine: Boolean,
    ): List<BrandedAudioStep> = buildList {
        add(BrandedAudioStep.Tone(BrandedToneKind.READY))
        if (includeBootBrandLine) {
            add(BrandedAudioStep.Speech(CONCEPTFLOW_SPOKEN))
            add(BrandedAudioStep.Pause(AFTER_CONCEPTFLOW_MILLIS))
            add(BrandedAudioStep.Speech(MACHINE_INTELLIGENCE_SPOKEN))
            add(BrandedAudioStep.Pause(BETWEEN_PARENT_BRAND_PHRASES_MILLIS))
            add(BrandedAudioStep.Speech(HUMAN_ARCHITECTURE_SPOKEN))
            add(BrandedAudioStep.Pause(AFTER_PARENT_BRAND_MILLIS))
            add(BrandedAudioStep.Tone(BrandedToneKind.STARTUP_SEPARATOR))
            add(BrandedAudioStep.Pause(AFTER_SEPARATOR_TONE_MILLIS))
        }
        if (includeProductLine) {
            add(BrandedAudioStep.Speech(MACHINE_PERCEPTION_LAYER_SPOKEN))
            add(BrandedAudioStep.Pause(AFTER_PRODUCT_NAME_MILLIS))
            add(BrandedAudioStep.Speech(MAP_SPOKEN))
            add(BrandedAudioStep.Pause(BETWEEN_PRODUCT_PRINCIPLES_MILLIS))
            add(BrandedAudioStep.Speech(MORPH_SPOKEN))
            add(BrandedAudioStep.Pause(BETWEEN_PRODUCT_PRINCIPLES_MILLIS))
            add(BrandedAudioStep.Speech(MOVE_SPOKEN))
            add(BrandedAudioStep.Pause(BEFORE_SUPPLEMENTAL_AWARENESS_MILLIS))
            add(BrandedAudioStep.Speech(SUPPLEMENTAL_AWARENESS))
        }
    }

    fun fullBrandTest(): List<BrandedAudioStep> = activationReady(
        includeBootBrandLine = true,
        includeProductLine = true,
    )
}

class ProcessStartupAnnouncementGate {
    private val claimed = AtomicBoolean(false)

    fun claim(): Boolean = claimed.compareAndSet(false, true)
}

object ProductLineRepeatPolicy {
    fun mayClaim(lastClaimEpochMillis: Long?, nowEpochMillis: Long): Boolean {
        if (nowEpochMillis < 0L) return false
        if (lastClaimEpochMillis == null) return true
        if (nowEpochMillis < lastClaimEpochMillis) return false
        return nowEpochMillis - lastClaimEpochMillis >= PRODUCT_LINE_REPEAT_INTERVAL_MILLIS
    }
}

object BootBrandLinePolicy {
    fun mayClaim(lastClaimedBootCount: Int?, currentBootCount: Int?): Boolean {
        if (currentBootCount == null || currentBootCount < 0) return true
        return lastClaimedBootCount != currentBootCount
    }
}

class AuthenticatedSessionToneGate {
    private var greatestObservedReadySessions = 0L

    @Synchronized
    fun observe(readySessions: Long): Boolean {
        if (readySessions <= greatestObservedReadySessions) return false
        greatestObservedReadySessions = readySessions
        return true
    }
}

data class SpeechVoiceCapability(
    val name: String,
    val languageTag: String,
    val requiresNetwork: Boolean,
    val quality: Int,
    val latency: Int,
)

object SpeechVoicePolicy {
    fun selectPreferredEnglish(
        voices: Collection<SpeechVoiceCapability>,
        preferredLanguageTag: String = "en-US",
    ): SpeechVoiceCapability? {
        val preferred = preferredLanguageTag.lowercase(java.util.Locale.ROOT)
        return voices
            .asSequence()
            .filter { it.languageTag.substringBefore('-').equals("en", ignoreCase = true) }
            .sortedWith(
                compareBy<SpeechVoiceCapability> { it.requiresNetwork }
                    .thenBy {
                        if (it.languageTag.lowercase(java.util.Locale.ROOT) == preferred) 0 else 1
                    }.thenByDescending { it.quality }
                    .thenBy { it.latency }
                    .thenBy { it.name },
            )
            .firstOrNull()
    }
}
