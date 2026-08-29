// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dependency-free executable regression tests for accessibility token bounds and deduplication. */
public final class AccessibilityAnnouncementGateTest {
    private AccessibilityAnnouncementGateTest() {}

    public static void main(String[] args) {
        duplicateAndSupersededTokensAreRejected();
        deliveryClaimAndDispatchAreAtomic();
        suspensionCancelsQueuedCallbacks();
        boundsAndControlsFailClosed();
        resetStartsANewLifecycle();
    }

    private static void duplicateAndSupersededTokensAreRejected() {
        AccessibilityAnnouncementGate gate = new AccessibilityAnnouncementGate();
        require(gate.reserve("ready:1:2", "Chair at 11 o'clock."), "first token must reserve");
        require(!gate.reserve("ready:1:2", "Chair at 11 o'clock."), "pending duplicate must reject");
        require(gate.reserve("menu:1:2:0", "Ask about this object. Option 1 of 3."),
                "newest semantic transition must replace pending work");
        require(!gate.deliverIfCurrent("ready:1:2", () -> {
            throw new AssertionError("superseded callback ran");
        }), "superseded UI callback must be silent");
        AtomicBoolean delivered = new AtomicBoolean();
        require(gate.deliverIfCurrent("menu:1:2:0", () -> delivered.set(true)),
                "newest token must remain deliverable");
        require(delivered.get(), "delivery must run exactly for the current token");
        require(!gate.reserve("menu:1:2:0", "Ask about this object. Option 1 of 3."),
                "delivered duplicate must reject");
    }

    private static void deliveryClaimAndDispatchAreAtomic() {
        AccessibilityAnnouncementGate gate = new AccessibilityAnnouncementGate();
        require(gate.reserve("ready:1:2", "Chair at 11 o'clock."), "first token must reserve");
        CountDownLatch deliveryEntered = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        CountDownLatch replacementEntered = new CountDownLatch(1);
        CountDownLatch replacementFinished = new CountDownLatch(1);
        AtomicBoolean oldDelivered = new AtomicBoolean();
        AtomicBoolean replacementReserved = new AtomicBoolean();
        Thread delivery = new Thread(() -> gate.deliverIfCurrent("ready:1:2", () -> {
            deliveryEntered.countDown();
            await(releaseDelivery, "delivery release");
            oldDelivered.set(true);
        }), "accessibility-delivery");
        delivery.start();
        await(deliveryEntered, "delivery entry");
        Thread replacement = new Thread(() -> {
            replacementEntered.countDown();
            replacementReserved.set(gate.reserve("menu:1:2:0", "Ask about this object."));
            replacementFinished.countDown();
        }, "accessibility-replacement");
        replacement.start();
        await(replacementEntered, "replacement entry");
        awaitBlocked(replacement, replacementFinished,
                "replacement must block on the atomic current-check and dispatch monitor");
        releaseDelivery.countDown();
        join(delivery);
        await(replacementFinished, "replacement completion");
        join(replacement);
        require(oldDelivered.get(), "claimed current delivery must complete");
        require(replacementReserved.get(), "new transition must reserve after committed delivery");
    }

    private static void suspensionCancelsQueuedCallbacks() {
        AccessibilityAnnouncementGate gate = new AccessibilityAnnouncementGate();
        require(gate.reserve("ready:1:2", "Chair at 11 o'clock."), "announcement must queue");
        gate.setSuspended(true);
        require(!gate.deliverIfCurrent("ready:1:2", () -> {
            throw new AssertionError("suspended callback ran");
        }), "suspension must invalidate queued delivery");
        require(!gate.reserve("menu:1:2:0", "Ask about this object."),
                "suspension must reject new delivery");
        gate.setSuspended(false);
        require(gate.reserve("menu:1:2:0", "Ask about this object."),
                "resume must admit a fresh semantic transition");
    }

    private static void boundsAndControlsFailClosed() {
        AccessibilityAnnouncementGate gate = new AccessibilityAnnouncementGate();
        require(!gate.reserve("", "Text"), "empty token must reject");
        require(!gate.reserve("bad token", "Text"), "token whitespace must reject");
        require(!gate.reserve("ready:1:2", "Line one\nline two"), "control text must reject");
        require(!gate.reserve("x".repeat(97), "Text"), "oversized token must reject");
        require(!gate.reserve("ready:1:2", "x".repeat(385)), "oversized text must reject");
    }

    private static void resetStartsANewLifecycle() {
        AccessibilityAnnouncementGate gate = new AccessibilityAnnouncementGate();
        require(gate.reserve("inactive", "Spatial focus inactive."), "token must reserve");
        require(gate.deliverIfCurrent("inactive", () -> {}), "token must commit");
        gate.reset();
        require(gate.reserve("inactive", "Spatial focus inactive."),
                "new Unity lifecycle may announce current state again");
    }

    private static void await(CountDownLatch latch, String operation) {
        try {
            require(latch.await(2L, TimeUnit.SECONDS), operation + " timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(operation + " interrupted", error);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(2_000L);
            require(!thread.isAlive(), "test thread did not finish");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("thread join interrupted", error);
        }
    }

    private static void awaitBlocked(Thread thread, CountDownLatch completion, String message) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (thread.getState() != Thread.State.BLOCKED
                && completion.getCount() != 0L
                && System.nanoTime() < deadline) Thread.yield();
        require(thread.getState() == Thread.State.BLOCKED && completion.getCount() != 0L, message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
