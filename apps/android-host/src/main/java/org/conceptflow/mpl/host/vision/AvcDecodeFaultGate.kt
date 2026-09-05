// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import java.util.concurrent.atomic.AtomicBoolean

/** One-shot gate used only by the shell-only debug fault-injection surface. */
internal class AvcDecodeFaultGate {
    private val armed = AtomicBoolean(false)

    fun arm(): Boolean = armed.compareAndSet(false, true)
    fun consume(): Boolean = armed.compareAndSet(true, false)
    fun clear() = armed.set(false)
}

internal class InjectedAvcDecodeFailure : IllegalStateException("debug AVC decode failure")
