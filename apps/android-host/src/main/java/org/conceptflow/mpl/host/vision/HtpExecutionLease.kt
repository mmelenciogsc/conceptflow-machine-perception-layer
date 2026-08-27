// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class HtpLeaseWorkload { QNN, VLM }

enum class HtpLeaseRefusalReason {
    QNN_PRIORITY,
    BUSY,
    TIMEOUT,
    CANCELLED,
    IO_FAILURE,
}

data class HtpLeaseTelemetry(
    val workload: HtpLeaseWorkload,
    val acquired: Boolean,
    val refusalReason: HtpLeaseRefusalReason?,
    val waitNanos: Long,
    val holdNanos: Long?,
)

data class HtpLeaseTelemetrySnapshot(
    val acquisitions: Long,
    val refusals: Long,
    val cancellations: Long,
    val totalWaitNanos: Long,
    val totalHoldNanos: Long,
    val maximumWaitNanos: Long,
    val maximumHoldNanos: Long,
    val activeHolders: Long,
)

sealed interface HtpLeaseAcquisition {
    data class Acquired(val handle: HtpExecutionLease.Handle, val waitNanos: Long) : HtpLeaseAcquisition
    data class Refused(val reason: HtpLeaseRefusalReason, val waitNanos: Long) : HtpLeaseAcquisition
}

/**
 * Deadline-bounded cross-process HTP arbitration.
 *
 * QNN takes a crash-released priority file lock before waiting for graph execution. VLM admission
 * probes that lock and fails immediately while QNN demand exists. Once VLM execution has begun it
 * cannot be forcibly preempted here; [qnnPriorityRequested] lets its supported streaming runtime
 * cooperatively stop. Android/Linux releases both file locks if either process dies.
 */
