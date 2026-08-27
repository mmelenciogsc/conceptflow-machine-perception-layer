// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HtpExecutionLeaseTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun qnnDemandRefusesNewVlmAdmission() {
        val lease = lease()
        val qnn = acquired(lease.tryAcquire(HtpLeaseWorkload.QNN, 0L))
        try {
            val vlm = lease.tryAcquire(HtpLeaseWorkload.VLM, 0L)

            assertTrue(vlm is HtpLeaseAcquisition.Refused)
            assertEquals(HtpLeaseRefusalReason.QNN_PRIORITY, (vlm as HtpLeaseAcquisition.Refused).reason)
            assertTrue(lease.qnnPriorityRequested())
        } finally {
            qnn.close()
        }
        assertFalse(lease.qnnPriorityRequested())
    }

    @Test
    fun qnnSignalsPriorityWhileWaitingAndAcquiresAfterVlmCooperativelyReleases() {
        val lease = lease()
        val vlm = acquired(lease.tryAcquire(HtpLeaseWorkload.VLM, 0L))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit<HtpLeaseAcquisition> {
                lease.tryAcquire(HtpLeaseWorkload.QNN, 1_000L)
            }
            awaitQnnDemand(lease)
            val competingVlm = lease.tryAcquire(HtpLeaseWorkload.VLM, 0L)
            assertEquals(
                HtpLeaseRefusalReason.QNN_PRIORITY,
                (competingVlm as HtpLeaseAcquisition.Refused).reason,
            )

            vlm.close()
            acquired(waiting.get(1L, TimeUnit.SECONDS)).close()
        } finally {
            vlm.close()
            executor.shutdownNow()
        }
        assertEquals(0L, lease.telemetrySnapshot().activeHolders)
    }

    @Test
    fun qnnWaitHonorsCancellationBeforeItsDeadline() {
        val lease = lease()
        val vlm = acquired(lease.tryAcquire(HtpLeaseWorkload.VLM, 0L))
        val cancelled = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit<HtpLeaseAcquisition> {
                lease.tryAcquire(HtpLeaseWorkload.QNN, 2_000L, cancelled::get)
            }
            awaitQnnDemand(lease)
            cancelled.set(true)
            val result = waiting.get(500L, TimeUnit.MILLISECONDS) as HtpLeaseAcquisition.Refused

            assertEquals(HtpLeaseRefusalReason.CANCELLED, result.reason)
            assertTrue(result.waitNanos < TimeUnit.SECONDS.toNanos(2L))
        } finally {
            vlm.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun qnnWaitTimesOutWithoutAnUnboundedFileLockCall() {
        val lease = lease()
        val vlm = acquired(lease.tryAcquire(HtpLeaseWorkload.VLM, 0L))
        try {
            val result = lease.tryAcquire(HtpLeaseWorkload.QNN, 25L) as HtpLeaseAcquisition.Refused

            assertEquals(HtpLeaseRefusalReason.TIMEOUT, result.reason)
            assertTrue(result.waitNanos >= 0L)
        } finally {
            vlm.close()
        }
    }

    @Test
    fun kernelLockIsReleasedAfterAbruptOwnerChannelLoss() {
        val lockFile = temporaryFolder.newFile("htp-crash.lock")
        val owner = RandomAccessFile(lockFile, "rw")
        owner.channel.lock()
        // Closing the process-owned descriptor models the kernel cleanup relied on after death.
        owner.channel.close()
        owner.close()

        acquired(HtpExecutionLease(lockFile).tryAcquire(HtpLeaseWorkload.VLM, 0L)).close()
    }

    @Test
    fun waitAndHoldTelemetryIsBoundedAndExactlyOnce() {
        val events = mutableListOf<HtpLeaseTelemetry>()
        val lease = HtpExecutionLease(temporaryFolder.newFile("htp-telemetry.lock")) { events += it }
        val handle = acquired(lease.tryAcquire(HtpLeaseWorkload.VLM, 0L))
        handle.close()
        handle.close()

        val snapshot = lease.telemetrySnapshot()
        assertEquals(1L, snapshot.acquisitions)
        assertEquals(0L, snapshot.refusals)
        assertEquals(0L, snapshot.activeHolders)
        assertEquals(1, events.size)
        assertTrue(events.single().holdNanos!! >= 0L)
    }

    @Test
    fun telemetryFailureCannotLeakLeaseOrChangeRefusalResult() {
        val lease = HtpExecutionLease(temporaryFolder.newFile("htp-telemetry-failure.lock")) {
            error("diagnostic sink failed")
        }
        val qnn = acquired(lease.tryAcquire(HtpLeaseWorkload.QNN, 0L))
        val refusal = lease.tryAcquire(HtpLeaseWorkload.VLM, 0L)

        assertEquals(
            HtpLeaseRefusalReason.QNN_PRIORITY,
            (refusal as HtpLeaseAcquisition.Refused).reason,
        )
        qnn.close()
        acquired(lease.tryAcquire(HtpLeaseWorkload.VLM, 0L)).close()
        assertEquals(0L, lease.telemetrySnapshot().activeHolders)
    }

    private fun lease() = HtpExecutionLease(temporaryFolder.newFile("htp-${System.nanoTime()}.lock"))

    private fun acquired(result: HtpLeaseAcquisition): HtpExecutionLease.Handle {
        assertTrue("expected acquired lease but got $result", result is HtpLeaseAcquisition.Acquired)
        return (result as HtpLeaseAcquisition.Acquired).handle
    }

    private fun awaitQnnDemand(lease: HtpExecutionLease) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L)
        while (!lease.qnnPriorityRequested() && System.nanoTime() < deadline) {
            Thread.sleep(2L)
        }
        assertTrue("QNN priority was not externally visible", lease.qnnPriorityRequested())
    }
}
