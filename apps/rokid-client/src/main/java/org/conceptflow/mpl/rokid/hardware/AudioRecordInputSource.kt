// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import org.conceptflow.mpl.rokid.core.AudioInputSource
import org.conceptflow.mpl.rokid.core.MonotonicFrameSequence
import org.conceptflow.mpl.rokid.core.PcmAudioChunk
import java.util.concurrent.atomic.AtomicLong

internal const val MICROPHONE_PERMISSION_UNAVAILABLE_MESSAGE =
    "Microphone permission is unavailable; audio input remains stopped"
internal const val MICROPHONE_INITIALIZATION_FAILURE_MESSAGE =
    "Microphone input could not be initialized; audio input remains stopped"
internal const val MICROPHONE_READ_FAILURE_MESSAGE =
    "Microphone input failed while reading; audio input remains stopped"

class AudioRecordInputSource(
    context: Context,
    private val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
) : AudioInputSource {
    private val appContext = context.applicationContext
    private val stateLock = Any()
    private val sequence = AtomicLong(0L)
    private val timestamps = MonotonicFrameSequence()
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var listener: AudioInputSource.Listener? = null
    private var running = false

    init {
        require(sampleRateHz in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ)
    }

    override val isRunning: Boolean
        get() = synchronized(stateLock) { running }

    override fun start(listener: AudioInputSource.Listener) {
        synchronized(stateLock) {
            check(!running && recorder == null) { "Audio input source is already running" }
        }
        check(appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            MICROPHONE_PERMISSION_UNAVAILABLE_MESSAGE
        }
        val minimumBufferSize = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBufferSize > 0 && minimumBufferSize <= MAX_BUFFER_BYTES) {
            MICROPHONE_INITIALIZATION_FAILURE_MESSAGE
        }
        val bufferSize = maxOf(minimumBufferSize * 2, MIN_BUFFER_BYTES).coerceAtMost(MAX_BUFFER_BYTES)
        val audioRecord = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (error: SecurityException) {
            throw IllegalStateException(MICROPHONE_PERMISSION_UNAVAILABLE_MESSAGE, error)
        } catch (error: RuntimeException) {
            throw IllegalStateException(MICROPHONE_INITIALIZATION_FAILURE_MESSAGE, error)
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            error(MICROPHONE_INITIALIZATION_FAILURE_MESSAGE)
        }

        val audioWorker = Thread(
            { readAudio(audioRecord, bufferSize) },
            "bounded-microphone-input",
        )
        val attached = synchronized(stateLock) {
            if (running || recorder != null) {
                false
            } else {
                this.listener = listener
                recorder = audioRecord
                worker = audioWorker
                running = true
                true
            }
        }
        if (!attached) {
            audioRecord.release()
            error("Audio input source is already running")
        }
        try {
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                MICROPHONE_INITIALIZATION_FAILURE_MESSAGE
            }
            audioWorker.start()
        } catch (error: SecurityException) {
            detachAndRelease(audioRecord)
            throw IllegalStateException(MICROPHONE_PERMISSION_UNAVAILABLE_MESSAGE, error)
        } catch (error: RuntimeException) {
            detachAndRelease(audioRecord)
            throw IllegalStateException(MICROPHONE_INITIALIZATION_FAILURE_MESSAGE, error)
        }
    }

    private fun readAudio(audioRecord: AudioRecord, bufferSize: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buffer = ByteArray(bufferSize)
        while (isCurrent(audioRecord)) {
            val bytesRead = try {
                audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            } catch (_: RuntimeException) {
                AudioRecord.ERROR_INVALID_OPERATION
            }
            if (bytesRead <= 0) {
                fail(audioRecord, MICROPHONE_READ_FAILURE_MESSAGE)
                return
            }
            val target = synchronized(stateLock) {
                if (running && recorder === audioRecord) listener else null
            } ?: return
            try {
                target.onAudioChunk(
                    PcmAudioChunk(
                        chunkId = sequence.incrementAndGet(),
                        captureMonotonicTimestampNs = timestamps.normalizeTimestamp(SystemClock.elapsedRealtimeNanos()),
                        sampleRateHz = sampleRateHz,
                        channelCount = 1,
                        pcm16LittleEndian = buffer.copyOf(bytesRead),
                    ),
                )
            } catch (_: RuntimeException) {
                fail(audioRecord, MICROPHONE_READ_FAILURE_MESSAGE)
                return
            }
        }
    }

    private fun isCurrent(audioRecord: AudioRecord): Boolean = synchronized(stateLock) {
        running && recorder === audioRecord
    }

    private fun fail(audioRecord: AudioRecord, message: String) {
        val target = synchronized(stateLock) {
            if (!running || recorder !== audioRecord) return
            val current = listener
            running = false
            recorder = null
            worker = null
            listener = null
            current
        }
        stopAndRelease(audioRecord)
        target?.onError(message)
    }

    override fun stop() {
        val detached = synchronized(stateLock) {
            val currentRecorder = recorder ?: return
            val currentWorker = worker
            running = false
            recorder = null
            worker = null
            listener = null
            currentRecorder to currentWorker
        }
        stopAndRelease(detached.first)
        if (shouldJoinAudioThread(detached.second, Thread.currentThread())) {
            runCatching { detached.second?.join(WORKER_JOIN_TIMEOUT_MS) }
        }
    }

    private fun detachAndRelease(audioRecord: AudioRecord) {
        synchronized(stateLock) {
            if (recorder !== audioRecord) return
            running = false
            recorder = null
            worker = null
            listener = null
        }
        stopAndRelease(audioRecord)
    }

    private fun stopAndRelease(audioRecord: AudioRecord) {
        runCatching {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop()
        }
        runCatching { audioRecord.release() }
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        private const val MIN_SAMPLE_RATE_HZ = 8_000
        private const val MAX_SAMPLE_RATE_HZ = 48_000
        private const val MIN_BUFFER_BYTES = 4_096
        private const val MAX_BUFFER_BYTES = 64 * 1_024
        private const val WORKER_JOIN_TIMEOUT_MS = 1_000L
    }
}

internal fun shouldJoinAudioThread(worker: Thread?, current: Thread): Boolean =
    worker != null && worker !== current
