// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import org.conceptflow.mpl.v1.AudioSampleEncoding
import org.conceptflow.mpl.v1.MicrophoneChunk

enum class AmbientEnvironmentPrior(val wireValue: Int) {
    UNKNOWN(0),
    INDOOR(1),
    OUTDOOR(2),
    TRANSITION(3),
}

/** Admits one ambient-profile window for each genuinely new VLM classification result. */
class AmbientClassificationProfileGate {
    private var lastClassificationFrameId = 0L

    @Synchronized
    fun admit(classificationFrameId: Long?): Boolean {
        if (classificationFrameId == null || classificationFrameId <= 0L ||
            classificationFrameId == lastClassificationFrameId
        ) return false
        lastClassificationFrameId = classificationFrameId
        return true
    }

    @Synchronized fun reset() {
        lastClassificationFrameId = 0L
    }
}

/**
 * Content-free acoustic features derived from one explicitly authorized microphone window.
 * No PCM, transcription, speech embedding, or persistent recording is retained here.
 */
data class AmbientSoundProfile(
    val revision: Long = 0L,
    val sessionGeneration: Long,
    val prior: AmbientEnvironmentPrior,
    val captureStartTimestampNs: Long,
    val captureEndTimestampNs: Long,
    val validUntilTimestampNs: Long,
    val sampleRateHz: Int,
    val channelCount: Int,
    val sampleCount: Long,
    val rmsDbFs: Float,
    val peakDbFs: Float,
    val noiseFloorDbFs: Float,
    val lowBandRatio: Float,
    val midBandRatio: Float,
    val highBandRatio: Float,
    val transientDensity: Float,
    val recommendedCalibrationGain: Float,
    val recommendedPulseIntervalMs: Int,
) {
    init {
        require(revision >= 0L && sessionGeneration > 0L)
        require(captureStartTimestampNs >= 0L && captureEndTimestampNs >= captureStartTimestampNs)
        require(validUntilTimestampNs >= captureEndTimestampNs)
        require(sampleRateHz in 8_000..48_000 && channelCount in 1..2 && sampleCount > 0L)
        require(listOf(rmsDbFs, peakDbFs, noiseFloorDbFs).all(Float::isFinite))
        require(rmsDbFs in MINIMUM_DB_FS..0f && peakDbFs in MINIMUM_DB_FS..0f)
        require(noiseFloorDbFs in MINIMUM_DB_FS..0f)
        require(listOf(lowBandRatio, midBandRatio, highBandRatio, transientDensity).all {
            it.isFinite() && it in 0f..1f
        })
        require(kotlin.math.abs(lowBandRatio + midBandRatio + highBandRatio - 1f) <= 0.002f)
        require(recommendedCalibrationGain.isFinite() && recommendedCalibrationGain in 0f..1f)
        require(recommendedPulseIntervalMs in 450..900)
    }

    companion object {
        const val MINIMUM_DB_FS = -120f
    }
}

/**
 * Bounded streaming PCM16LE analyzer. It intentionally measures relative digital level rather
 * than claiming calibrated sound-pressure level; Android does not expose a calibrated dBA value.
 */
