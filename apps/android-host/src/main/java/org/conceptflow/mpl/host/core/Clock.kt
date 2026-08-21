// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.core

import android.os.SystemClock

fun interface HostClock {
    fun nowNanos(): Long
}

object ElapsedHostClock : HostClock {
    override fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
