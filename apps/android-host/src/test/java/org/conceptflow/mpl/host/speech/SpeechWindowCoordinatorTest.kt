// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.speech

import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.conceptflow.mpl.host.realtime.TimedAudioBlock
import org.conceptflow.mpl.v1.AudioSampleEncoding
import org.conceptflow.mpl.v1.MicrophoneChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechWindowCoordinatorTest {
    @Test
    fun `seventy four continuously drained blocks remain bounded and reach private recognition`() {
        val resultReady = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val samplesHeldByFake = AtomicReference<FloatArray>()
        val result = AtomicReference<PrivateSpeechResult>()
        val coordinator = SpeechWindowCoordinator(
            engine = FakeEngine(samplesHeldByFake),
            onStatus = { if (it.phase == SpeechRuntimePhase.READY) ready.countDown() },
            onPrivateResult = {
                result.set(it)
                resultReady.countDown()
            },
        )
        coordinator.prewarm()
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        assertTrue(coordinator.begin(SpeechWindowPurpose.USER_QUERY, 4L, 1_000_000_000L))
        repeat(74) { index ->
            coordinator.accept(listOf(block(index + 1L, 1_000_000_000L + index * 100_000_000L)))
        }
        coordinator.finish()

        assertTrue(resultReady.await(2, TimeUnit.SECONDS))
        assertTrue(requireNotNull(result.get()).speechDetected)
        assertEquals("where is the chair", result.get().transcript)
        assertEquals(74L, coordinator.snapshot().acceptedBlocks)
        assertEquals(0L, coordinator.snapshot().rejectedBlocks)
        assertTrue(requireNotNull(samplesHeldByFake.get()).all { it == 0f })
        coordinator.close()
    }

    @Test
    fun `ambient window runs VAD but never releases transcript content`() {
        val completed = CountDownLatch(1)
        val privateResult = AtomicReference<PrivateSpeechResult>()
        val coordinator = SpeechWindowCoordinator(
            engine = FakeEngine(),
            onStatus = {
                if (it.phase == SpeechRuntimePhase.READY && it.acceptedBlocks > 0L) completed.countDown()
            },
            onPrivateResult = privateResult::set,
        )
        coordinator.prewarm()
        while (coordinator.snapshot().phase == SpeechRuntimePhase.PREWARMING) Thread.yield()
        assertTrue(coordinator.begin(SpeechWindowPurpose.AMBIENT_AND_VAD, 1L, 0L))
        coordinator.accept(listOf(block(1L, 1L)))
        coordinator.finish()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(null, privateResult.get())
        assertTrue(coordinator.snapshot().speechDetected)
        assertEquals(0, coordinator.snapshot().transcriptCharacterCount)
        coordinator.close()
    }

    @Test
    fun `known playback blocks are excluded from recognition window`() {
        val coordinator = SpeechWindowCoordinator(FakeEngine())
        coordinator.prewarm()
        while (coordinator.snapshot().phase == SpeechRuntimePhase.PREWARMING) Thread.yield()
        assertTrue(coordinator.begin(SpeechWindowPurpose.USER_QUERY, 1L, 1_000L))
        coordinator.suppressKnownPlayback(2_000L)
        coordinator.accept(listOf(block(1L, 1_500L)))
        assertEquals(1L, coordinator.snapshot().suppressedBlocks)
        assertEquals(0L, coordinator.snapshot().acceptedBlocks)
        coordinator.cancelWindow()
        assertFalse(coordinator.snapshot().speechDetected)
        coordinator.close()
    }

    @Test
    fun `leading glasses consent cue is excluded relative to first audio arrival`() {
        val coordinator = SpeechWindowCoordinator(FakeEngine())
        coordinator.prewarm()
        while (coordinator.snapshot().phase == SpeechRuntimePhase.PREWARMING) Thread.yield()
        assertTrue(coordinator.begin(SpeechWindowPurpose.USER_QUERY, 1L, 1_000_000_000L))
        coordinator.suppressLeadingAudio(700_000_000L)

        coordinator.accept(
            listOf(
                block(1L, 1_100_000_000L),
                block(2L, 1_700_000_000L),
                block(3L, 1_800_000_000L),
            ),
        )

        assertEquals(2L, coordinator.snapshot().suppressedBlocks)
        assertEquals(1L, coordinator.snapshot().acceptedBlocks)
        coordinator.cancelWindow()
        coordinator.close()
    }

    @Test
    fun `a rejected PCM block is counted exactly once`() {
        val coordinator = SpeechWindowCoordinator(FakeEngine())
        coordinator.prewarm()
        while (coordinator.snapshot().phase == SpeechRuntimePhase.PREWARMING) Thread.yield()
        assertTrue(coordinator.begin(SpeechWindowPurpose.USER_QUERY, 1L, 1_000_000_000L))

        coordinator.accept(listOf(block(1L, 11_000_000_001L)))

        assertEquals(0L, coordinator.snapshot().acceptedBlocks)
        assertEquals(1L, coordinator.snapshot().rejectedBlocks)
        coordinator.cancelWindow()
        coordinator.close()
    }

    @Test
    fun `cancelling an in-flight analysis suppresses its stale private result`() {
        val analysisStarted = CountDownLatch(1)
        val releaseAnalysis = CountDownLatch(1)
        val privateResult = CountDownLatch(1)
        val coordinator = SpeechWindowCoordinator(
            engine = BlockingEngine(analysisStarted, releaseAnalysis),
            onPrivateResult = { privateResult.countDown() },
        )
        coordinator.prewarm()
        while (coordinator.snapshot().phase == SpeechRuntimePhase.PREWARMING) Thread.yield()
        assertTrue(coordinator.begin(SpeechWindowPurpose.USER_QUERY, 1L, 1_000L))
        coordinator.accept(listOf(block(1L, 1_001L)))
        coordinator.finish()
        assertTrue(analysisStarted.await(2, TimeUnit.SECONDS))

        coordinator.cancelWindow()
        releaseAnalysis.countDown()

        assertFalse(privateResult.await(250, TimeUnit.MILLISECONDS))
        assertFalse(coordinator.snapshot().speechDetected)
        coordinator.close()
    }

    @Test
    fun `VAD result is observable before bounded transcription completes`() {
        val transcriptionStarted = CountDownLatch(1)
        val releaseTranscription = CountDownLatch(1)
        val coordinator = SpeechWindowCoordinator(
            engine = BlockingEngine(transcriptionStarted, releaseTranscription),
        )
        coordinator.prewarm()
        while (coordinator.snapshot().phase == SpeechRuntimePhase.PREWARMING) Thread.yield()
        assertTrue(coordinator.begin(SpeechWindowPurpose.USER_QUERY, 1L, 1_000L))
        coordinator.accept(listOf(block(1L, 1_001L)))

        coordinator.finish()

        assertTrue(transcriptionStarted.await(2, TimeUnit.SECONDS))
        assertEquals(SpeechRuntimePhase.TRANSCRIBING, coordinator.snapshot().phase)
        assertTrue(coordinator.snapshot().speechDetected)
        releaseTranscription.countDown()
        coordinator.close()
    }

    @Test
    fun `transcription timeout remains a ready result instead of disabling speech`() {
        val resultReady = CountDownLatch(1)
        val result = AtomicReference<PrivateSpeechResult>()
        val coordinator = SpeechWindowCoordinator(
            engine = TimeoutEngine(),
            onPrivateResult = {
                result.set(it)
                resultReady.countDown()
            },
        )
        coordinator.prewarm()
        while (coordinator.snapshot().phase == SpeechRuntimePhase.PREWARMING) Thread.yield()
        assertTrue(coordinator.begin(SpeechWindowPurpose.USER_QUERY, 1L, 1_000L))
        coordinator.accept(listOf(block(1L, 1_001L)))

        coordinator.finish()

        assertTrue(resultReady.await(2, TimeUnit.SECONDS))
        assertEquals(SpeechRuntimePhase.READY, coordinator.snapshot().phase)
        assertTrue(coordinator.snapshot().speechDetected)
        assertTrue(coordinator.snapshot().transcriptionTimedOut)
        assertTrue(requireNotNull(result.get()).transcriptionTimedOut)
        coordinator.close()
    }

    @Test
    fun `worker shutdown during capture fails closed without leaking an exception`() {
        val worker = Executors.newSingleThreadExecutor()
        val coordinator = SpeechWindowCoordinator(FakeEngine(), worker)
        assertTrue(coordinator.begin(SpeechWindowPurpose.USER_QUERY, 1L, 1_000L))
        coordinator.accept(listOf(block(1L, 1_001L)))
        worker.shutdownNow()

        coordinator.finish()

        assertEquals(SpeechRuntimePhase.UNAVAILABLE, coordinator.snapshot().phase)
        coordinator.close()
    }

    @Test
    fun `status callbacks never execute while holding the coordinator monitor`() {
        val ready = CountDownLatch(1)
        val resultReady = CountDownLatch(1)
        val callbackHeldMonitor = AtomicBoolean(false)
        lateinit var coordinator: SpeechWindowCoordinator
        coordinator = SpeechWindowCoordinator(
            engine = FakeEngine(),
            onStatus = {
                if (Thread.holdsLock(coordinator)) callbackHeldMonitor.set(true)
                if (it.phase == SpeechRuntimePhase.READY) ready.countDown()
            },
            onPrivateResult = { resultReady.countDown() },
        )

        coordinator.prewarm()
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        assertTrue(coordinator.begin(SpeechWindowPurpose.USER_QUERY, 1L, 1_000L))
        coordinator.accept(listOf(block(1L, 1_001L)))
        coordinator.finish()
        assertTrue(resultReady.await(2, TimeUnit.SECONDS))
        coordinator.cancelWindow()
        coordinator.close()

        assertFalse(callbackHeldMonitor.get())
    }

    private class FakeEngine(
        private val observed: AtomicReference<FloatArray> = AtomicReference(),
    ) : WhisperSpeechEngine {
        override fun prewarm() = Unit

        override fun detectSpeech(samples: FloatArray): WhisperVadResult {
            observed.set(samples)
            return WhisperVadResult(true, 5L)
        }

        override fun transcribe(samples: FloatArray, timeoutMillis: Long) =
            WhisperTranscription("where is the chair", 7L)

        override fun close() = Unit
    }

    private class BlockingEngine(
        private val started: CountDownLatch,
        private val release: CountDownLatch,
    ) : WhisperSpeechEngine {
        override fun prewarm() = Unit

        override fun detectSpeech(samples: FloatArray) = WhisperVadResult(true, 1L)

        override fun transcribe(samples: FloatArray, timeoutMillis: Long): WhisperTranscription {
            started.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            return WhisperTranscription("stale result", 1L)
        }

        override fun close() = Unit
    }

    private class TimeoutEngine : WhisperSpeechEngine {
        private val transcribed = AtomicBoolean(false)

        override fun prewarm() = Unit

        override fun detectSpeech(samples: FloatArray) = WhisperVadResult(true, 1L)

        override fun transcribe(samples: FloatArray, timeoutMillis: Long): WhisperTranscription {
            check(transcribed.compareAndSet(false, true))
            assertEquals(15_000L, timeoutMillis)
            return WhisperTranscription("", timeoutMillis * 1_000_000L, timedOut = true)
        }

        override fun close() = Unit
    }

    private fun block(id: Long, timestampNs: Long): TimedAudioBlock {
        val samples = ShortArray(1_600) { if (it % 16 < 8) 2_000 else -2_000 }
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putShort)
        return TimedAudioBlock(
            MicrophoneChunk.newBuilder()
                .setLeaseId("lease")
                .setChunkId(id)
                .setCaptureMonotonicTimestampNs(timestampNs)
                .setSampleRateHz(16_000)
                .setChannelCount(1)
                .setEncoding(AudioSampleEncoding.AUDIO_SAMPLE_ENCODING_PCM_S16LE)
                .setAudioData(ByteString.copyFrom(bytes.array()))
                .build(),
            timestampNs,
            0L,
        )
    }
}
