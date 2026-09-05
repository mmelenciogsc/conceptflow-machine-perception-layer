// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.speech

import kotlin.math.floor
import org.conceptflow.mpl.host.realtime.TimedAudioBlock
import org.conceptflow.mpl.v1.AudioSampleEncoding

enum class SpeechWindowPurpose { AMBIENT_AND_VAD, USER_QUERY }

data class Pcm16WindowSnapshot(
    val purpose: SpeechWindowPurpose,
    val sessionGeneration: Long,
    val captureStartTimestampNs: Long,
    val captureEndTimestampNs: Long,
    val sampleRateHz: Int,
    val channelCount: Int,
    val pcm16LittleEndian: ByteArray,
) {
    init {
        require(sessionGeneration > 0L)
        require(captureStartTimestampNs >= 0L && captureEndTimestampNs >= captureStartTimestampNs)
        require(sampleRateHz in 8_000..48_000 && channelCount in 1..2)
        require(pcm16LittleEndian.isNotEmpty() && pcm16LittleEndian.size % (channelCount * 2) == 0)
    }

    fun zeroize() = pcm16LittleEndian.fill(0)
}

/** A fixed-capacity, RAM-only microphone window. It never persists or logs sample content. */
class Pcm16SpeechWindow(
    private val maximumDurationNanos: Long = MAXIMUM_DURATION_NANOS,
    maximumBytes: Int = MAXIMUM_PCM_BYTES,
) {
    private val storage = ByteArray(maximumBytes)
    private var purpose: SpeechWindowPurpose? = null
    private var sessionGeneration = 0L
    private var startedNs = 0L
    private var endedNs = 0L
    private var sampleRateHz = 0
    private var channelCount = 0
    private var used = 0
    private var rejectedBlocks = 0L

    init {
        require(maximumDurationNanos in 1_000_000_000L..10_000_000_000L)
        require(maximumBytes in 32_000..MAXIMUM_PCM_BYTES)
    }

    @Synchronized
    fun begin(windowPurpose: SpeechWindowPurpose, generation: Long, startTimestampNs: Long) {
        require(generation > 0L && startTimestampNs >= 0L)
        reset()
        rejectedBlocks = 0L
        purpose = windowPurpose
        sessionGeneration = generation
        startedNs = startTimestampNs
        endedNs = startTimestampNs
    }

    @Synchronized
    fun accept(block: TimedAudioBlock): Boolean {
        if (purpose == null) return false
        val chunk = block.block
        val bytes = chunk.audioData
        val frameBytes = chunk.channelCount * Short.SIZE_BYTES
        if (block.hostCaptureTimestampNs < startedNs ||
            block.hostCaptureTimestampNs - startedNs > maximumDurationNanos ||
            chunk.encoding != AudioSampleEncoding.AUDIO_SAMPLE_ENCODING_PCM_S16LE ||
            chunk.sampleRateHz !in 8_000..48_000 || chunk.channelCount !in 1..2 ||
            bytes.isEmpty || bytes.size() % frameBytes != 0
        ) {
            rejectedBlocks = Math.addExact(rejectedBlocks, 1L)
            return false
        }
        if (sampleRateHz == 0) {
            sampleRateHz = chunk.sampleRateHz
            channelCount = chunk.channelCount
        } else if (sampleRateHz != chunk.sampleRateHz || channelCount != chunk.channelCount) {
            rejectedBlocks = Math.addExact(rejectedBlocks, 1L)
            return false
        }
        if (bytes.size() > storage.size - used) {
            rejectedBlocks = Math.addExact(rejectedBlocks, 1L)
            return false
        }
        bytes.copyTo(storage, used)
        used += bytes.size()
        endedNs = block.hostCaptureTimestampNs
        return true
    }

    @Synchronized
    fun finish(): Pcm16WindowSnapshot? {
        val currentPurpose = purpose ?: return null
        val result = if (used == 0 || sampleRateHz == 0) {
            null
        } else {
            Pcm16WindowSnapshot(
                currentPurpose,
                sessionGeneration,
                startedNs,
                endedNs,
                sampleRateHz,
                channelCount,
                storage.copyOf(used),
            )
        }
        reset()
        return result
    }

    @Synchronized fun isActive(): Boolean = purpose != null
    @Synchronized fun rejectedBlockCount(): Long = rejectedBlocks

    @Synchronized
    fun reset() {
        storage.fill(0, 0, used)
        purpose = null
        sessionGeneration = 0L
        startedNs = 0L
        endedNs = 0L
        sampleRateHz = 0
        channelCount = 0
        used = 0
    }

    companion object {
        const val MAXIMUM_DURATION_NANOS = 10_000_000_000L
        const val MAXIMUM_PCM_BYTES = 48_000 * 2 * Short.SIZE_BYTES * 10
    }
}

/** Deterministic downmix plus linear resampling for Whisper's required mono 16 kHz input. */
object WhisperPcmConverter {
    const val TARGET_SAMPLE_RATE_HZ = 16_000

    fun toMono16Khz(snapshot: Pcm16WindowSnapshot): FloatArray {
        val input = snapshot.pcm16LittleEndian
        val channels = snapshot.channelCount
        val inputFrames = input.size / (channels * Short.SIZE_BYTES)
        val mono = FloatArray(inputFrames)
        var byteOffset = 0
        for (frame in 0 until inputFrames) {
            var sum = 0
            repeat(channels) {
                val sample = ((input[byteOffset].toInt() and 0xff) or
                    (input[byteOffset + 1].toInt() shl 8)).toShort().toInt()
                sum += sample
                byteOffset += 2
            }
            mono[frame] = (sum.toFloat() / channels.toFloat() / 32768f).coerceIn(-1f, 1f)
        }
        if (snapshot.sampleRateHz == TARGET_SAMPLE_RATE_HZ) return mono
        val outputSize = ((inputFrames.toLong() * TARGET_SAMPLE_RATE_HZ) / snapshot.sampleRateHz)
            .toInt().coerceAtLeast(1)
        val output = FloatArray(outputSize)
        val ratio = snapshot.sampleRateHz.toDouble() / TARGET_SAMPLE_RATE_HZ.toDouble()
        for (index in output.indices) {
            val source = index * ratio
            val lower = floor(source).toInt().coerceIn(0, mono.lastIndex)
            val upper = (lower + 1).coerceAtMost(mono.lastIndex)
            val fraction = (source - lower).toFloat()
            output[index] = mono[lower] + (mono[upper] - mono[lower]) * fraction
        }
        mono.fill(0f)
        return output
    }
}