class HtpExecutionLease(
    private val lockFile: File,
    private val clockNanos: () -> Long = System::nanoTime,
    private val onTelemetry: (HtpLeaseTelemetry) -> Unit = {},
) {
    constructor(
        context: Context,
        onTelemetry: (HtpLeaseTelemetry) -> Unit = {},
    ) : this(
        File(context.applicationContext.noBackupFilesDir, RELATIVE_LOCK_PATH),
        System::nanoTime,
        onTelemetry,
    )

    private val acquisitions = AtomicLong()
    private val refusals = AtomicLong()
    private val cancellations = AtomicLong()
    private val totalWaitNanos = AtomicLong()
    private val totalHoldNanos = AtomicLong()
    private val maximumWaitNanos = AtomicLong()
    private val maximumHoldNanos = AtomicLong()
    private val activeHolders = AtomicLong()

    fun tryAcquire(
        workload: HtpLeaseWorkload,
        timeoutMillis: Long,
        cancelled: () -> Boolean = { false },
    ): HtpLeaseAcquisition {
        require(timeoutMillis in 0L..MAXIMUM_ACQUISITION_TIMEOUT_MILLIS)
        val started = clockNanos()
        val deadline = saturatingAdd(started, TimeUnit.MILLISECONDS.toNanos(timeoutMillis))
        if (cancelled()) return refusal(workload, HtpLeaseRefusalReason.CANCELLED, started)

        var priority: OpenLock? = null
        var execution: OpenLock? = null
        var permitHeld = false
        try {
            ensureParentDirectory()
            priority = openLock(priorityFile())
            if (workload == HtpLeaseWorkload.VLM) {
                priority.lock = tryFileLock(priority.channel)
                    ?: return refusal(workload, HtpLeaseRefusalReason.QNN_PRIORITY, started)
            } else {
                val result = waitForFileLock(priority.channel, deadline, cancelled)
                if (result.lock == null) return refusal(workload, result.reason, started)
                priority.lock = result.lock
            }

            val processPermit = processPermit()
            permitHeld = if (workload == HtpLeaseWorkload.VLM) {
                processPermit.tryAcquire()
            } else {
                waitForPermit(processPermit, deadline, cancelled)
            }
            if (!permitHeld) {
                val reason = if (cancelled()) HtpLeaseRefusalReason.CANCELLED else {
                    if (workload == HtpLeaseWorkload.VLM) HtpLeaseRefusalReason.BUSY
                    else HtpLeaseRefusalReason.TIMEOUT
                }
                return refusal(workload, reason, started)
            }

            execution = openLock(lockFile)
            if (workload == HtpLeaseWorkload.VLM) {
                execution.lock = tryFileLock(execution.channel)
                    ?: return refusal(workload, HtpLeaseRefusalReason.BUSY, started)
            } else {
                val result = waitForFileLock(execution.channel, deadline, cancelled)
                if (result.lock == null) return refusal(workload, result.reason, started)
                execution.lock = result.lock
            }

            // VLM needed the priority lock only to make admission atomic with execution-lock
            // acquisition. Releasing it now lets a later QNN request become externally visible.
            if (workload == HtpLeaseWorkload.VLM) {
                priority.close()
                priority = null
            }
            val acquiredAt = clockNanos()
            val waitNanos = elapsed(started, acquiredAt)
            acquisitions.incrementAndGet()
            activeHolders.incrementAndGet()
            addSaturated(totalWaitNanos, waitNanos)
            updateMaximum(maximumWaitNanos, waitNanos)
            val handle = Handle(
                execution = requireNotNull(execution),
                priority = priority,
                processPermit = processPermit,
                workload = workload,
                acquiredAtNanos = acquiredAt,
                waitNanos = waitNanos,
                clockNanos = clockNanos,
                onClosed = ::recordClosed,
            )
            execution = null
            priority = null
            permitHeld = false
            return HtpLeaseAcquisition.Acquired(handle, waitNanos)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return refusal(workload, HtpLeaseRefusalReason.CANCELLED, started)
        } catch (_: Exception) {
            return refusal(workload, HtpLeaseRefusalReason.IO_FAILURE, started)
        } finally {
            execution?.close()
            priority?.close()
            if (permitHeld) processPermit().release()
        }
    }

    /** Fail-closed probe used by the isolated VLM's cooperative cancellation monitor. */
    fun qnnPriorityRequested(): Boolean {
        var opened: OpenLock? = null
        return try {
            ensureParentDirectory()
            opened = openLock(priorityFile())
            opened.lock = tryFileLock(opened.channel)
            opened.lock == null
        } catch (_: Exception) {
            true
        } finally {
            opened?.close()
        }
    }

    fun telemetrySnapshot() = HtpLeaseTelemetrySnapshot(
        acquisitions.get(),
        refusals.get(),
        cancellations.get(),
        totalWaitNanos.get(),
        totalHoldNanos.get(),
        maximumWaitNanos.get(),
        maximumHoldNanos.get(),
        activeHolders.get(),
    )

    class Handle internal constructor(
        private val execution: OpenLock,
        private val priority: OpenLock?,
        private val processPermit: Semaphore,
        private val workload: HtpLeaseWorkload,
        private val acquiredAtNanos: Long,
        private val waitNanos: Long,
        private val clockNanos: () -> Long,
        private val onClosed: (HtpLeaseTelemetry) -> Unit,
    ) : Closeable {
        private var closed = false

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            val holdNanos = elapsed(acquiredAtNanos, clockNanos())
            try {
                execution.close()
                priority?.close()
            } finally {
                processPermit.release()
                onClosed(HtpLeaseTelemetry(workload, true, null, waitNanos, holdNanos))
            }
        }
    }

    private fun refusal(
        workload: HtpLeaseWorkload,
        reason: HtpLeaseRefusalReason,
        startedNanos: Long,
    ): HtpLeaseAcquisition.Refused {
        val waitNanos = elapsed(startedNanos, clockNanos())
        refusals.incrementAndGet()
        if (reason == HtpLeaseRefusalReason.CANCELLED) cancellations.incrementAndGet()
        addSaturated(totalWaitNanos, waitNanos)
        updateMaximum(maximumWaitNanos, waitNanos)
        // Diagnostics must never turn a bounded admission refusal into an inference failure.
        runCatching { onTelemetry(HtpLeaseTelemetry(workload, false, reason, waitNanos, null)) }
        return HtpLeaseAcquisition.Refused(reason, waitNanos)
    }

    private fun recordClosed(event: HtpLeaseTelemetry) {
        val holdNanos = requireNotNull(event.holdNanos)
        activeHolders.decrementAndGet()
        addSaturated(totalHoldNanos, holdNanos)
        updateMaximum(maximumHoldNanos, holdNanos)
        // A logger/exporter is outside the arbitration correctness boundary.
        runCatching { onTelemetry(event) }
    }

    private fun waitForPermit(
        permit: Semaphore,
        deadlineNanos: Long,
        cancelled: () -> Boolean,
    ): Boolean {
        var firstAttempt = true
        while (!cancelled()) {
            val remaining = remaining(deadlineNanos)
            if (!firstAttempt && remaining == 0L) return false
            if (permit.tryAcquire(minOf(remaining, POLL_NANOS), TimeUnit.NANOSECONDS)) return true
            if (remaining == 0L) return false
            firstAttempt = false
        }
        return false
    }

    private fun waitForFileLock(
        channel: FileChannel,
        deadlineNanos: Long,
        cancelled: () -> Boolean,
    ): LockAttempt {
        var firstAttempt = true
        while (!cancelled()) {
            val remaining = remaining(deadlineNanos)
            if (!firstAttempt && remaining == 0L) {
                return LockAttempt(null, HtpLeaseRefusalReason.TIMEOUT)
            }
            val lock = tryFileLock(channel)
            if (lock != null) return LockAttempt(lock, HtpLeaseRefusalReason.TIMEOUT)
            if (remaining == 0L) return LockAttempt(null, HtpLeaseRefusalReason.TIMEOUT)
            TimeUnit.NANOSECONDS.sleep(minOf(remaining, POLL_NANOS))
            firstAttempt = false
        }
        return LockAttempt(null, HtpLeaseRefusalReason.CANCELLED)
    }

    private fun tryFileLock(channel: FileChannel): FileLock? = try {
        channel.tryLock()
    } catch (_: OverlappingFileLockException) {
        null
    }

    private fun ensureParentDirectory() {
        lockFile.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) { "could not create HTP lease directory" }
        }
    }

    private fun priorityFile() = File(lockFile.parentFile, PRIORITY_LOCK_NAME)

    private fun openLock(file: File): OpenLock {
        val randomAccessFile = RandomAccessFile(file, "rw")
        return try {
            OpenLock(randomAccessFile, randomAccessFile.channel)
        } catch (error: Throwable) {
            randomAccessFile.close()
            throw error
        }
    }

    private fun processPermit(): Semaphore = PROCESS_PERMITS.computeIfAbsent(lockFile.canonicalPath) {
        Semaphore(1, true)
    }

    private fun remaining(deadlineNanos: Long): Long =
        (deadlineNanos - clockNanos()).coerceAtLeast(0L)

    private data class LockAttempt(val lock: FileLock?, val reason: HtpLeaseRefusalReason)

    internal class OpenLock(
        private val file: RandomAccessFile,
        val channel: FileChannel,
    ) : Closeable {
        var lock: FileLock? = null

        override fun close() {
            runCatching { lock?.release() }
            runCatching { channel.close() }
            runCatching { file.close() }
        }
    }

    private companion object {
        const val RELATIVE_LOCK_PATH = "htp/graph-execution.lock"
        const val PRIORITY_LOCK_NAME = "qnn-priority.lock"
        const val MAXIMUM_ACQUISITION_TIMEOUT_MILLIS = 10_000L
        val POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(5L)
        val PROCESS_PERMITS = ConcurrentHashMap<String, Semaphore>()

        fun elapsed(start: Long, end: Long): Long = (end - start).coerceAtLeast(0L)

        fun saturatingAdd(left: Long, right: Long): Long =
            if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

        fun addSaturated(target: AtomicLong, increment: Long) {
            while (true) {
                val current = target.get()
                val updated = saturatingAdd(current, increment)
                if (target.compareAndSet(current, updated)) return
            }
        }

        fun updateMaximum(target: AtomicLong, candidate: Long) {
            while (true) {
                val current = target.get()
                if (candidate <= current || target.compareAndSet(current, candidate)) return
            }
        }
    }
}
