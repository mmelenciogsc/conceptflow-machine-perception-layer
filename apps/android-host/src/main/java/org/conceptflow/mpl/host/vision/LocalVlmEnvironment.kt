// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Stable task identifiers allow one local VLM runtime to acquire more typed capabilities later. */
enum class LocalVlmTaskKind {
    SCENE_ENVIRONMENT_CLASSIFICATION_V1,
    FOCUSED_OBJECT_VQA_V1,
    ;

    companion object {
        fun parse(value: String?): LocalVlmTaskKind? = entries.singleOrNull { it.name == value }
    }
}

enum class LocalVlmEnvironmentLabel {
    INDOOR,
    OUTDOOR,
    TRANSITION,
    UNKNOWN,
}

data class LocalVlmEnvironmentResult(
    val requestId: Long,
    val frameId: Long,
    val captureMonotonicTimestampNanos: Long,
    val completedMonotonicTimestampNanos: Long,
    val label: LocalVlmEnvironmentLabel,
    val modelId: String,
    val runtimeId: String,
    val computeUnit: String,
) {
    init {
        require(requestId > 0L && frameId > 0L)
        require(captureMonotonicTimestampNanos >= 0L)
        require(completedMonotonicTimestampNanos >= captureMonotonicTimestampNanos)
        require(modelId == LocalVlmModelProfile.MODEL_ID)
        require(runtimeId == LocalVlmModelProfile.RUNTIME_ID)
        require(computeUnit == LocalVlmModelProfile.COMPUTE_UNIT)
    }

    /** The score is a routing-policy weight, not a statistically calibrated model probability. */
    fun toEnvironmentSignal(): EnvironmentSignal? = when (label) {
        LocalVlmEnvironmentLabel.INDOOR -> signal(0.94)
        LocalVlmEnvironmentLabel.OUTDOOR -> signal(0.06)
        LocalVlmEnvironmentLabel.TRANSITION -> signal(0.50)
        LocalVlmEnvironmentLabel.UNKNOWN -> null
    }

    private fun signal(indoorProbability: Double) = EnvironmentSignal(
        sourceId = SOURCE_ID,
        family = EnvironmentSignalFamily.VLM_CAMERA,
        timestampNanos = captureMonotonicTimestampNanos,
        indoorProbability = indoorProbability,
        outdoorProbability = 1.0 - indoorProbability,
        reliability = EVIDENCE_RELIABILITY,
        originatingFrameId = frameId,
    )

    private companion object {
        const val SOURCE_ID = "qwen3-vl-2b-environment"
        const val EVIDENCE_RELIABILITY = 0.85
    }
}

/** Strict parser for grammar-constrained output. Descriptive or multi-label output fails closed. */
object LocalVlmEnvironmentOutputParser {
    fun parse(output: String): LocalVlmEnvironmentLabel? {
        if (output.length > MAX_OUTPUT_CHARACTERS) return null
        val normalized = output.trim().uppercase()
        return LocalVlmEnvironmentLabel.entries.singleOrNull { it.name == normalized }
    }

    const val GRAMMAR = "root ::= \"INDOOR\" | \"OUTDOOR\" | \"TRANSITION\" | \"UNKNOWN\""
    private const val MAX_OUTPUT_CHARACTERS = 32
}

data class LocalVlmFocusedObjectCorrelation(
    val focusRequestId: Long,
    val sessionGeneration: Long,
    val snapshotId: Long,
    val focusGeneration: Long,
    val stableTrackId: String,
    val sourceFrameId: Long,
    val sourceCaptureTimestampNanos: Long,
) {
    init {
        require(focusRequestId > 0L)
        require(sessionGeneration > 0L && snapshotId > 0L && focusGeneration > 0L)
        require(sourceFrameId > 0L && sourceCaptureTimestampNanos >= 0L)
        require(LocalVlmPlainText.normalizeIdentifier(stableTrackId) == stableTrackId)
    }
}

