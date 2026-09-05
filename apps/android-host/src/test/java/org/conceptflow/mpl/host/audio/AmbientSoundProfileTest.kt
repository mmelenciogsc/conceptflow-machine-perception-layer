// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.audio

import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.conceptflow.mpl.v1.AudioSampleEncoding
import org.conceptflow.mpl.v1.MicrophoneChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientSoundProfileTest {
    @Test
    fun `classification profile gate admits one window per source result`() {
        val gate = AmbientClassificationProfileGate()

        assertFalse(gate.admit(null))
        assertFalse(gate.admit(0L))
        assertTrue(gate.admit(41L))
        assertFalse(gate.admit(41L))
        assertTrue(gate.admit(57L))
        assertFalse(gate.admit(57L))
        gate.reset()
        assertTrue(gate.admit(57L))
    }

    @Test
    fun `bounded pcm window produces content-free normalized profile`() {
        val profiler = AmbientSoundProfiler(minimumProfileNanos = 900_000_000L)
        profiler.begin(7L, AmbientEnvironmentPrior.INDOOR, 1_000_000_000L)
        repeat(10) { index ->
            assertTrue(profiler.accept(tone(index + 1L), 1_000_000_000L + index * 100_000_000L))
        }
        val profile = requireNotNull(profiler.complete(2_100_000_000L))

        assertEquals(7L, profile.sessionGeneration)
        assertEquals(AmbientEnvironmentPrior.INDOOR, profile.prior)
        assertEquals(16_000, profile.sampleRateHz)
        assertEquals(1, profile.channelCount)
        assertEquals(16_000L, profile.sampleCount)
        assertTrue(profile.peakDbFs < 0f)
        assertTrue(profile.lowBandRatio + profile.midBandRatio + profile.highBandRatio in 0.999f..1.001f)
        assertTrue(profile.recommendedCalibrationGain in 0.62f..0.94f)
        assertFalse(profiler.isActive())
    }

    @Test
    fun `rejects format changes and incomplete windows`() {
        val profiler = AmbientSoundProfiler()
        profiler.begin(1L, AmbientEnvironmentPrior.OUTDOOR, 10L)
        assertTrue(profiler.accept(tone(1L), 100_000_000L))
        val malformed = tone(1L).toBuilder().setChannelCount(2).build()
        assertFalse(profiler.accept(malformed, 200_000_000L))
        assertEquals(null, profiler.complete(500_000_000L))
    }

    @Test
    fun `leading consent cue is omitted from ambient statistics`() {
        val profiler = AmbientSoundProfiler(minimumProfileNanos = 250_000_000L)
        profiler.begin(
            2L,
            AmbientEnvironmentPrior.INDOOR,
            1_000_000_000L,
            leadingSuppressionNanos = 700_000_000L,
        )

        assertTrue(profiler.accept(tone(1L), 1_100_000_000L))
        assertTrue(profiler.accept(tone(2L), 1_800_000_000L))
        val profile = requireNotNull(profiler.complete(2_100_000_000L))

        assertEquals(1_600L, profile.sampleCount)
    }

    @Test
    fun `outdoor and masking priors raise but cap calibration level`() {
        val quietIndoor = AmbientCalibrationPolicy.tune(AmbientEnvironmentPrior.INDOOR, -75f, 0f)
        val loudOutdoor = AmbientCalibrationPolicy.tune(AmbientEnvironmentPrior.OUTDOOR, -18f, 1f)

        assertTrue(loudOutdoor.gain > quietIndoor.gain)
        assertEquals(0.94f, loudOutdoor.gain, 0.0001f)
        assertTrue(loudOutdoor.pulseIntervalMs > quietIndoor.pulseIntervalMs)
    }

    @Test
    fun `stereo channels retain independent filter state`() {
        val profiler = AmbientSoundProfiler(minimumProfileNanos = 250_000_000L)
        profiler.begin(3L, AmbientEnvironmentPrior.TRANSITION, 1_000_000_000L)
        val samples = ShortArray(16_000) { index -> if (index % 2 == 0) 8_000 else 0 }
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putShort)
        val chunk = MicrophoneChunk.newBuilder()
            .setLeaseId("lease")
            .setChunkId(1L)
            .setCaptureMonotonicTimestampNs(1_000_000_000L)
            .setSampleRateHz(16_000)
            .setChannelCount(2)
            .setEncoding(AudioSampleEncoding.AUDIO_SAMPLE_ENCODING_PCM_S16LE)
            .setAudioData(ByteString.copyFrom(bytes.array()))
            .build()

        assertTrue(profiler.accept(chunk, 1_500_000_000L))
        val profile = requireNotNull(profiler.complete(1_500_000_000L))
        assertEquals(2, profile.channelCount)
        assertEquals(samples.size.toLong(), profile.sampleCount)
        assertTrue(profile.lowBandRatio + profile.midBandRatio + profile.highBandRatio in 0.999f..1.001f)
    }

    private fun tone(chunkId: Long): MicrophoneChunk {
        val samples = ShortArray(1_600) { index ->
            (sin(2.0 * PI * 440.0 * index / 16_000.0) * 4_000.0).toInt().toShort()
        }
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putShort)
        return MicrophoneChunk.newBuilder()
            .setLeaseId("lease")
            .setChunkId(chunkId)
            .setCaptureMonotonicTimestampNs(chunkId * 100_000_000L)
            .setSampleRateHz(16_000)
            .setChannelCount(1)
            .setEncoding(AudioSampleEncoding.AUDIO_SAMPLE_ENCODING_PCM_S16LE)
            .setAudioData(ByteString.copyFrom(bytes.array()))
            .build()
    }
}
