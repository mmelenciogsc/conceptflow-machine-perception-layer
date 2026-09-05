// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

/** Exact device-family guard for the private YodaOS-Sprite control broadcast. */
object YodaOsWifiControlCompatibility {
    fun isSupported(
        manufacturer: String,
        brand: String,
        device: String,
        product: String,
    ): Boolean {
        val rokidVendor = manufacturer.equals("Rokid", ignoreCase = true) &&
            brand.equals("Rokid", ignoreCase = true)
        return rokidVendor &&
            device.equals("glasses", ignoreCase = true) &&
            product.equals("glasses", ignoreCase = true)
    }
}

enum class YodaOsTemporaryP2pGroupDecision {
    ALREADY_ABSENT,
    REMOVE_EMPTY_OWNER,
    RETAIN_NOT_OWNER,
    RETAIN_ACTIVE_CLIENTS,
}

/** Never removes a peer-owned group or a locally owned group that has an active client. */
object YodaOsTemporaryP2pGroupPolicy {
    fun releaseDecision(
        groupPresent: Boolean,
        isGroupOwner: Boolean,
        clientCount: Int,
    ): YodaOsTemporaryP2pGroupDecision {
        require(clientCount >= 0)
        return when {
            !groupPresent -> YodaOsTemporaryP2pGroupDecision.ALREADY_ABSENT
            !isGroupOwner -> YodaOsTemporaryP2pGroupDecision.RETAIN_NOT_OWNER
            clientCount > 0 -> YodaOsTemporaryP2pGroupDecision.RETAIN_ACTIVE_CLIENTS
            else -> YodaOsTemporaryP2pGroupDecision.REMOVE_EMPTY_OWNER
        }
    }
}

/**
 * Finite retry schedule for one YodaOS Wi-Fi recovery episode.
 *
 * A later explicit rendezvous epoch may start another episode. This keeps a firmware failure from
 * becoming an unbounded hot loop while still allowing the already-bounded node retry lifecycle to
 * recover after a transient outage.
 */
class YodaOsWifiRecoverySchedule(
    private val delaysMillis: List<Long> = DEFAULT_DELAYS_MILLIS,
) {
    private var nextIndex = 0

    init {
        require(delaysMillis.isNotEmpty())
        require(delaysMillis.first() == 0L)
        require(delaysMillis.all { it >= 0L })
        require(delaysMillis.zipWithNext().all { (previous, next) -> next >= previous })
    }

    fun restart() {
        nextIndex = 0
    }

    fun nextDelayMillis(): Long? = delaysMillis.getOrNull(nextIndex++)

    val maximumAttempts: Int
        get() = delaysMillis.size

    companion object {
        val DEFAULT_DELAYS_MILLIS = listOf(0L, 1_000L, 3_000L, 8_000L)
        val DEFAULT_RELEASE_DELAYS_MILLIS = listOf(4_000L, 8_000L)
    }
}
