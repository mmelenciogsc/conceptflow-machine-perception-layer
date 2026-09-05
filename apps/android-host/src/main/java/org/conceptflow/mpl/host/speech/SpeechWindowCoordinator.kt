// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.speech

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import org.conceptflow.mpl.host.realtime.TimedAudioBlock

enum class SpeechRuntimePhase {
    STOPPED,
    PREWARMING,
    READY,
    CAPTURING,
    ANALYZING,
    TRANSCRIBING,
    UNAVAILABLE,
}

data class SpeechRuntimeStatus(
    val phase: SpeechRuntimePhase,
    val purpose: SpeechWindowPurpose? = null,
    val speechDetected: Boolean = false,
    val transcriptCharacterCount: Int = 0,
    val analysisElapsedNanos: Long = 0L,
    val transcriptionTimedOut: Boolean = false,
    val acceptedBlocks: Long = 0L,
    val rejectedBlocks: Long = 0L,
    val suppressedBlocks: Long = 0L,
) {
    init {
        require(transcriptCharacterCount in 0..AndroidWhisperCppEngine.MAXIMUM_TRANSCRIPT_CHARACTERS)
        require(analysisElapsedNanos >= 0L)
        require(acceptedBlocks >= 0L && rejectedBlocks >= 0L && suppressedBlocks >= 0L)
    }
}

data class PrivateSpeechResult(
    val sessionGeneration: Long,
    val purpose: SpeechWindowPurpose,
    val speechDetected: Boolean,
    val transcript: String,
    val transcriptionTimedOut: Boolean,
    val captureStartTimestampNs: Long,
    val captureEndTimestampNs: Long,
)

/**
 * One bounded owner for remote PCM, native VAD, and optional speech recognition. Raw samples are
 * zeroized after analysis; only aggregate status may leave this class unless the user explicitly
 * opened a [SpeechWindowPurpose.USER_QUERY] window.
 */