class AmbientSoundProfiler(
    private val minimumProfileNanos: Long = 1_000_000_000L,
    private val maximumProfileNanos: Long = 3_000_000_000L,
    private val profileTtlNanos: Long = 60_000_000_000L,
) {
    private var active = false
    private var sessionGeneration = 0L
    private var prior = AmbientEnvironmentPrior.UNKNOWN
    private var startedNs = 0L
    private var endedNs = 0L
    private var sampleRateHz = 0
    private var channelCount = 0
    private var sampleCount = 0L
    private var sumSquares = 0.0
    private var peak = 0.0
    private var lowEnergy = 0.0
    private var midEnergy = 0.0
    private var highEnergy = 0.0
    private val lowState = DoubleArray(MAXIMUM_CHANNELS)
    private val broadState = DoubleArray(MAXIMUM_CHANNELS)
    private var frameSumSquares = 0.0
    private var frameSamples = 0
    private var previousFrameDb = AmbientSoundProfile.MINIMUM_DB_FS.toDouble()
    private var transientFrames = 0L
    private var analyzedFrames = 0L
    private val frameLevels = ArrayList<Double>(MAXIMUM_FRAME_LEVELS)

    init {
        require(minimumProfileNanos in 250_000_000L..maximumProfileNanos)
        require(maximumProfileNanos <= 10_000_000_000L)
        require(profileTtlNanos in maximumProfileNanos..300_000_000_000L)
    }

    @Synchronized
    fun begin(generation: Long, environmentPrior: AmbientEnvironmentPrior, startedTimestampNs: Long) {
        require(generation > 0L && startedTimestampNs >= 0L)
        reset()
        active = true
        sessionGeneration = generation
        prior = environmentPrior
        startedNs = startedTimestampNs
        endedNs = startedTimestampNs
    }

    @Synchronized
    fun accept(chunk: MicrophoneChunk, hostCaptureTimestampNs: Long): Boolean {
        if (!active || hostCaptureTimestampNs < startedNs ||
            hostCaptureTimestampNs - startedNs > maximumProfileNanos
        ) return false
        if (chunk.encoding != AudioSampleEncoding.AUDIO_SAMPLE_ENCODING_PCM_S16LE ||
            chunk.sampleRateHz !in 8_000..48_000 || chunk.channelCount !in 1..2
        ) return false
        val bytes = chunk.audioData
        val frameBytes = chunk.channelCount * 2
        if (bytes.isEmpty || bytes.size() % frameBytes != 0) return false
        if (sampleRateHz == 0) {
            sampleRateHz = chunk.sampleRateHz
            channelCount = chunk.channelCount
        } else if (sampleRateHz != chunk.sampleRateHz || channelCount != chunk.channelCount) {
            return false
        }

        val lowAlpha = onePoleAlpha(300.0, sampleRateHz)
        val broadAlpha = onePoleAlpha(3_000.0, sampleRateHz)
        val frameLength = maxOf(1, sampleRateHz / 50) * channelCount
        var offset = 0
        while (offset < bytes.size()) {
            val channel = (sampleCount % channelCount.toLong()).toInt()
            val value = ((bytes.byteAt(offset).toInt() and 0xff) or
                (bytes.byteAt(offset + 1).toInt() shl 8)).toShort().toDouble() / 32768.0
            lowState[channel] += lowAlpha * (value - lowState[channel])
            broadState[channel] += broadAlpha * (value - broadState[channel])
            val low = lowState[channel]
            val mid = broadState[channel] - lowState[channel]
            val high = value - broadState[channel]
            val square = value * value
            sumSquares += square
            lowEnergy += low * low
            midEnergy += mid * mid
            highEnergy += high * high
            peak = maxOf(peak, kotlin.math.abs(value))
            frameSumSquares += square
            frameSamples += 1
            sampleCount += 1L
            if (frameSamples >= frameLength) finishAnalysisFrame()
            offset += 2
        }
        endedNs = hostCaptureTimestampNs
        return true
    }

    @Synchronized
    fun complete(nowNs: Long): AmbientSoundProfile? {
        if (!active || nowNs < startedNs) return null
        if (frameSamples > 0) finishAnalysisFrame()
        active = false
        if (sampleRateHz == 0 || sampleCount == 0L || endedNs - startedNs < minimumProfileNanos) {
            return null
        }
        val rmsDb = amplitudeDb(sqrt(sumSquares / sampleCount.toDouble()))
        val peakDb = amplitudeDb(peak)
        val sorted = frameLevels.sorted()
        val floorIndex = ((sorted.size - 1) * 0.20).toInt().coerceAtLeast(0)
        val floorDb = sorted.getOrElse(floorIndex) { rmsDb }
        val totalBandEnergy = lowEnergy + midEnergy + highEnergy
        val ratios = if (totalBandEnergy <= 1e-18) {
            floatArrayOf(1f / 3f, 1f / 3f, 1f / 3f)
        } else {
            floatArrayOf(
                (lowEnergy / totalBandEnergy).toFloat(),
                (midEnergy / totalBandEnergy).toFloat(),
                (highEnergy / totalBandEnergy).toFloat(),
            )
        }
        val density = if (analyzedFrames == 0L) 0f else
            (transientFrames.toDouble() / analyzedFrames.toDouble()).toFloat().coerceIn(0f, 1f)
        val tuning = AmbientCalibrationPolicy.tune(prior, floorDb.toFloat(), density)
        return AmbientSoundProfile(
            sessionGeneration = sessionGeneration,
            prior = prior,
            captureStartTimestampNs = startedNs,
            captureEndTimestampNs = endedNs,
            validUntilTimestampNs = Math.addExact(endedNs, profileTtlNanos),
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            sampleCount = sampleCount,
            rmsDbFs = rmsDb.toFloat(),
            peakDbFs = peakDb.toFloat(),
            noiseFloorDbFs = floorDb.toFloat(),
            lowBandRatio = ratios[0],
            midBandRatio = ratios[1],
            highBandRatio = ratios[2],
            transientDensity = density,
            recommendedCalibrationGain = tuning.gain,
            recommendedPulseIntervalMs = tuning.pulseIntervalMs,
        )
    }

    @Synchronized fun isActive(): Boolean = active

    @Synchronized
    fun reset() {
        active = false
        sessionGeneration = 0L
        prior = AmbientEnvironmentPrior.UNKNOWN
        startedNs = 0L
        endedNs = 0L
        sampleRateHz = 0
        channelCount = 0
        sampleCount = 0L
        sumSquares = 0.0
        peak = 0.0
        lowEnergy = 0.0
        midEnergy = 0.0
        highEnergy = 0.0
        lowState.fill(0.0)
        broadState.fill(0.0)
        frameSumSquares = 0.0
        frameSamples = 0
        previousFrameDb = AmbientSoundProfile.MINIMUM_DB_FS.toDouble()
        transientFrames = 0L
        analyzedFrames = 0L
        frameLevels.clear()
    }

    private fun finishAnalysisFrame() {
        if (frameSamples <= 0) return
        val db = amplitudeDb(sqrt(frameSumSquares / frameSamples.toDouble()))
        if (analyzedFrames > 0L && db - previousFrameDb >= TRANSIENT_RISE_DB) transientFrames += 1L
        previousFrameDb = db
        analyzedFrames += 1L
        if (frameLevels.size < MAXIMUM_FRAME_LEVELS) frameLevels += db
        frameSumSquares = 0.0
        frameSamples = 0
    }

    private fun onePoleAlpha(cutoffHz: Double, rateHz: Int): Double =
        1.0 - exp(-2.0 * PI * cutoffHz / rateHz.toDouble())

    private fun amplitudeDb(value: Double): Double =
        (20.0 * ln(maxOf(value, 1e-6)) / ln(10.0)).coerceIn(
            AmbientSoundProfile.MINIMUM_DB_FS.toDouble(),
            0.0,
        )

    companion object {
        private const val MAXIMUM_FRAME_LEVELS = 500
        private const val MAXIMUM_CHANNELS = 2
        private const val TRANSIENT_RISE_DB = 9.0
    }
}