data class LocalVlmFocusedObjectRequest(
    val correlation: LocalVlmFocusedObjectCorrelation,
    val requestedMonotonicTimestampNanos: Long,
    val question: String,
    val deadlineMonotonicTimestampNanos: Long =
        FocusedVqaTiming.deadlineNanos(requestedMonotonicTimestampNanos),
) {
    init {
        require(requestedMonotonicTimestampNanos >= correlation.sourceCaptureTimestampNanos)
        require(
            deadlineMonotonicTimestampNanos ==
                FocusedVqaTiming.deadlineNanos(requestedMonotonicTimestampNanos),
        )
        require(LocalVlmFocusedObjectQuestionSanitizer.sanitize(question) == question)
    }
}

/** One end-to-end monotonic budget shared by preparation, IPC, lease wait, and generation. */
internal object FocusedVqaTiming {
    const val REQUEST_BUDGET_NANOS = 8_500_000_000L
    const val PUBLICATION_BUDGET_NANOS = 9_000_000_000L
    const val LEASE_RETRY_BUDGET_NANOS = 1_500_000_000L
    const val MAXIMUM_GENERATION_MILLIS = 8_000L

    fun deadlineNanos(requestedNanos: Long): Long = Math.addExact(requestedNanos, REQUEST_BUDGET_NANOS)

    fun hasTimeRemaining(deadlineNanos: Long, nowNanos: Long): Boolean = nowNanos < deadlineNanos

    /** Floor conversion guarantees the model timeout never exceeds the remaining wall budget. */
    fun remainingGenerationMillis(deadlineNanos: Long, nowNanos: Long): Long? {
        val remainingNanos = deadlineNanos - nowNanos
        if (remainingNanos < 1_000_000L) return null
        return minOf(MAXIMUM_GENERATION_MILLIS, remainingNanos / 1_000_000L)
    }

    fun mayRetryLease(
        task: LocalVlmTaskKind,
        reason: HtpLeaseRefusalReason,
        leaseStartedNanos: Long,
        nowNanos: Long,
        deadlineNanos: Long,
    ): Boolean = task == LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1 &&
        (reason == HtpLeaseRefusalReason.QNN_PRIORITY ||
            reason == HtpLeaseRefusalReason.BUSY ||
            reason == HtpLeaseRefusalReason.TIMEOUT) &&
        nowNanos < deadlineNanos &&
        nowNanos - leaseStartedNanos < LEASE_RETRY_BUDGET_NANOS
}

/**
 * Bounds the interval between cancellation of any native VLM task and forced teardown of the
 * dedicated `:local_vlm` process. GenieX cancellation is cooperative and may not return while a
 * native image-prefill call is in progress, so coroutine cancellation alone is not a hard HTP
 * occupancy bound.
 */
internal object LocalVlmNativeAbortPolicy {
    const val COOPERATIVE_STOP_GRACE_NANOS = 250_000_000L

    fun deadlineDelayNanos(deadlineNanos: Long, nowNanos: Long): Long {
        require(deadlineNanos >= 0L && nowNanos >= 0L)
        val abortAt = if (Long.MAX_VALUE - deadlineNanos < COOPERATIVE_STOP_GRACE_NANOS) {
            Long.MAX_VALUE
        } else {
            deadlineNanos + COOPERATIVE_STOP_GRACE_NANOS
        }
        return if (abortAt <= nowNanos) 0L else abortAt - nowNanos
    }
}

/** Measured on-device starting point; physical profiling remains the authority for adjustment. */
internal object LocalVlmRuntimeTuning {
    const val PREFILL_BATCH_TOKENS = 32
    // A physical Poco run measured the isolated VLM at 2.7 GB PSS immediately before HyperOS
    // killed both it and the foreground sensor process for LOW_MEMORY. Keep the runtime warm long
    // enough for bootstrap and one deliberate focus/VQA interaction, but never for the four-hour
    // sensor epoch. Stable scenes do not rewarm after this lease expires; a material scene change
    // or explicit focus interaction does.
    const val ENGINE_IDLE_RETENTION_NANOS = 120_000_000_000L
    const val CLIENT_READY_WINDOW_NANOS = 105_000_000_000L
    const val PROCESS_RESTART_PREWARM_BACKOFF_NANOS = 2_000_000_000L
    const val MEMORY_PRESSURE_TRIM_THRESHOLD = 10

