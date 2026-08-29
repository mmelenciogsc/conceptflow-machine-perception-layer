// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.conceptflow.mpl.host.focus.FocusedVqaCorrelation
import org.conceptflow.mpl.host.focus.FocusedVqaRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusedVqaFrameStoreTest {
    @Test
    fun `store retains only exact aspect-preserved bounded RGB source frames`() {
        var now = 1_000L
        val store = BoundedFocusedVqaFrameStore({ now })
        store.beginSession(3L)
        val image = RgbImage(1_920, 1_080, ByteArray(1_920 * 1_080 * 3) { (it % 251).toByte() })

        assertTrue(store.offer(3L, visionFrame(7L, 1_000L, 1_920, 1_080), image))
        assertNull(store.exact(FocusedVqaFrameKey(3L, 7L, 999L)))
        val retained = requireNotNull(store.exact(FocusedVqaFrameKey(3L, 7L, 1_000L)))

        assertEquals(1_920, retained.sourceWidth)
        assertEquals(1_080, retained.sourceHeight)
        assertEquals(640, retained.image.width)
        assertEquals(360, retained.image.height)
        assertTrue(retained.image.pixels.size <= BoundedFocusedVqaFrameStore.MAXIMUM_RGB_BYTES_PER_FRAME)
        retained.image.pixels.fill(0)
        assertTrue(requireNotNull(store.exact(retained.key)).image.pixels.any { it.toInt() != 0 })
        assertTrue(store.stats().rgbBytes <= BoundedFocusedVqaFrameStore.MAXIMUM_TOTAL_RGB_BYTES)

        now += BoundedFocusedVqaFrameStore.FRAME_TTL_NANOS
        assertNull(store.exact(retained.key))
    }

    @Test
    fun `capacity eviction and session reset remove retained sources`() {
        var now = 100L
        val store = BoundedFocusedVqaFrameStore({ now }, maximumFrameCount = 2, frameTtlNanos = 100L)
        store.beginSession(1L)
        for (id in 1L..3L) {
            now += 1L
            assertTrue(store.offer(1L, visionFrame(id, now, 1, 1), RgbImage(1, 1, byteArrayOf(id.toByte(), 2, 3))))
        }

        assertNull(store.exact(FocusedVqaFrameKey(1L, 1L, 101L)))
        assertNotNull(store.exact(FocusedVqaFrameKey(1L, 2L, 102L)))
        assertEquals(2, store.stats().frameCount)

        store.reset()

        assertEquals(0, store.stats().frameCount)
        assertEquals(0, store.stats().rgbBytes)
        assertNull(store.exact(FocusedVqaFrameKey(1L, 3L, 103L)))
    }

    @Test
    fun `late frame from prior session cannot repopulate reset store`() {
        var now = 100L
        val store = BoundedFocusedVqaFrameStore({ now })
        store.beginSession(1L)
        assertTrue(store.offer(1L, visionFrame(1L, now, 1, 1), RgbImage(1, 1, byteArrayOf(1, 2, 3))))

        store.beginSession(2L)
        now += 1L
        assertFalse(store.offer(1L, visionFrame(2L, now, 1, 1), RgbImage(1, 1, byteArrayOf(4, 5, 6))))
        assertNull(store.exact(FocusedVqaFrameKey(1L, 2L, now)))
        assertEquals(0, store.stats().frameCount)

        assertTrue(store.offer(2L, visionFrame(1L, now, 1, 1), RgbImage(1, 1, byteArrayOf(7, 8, 9))))
        assertNotNull(store.exact(FocusedVqaFrameKey(2L, 1L, now)))
    }

    @Test
    fun `provider encodes only explicit exact request and uses bounded context crop`() {
        val now = 500L
        val store = BoundedFocusedVqaFrameStore({ now })
        store.beginSession(1L)
        val image = RgbImage(100, 50, ByteArray(100 * 50 * 3) { 12 })
        assertTrue(store.offer(1L, visionFrame(9L, now, 100, 50), image))
        val encodingCalls = AtomicInteger()
        val encodedShape = AtomicReference<Pair<Int, Int>>()
        val provider = StoredFocusedVqaFrameProvider(
            store,
            FocusedVqaJpegEncoder { selected ->
                encodingCalls.incrementAndGet()
                encodedShape.set(selected.width to selected.height)
                byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
            },
        )
        assertEquals("camera/store path must not JPEG-encode", 0, encodingCalls.get())
        val request = focusRequest(9L, now).copy(
            imageGeometry = InstanceMaskGeometry(100, 50, 20, 10, 60, 30),
        )

        val encoded = requireNotNull(provider.prepare(request))

        assertEquals(1, encodingCalls.get())
        assertEquals(60 to 40, encodedShape.get())
        assertEquals(9L, encoded.frameId)
        assertEquals(now, encoded.captureMonotonicTimestampNanos)
        assertNull(provider.prepare(focusRequest(9L, now - 1L)))
        assertEquals(1, encodingCalls.get())
    }

    @Test
    fun `gateway is asynchronous bounded and delivers an exactly correlated answer`() {
        val providerStarted = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        val outcomeLatch = CountDownLatch(1)
        val outcome = AtomicReference<LocalVlmFocusedObjectOutcome>()
        val providerThread = AtomicReference<String>()
        val client = RecordingClient { request, frame, callback ->
            assertEquals(14L, request.correlation.sourceFrameId)
            callback.onOutcome(
                LocalVlmFocusedObjectOutcome.Answered(
                    LocalVlmFocusedObjectAnswer(request.correlation, 1_200L, "A doorway."),
                ),
            )
            LocalVlmSubmissionResult.ACCEPTED
        }
        val gateway = AndroidLocalVlmFocusedVqaGateway(
            client,
            FocusedVqaFrameProvider { request ->
                providerThread.set(Thread.currentThread().name)
                providerStarted.countDown()
                releaseProvider.await(2, TimeUnit.SECONDS)
                encoded(request)
            },
            LocalVlmFocusedObjectCallback {
                outcome.set(it)
                outcomeLatch.countDown()
            },
            clockNanos = { 1_000L },
        )
        val request = focusRequest(14L, 1_000L)

        assertTrue(gateway.submit(request))
        assertTrue(providerStarted.await(1, TimeUnit.SECONDS))
        assertFalse(providerThread.get().contains("Test worker"))
        assertEquals(LocalVlmSubmissionResult.BUSY, gateway.submitDetailed(focusRequest(15L, 1_001L)))
        releaseProvider.countDown()
        assertTrue(outcomeLatch.await(2, TimeUnit.SECONDS))

        val answered = outcome.get() as LocalVlmFocusedObjectOutcome.Answered
        assertEquals(request.correlation.requestId, answered.value.correlation.focusRequestId)
        assertTrue(requireNotNull(client.lastFrame.get()).jpeg.all { it.toInt() == 0 })
        gateway.close()
    }

    @Test
    fun `accepted submission telemetry precedes concurrent terminal and rejection omits submitted`() {
        val submittedEmissionStarted = CountDownLatch(1)
        val callbackReturned = CountDownLatch(1)
        val outcomeLatch = CountDownLatch(1)
        val callbackThread = AtomicReference<Thread>()
        val phases = mutableListOf<FocusedVqaPhase>()
        val client = RecordingClient { request, _, callback ->
            callbackThread.set(
                Thread({
                    submittedEmissionStarted.await(1L, TimeUnit.SECONDS)
                    callback.onOutcome(
                        LocalVlmFocusedObjectOutcome.Answered(
                            LocalVlmFocusedObjectAnswer(request.correlation, 5_200L, "A doorway."),
                        ),
                    )
                    callbackReturned.countDown()
                }, "focused-vqa-test-callback").also(Thread::start),
            )
            LocalVlmSubmissionResult.ACCEPTED
        }
        val gateway = AndroidLocalVlmFocusedVqaGateway(
            client,
            FocusedVqaFrameProvider(::encoded),
            LocalVlmFocusedObjectCallback { outcomeLatch.countDown() },
            clockNanos = { 5_000L },
            onTelemetry = { event ->
                if (event.phase == FocusedVqaPhase.SUBMITTED) {
                    submittedEmissionStarted.countDown()
                    callbackReturned.await(1L, TimeUnit.SECONDS)
                }
                synchronized(phases) { phases += event.phase }
            },
        )

        assertTrue(gateway.submit(focusRequest(51L, 5_000L)))
        assertTrue(outcomeLatch.await(2L, TimeUnit.SECONDS))
        callbackThread.get()?.join(1_000L)
        assertEquals(
            listOf(
                FocusedVqaPhase.ADMITTED,
                FocusedVqaPhase.FRAME_READY,
                FocusedVqaPhase.SUBMITTED,
                FocusedVqaPhase.TERMINAL,
            ),
            synchronized(phases) { phases.toList() },
        )
        gateway.close()

        val rejectedPhases = mutableListOf<FocusedVqaPhase>()
        val rejectedOutcome = CountDownLatch(1)
        val rejectedGateway = AndroidLocalVlmFocusedVqaGateway(
            RecordingClient { _, _, _ -> LocalVlmSubmissionResult.BUSY },
            FocusedVqaFrameProvider(::encoded),
            LocalVlmFocusedObjectCallback { rejectedOutcome.countDown() },
            clockNanos = { 5_000L },
            onTelemetry = { synchronized(rejectedPhases) { rejectedPhases += it.phase } },
        )
        assertTrue(rejectedGateway.submit(focusRequest(52L, 5_000L)))
        assertTrue(rejectedOutcome.await(2L, TimeUnit.SECONDS))
        assertFalse(synchronized(rejectedPhases) { FocusedVqaPhase.SUBMITTED in rejectedPhases })
        rejectedGateway.close()
    }

    @Test
    fun `missing and mismatched sources fail closed with expected correlation`() {
        val missingOutcome = AtomicReference<LocalVlmFocusedObjectOutcome>()
        val missingLatch = CountDownLatch(1)
        val missingGateway = AndroidLocalVlmFocusedVqaGateway(
            RecordingClient { _, _, _ -> error("missing frame must not reach client") },
            FocusedVqaFrameProvider { null },
            LocalVlmFocusedObjectCallback {
                missingOutcome.set(it)
                missingLatch.countDown()
            },
            clockNanos = { 2_000L },
        )
        val request = focusRequest(21L, 2_000L)
        assertTrue(missingGateway.submit(request))
        assertTrue(missingLatch.await(2, TimeUnit.SECONDS))
        assertEquals(
            LocalVlmFocusedObjectFailure.INVALID_REQUEST,
            (missingOutcome.get() as LocalVlmFocusedObjectOutcome.Rejected).reason,
        )
        missingGateway.close()

        val mismatchOutcome = AtomicReference<LocalVlmFocusedObjectOutcome>()
        val mismatchLatch = CountDownLatch(1)
        val mismatchGateway = AndroidLocalVlmFocusedVqaGateway(
            RecordingClient { local, _, callback ->
                val wrong = local.correlation.copy(focusGeneration = local.correlation.focusGeneration + 1L)
                callback.onOutcome(
                    LocalVlmFocusedObjectOutcome.Answered(
                        LocalVlmFocusedObjectAnswer(wrong, 2_100L, "Wrong generation."),
                    ),
                )
                LocalVlmSubmissionResult.ACCEPTED
            },
            FocusedVqaFrameProvider(::encoded),
            LocalVlmFocusedObjectCallback {
                mismatchOutcome.set(it)
                mismatchLatch.countDown()
            },
            clockNanos = { 2_000L },
        )
        assertTrue(mismatchGateway.submit(request))
        assertTrue(mismatchLatch.await(2, TimeUnit.SECONDS))
        val rejected = mismatchOutcome.get() as LocalVlmFocusedObjectOutcome.Rejected
        assertEquals(LocalVlmFocusedObjectFailure.STALE_OR_MISMATCHED, rejected.reason)
        assertEquals(request.correlation.requestId, rejected.correlation.focusRequestId)
        mismatchGateway.close()
    }

    @Test
    fun `reset cancels queued preparation without delivering a stale outcome`() {
        val providerStarted = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        val callbackCount = AtomicInteger()
        val client = RecordingClient { _, _, _ -> error("reset request must not reach client") }
        val gateway = AndroidLocalVlmFocusedVqaGateway(
            client,
            FocusedVqaFrameProvider { request ->
                providerStarted.countDown()
                runCatching { releaseProvider.await(2, TimeUnit.SECONDS) }
                encoded(request)
            },
            LocalVlmFocusedObjectCallback { callbackCount.incrementAndGet() },
            clockNanos = { 3_000L },
        )

        assertTrue(gateway.submit(focusRequest(31L, 3_000L)))
        assertTrue(providerStarted.await(1, TimeUnit.SECONDS))
        gateway.reset()
        releaseProvider.countDown()
        Thread.sleep(50L)

        assertEquals(0, callbackCount.get())
        assertEquals(0, client.submissions.get())
        gateway.close()
    }

    @Test
    fun `watchdog times out no-callback request ignores late reply and reuses slot`() {
        val firstSubmitted = CountDownLatch(1)
        val outcomeLatch = CountDownLatch(1)
        val outcomeCount = AtomicInteger()
        val outcome = AtomicReference<LocalVlmFocusedObjectOutcome>()
        val nowNanos = AtomicLong()
        val telemetry = mutableListOf<FocusedVqaPhaseTelemetry>()
        val client = RecordingClient { _, _, _ ->
            firstSubmitted.countDown()
            LocalVlmSubmissionResult.ACCEPTED
        }
        val first = focusRequest(41L, 1_000L)
        nowNanos.set(
            FocusedVqaTiming.deadlineNanos(first.requestedTimestampNanos) - 150_000_000L,
        )
        val gateway = AndroidLocalVlmFocusedVqaGateway(
            client,
            FocusedVqaFrameProvider(::encoded),
            LocalVlmFocusedObjectCallback {
                outcome.set(it)
                outcomeCount.incrementAndGet()
                outcomeLatch.countDown()
            },
            clockNanos = nowNanos::get,
            onTelemetry = { synchronized(telemetry) { telemetry += it } },
        )

        assertTrue(gateway.submit(first))
        assertTrue(firstSubmitted.await(1L, TimeUnit.SECONDS))
        assertTrue(outcomeLatch.await(1L, TimeUnit.SECONDS))
        assertEquals(
            LocalVlmFocusedObjectFailure.TIMED_OUT,
            (outcome.get() as LocalVlmFocusedObjectOutcome.Rejected).reason,
        )
        assertEquals(1, client.cancellations.get())
        assertEquals(requireNotNull(first.toLocalVlmRequest()).correlation, client.lastCancellation.get())

        val lateCallback = requireNotNull(client.lastCallback.get())
        val localCorrelation = requireNotNull(first.toLocalVlmRequest()).correlation
        lateCallback.onOutcome(
            LocalVlmFocusedObjectOutcome.Answered(
                LocalVlmFocusedObjectAnswer(localCorrelation, nowNanos.get(), "A late answer."),
            ),
        )
        Thread.sleep(25L)
        assertEquals(1, outcomeCount.get())

        val second = focusRequest(42L, nowNanos.get())
        assertEquals(LocalVlmSubmissionResult.ACCEPTED, gateway.submitDetailed(second))
        assertTrue(synchronized(telemetry) { telemetry.any {
            it.requestId == first.correlation.requestId &&
                it.phase == FocusedVqaPhase.TERMINAL &&
                it.outcome == LocalVlmFocusedObjectFailure.TIMED_OUT
        } })
        gateway.close()
    }

    private class RecordingClient(
        private val onSubmit: (
            LocalVlmFocusedObjectRequest,
            EncodedJpegFrame,
            LocalVlmFocusedObjectCallback,
        ) -> LocalVlmSubmissionResult,
    ) : LocalFocusedVqaClient {
        val submissions = AtomicInteger()
        val cancellations = AtomicInteger()
        val lastFrame = AtomicReference<EncodedJpegFrame>()
        val lastCallback = AtomicReference<LocalVlmFocusedObjectCallback>()
        val lastCancellation = AtomicReference<LocalVlmFocusedObjectCorrelation>()

        override fun submitFocusedObjectVqa(
            request: LocalVlmFocusedObjectRequest,
            frame: EncodedJpegFrame,
            callback: LocalVlmFocusedObjectCallback,
        ): LocalVlmSubmissionResult {
            submissions.incrementAndGet()
            lastFrame.set(frame)
            lastCallback.set(callback)
            return onSubmit(request, frame, callback)
        }

        override fun cancelFocusedObjectVqa(correlation: LocalVlmFocusedObjectCorrelation): Boolean {
            cancellations.incrementAndGet()
            lastCancellation.set(correlation)
            return true
        }
    }

    private companion object {
        fun visionFrame(id: Long, capture: Long, width: Int, height: Int) =
            VisionFrame(id, capture, width, height, synthetic = false)

        fun focusRequest(frameId: Long, capture: Long) = FocusedVqaRequest(
            FocusedVqaCorrelation(frameId, 1L, 1L, 1L, "track-$frameId", frameId),
            requestedTimestampNanos = capture + 100L,
            sourceCaptureTimestampNanos = capture,
            question = "What is this?",
        )

        fun encoded(request: FocusedVqaRequest) = EncodedJpegFrame(
            request.correlation.sourceFrameId,
            request.sourceCaptureTimestampNanos,
            2,
            2,
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()),
        )
    }
}
