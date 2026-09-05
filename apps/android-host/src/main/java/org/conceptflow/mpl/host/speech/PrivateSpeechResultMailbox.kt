// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.speech

data class PrivateSpeechResultSummary(
    val pending: Boolean = false,
    val speechDetected: Boolean = false,
    val transcriptCharacterCount: Int = 0,
    val transcriptionTimedOut: Boolean = false,
) {
    init {
        require(transcriptCharacterCount in 0..AndroidWhisperCppEngine.MAXIMUM_TRANSCRIPT_CHARACTERS)
    }
}

/**
 * Holds at most one explicit user-query result in RAM. Ambient captures never enter this mailbox,
 * and transcript content is released only through [consume].
 */
class PrivateSpeechResultMailbox(
    private val retentionNanos: Long = DEFAULT_RETENTION_NANOS,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private var retained: Retained? = null

    init {
        require(retentionNanos in 1_000_000_000L..MAXIMUM_RETENTION_NANOS)
    }

    @Synchronized
    fun offer(result: PrivateSpeechResult) {
        require(result.purpose == SpeechWindowPurpose.USER_QUERY)
        retained = Retained(result, expiryAfter(clockNanos()))
    }

    @Synchronized
    fun summary(): PrivateSpeechResultSummary {
        purgeExpired(clockNanos())
        val result = retained?.result ?: return PrivateSpeechResultSummary()
        return PrivateSpeechResultSummary(
            pending = true,
            speechDetected = result.speechDetected,
            transcriptCharacterCount = result.transcript.length,
            transcriptionTimedOut = result.transcriptionTimedOut,
        )
    }

    @Synchronized
    fun consume(): PrivateSpeechResult? {
        purgeExpired(clockNanos())
        return retained?.result.also { retained = null }
    }

    @Synchronized
    fun clear() {
        retained = null
    }

    private fun purgeExpired(nowNanos: Long) {
        if ((retained?.expiresAtNanos ?: Long.MAX_VALUE) <= nowNanos) retained = null
    }

    private fun expiryAfter(nowNanos: Long): Long =
        if (Long.MAX_VALUE - nowNanos < retentionNanos) Long.MAX_VALUE else nowNanos + retentionNanos

    private data class Retained(val result: PrivateSpeechResult, val expiresAtNanos: Long)

    private companion object {
        const val DEFAULT_RETENTION_NANOS = 120_000_000_000L
        const val MAXIMUM_RETENTION_NANOS = 600_000_000_000L
    }
}