    fun readyUntilNanos(nowNanos: Long): Long = Math.addExact(nowNanos, CLIENT_READY_WINDOW_NANOS)

    fun restartBackoffUntilNanos(currentNanos: Long, nowNanos: Long): Long = maxOf(
        currentNanos,
        Math.addExact(nowNanos, PROCESS_RESTART_PREWARM_BACKOFF_NANOS),
    )

    fun shouldReleaseForTrimLevel(level: Int): Boolean = level >= MEMORY_PRESSURE_TRIM_THRESHOLD
}

/**
 * Keeps an explicit focus prewarm visible to the main-process HTP scheduler while the isolated
 * VLM process retries a contended lease. A one-shot Binder request can otherwise be refused before
 * the next camera frame observes it, allowing continuous QNN work to starve every retry.
 *
 * The intent is deliberately bounded. It reserves an opportunity for prewarm, not permanent VLM
 * priority, and therefore cannot suppress fresh geometry work indefinitely.
 */
internal class FocusedVlmPrewarmIntent(
    private val maximumWindowNanos: Long = 8_000_000_000L,
) {
    private var startedNanos = -1L
    private var validUntilNanos = 0L

    init {
        require(maximumWindowNanos in 1_000_000L..30_000_000_000L)
    }

    @Synchronized
    fun request(nowNanos: Long): LocalVlmHtpWorkState {
        require(nowNanos >= 0L)
        if (!isActiveLocked(nowNanos)) {
            startedNanos = nowNanos
            validUntilNanos = if (Long.MAX_VALUE - nowNanos < maximumWindowNanos) {
                Long.MAX_VALUE
            } else {
                nowNanos + maximumWindowNanos
            }
        }
        return LocalVlmHtpWorkState(LocalVlmHtpWorkKind.PREWARM, startedNanos)
    }

    @Synchronized
    fun workState(nowNanos: Long): LocalVlmHtpWorkState? {
        require(nowNanos >= 0L)
        if (!isActiveLocked(nowNanos)) return null
        return LocalVlmHtpWorkState(LocalVlmHtpWorkKind.PREWARM, startedNanos)
    }

    @Synchronized
    fun clear() {
        startedNanos = -1L
        validUntilNanos = 0L
    }

    private fun isActiveLocked(nowNanos: Long): Boolean {
        if (startedNanos >= 0L && nowNanos < validUntilNanos) return true
        clear()
        return false
    }
}

data class LocalVlmFocusedObjectAnswer(
    val correlation: LocalVlmFocusedObjectCorrelation,
    val completedMonotonicTimestampNanos: Long,
    val answer: String,
) {
    init {
        require(completedMonotonicTimestampNanos >= correlation.sourceCaptureTimestampNanos)
        require(LocalVlmFocusedObjectAnswerParser.parse(answer) == answer)
    }
}

enum class LocalVlmFocusedObjectFailure {
    BUSY,
    DEFERRED_FOR_QNN,
    INVALID_REQUEST,
    INFERENCE_FAILED,
    STALE_OR_MISMATCHED,
    TIMED_OUT,
    UNAVAILABLE,
}

sealed interface LocalVlmFocusedObjectOutcome {
    data class Answered(val value: LocalVlmFocusedObjectAnswer) : LocalVlmFocusedObjectOutcome
    data class Rejected(
        val correlation: LocalVlmFocusedObjectCorrelation,
        val reason: LocalVlmFocusedObjectFailure,
    ) : LocalVlmFocusedObjectOutcome
}

fun interface LocalVlmFocusedObjectCallback {
    fun onOutcome(outcome: LocalVlmFocusedObjectOutcome)
}

