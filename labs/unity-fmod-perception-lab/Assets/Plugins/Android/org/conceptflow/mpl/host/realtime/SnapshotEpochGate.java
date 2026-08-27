// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Allows exactly one valid snapshot through after a successful Binder connection, even when the
 * producer process restarted its counter below the Unity caller's prior-process counter.
 */
final class SnapshotEpochGate {
    private final AtomicBoolean rebootstrapPending = new AtomicBoolean();

    void arm() {
        rebootstrapPending.set(true);
    }

    void clear() {
        rebootstrapPending.set(false);
    }

    boolean shouldDeliver(long snapshotCounter, long callerCounter) {
        if (snapshotCounter <= 0L || callerCounter < 0L) return false;
        if (snapshotCounter > callerCounter) {
            rebootstrapPending.compareAndSet(true, false);
            return true;
        }
        return rebootstrapPending.compareAndSet(true, false);
    }
}
