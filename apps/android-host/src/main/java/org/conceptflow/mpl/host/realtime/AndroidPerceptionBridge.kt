// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime

import android.os.SystemClock

/**
 * Thin Unity-facing JVM boundary. It exports compact state/events only; it never exposes camera or
 * microphone buffers and never blocks on transport or inference.
 */
object AndroidPerceptionBridge {
    internal val runtimeBus = PerceptionBus()

    @JvmStatic
    fun pollWorldState(lastRevision: Long): ByteArray? =
        runtimeBus.latestAfter(lastRevision.coerceAtLeast(0L), SystemClock.elapsedRealtimeNanos())
            ?.let(PerceptionBusBinaryCodec::encodeWorld)

    @JvmStatic
    fun drainTouchEvents(maximumEvents: Int): ByteArray = PerceptionBusBinaryCodec.encodeTouchBatch(
        runtimeBus.drainTouch(maximumEvents.coerceIn(1, 128)),
    )

    @JvmStatic
    fun pollFocusState(lastRevision: Long): ByteArray? =
        runtimeBus.latestFocusAfter(lastRevision.coerceAtLeast(0L), SystemClock.elapsedRealtimeNanos())
            ?.let(PerceptionBusBinaryCodec::encodeFocus)

    @JvmStatic
    fun pollHeadPose(lastRevision: Long): ByteArray? =
        runtimeBus.latestHeadAfter(lastRevision.coerceAtLeast(0L))
            ?.let(PerceptionBusBinaryCodec::encodeHead)

    @JvmStatic
    fun pollAmbientSoundProfile(lastRevision: Long): ByteArray? =
        runtimeBus.latestAmbientSoundProfileAfter(
            lastRevision.coerceAtLeast(0L),
            SystemClock.elapsedRealtimeNanos(),
        )?.let(PerceptionBusBinaryCodec::encodeAmbientSoundProfile)
}