/** Queues external callbacks so callers may safely request delivery while holding internal state locks. */
internal class LocalVlmFocusedCallbackDispatcher(
    private val enqueue: (() -> Unit) -> Boolean,
    private val onCallbackFailure: (Throwable) -> Unit = {},
) {
    fun dispatch(callback: LocalVlmFocusedObjectCallback, outcome: LocalVlmFocusedObjectOutcome): Boolean =
        enqueue {
            runCatching { callback.onOutcome(outcome) }.onFailure(onCallbackFailure)
        }
}

internal data class LocalVlmReconnectTicket(val generation: Long, val delayMillis: Long)

/** Bounded, duplicate-free retry state independent of Android lifecycle classes for JVM testing. */
internal class LocalVlmReconnectPolicy(
    retryDelaysMillis: List<Long> = listOf(250L, 500L, 1_000L, 2_000L, 5_000L, 15_000L, 30_000L),
) {
    private val delays = retryDelaysMillis.toList()
    private var generation = 0L
    private var attempt = 0
    private var pending = false
    private var closed = false

    init {
        require(delays.isNotEmpty() && delays.all { it > 0L })
    }

    @Synchronized
    fun schedule(): LocalVlmReconnectTicket? {
        if (closed || pending) return null
        pending = true
        val delay = delays[minOf(attempt, delays.lastIndex)]
        attempt = minOf(attempt + 1, delays.lastIndex)
        return LocalVlmReconnectTicket(++generation, delay)
    }

    @Synchronized
    fun consume(ticket: LocalVlmReconnectTicket): Boolean {
        if (closed || !pending || ticket.generation != generation) return false
        pending = false
        return true
    }

    @Synchronized
    fun connected() {
        generation += 1L
        attempt = 0
        pending = false
    }

    @Synchronized
    fun close() {
        generation += 1L
        pending = false
        closed = true
    }
}

enum class LocalVlmSubmissionResult { ACCEPTED, BUSY, INVALID_REQUEST, UNAVAILABLE }

/** Normalizes a bounded explicit question before it can enter a model prompt or IPC bundle. */
object LocalVlmFocusedObjectQuestionSanitizer {
    fun sanitize(question: String): String? = LocalVlmPlainText.normalize(
        question,
        maximumCharacters = MAXIMUM_CHARACTERS,
        maximumUtf8Bytes = MAXIMUM_UTF8_BYTES,
    )

    const val MAXIMUM_CHARACTERS = 192
    const val MAXIMUM_UTF8_BYTES = 384
}

/** Accepts one bounded plain-text answer and rejects control or formatting characters. */
object LocalVlmFocusedObjectAnswerParser {
    fun parse(output: String): String? {
        val normalized = LocalVlmPlainText.normalize(
            output,
            maximumCharacters = MAXIMUM_CHARACTERS,
            maximumUtf8Bytes = MAXIMUM_UTF8_BYTES,
        ) ?: return null
        return normalized.takeIf { it.split(' ').size <= MAXIMUM_WORDS }
    }

    const val MAXIMUM_CHARACTERS = 240
    const val MAXIMUM_UTF8_BYTES = 512
    const val MAXIMUM_WORDS = 16
}

/** Pure validation shared by the Android callback path and deterministic JVM tests. */
internal object LocalVlmFocusedObjectResponseValidator {
    fun matches(
        expectedCorrelation: LocalVlmFocusedObjectCorrelation,
        expectedRequestedNanos: Long,
        returnedTask: LocalVlmTaskKind?,
        returnedCorrelation: LocalVlmFocusedObjectCorrelation?,
        returnedRequestedNanos: Long,
    ): Boolean = returnedTask == LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1 &&
        returnedCorrelation == expectedCorrelation &&
        returnedRequestedNanos == expectedRequestedNanos

