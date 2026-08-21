// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import org.conceptflow.mpl.rokid.core.AudioCueOutput
import org.conceptflow.mpl.rokid.core.HapticCueOutput
import org.conceptflow.mpl.rokid.core.StereoBalance
import org.conceptflow.mpl.v1.Haptic
import org.conceptflow.mpl.v1.HapticPattern
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.sin

class PlatformStereoAudioOutput : AudioCueOutput, AutoCloseable {
    private val activeTracks = ArrayDeque<AudioTrack>()

    @Synchronized
    override fun play(earconId: String, gain: Float, pitch: Float, balance: StereoBalance): Boolean {
        val sampleRate = 24_000
        val frameCount = 2_880
        val frequency = (620.0 * pitch.coerceIn(0.5f, 2f)).coerceIn(310.0, 1_100.0)
        val pcm = ShortArray(frameCount * 2)
        for (frame in 0 until frameCount) {
            val envelope = minOf(frame / 240f, (frameCount - frame) / 360f, 1f).coerceAtLeast(0f)
            val wave = sin(2.0 * PI * frequency * frame / sampleRate)
            val amplitude = (wave * Short.MAX_VALUE * gain.coerceIn(0f, 0.75f) * envelope)
            pcm[frame * 2] = (amplitude * balance.left).toInt().toShort()
            pcm[frame * 2 + 1] = (amplitude * balance.right).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return false
        }
        if (track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) != pcm.size) {
            track.release()
            return false
        }
        try {
            track.play()
        } catch (_: IllegalStateException) {
            track.release()
            return false
        }
        activeTracks.addLast(track)
        while (activeTracks.size > 3) {
            activeTracks.removeFirst().release()
        }
        return true
    }

    @Synchronized
    override fun close() {
        while (activeTracks.isNotEmpty()) activeTracks.removeFirst().release()
    }
}

class PlatformHapticOutput(context: Context) : HapticCueOutput {
    private val vibrator = context.getSystemService(Vibrator::class.java)

    override fun play(haptic: Haptic): Boolean {
        if (vibrator?.hasVibrator() != true) return false
        val amplitude = (haptic.intensity.coerceIn(0f, 1f) * 160f).toInt().coerceIn(1, 160)
        val duration = haptic.durationMs.toLong().coerceIn(20L, 600L)
        val effect = when (haptic.pattern) {
            HapticPattern.HAPTIC_PATTERN_DOUBLE_PULSE -> VibrationEffect.createWaveform(
                longArrayOf(0L, duration / 2L, 70L, duration / 2L),
                intArrayOf(0, amplitude, 0, amplitude),
                -1,
            )
            HapticPattern.HAPTIC_PATTERN_RAMP -> VibrationEffect.createWaveform(
                longArrayOf(0L, duration / 3L, duration / 3L, duration / 3L),
                intArrayOf(0, amplitude / 3, amplitude * 2 / 3, amplitude),
                -1,
            )
            else -> VibrationEffect.createOneShot(duration, amplitude)
        }
        vibrator.vibrate(effect)
        return true
    }
}
