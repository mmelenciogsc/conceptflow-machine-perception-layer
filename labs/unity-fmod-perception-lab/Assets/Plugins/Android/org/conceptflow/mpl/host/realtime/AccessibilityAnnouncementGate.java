// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime;

import java.nio.charset.StandardCharsets;

/** Bounded newest-wins token gate kept Android-free for deterministic JVM tests. */
final class AccessibilityAnnouncementGate {
    static final int MAXIMUM_TOKEN_BYTES = 96;
    static final int MAXIMUM_TEXT_BYTES = 384;

    private String deliveredToken;
    private String pendingToken;
    private boolean suspended;

    synchronized boolean reserve(String token, String text) {
        if (suspended
                || !valid(token, text)
                || token.equals(deliveredToken)
                || token.equals(pendingToken)) return false;
        pendingToken = token;
        return true;
    }

    /**
     * Linearizes the final current-token check with platform dispatch. A newer reservation,
     * suspension, or cancellation cannot enter between that check and {@code delivery}.
     */
    synchronized boolean deliverIfCurrent(String token, Runnable delivery) {
        if (suspended || token == null || !token.equals(pendingToken)
                || token.equals(deliveredToken) || delivery == null) return false;
        try {
            delivery.run();
            deliveredToken = token;
            pendingToken = null;
            return true;
        } catch (RuntimeException error) {
            pendingToken = null;
            throw error;
        }
    }

    synchronized void cancel(String token) {
        if (token != null && token.equals(pendingToken)) pendingToken = null;
    }

    synchronized void reset() {
        deliveredToken = null;
        pendingToken = null;
        suspended = false;
    }

    synchronized void setSuspended(boolean value) {
        suspended = value;
        if (value) pendingToken = null;
    }

    private static boolean valid(String token, String text) {
        if (token == null || text == null || token.isEmpty() || text.trim().isEmpty()) return false;
        if (!token.equals(token.trim()) || !text.equals(text.trim())) return false;
        if (token.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_TOKEN_BYTES
                || text.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_TEXT_BYTES) return false;
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            boolean alphaNumeric = value >= 'a' && value <= 'z'
                    || value >= 'A' && value <= 'Z'
                    || value >= '0' && value <= '9';
            if (!alphaNumeric && value != ':' && value != '-' && value != '_' && value != '.') {
                return false;
            }
        }
        for (int index = 0; index < text.length(); index++) {
            if (Character.isISOControl(text.charAt(index))) return false;
        }
        return true;
    }
}