    fun validateAnswer(
        expectedCorrelation: LocalVlmFocusedObjectCorrelation,
        expectedRequestedNanos: Long,
        requestStartedNanos: Long,
        nowNanos: Long,
        isAnswerResponse: Boolean,
        returnedTask: LocalVlmTaskKind?,
        returnedCorrelation: LocalVlmFocusedObjectCorrelation?,
        returnedRequestedNanos: Long,
        completedNanos: Long,
        rawAnswer: String?,
    ): LocalVlmFocusedObjectAnswer? {
        if (!isAnswerResponse ||
            !matches(
                expectedCorrelation,
                expectedRequestedNanos,
                returnedTask,
                returnedCorrelation,
                returnedRequestedNanos,
            ) ||
            nowNanos < requestStartedNanos ||
            !FocusedVqaTiming.hasTimeRemaining(
                FocusedVqaTiming.deadlineNanos(expectedRequestedNanos),
                nowNanos,
            ) ||
            completedNanos < expectedCorrelation.sourceCaptureTimestampNanos ||
            completedNanos > nowNanos
        ) return null
        val answer = rawAnswer?.let(LocalVlmFocusedObjectAnswerParser::parse) ?: return null
        return LocalVlmFocusedObjectAnswer(expectedCorrelation, completedNanos, answer)
    }
}

private object LocalVlmPlainText {
    private const val MAXIMUM_IDENTIFIER_CHARACTERS = 128
    private const val MAXIMUM_IDENTIFIER_UTF8_BYTES = 256

    fun normalizeIdentifier(identifier: String): String? = normalize(
        identifier,
        MAXIMUM_IDENTIFIER_CHARACTERS,
        MAXIMUM_IDENTIFIER_UTF8_BYTES,
    )

    fun normalize(value: String, maximumCharacters: Int, maximumUtf8Bytes: Int): String? {
        if (value.isEmpty() || value.length > maximumCharacters * 2) return null
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        val result = StringBuilder(normalized.length.coerceAtMost(maximumCharacters))
        var spacePending = false
        normalized.forEach { character ->
            if (character.isWhitespace()) {
                spacePending = result.isNotEmpty()
            } else {
                val type = Character.getType(character)
                if (Character.isISOControl(character) ||
                    type == Character.FORMAT.toInt() ||
                    type == Character.PRIVATE_USE.toInt() ||
                    type == Character.SURROGATE.toInt() ||
                    type == Character.UNASSIGNED.toInt()
                ) return null
                if (spacePending) result.append(' ')
                result.append(character)
                spacePending = false
            }
            if (result.length > maximumCharacters) return null
        }
        val text = result.toString()
        if (text.isBlank() || text.encodeToByteArray().size > maximumUtf8Bytes) return null
        return text
    }
}

/**
 * Admission and retry policy for a relatively expensive VLM that must never become a 3-5 FPS
 * dependency. Bootstrap obtains two agreeing labels; after that, inference is admitted only for a
 * confirmed visual-scene change. Only one request can be in flight and failures use bounded
 * exponential backoff.
 */
