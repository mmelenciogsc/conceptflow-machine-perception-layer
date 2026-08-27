// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime;

/** Dependency-free executable regression tests for stale Binder callback rejection. */
public final class AndroidPerceptionBridgeLifecycleGateTest {
    private AndroidPerceptionBridgeLifecycleGateTest() {}

    public static void main(String[] args) {
        invalidatedAttemptCannotReenter();
        stoppingRejectsQueuedAndNewCallbacks();
        restartUsesANewGeneration();
    }

    private static void invalidatedAttemptCannotReenter() {
        AndroidPerceptionBridge.CallbackGenerationGate gate =
                new AndroidPerceptionBridge.CallbackGenerationGate();
        long first = gate.beginAttempt();
        require(gate.accepts(first), "active binding callback must be accepted");
        gate.invalidate();
        require(!gate.accepts(first), "invalidated binding callback must be rejected");
    }

    private static void stoppingRejectsQueuedAndNewCallbacks() {
        AndroidPerceptionBridge.CallbackGenerationGate gate =
                new AndroidPerceptionBridge.CallbackGenerationGate();
        long queued = gate.beginAttempt();
        gate.beginStopping();
        require(gate.isStopping(), "stop must be visible to callback threads");
        require(!gate.accepts(queued), "queued callback must not run during stop");
        long attemptedDuringStop = gate.beginAttempt();
        require(!gate.accepts(attemptedDuringStop), "new callback must not run during stop");
    }

    private static void restartUsesANewGeneration() {
        AndroidPerceptionBridge.CallbackGenerationGate gate =
                new AndroidPerceptionBridge.CallbackGenerationGate();
        long old = gate.beginAttempt();
        gate.beginStopping();
        gate.finishStopping();
        require(!gate.isStopping(), "completed stop must allow a later restart");
        require(!gate.accepts(old), "pre-stop callback must remain rejected after restart");
        long restarted = gate.beginAttempt();
        require(gate.accepts(restarted), "new lifecycle callback must be accepted");
        require(!gate.accepts(old), "new lifecycle must not revive a stale callback");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
