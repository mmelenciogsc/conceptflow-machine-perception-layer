// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime;

/** Dependency-free executable regression tests for stale Binder callback rejection. */
public final class AndroidPerceptionBridgeLifecycleGateTest {
    private AndroidPerceptionBridgeLifecycleGateTest() {}

    public static void main(String[] args) {
        invalidatedAttemptCannotReenter();
        stoppingRejectsQueuedAndNewCallbacks();
        restartUsesANewGeneration();
        focusSnapshotVersionsRemainCompatible();
        ambientProfileSnapshotUsesTheVersionedCacheHeader();
    }

    private static void ambientProfileSnapshotUsesTheVersionedCacheHeader() {
        byte[] ambient = snapshotHeader(0x43464150, 1, 14L);
        require(AndroidPerceptionBridge.readSnapshotCounter(ambient, 0x43464150) == 14L,
                "ambient profile ABI must reach Unity cache");
        byte[] future = snapshotHeader(0x43464150, 2, 15L);
        require(AndroidPerceptionBridge.readSnapshotCounter(future, 0x43464150) == -1L,
                "unknown ambient profile ABI must fail closed");
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

    private static void focusSnapshotVersionsRemainCompatible() {
        byte[] focusV1 = snapshotHeader(0x43464653, 1, 9L);
        byte[] focusV2 = snapshotHeader(0x43464653, 2, 10L);
        byte[] focusV3 = snapshotHeader(0x43464653, 3, 11L);
        byte[] focusV4 = snapshotHeader(0x43464653, 4, 12L);
        byte[] worldV2 = snapshotHeader(0x43465753, 2, 13L);
        require(AndroidPerceptionBridge.readSnapshotCounter(focusV1, 0x43464653) == 9L,
                "legacy focus ABI must remain readable");
        require(AndroidPerceptionBridge.readSnapshotCounter(focusV2, 0x43464653) == 10L,
                "beacon focus ABI must reach Unity cache");
        require(AndroidPerceptionBridge.readSnapshotCounter(focusV3, 0x43464653) == 11L,
                "accessibility focus ABI must reach Unity cache");
        require(AndroidPerceptionBridge.readSnapshotCounter(focusV4, 0x43464653) == -1L,
                "unknown future focus ABI must fail closed");
        require(AndroidPerceptionBridge.readSnapshotCounter(worldV2, 0x43465753) == -1L,
                "non-focus payload ABI must not be widened");
    }

    private static byte[] snapshotHeader(int magic, int version, long counter) {
        byte[] result = new byte[16];
        for (int index = 0; index < 4; index++) {
            result[index] = (byte) (magic >>> (24 - index * 8));
        }
        result[4] = (byte) (version >>> 8);
        result[5] = (byte) version;
        for (int index = 0; index < 8; index++) {
            result[8 + index] = (byte) (counter >>> (56 - index * 8));
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
