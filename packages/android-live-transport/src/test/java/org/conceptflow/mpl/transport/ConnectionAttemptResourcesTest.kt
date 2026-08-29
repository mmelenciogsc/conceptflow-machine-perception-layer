// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionAttemptResourcesTest {
    @Test
    fun `pre-authentication rejection cannot terminate an established listener lifecycle`() {
        assertFalse(shouldNotifySessionDisconnect(sessionWasReady = false))
        assertTrue(shouldNotifySessionDisconnect(sessionWasReady = true))
    }

    @Test
    fun `late old worker cannot close resources owned by new attempt`() {
        val active = ActiveConnectionAttempt()
        val oldResource = RecordingCloseable()
        val newResource = RecordingCloseable()
        val oldAttempt = ConnectionAttemptResources().also {
            it.own(oldResource)
            active.activate(it)
        }

        active.release(oldAttempt)
        val newAttempt = ConnectionAttemptResources().also {
            it.own(newResource)
            active.activate(it)
        }
        oldAttempt.close()

        assertTrue(oldResource.closed)
        assertFalse(newResource.closed)
        active.closeCurrent()
        assertTrue(newResource.closed)
        active.release(newAttempt)
    }

    @Test
    fun `attempt close is idempotent and owns a late resource safely`() {
        val attempt = ConnectionAttemptResources()
        val first = RecordingCloseable()
        attempt.own(first)
        attempt.close()
        attempt.close()
        assertEquals(1, first.closeCount)

        val late = RecordingCloseable()
        runCatching { attempt.own(late) }
        assertEquals(1, late.closeCount)
    }

    @Test
    fun `cancelled accepted resource is closed even when ownership arrives late`() {
        val attempt = ConnectionAttemptResources()
        val late = RecordingCloseable()
        attempt.close()

        runCatching { attempt.own(late) }

        assertEquals(1, late.closeCount)
    }

    @Test
    fun `worker completion distinguishes cancellation from thread exit`() {
        val completion = WorkerCompletion()
        val task = FutureTask {
            completion.begin()
            completion.finish()
        }
        task.cancel(false)
        assertTrue(completion.await(task, 10))
    }

    @Test
    fun `interrupted shutdown wait returns promptly without escaping and preserves interruption`() {
        val completion = WorkerCompletion().also(WorkerCompletion::begin)
        val unfinished = FutureTask<Unit> { error("must not run") }
        val returned = AtomicBoolean(true)
        val interruptionPreserved = AtomicBoolean(false)
        val uncaught = AtomicReference<Throwable?>()
        val startedNs = System.nanoTime()
        val serverThread = Thread({
            Thread.currentThread().interrupt()
            try {
                returned.set(completion.await(unfinished, 10_000L))
                interruptionPreserved.set(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) {
                uncaught.set(error)
            }
        }, "simulated-poco-serve-shutdown")

        serverThread.start()
        serverThread.join(1_000L)

        assertFalse(serverThread.isAlive)
        assertFalse(returned.get())
        assertTrue(interruptionPreserved.get())
        assertEquals(null, uncaught.get())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs) < 1_000L)
    }

    @Test
    fun `interrupted graceful drain remains a bounded unsuccessful drain`() {
        val binding = LiveSessionBinding("session", "lease", ByteArray(32) { 7 })
        val close = GracefulSessionCloseState(binding)
        val completion = WorkerCompletion().also(WorkerCompletion::begin)
        val unfinished = FutureTask<Unit> { error("must not run") }
        val returned = AtomicBoolean(true)
        val interruptionPreserved = AtomicBoolean(false)
        val uncaught = AtomicReference<Throwable?>()
        val closer = Thread({
            Thread.currentThread().interrupt()
            try {
                returned.set(close.awaitWriterDrain(listOf(completion to unfinished), 10_000L))
                interruptionPreserved.set(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) {
                uncaught.set(error)
            }
        }, "simulated-live-link-close")

        closer.start()
        closer.join(1_000L)

        assertFalse(closer.isAlive)
        assertFalse(returned.get())
        assertTrue(interruptionPreserved.get())
        assertEquals(null, uncaught.get())
    }

    @Test
    fun `graceful close waits for an in-progress record before accepting the remote acknowledgement`() {
        val binding = LiveSessionBinding("session", "lease", ByteArray(32) { 7 })
        val close = GracefulSessionCloseState(binding)
        val completion = WorkerCompletion()
        val started = CountDownLatch(1)
        val allowRecordToFinish = CountDownLatch(1)
        val task = FutureTask {
            completion.begin()
            started.countDown()
            try {
                allowRecordToFinish.await()
            } finally {
                completion.finish()
            }
        }
        val thread = Thread(task, "graceful-close-test").also { it.start() }
        assertTrue(started.await(1, TimeUnit.SECONDS))

        assertTrue(close.beginDrain())
        assertFalse(close.awaitWriterDrain(listOf(completion to task), 20))
        allowRecordToFinish.countDown()
        assertTrue(close.awaitWriterDrain(listOf(completion to task), 1_000))
        assertTrue(close.beginCloseRequest())
        assertFalse(close.awaitAcknowledgement(20))
        assertTrue(close.acceptAcknowledgement(LiveControlMessages.leaseCloseAcknowledged(binding)))
        assertTrue(close.awaitAcknowledgement(20))
        thread.join(1_000)
        assertFalse(thread.isAlive)
    }

    @Test
    fun `camera EOF waits for exact authenticated remote close`() {
        val binding = LiveSessionBinding("session", "lease", ByteArray(32) { 8 })
        val otherBinding = LiveSessionBinding("other-session", "lease", ByteArray(32) { 8 })
        val close = GracefulSessionCloseState(binding)
        val cameraClosed = CountDownLatch(1)
        val result = FutureTask {
            cameraClosed.countDown()
            awaitAuthenticatedCameraLaneClosure(CameraLaneClosedException(), close, 1_000L)
        }
        val thread = Thread(result, "camera-close-before-control-test").also { it.start() }
        assertTrue(cameraClosed.await(1, TimeUnit.SECONDS))

        assertFalse(close.acceptRemoteCloseRequest(LiveControlMessages.leaseClose(otherBinding)))
        assertFalse(close.hasAuthenticatedCompletion())
        assertTrue(close.acceptRemoteCloseRequest(LiveControlMessages.leaseClose(binding)))

        assertTrue(result.get(1, TimeUnit.SECONDS))
        assertTrue(close.hasAuthenticatedCompletion())
        assertTrue(close.hasAuthenticatedRemoteClose())
        thread.join(1_000L)
        assertFalse(thread.isAlive)
    }

    @Test
    fun `camera socket close around acknowledgement is graceful but arbitrary failures are not`() {
        val binding = LiveSessionBinding("session", "lease", ByteArray(32) { 9 })
        val close = GracefulSessionCloseState(binding)
        assertTrue(close.beginDrain())
        assertTrue(close.beginCloseRequest())
        assertTrue(close.acceptAcknowledgement(LiveControlMessages.leaseCloseAcknowledged(binding)))

        assertTrue(
            awaitAuthenticatedCameraLaneClosure(
                SocketException("camera peer closed"),
                close,
                timeoutMs = 10L,
            ),
        )
        assertFalse(
            awaitAuthenticatedCameraLaneClosure(
                SocketTimeoutException("camera read timed out"),
                close,
                timeoutMs = 10L,
            ),
        )
        assertFalse(
            awaitAuthenticatedCameraLaneClosure(
                IOException("unrelated camera I/O failure"),
                close,
                timeoutMs = 10L,
            ),
        )
        assertFalse(
            awaitAuthenticatedCameraLaneClosure(
                EOFException("truncated camera frame"),
                close,
                timeoutMs = 10L,
            ),
        )
    }

    @Test
    fun `initiated close writes before writer drain and retains an early acknowledgement`() {
        val binding = LiveSessionBinding("session", "lease", ByteArray(32) { 10 })
        val close = GracefulSessionCloseState(binding)
        val writerCompletion = WorkerCompletion()
        val writerStarted = CountDownLatch(1)
        val allowWriterToFinish = CountDownLatch(1)
        val closeRequestWritten = CountDownLatch(1)
        val writer = FutureTask {
            writerCompletion.begin()
            writerStarted.countDown()
            try {
                allowWriterToFinish.await()
            } finally {
                writerCompletion.finish()
            }
        }
        val writerThread = Thread(writer, "close-order-writer-test").also { it.start() }
        assertTrue(writerStarted.await(1, TimeUnit.SECONDS))
        val closeTask = FutureTask {
            coordinateInitiatedSessionClose(
                close,
                listOf(writerCompletion to writer),
                drainTimeoutMs = 1_000L,
                acknowledgementTimeoutMs = 1_000L,
            ) {
                check(!writer.isDone) { "close request must precede writer drain" }
                closeRequestWritten.countDown()
                check(close.acceptAcknowledgement(LiveControlMessages.leaseCloseAcknowledged(binding)))
            }
        }
        val closeThread = Thread(closeTask, "close-order-coordinator-test").also { it.start() }
        assertTrue(closeRequestWritten.await(1, TimeUnit.SECONDS))
        assertFalse(writer.isDone)
        allowWriterToFinish.countDown()

        val outcome = closeTask.get(1, TimeUnit.SECONDS)
        assertTrue(outcome.closeRequestWritten)
        assertTrue(outcome.writersDrained)
        assertTrue(outcome.acknowledgementReceived)
        assertTrue(outcome.complete)
        writerThread.join(1_000L)
        closeThread.join(1_000L)
        assertFalse(writerThread.isAlive)
        assertFalse(closeThread.isAlive)
    }

    @Test
    fun `writer drain timeout cannot suppress an authenticated close request`() {
        val binding = LiveSessionBinding("session", "lease", ByteArray(32) { 11 })
        val close = GracefulSessionCloseState(binding)
        val writerCompletion = WorkerCompletion()
        val writerStarted = CountDownLatch(1)
        val allowWriterToFinish = CountDownLatch(1)
        val writer = FutureTask {
            writerCompletion.begin()
            writerStarted.countDown()
            try {
                allowWriterToFinish.await()
            } finally {
                writerCompletion.finish()
            }
        }
        val writerThread = Thread(writer, "close-timeout-writer-test").also { it.start() }
        assertTrue(writerStarted.await(1, TimeUnit.SECONDS))
        try {
            val outcome = coordinateInitiatedSessionClose(
                close,
                listOf(writerCompletion to writer),
                drainTimeoutMs = 20L,
                acknowledgementTimeoutMs = 20L,
            ) {
                check(close.acceptAcknowledgement(LiveControlMessages.leaseCloseAcknowledged(binding)))
            }

            assertTrue(outcome.closeRequestWritten)
            assertFalse(outcome.writersDrained)
            assertTrue(outcome.acknowledgementReceived)
            assertFalse(outcome.complete)
        } finally {
            allowWriterToFinish.countDown()
            writerThread.join(1_000L)
        }
        assertFalse(writerThread.isAlive)
    }

    @Test
    fun `close write failure is retained as a typed privacy safe category`() {
        val close = GracefulSessionCloseState(
            LiveSessionBinding("session", "lease", ByteArray(32) { 12 }),
        )

        val outcome = coordinateInitiatedSessionClose(
            close,
            emptyList(),
            drainTimeoutMs = 20L,
            acknowledgementTimeoutMs = 20L,
        ) {
            throw IOException("sensitive transport detail")
        }

        assertFalse(outcome.closeRequestWritten)
        assertTrue(outcome.writersDrained)
        assertFalse(outcome.acknowledgementReceived)
        assertEquals(LiveLinkCloseRequestFailure.TRANSPORT_IO, outcome.requestFailure)
    }

    @Test
    fun `bounded endpoint shutdown runs off caller and completes asynchronously`() {
        val caller = Thread.currentThread()
        val operationStarted = CountDownLatch(1)
        val allowCompletion = CountDownLatch(1)
        val operationThread = AtomicReference<Thread?>()
        val shutdownExecutor = Executors.newScheduledThreadPool(2) { runnable ->
            Thread(runnable, "injected-close-worker").apply { isDaemon = true }
        }
        val worker = BoundedEndpointShutdownWorker<String>(shutdownExecutor)

        val completion = worker.execute(
            timeoutMs = 1_000L,
            onTimeout = { "timeout" },
        ) {
            operationThread.set(Thread.currentThread())
            operationStarted.countDown()
            check(allowCompletion.await(1, TimeUnit.SECONDS))
            "complete"
        }

        assertTrue(operationStarted.await(1, TimeUnit.SECONDS))
        assertFalse(completion.isDone)
        assertTrue(operationThread.get() !== caller)
        allowCompletion.countDown()
        assertEquals("complete", completion.get(1, TimeUnit.SECONDS))
    }

    @Test
    fun `bounded endpoint shutdown watchdog supplies fallback and interrupts stalled operation`() {
        val operationStarted = CountDownLatch(1)
        val operationInterrupted = CountDownLatch(1)
        val shutdownExecutor = Executors.newScheduledThreadPool(2) { runnable ->
            Thread(runnable, "injected-close-timeout-worker").apply { isDaemon = true }
        }
        val worker = BoundedEndpointShutdownWorker<String>(shutdownExecutor)

        val completion = worker.execute(
            timeoutMs = 50L,
            onTimeout = { "bounded-timeout" },
        ) {
            operationStarted.countDown()
            try {
                Thread.sleep(5_000L)
                "unexpected"
            } catch (_: InterruptedException) {
                operationInterrupted.countDown()
                "interrupted"
            }
        }

        assertTrue(operationStarted.await(1, TimeUnit.SECONDS))
        assertEquals("bounded-timeout", completion.get(1, TimeUnit.SECONDS))
        assertTrue(operationInterrupted.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `bounded endpoint shutdown operation is one shot`() {
        val shutdownExecutor = Executors.newScheduledThreadPool(2) { runnable ->
            Thread(runnable, "injected-one-shot-worker").apply { isDaemon = true }
        }
        val worker = BoundedEndpointShutdownWorker<String>(shutdownExecutor)

        assertEquals(
            "complete",
            worker.execute(timeoutMs = 1_000L, onTimeout = { "timeout" }) { "complete" }
                .get(1, TimeUnit.SECONDS),
        )
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            worker.execute(timeoutMs = 1_000L, onTimeout = { "timeout" }) { "duplicate" }
        }
    }

    private class RecordingCloseable : Closeable {
        var closeCount = 0
        val closed get() = closeCount > 0
        override fun close() { closeCount += 1 }
    }
}
