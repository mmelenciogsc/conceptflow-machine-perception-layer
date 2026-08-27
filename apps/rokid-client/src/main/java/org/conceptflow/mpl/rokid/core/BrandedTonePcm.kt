// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import kotlin.math.PI
import kotlin.math.sin

data class BrandedTonePcm(
    val sampleRateHz: Int,
    val frameCount: Int,
    val stereoPcm16: ShortArray,
)

object BrandedToneGenerator {
    const val SAMPLE_RATE_HZ = 24_000
    const val DURATION_MILLIS = 260L
    const val MAX_LINEAR_GAIN = 0.32

    fun generate(kind: BrandedToneKind): BrandedTonePcm {
        val frameCount = (SAMPLE_RATE_HZ * DURATION_MILLIS / 1_000L).toInt()
        val (startFrequencyHz, endFrequencyHz) = when (kind) {
            BrandedToneKind.READY -> 196.0 to 164.0
            BrandedToneKind.STARTUP_SEPARATOR -> 147.0 to 174.0
            BrandedToneKind.AUTHENTICATED_CONNECTION -> 164.0 to 196.0
        }
        val pcm = ShortArray(frameCount * 2)
        var primaryPhase = 0.0
        var colorPhase = 0.0
        for (frame in 0 until frameCount) {
            val progress = frame.toDouble() / (frameCount - 1).coerceAtLeast(1)
            val frequencyHz = startFrequencyHz + (endFrequencyHz - startFrequencyHz) * progress
            primaryPhase += 2.0 * PI * frequencyHz / SAMPLE_RATE_HZ
            colorPhase += 2.0 * PI * frequencyHz * 1.5 / SAMPLE_RATE_HZ
            val attack = (progress / 0.10).coerceIn(0.0, 1.0)
            val release = ((1.0 - progress) / 0.30).coerceIn(0.0, 1.0)
            val envelope = minOf(attack, release)
            val wave = 0.82 * sin(primaryPhase) + 0.18 * sin(colorPhase)
            val sample = (wave * envelope * MAX_LINEAR_GAIN * Short.MAX_VALUE)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            pcm[frame * 2] = sample
            pcm[frame * 2 + 1] = sample
        }
        return BrandedTonePcm(SAMPLE_RATE_HZ, frameCount, pcm)
    }
}

object BrandedAmbientBedGenerator {
    const val SAMPLE_RATE_HZ = 24_000
    const val LOOP_DURATION_MILLIS = 4_000L
    const val MAX_LINEAR_GAIN = 0.035

    fun generate(): BrandedTonePcm {
        val frameCount = (SAMPLE_RATE_HZ * LOOP_DURATION_MILLIS / 1_000L).toInt()
        val pcm = ShortArray(frameCount * 2)
        for (frame in 0 until frameCount) {
            val time = frame.toDouble() / SAMPLE_RATE_HZ
            val movement = 0.86 + 0.14 * sin(2.0 * PI * 0.25 * time - PI / 2.0)
            val left = (
                0.72 * sin(2.0 * PI * 147.0 * time) +
                    0.28 * sin(2.0 * PI * 220.5 * time + 0.18)
                ) * movement * MAX_LINEAR_GAIN
            val right = (
                0.72 * sin(2.0 * PI * 147.0 * time + 0.04) +
                    0.28 * sin(2.0 * PI * 220.5 * time - 0.18)
                ) * movement * MAX_LINEAR_GAIN
            pcm[frame * 2] = toPcm16(left)
            pcm[frame * 2 + 1] = toPcm16(right)
        }
        return BrandedTonePcm(SAMPLE_RATE_HZ, frameCount, pcm)
    }

    private fun toPcm16(value: Double): Short = (value * Short.MAX_VALUE)
        .toInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        .toShort()
}
