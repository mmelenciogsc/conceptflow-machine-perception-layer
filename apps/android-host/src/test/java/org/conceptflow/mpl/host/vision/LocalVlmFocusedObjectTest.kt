// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.conceptflow.mpl.host.focus.FocusedVqaCorrelation
import org.conceptflow.mpl.host.focus.FocusedVqaRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalVlmFocusedObjectTest {
    @Test
    fun typedTaskParserDoesNotAliasUnknownOrEnvironmentTasks() {
        assertEquals(
            LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1,
            LocalVlmTaskKind.parse("FOCUSED_OBJECT_VQA_V1"),
        )
        assertEquals(
            LocalVlmTaskKind.SCENE_ENVIRONMENT_CLASSIFICATION_V1,
            LocalVlmTaskKind.parse("SCENE_ENVIRONMENT_CLASSIFICATION_V1"),
        )
        assertNull(LocalVlmTaskKind.parse("focused_object_vqa_v1"))
        assertNull(LocalVlmTaskKind.parse("FOCUSED_OBJECT_VQA_V2"))
    }

    @Test
    fun questionAndAnswerTextAreNormalizedAndBounded() {
        assertEquals(
            "What color is it?",
            LocalVlmFocusedObjectQuestionSanitizer.sanitize("  What\tcolor  is it? \n"),
        )
        assertEquals("Wide", LocalVlmFocusedObjectAnswerParser.parse("  \uFF37ide  "))
        assertNull(LocalVlmFocusedObjectQuestionSanitizer.sanitize("What\u0000color?"))
        assertNull(
            LocalVlmFocusedObjectQuestionSanitizer.sanitize(
                "x".repeat(LocalVlmFocusedObjectQuestionSanitizer.MAXIMUM_CHARACTERS + 1),
            ),
        )
        assertNull(
            LocalVlmFocusedObjectAnswerParser.parse(
                "x".repeat(LocalVlmFocusedObjectAnswerParser.MAXIMUM_CHARACTERS + 1),
            ),
        )
        assertNull(LocalVlmFocusedObjectAnswerParser.parse(List(21) { "word" }.joinToString(" ")))
    }

    @Test
    fun focusRequestMapsEveryCorrelationFieldAndSanitizesQuestion() {
        val focus = FocusedVqaRequest(
            correlation = FocusedVqaCorrelation(
                requestId = 41L,
                sessionGeneration = 5L,
                snapshotId = 17L,
                focusGeneration = 9L,
                stableTrackId = "track-7",
                sourceFrameId = 88L,
            ),
            requestedTimestampNanos = 1_200L,
            sourceCaptureTimestampNanos = 1_000L,
            question = "  What   is visible? ",
        )

        val mapped = requireNotNull(focus.toLocalVlmRequest())

        assertEquals(41L, mapped.correlation.focusRequestId)
        assertEquals(5L, mapped.correlation.sessionGeneration)
        assertEquals(17L, mapped.correlation.snapshotId)
        assertEquals(9L, mapped.correlation.focusGeneration)
        assertEquals("track-7", mapped.correlation.stableTrackId)
        assertEquals(88L, mapped.correlation.sourceFrameId)
        assertEquals(1_000L, mapped.correlation.sourceCaptureTimestampNanos)
        assertEquals(1_200L, mapped.requestedMonotonicTimestampNanos)
        assertEquals("What is visible?", mapped.question)
    }

    @Test
    fun invalidCorrelationFailsClosedAndDistinctGenerationsAreNotEqual() {
        assertNull(
            FocusedVqaRequest(
                FocusedVqaCorrelation(1L, 1L, 1L, 1L, "bad\ntrack", 1L),
                requestedTimestampNanos = 10L,
                sourceCaptureTimestampNanos = 9L,
                question = "What is it?",
            ).toLocalVlmRequest(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            LocalVlmFocusedObjectCorrelation(1L, 0L, 1L, 1L, "track", 1L, 0L)
        }
        val first = correlation(focusGeneration = 2L)
        val stale = correlation(focusGeneration = 3L)
        assertNotEquals(first, stale)
        assertEquals(
            first,
            LocalVlmFocusedObjectAnswer(first, 101L, "It is blue.").correlation,
        )
    }

    @Test
    fun answerRequiresAnExactFreshResponseRatherThanSubmissionAlone() {
        val expected = correlation(focusGeneration = 2L)
        val valid = LocalVlmFocusedObjectResponseValidator.validateAnswer(
            expected,
            expectedRequestedNanos = 110L,
            requestStartedNanos = 110L,
            nowNanos = 150L,
            isAnswerResponse = true,
            returnedTask = LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1,
            returnedCorrelation = expected,
            returnedRequestedNanos = 110L,
            completedNanos = 140L,
            rawAnswer = "  It is blue. ",
        )
        assertEquals("It is blue.", valid?.answer)
        assertNull(
            LocalVlmFocusedObjectResponseValidator.validateAnswer(
                expectedCorrelation = expected,
                expectedRequestedNanos = 110L,
                requestStartedNanos = 110L,
                nowNanos = 150L,
                isAnswerResponse = false,
                returnedTask = LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1,
                returnedCorrelation = expected,
                returnedRequestedNanos = 110L,
                completedNanos = 140L,
                rawAnswer = "It is blue.",
            ),
        )
        assertNull(
            LocalVlmFocusedObjectResponseValidator.validateAnswer(
                expectedCorrelation = expected,
                expectedRequestedNanos = 110L,
                requestStartedNanos = 110L,
                nowNanos = 150L,
                isAnswerResponse = true,
                returnedTask = LocalVlmTaskKind.FOCUSED_OBJECT_VQA_V1,
                returnedCorrelation = correlation(focusGeneration = 3L),
                returnedRequestedNanos = 110L,
                completedNanos = 140L,
                rawAnswer = "It is blue.",
            ),
        )
    }

    @Test
    fun focusedCallbackDeliveryNeverRunsInlineUnderClientStateLock() {
        val queued = LinkedBlockingQueue<() -> Unit>()
        val clientStateLock = Object()
        val controllerLock = Object()
        val clientLockHeld = CountDownLatch(1)
        val controllerWaitingForClient = CountDownLatch(1)
        val callbackDelivered = CountDownLatch(1)
        val callbackObservedClientLock = AtomicBoolean(false)
        val outcome = LocalVlmFocusedObjectOutcome.Rejected(
            correlation(focusGeneration = 2L),
            LocalVlmFocusedObjectFailure.UNAVAILABLE,
        )
        val dispatcher = LocalVlmFocusedCallbackDispatcher(
            enqueue = { action -> queued.offer(action) },
        )

        val controllerThread = Thread({
            synchronized(controllerLock) {
                assertTrue(clientLockHeld.await(1L, TimeUnit.SECONDS))
                controllerWaitingForClient.countDown()
                synchronized(clientStateLock) {
                    // Mirrors reset/close: controller monitor, then client monitor.
                }
            }
        }, "controller-reset")
        val binderThread = Thread({
            synchronized(clientStateLock) {
                clientLockHeld.countDown()
                assertTrue(controllerWaitingForClient.await(1L, TimeUnit.SECONDS))
                assertTrue(
                    dispatcher.dispatch(LocalVlmFocusedObjectCallback {
                        callbackObservedClientLock.set(Thread.holdsLock(clientStateLock))
                        synchronized(controllerLock) { callbackDelivered.countDown() }
                    }, outcome),
                )
            }
        }, "binder-response")
        controllerThread.isDaemon = true
        binderThread.isDaemon = true

        controllerThread.start()
        binderThread.start()
        controllerThread.join(1_000L)
        binderThread.join(1_000L)
        assertFalse("controller/client lifecycle threads deadlocked", controllerThread.isAlive)
        assertFalse("binder/client lifecycle threads deadlocked", binderThread.isAlive)
        assertEquals(1, queued.size)

        requireNotNull(queued.poll()).invoke()
        assertTrue(callbackDelivered.await(1L, TimeUnit.SECONDS))
        assertFalse(callbackObservedClientLock.get())
    }

    @Test
    fun reconnectPolicyRecoversAfterDeathWithoutBusyLoopAndResetsAfterConnect() {
        val policy = LocalVlmReconnectPolicy(listOf(250L, 500L, 1_000L))

        val first = requireNotNull(policy.schedule())
        assertEquals(250L, first.delayMillis)
        assertNull("only one reconnect may be pending", policy.schedule())
        assertTrue(policy.consume(first))

        val second = requireNotNull(policy.schedule())
        assertEquals(500L, second.delayMillis)
        assertTrue(policy.consume(second))
        val third = requireNotNull(policy.schedule())
        assertEquals(1_000L, third.delayMillis)
        assertTrue(policy.consume(third))
        val capped = requireNotNull(policy.schedule())
        assertEquals(1_000L, capped.delayMillis)

        policy.connected()
        assertFalse("connection invalidates an old death timer", policy.consume(capped))
        val afterConnect = requireNotNull(policy.schedule())
        assertEquals(250L, afterConnect.delayMillis)

        policy.close()
        assertFalse(policy.consume(afterConnect))
        assertNull(policy.schedule())
    }

    private fun correlation(focusGeneration: Long) = LocalVlmFocusedObjectCorrelation(
        focusRequestId = 1L,
        sessionGeneration = 1L,
        snapshotId = 1L,
        focusGeneration = focusGeneration,
        stableTrackId = "track",
        sourceFrameId = 1L,
        sourceCaptureTimestampNanos = 100L,
    )
}