data class AmbientCalibrationTuning(val gain: Float, val pulseIntervalMs: Int)

/** Conservative relative-level policy; it does not estimate SPL or override user media volume. */
object AmbientCalibrationPolicy {
    fun tune(
        prior: AmbientEnvironmentPrior,
        noiseFloorDbFs: Float,
        transientDensity: Float,
    ): AmbientCalibrationTuning {
        require(noiseFloorDbFs.isFinite() && noiseFloorDbFs in AmbientSoundProfile.MINIMUM_DB_FS..0f)
        require(transientDensity.isFinite() && transientDensity in 0f..1f)
        val baseGain = when (prior) {
            AmbientEnvironmentPrior.INDOOR -> 0.66f
            AmbientEnvironmentPrior.OUTDOOR -> 0.74f
            AmbientEnvironmentPrior.TRANSITION -> 0.72f
            AmbientEnvironmentPrior.UNKNOWN -> 0.70f
        }
        val maskingAdjustment = ((noiseFloorDbFs + 58f) / 35f).coerceIn(0f, 1f) * 0.20f
        val gain = (baseGain + maskingAdjustment).coerceIn(0.62f, 0.94f)
        val interval = (when (prior) {
            AmbientEnvironmentPrior.INDOOR -> 560
            AmbientEnvironmentPrior.OUTDOOR -> 640
            AmbientEnvironmentPrior.TRANSITION -> 660
            AmbientEnvironmentPrior.UNKNOWN -> 600
        } + (transientDensity * 180f).toInt()).coerceIn(450, 900)
        return AmbientCalibrationTuning(gain, interval)
    }
}