class LocalVlmCadenceGate(
    private val bootstrapIntervalNanos: Long = 1_000_000_000L,
    private val minimumChangeIntervalNanos: Long = 5_000_000_000L,
    private val initialFailureBackoffNanos: Long = 2_000_000_000L,
    private val maximumFailureBackoffNanos: Long = 60_000_000_000L,
    private val stableConfirmationsRequired: Int = 2,
) {
    private var inFlight = false
    private var lastStartedNanos = Long.MIN_VALUE
    private var nextAllowedNanos = 0L
    private var confirmedLabel: LocalVlmEnvironmentLabel? = null
    private var candidateLabel: LocalVlmEnvironmentLabel? = null
    private var matchingCandidateResults = 0
    private var sceneChangePending = false
    private var consecutiveFailures = 0

    init {
        require(bootstrapIntervalNanos > 0L && minimumChangeIntervalNanos >= bootstrapIntervalNanos)
        require(initialFailureBackoffNanos > 0L)
        require(maximumFailureBackoffNanos >= initialFailureBackoffNanos)
        require(stableConfirmationsRequired in 2..10)
    }

    @Synchronized
    fun tryStart(nowNanos: Long, significantSceneChange: Boolean): Boolean {
        require(nowNanos >= 0L)
        if (inFlight || nowNanos < nextAllowedNanos) return false
        if (isStable() && !sceneChangePending && !significantSceneChange) return false
        val interval = if (isStable() && !sceneChangePending) {
            minimumChangeIntervalNanos
        } else {
            bootstrapIntervalNanos
        }
        if (lastStartedNanos != Long.MIN_VALUE && nowNanos - lastStartedNanos < interval) return false
        inFlight = true
        lastStartedNanos = nowNanos
        return true
    }

    /** Whether future classifier work is needed, without reserving an in-flight request. */
    @Synchronized
    fun needsClassification(significantSceneChange: Boolean): Boolean =
        confirmedLabel == null || sceneChangePending || significantSceneChange

    @Synchronized
    fun complete(label: LocalVlmEnvironmentLabel, nowNanos: Long): LocalVlmEnvironmentLabel? {
        require(inFlight && nowNanos >= 0L)
        inFlight = false
        consecutiveFailures = 0
        if (label == LocalVlmEnvironmentLabel.UNKNOWN || label == LocalVlmEnvironmentLabel.TRANSITION) {
            candidateLabel = null
            matchingCandidateResults = 0
            sceneChangePending = confirmedLabel != null
            nextAllowedNanos = nowNanos + bootstrapIntervalNanos
            return confirmedLabel
        }
        if (label == confirmedLabel) {
            candidateLabel = null
            matchingCandidateResults = 0
            sceneChangePending = false
        } else {
            if (candidateLabel == label) {
                matchingCandidateResults += 1
            } else {
                candidateLabel = label
                matchingCandidateResults = 1
            }
            sceneChangePending = confirmedLabel != null
            if (matchingCandidateResults >= stableConfirmationsRequired) {
                confirmedLabel = label
                candidateLabel = null
                matchingCandidateResults = 0
                sceneChangePending = false
            }
        }
        nextAllowedNanos = nowNanos + if (isStable() && !sceneChangePending) {
            minimumChangeIntervalNanos
        } else {
            bootstrapIntervalNanos
        }
        return confirmedLabel
    }

    @Synchronized
    fun fail(nowNanos: Long) {
        require(nowNanos >= 0L)
        inFlight = false
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(MAX_FAILURE_EXPONENT + 1)
        val multiplier = 1L shl (consecutiveFailures - 1).coerceAtMost(MAX_FAILURE_EXPONENT)
        val delay = multiplySaturated(initialFailureBackoffNanos, multiplier)
            .coerceAtMost(maximumFailureBackoffNanos)
        nextAllowedNanos = addSaturated(nowNanos, delay)
    }

    /** Cooperative HTP deferral is contention, not a model failure; preserve stable evidence. */
    @Synchronized
    fun defer(nowNanos: Long, retryAfterNanos: Long) {
        require(nowNanos >= 0L && retryAfterNanos >= 0L)
        require(inFlight)
        inFlight = false
        // No model work began, so the refused admission must not consume the scene-change interval.
        lastStartedNanos = Long.MIN_VALUE
        nextAllowedNanos = max(nextAllowedNanos, addSaturated(nowNanos, retryAfterNanos))
    }

    @Synchronized
    fun cancel() {
        inFlight = false
    }

    @Synchronized
    fun reset() {
        inFlight = false
        lastStartedNanos = Long.MIN_VALUE
        nextAllowedNanos = 0L
        confirmedLabel = null
        candidateLabel = null
        matchingCandidateResults = 0
        sceneChangePending = false
        consecutiveFailures = 0
    }

    @Synchronized
    fun invalidateForSceneChange(nowNanos: Long) {
        require(nowNanos >= 0L)
        candidateLabel = null
        matchingCandidateResults = 0
        sceneChangePending = confirmedLabel != null
        nextAllowedNanos = max(nextAllowedNanos, nowNanos)
    }

    @Synchronized fun isStable(): Boolean = confirmedLabel != null
    @Synchronized fun confirmedLabel(): LocalVlmEnvironmentLabel? = confirmedLabel

    private fun multiplySaturated(value: Long, multiplier: Long): Long =
        if (value > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else value * multiplier

    private fun addSaturated(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else max(0L, value + increment)

    private companion object {
        const val MAX_FAILURE_EXPONENT = 5
    }
}

data class LocalVlmSceneDescriptor(
    val meanLuma: Double,
    val lumaStandardDeviation: Double,
    val normalizedLumaTiles: List<Double>,
    val lumaHistogram: List<Double>,
) {
    init {
        require(meanLuma.isFinite() && meanLuma in 0.0..1.0)
        require(lumaStandardDeviation.isFinite() && lumaStandardDeviation >= 0.0)
        require(normalizedLumaTiles.isNotEmpty() && normalizedLumaTiles.all(Double::isFinite))
        require(lumaHistogram.isNotEmpty() && lumaHistogram.all { it.isFinite() && it >= 0.0 })
        require(abs(lumaHistogram.sum() - 1.0) <= 1e-6)
    }
}

data class LocalVlmSceneChangeDecision(
    val significantChange: Boolean,
    val baselineMatched: Boolean,
    val normalizedChangeScore: Double,
)

data class LocalVlmSceneComparison(
    val materiallyDifferent: Boolean,
    val normalizedChangeScore: Double,
)

/**
 * Low-cost illumination gate over sparse luma samples. Ordinary camera translation and object
 * motion must not repeatedly invoke the VLM: they can change tile structure and histogram shape
 * while the indoor/outdoor state remains stable. A change is therefore material only when the
 * global mean changes strongly, or when both the luma histogram and contrast spread change. Four
 * consecutive changed frames reject flashes, auto-exposure transients, and brief occlusion.
 */
class LocalVlmSceneChangeGate(
    private val requiredChangedFrames: Int = 4,
    private val meanLumaThreshold: Double = 0.14,
    private val histogramDistanceThreshold: Double = 0.28,
    private val lumaSpreadThreshold: Double = 0.10,
) {
    private var baseline: LocalVlmSceneDescriptor? = null
    private var changedFrames = 0

    init {
        require(requiredChangedFrames in 1..10)
        require(meanLumaThreshold in 0.01..1.0)
        require(histogramDistanceThreshold in 0.01..1.0)
        require(lumaSpreadThreshold in 0.01..1.0)
    }

    @Synchronized
    fun observe(current: LocalVlmSceneDescriptor): LocalVlmSceneChangeDecision {
        val reference = baseline ?: return LocalVlmSceneChangeDecision(
            significantChange = false,
            baselineMatched = false,
            normalizedChangeScore = 0.0,
        )
        val comparison = compare(reference, current)
        changedFrames = if (comparison.materiallyDifferent) changedFrames + 1 else 0
        return LocalVlmSceneChangeDecision(
            significantChange = changedFrames >= requiredChangedFrames,
            baselineMatched = !comparison.materiallyDifferent,
            normalizedChangeScore = comparison.normalizedChangeScore,
        )
    }

    /**
     * Compares any two descriptors with the same thresholds used by the stable-scene gate. This is
     * also used to reject a VLM response when the illumination state changed materially after its
     * source image was admitted.
     */
    fun compare(
        reference: LocalVlmSceneDescriptor,
        current: LocalVlmSceneDescriptor,
    ): LocalVlmSceneComparison {
        require(reference.normalizedLumaTiles.size == current.normalizedLumaTiles.size)
        require(reference.lumaHistogram.size == current.lumaHistogram.size)
        val meanDelta = abs(current.meanLuma - reference.meanLuma)
        val histogramDistance = reference.lumaHistogram.zip(current.lumaHistogram)
            .sumOf { (first, second) -> abs(first - second) } / 2.0
        val spreadDelta = abs(current.lumaStandardDeviation - reference.lumaStandardDeviation)
        val distributionShift = histogramDistance >= histogramDistanceThreshold &&
            spreadDelta >= lumaSpreadThreshold
        return LocalVlmSceneComparison(
            materiallyDifferent = meanDelta >= meanLumaThreshold || distributionShift,
            normalizedChangeScore = maxOf(
                meanDelta / meanLumaThreshold,
                minOf(
                    histogramDistance / histogramDistanceThreshold,
                    spreadDelta / lumaSpreadThreshold,
                ),
            ),
        )
    }

    @Synchronized
    fun markClassified(descriptor: LocalVlmSceneDescriptor) {
        baseline = descriptor
        changedFrames = 0
    }

    @Synchronized
    fun reset() {
        baseline = null
        changedFrames = 0
    }
}

object LocalVlmSceneDescriptorExtractor {
    private const val GRID_COLUMNS = 16
    private const val GRID_ROWS = 16
    private const val HISTOGRAM_BINS = 16
    private const val MINIMUM_NORMALIZATION_SCALE = 0.08

    fun fromRgb(rgb: ByteArray, width: Int, height: Int, rowStrideBytes: Int): LocalVlmSceneDescriptor {
        require(width > 0 && height > 0 && rowStrideBytes >= width * 3)
        require(rgb.size >= rowStrideBytes * height)
        val luma = ArrayList<Double>(GRID_COLUMNS * GRID_ROWS)
        for (row in 0 until GRID_ROWS) {
            val y = ((row + 0.5) * height / GRID_ROWS).toInt().coerceIn(0, height - 1)
            for (column in 0 until GRID_COLUMNS) {
                val x = ((column + 0.5) * width / GRID_COLUMNS).toInt().coerceIn(0, width - 1)
                val offset = y * rowStrideBytes + x * 3
                val red = rgb[offset].toInt() and 0xff
                val green = rgb[offset + 1].toInt() and 0xff
                val blue = rgb[offset + 2].toInt() and 0xff
                luma += (54 * red + 183 * green + 19 * blue) / (255.0 * 256.0)
            }
        }
        return fromLumaSamples(luma)
    }

    fun fromLumaSamples(samples: List<Double>): LocalVlmSceneDescriptor {
        require(samples.isNotEmpty() && samples.all { it.isFinite() && it in 0.0..1.0 })
        val mean = samples.average()
        val standardDeviation = sqrt(samples.sumOf { (it - mean) * (it - mean) } / samples.size)
        val normalizationScale = max(standardDeviation, MINIMUM_NORMALIZATION_SCALE)
        val histogram = DoubleArray(HISTOGRAM_BINS)
        samples.forEach { value ->
            histogram[(value * HISTOGRAM_BINS).toInt().coerceIn(0, HISTOGRAM_BINS - 1)] += 1.0
        }
        return LocalVlmSceneDescriptor(
            meanLuma = mean,
            lumaStandardDeviation = standardDeviation,
            normalizedLumaTiles = samples.map { ((it - mean) / normalizationScale).coerceIn(-2.0, 2.0) },
            lumaHistogram = histogram.map { it / samples.size },
        )
    }
}

object LocalVlmModelProfile {
    const val MODEL_ID = "qwen3-vl-2b-instruct-q4_0"
    const val UPSTREAM_REPOSITORY = "unsloth/Qwen3-VL-2B-Instruct-GGUF"
    const val UPSTREAM_REVISION = "main"
    const val MODEL_FILE = "Qwen3-VL-2B-Instruct-Q4_0.gguf"
    const val PROJECTOR_FILE = "mmproj-F16.gguf"
    const val MODEL_SHA256 = "d9ca31f524d063c04e49d1af7b0b37061b21e7f8a7e460141654efe287600234"
    const val PROJECTOR_SHA256 = "cd5a851d3928697fa1bd76d459d2cc409b6cf40c9d9682b2f5c8e7c6a9f9630f"
    const val MODEL_BYTES = 1_056_784_064L
    const val PROJECTOR_BYTES = 819_395_232L
    const val RUNTIME_ID = "llama_cpp"
    const val COMPUTE_UNIT = "npu"
    const val GENIEX_ANDROID_VERSION = "0.4.0"

    const val ENVIRONMENT_PROMPT =
        "Classify the physical setting shown. Output exactly one uppercase label: " +
            "INDOOR for an enclosed building or vehicle interior; OUTDOOR for open exterior space; " +
            "TRANSITION for a doorway or ambiguous indoor-outdoor boundary; UNKNOWN when the image " +
            "does not support a reliable choice. Output only the label."
}
