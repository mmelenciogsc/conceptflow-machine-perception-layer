// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.speech

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

data class WhisperModelArtifact(
    val fileName: String,
    val byteCount: Long,
    val sha256: String,
)

object WhisperArtifactPolicy {
    val MODEL = WhisperModelArtifact(
        "ggml-small.en-q5_1.bin",
        190_098_681L,
        "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30",
    )
    val VAD = WhisperModelArtifact(
        "ggml-silero-v6.2.0.bin",
        885_098L,
        "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987",
    )

    fun verify(directory: File, artifact: WhisperModelArtifact): File {
        val file = directory.resolve(artifact.fileName)
        require(file.isFile && file.length() == artifact.byteCount) {
            "${artifact.fileName} is missing or has the wrong size"
        }
        FileInputStream(file).use { input ->
            val header = ByteArray(4)
            require(input.read(header) == header.size && header.contentEquals(GGML_MAGIC)) {
                "${artifact.fileName} is not a supported GGML artifact"
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1_024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            buffer.fill(0)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(actual == artifact.sha256) { "${artifact.fileName} failed SHA-256 validation" }
        return file
    }

    private val GGML_MAGIC = byteArrayOf(0x6c, 0x6d, 0x67, 0x67)
}

data class WhisperInference(
    val speechDetected: Boolean,
    val transcript: String,
    val elapsedNanos: Long,
    val transcriptionTimedOut: Boolean = false,
) {
    init {
        require(elapsedNanos >= 0L)
        require(transcript.length <= AndroidWhisperCppEngine.MAXIMUM_TRANSCRIPT_CHARACTERS)
        require(speechDetected || transcript.isEmpty())
        require(!transcriptionTimedOut || transcript.isEmpty())
    }
}

data class WhisperVadResult(
    val speechDetected: Boolean,
    val elapsedNanos: Long,
) {
    init {
        require(elapsedNanos >= 0L)
    }
}

data class WhisperTranscription(
    val transcript: String,
    val elapsedNanos: Long,
    val timedOut: Boolean = false,
) {
    init {
        require(elapsedNanos >= 0L)
        require(transcript.length <= AndroidWhisperCppEngine.MAXIMUM_TRANSCRIPT_CHARACTERS)
        require(!timedOut || transcript.isEmpty())
    }
}

interface WhisperSpeechEngine : AutoCloseable {
    fun prewarm()
    fun detectSpeech(samples: FloatArray): WhisperVadResult
    fun transcribe(samples: FloatArray, timeoutMillis: Long): WhisperTranscription
}

/** JNI is intentionally optional: ordinary CI and model-neutral builds remain binary-free. */
class AndroidWhisperCppEngine(
    context: Context,
    private val clockNanos: () -> Long = System::nanoTime,
    private val threads: Int = recommendedThreadCount(Runtime.getRuntime().availableProcessors()),
) : WhisperSpeechEngine {
    private val modelDirectory = context.filesDir.resolve("speech")
    private val bridge = NativeWhisperBridge()
    private var handle = 0L

    override fun prewarm() {
        check(handle == 0L) { "Whisper is already initialized" }
        check(NativeWhisperBridge.available) { "conceptflow_whisper_jni is unavailable" }
        val model = WhisperArtifactPolicy.verify(modelDirectory, WhisperArtifactPolicy.MODEL)
        val vad = WhisperArtifactPolicy.verify(modelDirectory, WhisperArtifactPolicy.VAD)
        handle = bridge.create(model.absolutePath, vad.absolutePath, threads)
        check(handle != 0L) { "Whisper prewarm returned an invalid handle" }
    }

    override fun detectSpeech(samples: FloatArray): WhisperVadResult {
        check(handle != 0L) { "Whisper is not prewarmed" }
        val started = clockNanos()
        val detected = bridge.detectSpeech(handle, samples, DEFAULT_VAD_THRESHOLD)
        return WhisperVadResult(detected, max(0L, clockNanos() - started))
    }

    override fun transcribe(samples: FloatArray, timeoutMillis: Long): WhisperTranscription {
        check(handle != 0L) { "Whisper is not prewarmed" }
        require(timeoutMillis in MINIMUM_TRANSCRIPTION_TIMEOUT_MILLIS..MAXIMUM_TRANSCRIPTION_TIMEOUT_MILLIS)
        val started = clockNanos()
        val encoded = bridge.transcribe(handle, samples, timeoutMillis)
        val elapsed = max(0L, clockNanos() - started)
        if (encoded == null) return WhisperTranscription("", elapsed, timedOut = true)
        val transcript = try {
            sanitizeTranscript(encoded.toString(Charsets.UTF_8))
        } finally {
            encoded.fill(0)
        }
        return WhisperTranscription(transcript, elapsed)
    }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) bridge.destroy(current)
    }

    private fun sanitizeTranscript(value: String): String = value
        .filter { !it.isISOControl() || it.isWhitespace() }
        .trim()
        .replace(Regex("\\s+"), " ")
        .take(MAXIMUM_TRANSCRIPT_CHARACTERS)

    companion object {
        const val DEFAULT_VAD_THRESHOLD = 0.50f
        const val MAXIMUM_TRANSCRIPT_CHARACTERS = 512
        const val MINIMUM_TRANSCRIPTION_TIMEOUT_MILLIS = 1_000L
        const val MAXIMUM_TRANSCRIPTION_TIMEOUT_MILLIS = 60_000L

        internal fun recommendedThreadCount(processors: Int): Int = min(4, max(2, processors / 2))
    }
}

internal class NativeWhisperBridge {
    external fun create(modelPath: String, vadModelPath: String, threads: Int): Long
    external fun detectSpeech(handle: Long, samples: FloatArray, threshold: Float): Boolean
    /** Returns null only when the monotonic native deadline aborts decoding. */
    external fun transcribe(handle: Long, samples: FloatArray, timeoutMillis: Long): ByteArray?
    external fun destroy(handle: Long)

    companion object {
        val available: Boolean = runCatching {
            System.loadLibrary("conceptflow_whisper_jni")
            true
        }.getOrDefault(false)
    }
}
