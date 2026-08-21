// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import android.os.SystemClock

fun interface MonotonicClock {
    fun nowNanos(): Long
}

object ElapsedRealtimeClock : MonotonicClock {
    override fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

fun interface WallClock {
    fun nowMillis(): Long
}

object SystemWallClock : WallClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

internal fun protobufTimestamp(epochMillis: Long): com.google.protobuf.Timestamp =
    com.google.protobuf.Timestamp.newBuilder()
        .setSeconds(Math.floorDiv(epochMillis, 1_000L))
        .setNanos((Math.floorMod(epochMillis, 1_000L) * 1_000_000L).toInt())
        .build()