class SpeechWindowCoordinator(
    private val engine: WhisperSpeechEngine,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mpl-whisper-cpu").apply { isDaemon = true }
    },
    private val onStatus: (SpeechRuntimeStatus) -> Unit = {},
    private val onPrivateResult: (PrivateSpeechResult) -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val window = Pcm16SpeechWindow()
    private var ready = false
    private var phase = SpeechRuntimePhase.STOPPED
    private var purpose: SpeechWindowPurpose? = null
    private var acceptedBlocks = 0L
    private var rejectedBlocks = 0L
    private var suppressedBlocks = 0L
    private var suppressUntilTimestampNs = 0L
    private var suppressLeadingAudioNanos = 0L
    private var firstAudioTimestampNs: Long? = null
    private var lastInference: WhisperInference? = null
    private var operationToken = 0L

    fun prewarm() {
        val starting = synchronized(this) {
            check(!closed.get())
            if (phase != SpeechRuntimePhase.STOPPED) return@synchronized null
            phase = SpeechRuntimePhase.PREWARMING
            statusLocked()
        }
        starting?.let(onStatus)
        if (starting == null) return
        try {
            worker.execute {
                val loaded = runCatching { engine.prewarm() }.isSuccess
                val updated = synchronized(this) {
                    if (closed.get()) return@synchronized null
                    ready = loaded
                    phase = if (loaded) SpeechRuntimePhase.READY else SpeechRuntimePhase.UNAVAILABLE
                    statusLocked()
                }
                updated?.let(onStatus)
            }
        } catch (_: RejectedExecutionException) {
            val unavailable = synchronized(this) {
                if (!closed.get()) {
                    ready = false
                    phase = SpeechRuntimePhase.UNAVAILABLE
                    statusLocked()
                } else {
                    null
                }
            }
            unavailable?.let(onStatus)
        }
    }

    fun begin(windowPurpose: SpeechWindowPurpose, generation: Long, startTimestampNs: Long): Boolean {
        val updated = synchronized(this) {
            if (closed.get() || generation <= 0L || window.isActive() ||
                phase == SpeechRuntimePhase.ANALYZING || phase == SpeechRuntimePhase.TRANSCRIBING
            ) {
                return@synchronized null
            }
            purpose = windowPurpose
            acceptedBlocks = 0L
            rejectedBlocks = 0L
            suppressedBlocks = 0L
            suppressLeadingAudioNanos = 0L
            firstAudioTimestampNs = null
            lastInference = null
            window.begin(windowPurpose, generation, startTimestampNs)
            phase = SpeechRuntimePhase.CAPTURING
            statusLocked()
        }
        updated?.let(onStatus)
        return updated != null
    }

    @Synchronized
    fun accept(blocks: List<TimedAudioBlock>) {
        if (!window.isActive()) return
        blocks.forEach { block ->
            val firstTimestamp = firstAudioTimestampNs ?: block.hostCaptureTimestampNs.also {
                firstAudioTimestampNs = it
            }
            val leadingSuppressionDeadline = if (
                suppressLeadingAudioNanos > 0L &&
                Long.MAX_VALUE - firstTimestamp >= suppressLeadingAudioNanos
            ) {
                firstTimestamp + suppressLeadingAudioNanos
            } else {
                firstTimestamp
            }
            if (block.hostCaptureTimestampNs < suppressUntilTimestampNs ||
                block.hostCaptureTimestampNs < leadingSuppressionDeadline
            ) {
                suppressedBlocks = Math.addExact(suppressedBlocks, 1L)
            } else if (window.accept(block)) {
                acceptedBlocks = Math.addExact(acceptedBlocks, 1L)
            } else {
                rejectedBlocks = Math.addExact(rejectedBlocks, 1L)
            }
        }
    }

    @Synchronized
    fun suppressKnownPlayback(untilTimestampNs: Long) {
        require(untilTimestampNs >= 0L)
        suppressUntilTimestampNs = maxOf(suppressUntilTimestampNs, untilTimestampNs)
    }

    /** Excludes a known on-glasses start cue relative to the first arriving audio block. */
    @Synchronized
    fun suppressLeadingAudio(durationNanos: Long) {
        require(durationNanos in 0L..MAXIMUM_LEADING_SUPPRESSION_NANOS)
        check(window.isActive()) { "A speech window must be active before leading suppression" }
        suppressLeadingAudioNanos = maxOf(suppressLeadingAudioNanos, durationNanos)
    }

    fun cancelWindow() {
        val updated = synchronized(this) {
            if (closed.get()) return@synchronized null
            operationToken = Math.addExact(operationToken, 1L)
            window.reset()
            suppressLeadingAudioNanos = 0L
            firstAudioTimestampNs = null
            purpose = null
            lastInference = null
            phase = if (ready) SpeechRuntimePhase.READY else SpeechRuntimePhase.UNAVAILABLE
            statusLocked()
        }
        updated?.let(onStatus)
    }

    fun finish() {
        val start = synchronized(this) {
            if (closed.get()) return
            val captured = window.finish()
            if (captured == null) {
                phase = if (ready) SpeechRuntimePhase.READY else SpeechRuntimePhase.UNAVAILABLE
                purpose = null
                SpeechAnalysisStart(null, 0L, statusLocked())
            } else {
                operationToken = Math.addExact(operationToken, 1L)
                phase = SpeechRuntimePhase.ANALYZING
                SpeechAnalysisStart(captured, operationToken, statusLocked())
            }
        }
        onStatus(start.status)
        val captured = start.captured ?: return
        val token = start.token
        try {
            worker.execute {
                var samples: FloatArray? = null
                try {
                    if (closed.get()) return@execute
                    check(synchronized(this) { ready }) { "Whisper engine is not ready" }
                    samples = WhisperPcmConverter.toMono16Khz(captured)
                    val vad = engine.detectSpeech(requireNotNull(samples))
                    val vadInference = WhisperInference(vad.speechDetected, "", vad.elapsedNanos)
                    val transcribe = captured.purpose == SpeechWindowPurpose.USER_QUERY && vad.speechDetected
                    val vadUpdate = synchronized(this) {
                        if (closed.get() || token != operationToken) return@synchronized null
                        lastInference = vadInference
                        phase = if (transcribe) SpeechRuntimePhase.TRANSCRIBING else SpeechRuntimePhase.READY
                        if (!transcribe) purpose = null
                        statusLocked(vadInference)
                    }
                    if (vadUpdate == null) return@execute
                    onStatus(vadUpdate)

                    val inference = if (transcribe) {
                        val transcription = engine.transcribe(
                            requireNotNull(samples),
                            TRANSCRIPTION_TIMEOUT_MILLIS,
                        )
                        WhisperInference(
                            speechDetected = true,
                            transcript = transcription.transcript,
                            elapsedNanos = Math.addExact(vad.elapsedNanos, transcription.elapsedNanos),
                            transcriptionTimedOut = transcription.timedOut,
                        )
                    } else {
                        vadInference
                    }
                    samples?.fill(0f)
                    samples = null
                    captured.zeroize()
                    val completion = synchronized(this) {
                        if (closed.get() || token != operationToken) return@synchronized null
                        lastInference = inference
                        phase = SpeechRuntimePhase.READY
                        purpose = null
                        val privateResult = if (captured.purpose == SpeechWindowPurpose.USER_QUERY) {
                            PrivateSpeechResult(
                                captured.sessionGeneration,
                                captured.purpose,
                                inference.speechDetected,
                                inference.transcript,
                                inference.transcriptionTimedOut,
                                captured.captureStartTimestampNs,
                                captured.captureEndTimestampNs,
                            )
                        } else {
                            null
                        }
                        statusLocked(inference) to privateResult
                    }
                    completion?.let { (runtimeStatus, privateResult) ->
                        onStatus(runtimeStatus)
                        privateResult?.let(onPrivateResult)
                    }
                } catch (_: Exception) {
                    val unavailable = synchronized(this) {
                        if (!closed.get() && token == operationToken) {
                            ready = false
                            phase = SpeechRuntimePhase.UNAVAILABLE
                            purpose = null
                            statusLocked()
                        } else {
                            null
                        }
                    }
                    unavailable?.let(onStatus)
                } finally {
                    samples?.fill(0f)
                    captured.zeroize()
                }
            }
        } catch (_: RejectedExecutionException) {
            captured.zeroize()
            val unavailable = synchronized(this) {
                if (!closed.get() && token == operationToken) {
                    ready = false
                    phase = SpeechRuntimePhase.UNAVAILABLE
                    purpose = null
                    statusLocked()
                } else {
                    null
                }
            }
            unavailable?.let(onStatus)
        }
    }

    @Synchronized fun snapshot(): SpeechRuntimeStatus = statusLocked()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val stopped = synchronized(this) {
            operationToken = Math.addExact(operationToken, 1L)
            window.reset()
            ready = false
            phase = SpeechRuntimePhase.STOPPED
            purpose = null
            statusLocked()
        }
        onStatus(stopped)
        try {
            worker.execute { runCatching { engine.close() } }
        } catch (_: RejectedExecutionException) {
            runCatching { engine.close() }
        }
        worker.shutdown()
    }

    private companion object {
        const val MAXIMUM_LEADING_SUPPRESSION_NANOS = 2_000_000_000L
        const val TRANSCRIPTION_TIMEOUT_MILLIS = 15_000L
    }

    private data class SpeechAnalysisStart(
        val captured: Pcm16WindowSnapshot?,
        val token: Long,
        val status: SpeechRuntimeStatus,
    )

    private fun statusLocked(inference: WhisperInference? = null): SpeechRuntimeStatus {
        val observed = inference ?: lastInference
        return SpeechRuntimeStatus(
            phase,
            purpose,
            observed?.speechDetected ?: false,
            observed?.transcript?.length ?: 0,
            observed?.elapsedNanos ?: 0L,
            observed?.transcriptionTimedOut ?: false,
            acceptedBlocks,
            rejectedBlocks,
            suppressedBlocks,
        )
    }
}
