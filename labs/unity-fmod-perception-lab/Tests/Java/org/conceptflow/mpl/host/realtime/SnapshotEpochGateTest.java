// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime;

/** Dependency-free executable regression tests for Unity-to-Android counter epoch recovery. */
public final class SnapshotEpochGateTest {
    private SnapshotEpochGateTest() {}

    public static void main(String[] args) {
        restartsAtOneAfterCallerObservedHigherRevision();
        successfulNormalDeliveryConsumesRebootstrapAllowance();
        disconnectClearDoesNotPermitDeliveryBeforeReconnect();
        invalidCountersNeverConsumeRebootstrapAllowance();
    }

    private static void restartsAtOneAfterCallerObservedHigherRevision() {
        SnapshotEpochGate gate = new SnapshotEpochGate();
        gate.arm();
        require(gate.shouldDeliver(1L, 97L), "new producer epoch must rebootstrap once");
        require(!gate.shouldDeliver(1L, 97L), "same snapshot must not rebootstrap twice");
    }

    private static void successfulNormalDeliveryConsumesRebootstrapAllowance() {
        SnapshotEpochGate gate = new SnapshotEpochGate();
        gate.arm();
        require(gate.shouldDeliver(101L, 100L), "normal newer snapshot must be delivered");
        require(!gate.shouldDeliver(101L, 101L), "normal delivery must consume epoch allowance");
    }

    private static void disconnectClearDoesNotPermitDeliveryBeforeReconnect() {
        SnapshotEpochGate gate = new SnapshotEpochGate();
        gate.arm();
        gate.clear();
        require(!gate.shouldDeliver(1L, 97L), "cleared connection must not bypass caller revision");
        gate.arm();
        require(gate.shouldDeliver(1L, 97L), "successful reconnect must re-arm delivery");
    }

    private static void invalidCountersNeverConsumeRebootstrapAllowance() {
        SnapshotEpochGate gate = new SnapshotEpochGate();
        gate.arm();
        require(!gate.shouldDeliver(0L, 8L), "invalid producer counter must fail closed");
        require(!gate.shouldDeliver(1L, -1L), "invalid caller counter must fail closed");
        require(gate.shouldDeliver(1L, 8L), "invalid attempts must not consume allowance");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
