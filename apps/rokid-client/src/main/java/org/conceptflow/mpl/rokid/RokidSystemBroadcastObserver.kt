// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.conceptflow.mpl.rokid.core.RokidBroadcastInterceptionDecision
import org.conceptflow.mpl.rokid.core.RokidBroadcastInterceptionPolicy
import org.conceptflow.mpl.rokid.core.RokidSystemBroadcastInput
import org.conceptflow.mpl.rokid.core.RokidSystemTouchEvent
import org.conceptflow.mpl.rokid.core.RokidSystemTouchEventHub
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Dynamically observes Rokid's documented YodaOS-Sprite touch broadcasts.
 *
 * Interception remains disabled until physical validation identifies an exact ordered action on
 * the target firmware. This class deliberately does not register any temple-button action.
 */
class RokidSystemBroadcastObserver(
    context: Context,
    private val interceptionEnabled: () -> Boolean = { false },
    private val validatedActions: () -> Set<RokidSystemBroadcastInput> = { emptySet() },
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val observations = AtomicLong(0L)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val input = RokidSystemBroadcastInput.fromAction(intent?.action) ?: return
            val ordered = isOrderedBroadcast
            val decision = RokidBroadcastInterceptionPolicy.decide(
                input = input,
                isOrderedBroadcast = ordered,
                validatedActions = validatedActions(),
                interceptionEnabled = interceptionEnabled(),
            )
            val aborted = if (
                decision == RokidBroadcastInterceptionDecision.ABORT_ORDERED_BROADCAST
            ) {
                runCatching {
                    abortBroadcast()
                    true
                }.getOrElse {
                    Log.w(TAG, "state=abort_failed action=${input.name}")
                    false
                }
            } else {
                false
            }
            val observedMonotonicNs = SystemClock.elapsedRealtimeNanos()
            val published = if (input == RokidSystemBroadcastInput.TWO_FINGER_LONG_PRESS) {
                RokidSystemTouchEventHub.publish(
                    RokidSystemTouchEvent(
                        input = input,
                        sourceUptimeMillis = SystemClock.uptimeMillis(),
                        observedMonotonicTimestampNs = observedMonotonicNs,
                    ),
                )
            } else {
                false
            }
            Log.i(
                TAG,
                "state=touch_broadcast sequence=${observations.incrementAndGet()} " +
                    "action=${input.name} ordered=$ordered sticky=$isInitialStickyBroadcast " +
                    "decision=${decision.name.lowercase()} aborted=$aborted published=$published " +
                    "observed_monotonic_ns=$observedMonotonicNs",
            )
        }
    }

    init {
        val filter = IntentFilter().apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            RokidSystemBroadcastInput.entries.forEach { addAction(it.action) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            applicationContext.registerReceiver(receiver, filter)
        }
        Log.i(
            TAG,
            "state=registered actions=${RokidSystemBroadcastInput.entries.size} " +
                "priority=${IntentFilter.SYSTEM_HIGH_PRIORITY} interception=disabled",
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { applicationContext.unregisterReceiver(receiver) }
            .onFailure { Log.w(TAG, "state=unregister_failed") }
    }

    companion object {
        const val TAG = "ConceptFlowRokidBroadcast"
    }
}
