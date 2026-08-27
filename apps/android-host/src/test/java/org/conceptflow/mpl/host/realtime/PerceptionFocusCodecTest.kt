// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.conceptflow.mpl.host.focus.BeaconQuality
import org.conceptflow.mpl.host.focus.BeaconQualityReason
import org.conceptflow.mpl.host.focus.SpatialFocusDwell
import org.conceptflow.mpl.host.focus.SpatialFocusItem
import org.conceptflow.mpl.host.focus.SpatialFocusMenuOption
import org.conceptflow.mpl.host.focus.SpatialFocusMode
import org.conceptflow.mpl.host.focus.SpatialFocusState
import org.conceptflow.mpl.host.vision.MetricVector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptionFocusCodecTest {
    @Test
    fun `new focus and head payloads do not alter legacy magics`() {
        val focus = PerceptionBusBinaryCodec.encodeFocus(focusState())
        val focusBuffer = ByteBuffer.wrap(focus).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x43464653, focusBuffer.int)
        assertEquals(1, focusBuffer.short.toInt())
        assertEquals(1, focusBuffer.short.toInt())
        assertEquals(1L, focusBuffer.long)
        assertEquals(1L, focusBuffer.long)
        assertEquals(2L, focusBuffer.long)
        assertEquals(20L, focusBuffer.long)
        assertEquals(2_000_000_000L, focusBuffer.long)
        assertEquals(5, focusBuffer.short.toInt())
        assertEquals("track", Charsets.UTF_8.decode(focusBuffer.slice()).toString())
        val head = PerceptionBusBinaryCodec.encodeHead(
            PerceptionHeadSnapshot(4L, 2L, PerceptionHeadState(10L, 3, 1f, 0f, 0f, 0f)),
        )
        val headBuffer = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x43464850, headBuffer.int)
        assertEquals(1, headBuffer.short.toInt())
        assertEquals(0, headBuffer.short.toInt())
        assertEquals(4L, headBuffer.long)
        assertEquals(2L, headBuffer.long)
        assertEquals(10L, headBuffer.long)
        assertEquals(3, headBuffer.int)
        assertEquals(1f, headBuffer.float)
        assertEquals(0f, headBuffer.float)
        assertEquals(0f, headBuffer.float)
        assertEquals(0f, headBuffer.float)
        assertEquals(0, headBuffer.remaining())
        val touch = PerceptionBusBinaryCodec.encodeTouchBatch(emptyList())
        assertEquals(0x43465442, ByteBuffer.wrap(touch).order(ByteOrder.BIG_ENDIAN).int)
    }

    @Test
    fun `focus and high rate head polling are independently revisioned and bounded`() {
        val bus = PerceptionBus()
        bus.beginSession(1L, 0L)
        bus.publishFocus(focusState())
        assertNotNull(bus.latestFocusAfter(0L, 1L))
        assertNull(bus.latestFocusAfter(1L, 1L))
        assertNull(bus.latestFocusAfter(0L, 2_000_000_001L))

        bus.publishHeadPose(
            org.conceptflow.mpl.host.vision.HeadPoseObservation(
                10L,
                org.conceptflow.mpl.host.vision.UnitQuaternion.IDENTITY,
                3,
            ),
        )
        val first = requireNotNull(bus.latestHeadAfter(0L))
        assertEquals(1L, first.revision)
        assertNull(bus.latestHeadAfter(first.revision))
        bus.invalidate(PerceptionValidityReason.DISCONNECTED, 20L)
        assertNull(bus.latestHeadAfter(0L))
        assertNull(bus.latestFocusAfter(0L, 20L))
    }

    @Test
    fun `focus publication stays monotonic across controller-local revision restart`() {
        val bus = PerceptionBus()
        bus.beginSession(1L, 0L)
        val beforeStop = bus.publishFocus(focusState().copy(revision = 47L))
        bus.invalidate(PerceptionValidityReason.STOPPED, 21L)
        assertNull(bus.latestFocusAfter(0L, 21L))

        bus.beginSession(2L, 30L)
        val afterRestart = bus.publishFocus(
            focusState().copy(revision = 1L, sessionGeneration = 2L),
        )

        assertTrue(afterRestart.revision > beforeStop.revision)
        assertEquals(afterRestart, bus.latestFocusAfter(beforeStop.revision, 31L))
    }

    @Test
    fun `focus codec rejects track identifiers beyond its bounded contract`() {
        val oversized = focusState().copy(target = focusState().target!!.copy(stableTrackId = "x".repeat(129)))
        var rejected = false
        try {
            PerceptionBusBinaryCodec.encodeFocus(oversized)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private fun focusState(): SpatialFocusState {
        val target = SpatialFocusItem(
            "track", "chair", 7L, 10L, 2_000_000_000L, 0.9,
            MetricVector3(-0.8, 0.0, 1.0), 1.0, 0.1,
            BeaconQuality(true, BeaconQualityReason.ELIGIBLE, MetricVector3(1.0, 2.0, 3.0)),
        )
        return SpatialFocusState(
            1L, 1L, 1L, 2L, 20L, 2_000_000_000L, 3L,
            SpatialFocusMode.ACTION_MENU, 0, 1, 0, SpatialFocusMenuOption.VQA,
            SpatialFocusDwell.NONE, 0L, 0L, target,
            "chair. 11:00. about 3 feet away.",
            "chair. 11 o'clock. about 3 feet away.",
            "activate", "",
        )
    }
}
