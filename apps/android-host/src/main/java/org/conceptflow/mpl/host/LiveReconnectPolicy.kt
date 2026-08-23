// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host

import java.util.concurrent.atomic.AtomicLong
import org.conceptflow.mpl.transport.LiveLinkDisconnectReason

enum class LiveReconnectDecision { RETRY, FAIL_CLOSED, COMPLETE, IGNORE }

class LiveReconnectPolicy(private val maximumInterruptions: Int = 5) {
    private var interruptions = 0

    init { require(maximumInterruptions in 1..20) }

    @Synchronized
    fun onDisconnect(reason: LiveLinkDisconnectReason): LiveReconnectDecision = when (reason) {
        LiveLinkDisconnectReason.STOPPED -> LiveReconnectDecision.IGNORE
        LiveLinkDisconnectReason.REMOTE_COMPLETED -> LiveReconnectDecision.COMPLETE
        LiveLinkDisconnectReason.LEASE_EXPIRED -> LiveReconnectDecision.COMPLETE
        LiveLinkDisconnectReason.NETWORK, LiveLinkDisconnectReason.TIMEOUT -> {
            interruptions += 1
            if (interruptions <= maximumInterruptions) LiveReconnectDecision.RETRY else LiveReconnectDecision.FAIL_CLOSED
        }
        else -> LiveReconnectDecision.FAIL_CLOSED
    }
}

class LiveSessionGeneration {
    private val value = AtomicLong(0)
    fun advance(): Long = value.incrementAndGet()
    fun current(): Long = value.get()
    fun isCurrent(candidate: Long): Boolean = value.get() == candidate
}

/** Invalidates a slow native startup before it can publish resources after stop/destruction. */
class LiveStartupGate {
    private val generation = AtomicLong(0)
    @Volatile private var activeGeneration = 0L

    @Synchronized
    fun begin(): Long {
        check(activeGeneration == 0L) { "live startup is already active" }
        return generation.incrementAndGet().also { activeGeneration = it }
    }

    @Synchronized
    fun cancel() {
        activeGeneration = 0L
        generation.incrementAndGet()
    }

    @Synchronized
    fun mayPublish(token: Long): Boolean = token != 0L && token == activeGeneration
}
