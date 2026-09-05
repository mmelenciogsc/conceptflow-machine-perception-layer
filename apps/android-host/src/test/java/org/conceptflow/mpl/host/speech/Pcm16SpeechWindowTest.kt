// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.speech

import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.conceptflow.mpl.host.realtime.TimedAudioBlock
import org.conceptflow.mpl.v1.AudioSampleEncoding
import org.conceptflow.mpl.v1.MicrophoneChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16SpeechWindowTest {
    @Test
    fun `bounded window preserves ordered pcm and rejects samples after ten seconds`() {
        val window = Pcm16SpeechWindow()
        window.begin(SpeechWindowPurpose.USER_QUERY, 7L, 1_000_000_000L)

        assertTrue(window.accept(block(1L, 1_000_000_000L, shortArrayOf(100, -200))))
        assertTrue(window.accept(block(2L, 10_999_999_999L, shortArrayOf(300, -400))))
        assertFalse(window.accept(block(3L, 11_000_000_001L, shortArrayOf(500, -600))))

        val snapshot = requireNotNull(window.finish())
        assertEquals(SpeechWindowPurpose.USER_QUERY, snapshot.purpose)
        assertEquals(listOf<Short>(100, -200, 300, -400), decode(snapshot.pcm16LittleEndian))
        assertEquals(1L, window.rejectedBlockCount())
        snapshot.zeroize()
        assertTrue(snapshot.pcm16LittleEndian.all { it == 0.toByte() })
    }

    @Test
    fun `stereo 48 kilohertz downmixes and resamples to mono 16 kilohertz`() {
        val window = Pcm16SpeechWindow()
        window.begin(SpeechWindowPurpose.AMBIENT_AND_VAD, 1L, 0L)
        val stereo = ShortArray(96) { index -> if (index % 2 == 0) 16_384 else 0 }
        assertTrue(window.accept(block(1L, 1L, stereo, 48_000, 2)))

        val snapshot = requireNotNull(window.finish())
        val mono = WhisperPcmConverter.toMono16Khz(snapshot)

        assertEquals(16, mono.size)
        assertTrue(mono.all { it in 0.249f..0.251f })
    }

    private fun block(
        id: Long,
        timestampNs: Long,
        samples: ShortArray,
        sampleRateHz: Int = 16_000,
        channels: Int = 1,
    ): TimedAudioBlock {
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putShort)
        return TimedAudioBlock(
            MicrophoneChunk.newBuilder()
                .setLeaseId("lease")
                .setChunkId(id)
                .setCaptureMonotonicTimestampNs(timestampNs)
                .setSampleRateHz(sampleRateHz)
                .setChannelCount(channels)
                .setEncoding(AudioSampleEncoding.AUDIO_SAMPLE_ENCODING_PCM_S16LE)
                .setAudioData(ByteString.copyFrom(bytes.array()))
                .build(),
            timestampNs,
            0L,
        )
    }

    private fun decode(bytes: ByteArray): List<Short> {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return buildList { while (input.hasRemaining()) add(input.short) }
    }
}
