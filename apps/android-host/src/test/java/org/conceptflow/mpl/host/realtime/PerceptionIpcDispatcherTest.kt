// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerceptionIpcDispatcherTest {
    @Test
    fun `poll lanes reject negative counters without consulting source`() {
        val source = FakeSource().apply { failIfCalled = true }
        val dispatcher = PerceptionIpcDispatcher(source)

        listOf(
            PerceptionIpcProtocol.TRANSACTION_POLL_WORLD_STATE,
            PerceptionIpcProtocol.TRANSACTION_POLL_FOCUS_STATE,
            PerceptionIpcProtocol.TRANSACTION_POLL_HEAD_POSE,
        ).forEach { code ->
            assertEquals(
                PerceptionIpcProtocol.STATUS_INVALID_ARGUMENT,
                dispatcher.dispatch(code, -1L).status,
            )
        }
    }

    @Test
    fun `touch drain accepts only one through one hundred twenty eight`() {
        val source = FakeSource()
        val dispatcher = PerceptionIpcDispatcher(source)

        assertEquals(
            PerceptionIpcProtocol.STATUS_INVALID_ARGUMENT,
            dispatcher.dispatch(PerceptionIpcProtocol.TRANSACTION_DRAIN_TOUCH_EVENTS, 0L).status,
        )
        assertEquals(
            PerceptionIpcProtocol.STATUS_INVALID_ARGUMENT,
            dispatcher.dispatch(PerceptionIpcProtocol.TRANSACTION_DRAIN_TOUCH_EVENTS, 129L).status,
        )
        assertEquals(
            PerceptionIpcProtocol.STATUS_OK,
            dispatcher.dispatch(PerceptionIpcProtocol.TRANSACTION_DRAIN_TOUCH_EVENTS, 128L).status,
        )
        assertEquals(128, source.lastTouchMaximum)
    }

    @Test
    fun `null poll is a deterministic no update response`() {
        val response = PerceptionIpcDispatcher(FakeSource()).dispatch(
            PerceptionIpcProtocol.TRANSACTION_POLL_WORLD_STATE,
            9L,
        )

        assertEquals(PerceptionIpcProtocol.STATUS_NO_UPDATE, response.status)
        assertNull(response.payload)
    }

    @Test
    fun `reply payload is copied and limited per lane`() {
        val original = ByteArray(PerceptionIpcProtocol.MAXIMUM_FOCUS_BYTES) { 7 }
        val source = FakeSource().apply { focus = original }
        val dispatcher = PerceptionIpcDispatcher(source)

        val accepted = dispatcher.dispatch(
            PerceptionIpcProtocol.TRANSACTION_POLL_FOCUS_STATE,
            0L,
        )
        original[0] = 3
        assertEquals(PerceptionIpcProtocol.STATUS_OK, accepted.status)
        assertArrayEquals(ByteArray(PerceptionIpcProtocol.MAXIMUM_FOCUS_BYTES) { 7 }, accepted.payload)

        source.focus = ByteArray(PerceptionIpcProtocol.MAXIMUM_FOCUS_BYTES + 1)
        val rejected = dispatcher.dispatch(
            PerceptionIpcProtocol.TRANSACTION_POLL_FOCUS_STATE,
            0L,
        )
        assertEquals(PerceptionIpcProtocol.STATUS_OVERSIZE, rejected.status)
        assertNull(rejected.payload)
    }

    @Test
    fun `source runtime failures do not cross Binder boundary`() {
        val source = FakeSource().apply { throwOnCall = true }
        val response = PerceptionIpcDispatcher(source).dispatch(
            PerceptionIpcProtocol.TRANSACTION_POLL_HEAD_POSE,
            0L,
        )

        assertEquals(PerceptionIpcProtocol.STATUS_INTERNAL_ERROR, response.status)
        assertNull(response.payload)
    }

    @Test
    fun `unknown transaction is rejected without consulting source`() {
        val source = FakeSource().apply { failIfCalled = true }
        val response = PerceptionIpcDispatcher(source).dispatch(99, 0L)

        assertEquals(PerceptionIpcProtocol.STATUS_INVALID_ARGUMENT, response.status)
        assertNull(response.payload)
    }

    private class FakeSource : PerceptionIpcSource {
        var focus: ByteArray? = null
        var lastTouchMaximum = 0
        var throwOnCall = false
        var failIfCalled = false

        override fun pollWorldState(lastRevision: Long): ByteArray? = result(null)

        override fun pollFocusState(lastRevision: Long): ByteArray? = result(focus)

        override fun pollHeadPose(lastSequence: Long): ByteArray? = result(null)

        override fun drainTouchEvents(maximumEvents: Int): ByteArray {
            checkCall()
            lastTouchMaximum = maximumEvents
            return byteArrayOf(1)
        }

        private fun result(value: ByteArray?): ByteArray? {
            checkCall()
            return value
        }

        private fun checkCall() {
            check(!failIfCalled)
            if (throwOnCall) error("synthetic source failure")
        }
    }
}
