// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import kotlin.math.PI
import kotlin.math.sin

data class OperationalEarconPcm(
    val sampleRateHz: Int,
    val frameCount: Int,
    val stereoPcm16: ShortArray,
)

/** Audible, non-speech consent cues. Start rises twice; stop falls three times. */
object OperationalEarconGenerator {
    const val MICROPHONE_START_ID = "microphone-start"
    const val MICROPHONE_STOP_ID = "microphone-stop"
    const val SAMPLE_RATE_HZ = 24_000
    const val MAX_LINEAR_GAIN = 0.75
    const val MINIMUM_START_TO_STOP_ONSET_MILLIS = 1_350L

    fun stopCueDelayMillis(startCueMonotonicNs: Long?, stoppedAtMonotonicNs: Long): Long {
        if (startCueMonotonicNs == null || stoppedAtMonotonicNs < startCueMonotonicNs) return 0L
        val elapsedMillis = (stoppedAtMonotonicNs - startCueMonotonicNs) / 1_000_000L
        return (MINIMUM_START_TO_STOP_ONSET_MILLIS - elapsedMillis).coerceAtLeast(0L)
    }

    fun generateOrNull(earconId: String, gain: Float): OperationalEarconPcm? {
        val rising = when (earconId) {
            MICROPHONE_START_ID -> true
            MICROPHONE_STOP_ID -> false
            else -> return null
        }
        val durationMillis = if (rising) START_DURATION_MILLIS else STOP_DURATION_MILLIS
        val frameCount = SAMPLE_RATE_HZ * durationMillis / 1_000
        val pcm = ShortArray(frameCount * 2)
        val boundedGain = gain.coerceIn(0f, MAX_LINEAR_GAIN.toFloat()).toDouble()
        for (frame in 0 until frameCount) {
            val timeSeconds = frame.toDouble() / SAMPLE_RATE_HZ
            val first = chirpedPulse(
                timeSeconds,
                startSeconds = 0.0,
                durationSeconds = 0.20,
                startFrequencyHz = if (rising) 190.0 else 315.0,
                endFrequencyHz = if (rising) 250.0 else 235.0,
            )
            val second = chirpedPulse(
                timeSeconds,
                startSeconds = 0.28,
                durationSeconds = if (rising) 0.24 else 0.18,
                startFrequencyHz = if (rising) 235.0 else 250.0,
                endFrequencyHz = if (rising) 315.0 else 190.0,
            )
            val third = if (rising) {
                0.0
            } else {
                chirpedPulse(
                    timeSeconds,
                    startSeconds = 0.54,
                    durationSeconds = 0.18,
                    startFrequencyHz = 210.0,
                    endFrequencyHz = 155.0,
                )
            }
            val sample = ((first + second + third) * boundedGain * Short.MAX_VALUE)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            // Consent state is non-spatial and must remain audible in both open-ear speakers.
            pcm[frame * 2] = sample
            pcm[frame * 2 + 1] = sample
        }
        return OperationalEarconPcm(SAMPLE_RATE_HZ, frameCount, pcm)
    }

    private fun chirpedPulse(
        timeSeconds: Double,
        startSeconds: Double,
        durationSeconds: Double,
        startFrequencyHz: Double,
        endFrequencyHz: Double,
    ): Double {
        val local = timeSeconds - startSeconds
        if (local !in 0.0..durationSeconds) return 0.0
        val progress = (local / durationSeconds).coerceIn(0.0, 1.0)
        val envelope = sin(PI * progress).let { it * it }
        val sweepHzPerSecond = (endFrequencyHz - startFrequencyHz) / durationSeconds
        val phase = 2.0 * PI * (startFrequencyHz * local + 0.5 * sweepHzPerSecond * local * local)
        return (0.86 * sin(phase) + 0.14 * sin(phase * 2.0)) * envelope
    }

    private const val START_DURATION_MILLIS = 540
    private const val STOP_DURATION_MILLIS = 760
}
