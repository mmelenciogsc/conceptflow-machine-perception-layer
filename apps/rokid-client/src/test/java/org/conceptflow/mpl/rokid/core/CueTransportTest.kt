// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.v1.Direction
import org.conceptflow.mpl.v1.Earcon
import org.conceptflow.mpl.v1.PerceptionCue
import org.conceptflow.mpl.v1.PerceptionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CueTransportTest {
    @Test
    fun syntheticHostToGlassesFlowReachesRenderer() {
        val renderer = InspectableCueRenderer(
            MonotonicClock { 100L },
            audio = AudioCueOutput { _, _, _, _ -> true },
        )
        val transport = InProcessCueTransport()
        transport.connect(renderer::render)
        val cue = PerceptionCue.newBuilder()
            .setCueId("end-to-end")
            .setFrameId(1)
            .setCreatedMonotonicTimestampNs(100L)
            .setTtlMs(500)
            .setDescription("Synthetic left obstacle")
            .setConfidence(1.0)
            .setDirection(Direction.DIRECTION_LEFT)
            .setEarcon(Earcon.newBuilder().setEarconId("obstacle").setGain(0.4f).setPitch(1f))
            .build()

        val result = PerceptionResult.newBuilder()
            .setSessionId("result-session")
            .setStreamId("result-stream")
            .addCues(cue)
            .build()
        assertEquals(1, transport.deliver(result).size)
        assertEquals(RenderDisposition.RENDERED, renderer.snapshot().single().disposition)
        assertEquals("result-session", renderer.snapshot().single().sessionId)
        assertEquals("result-stream", renderer.snapshot().single().streamId)
        transport.disconnect()
        assertTrue(transport.deliver(result).isEmpty())
        assertNull(transport.deliver(CueEnvelope("result-session", "result-stream", cue)))
    }

    @Test
    fun proprietaryBoundariesFailClearlyAndRemainDistinct() {
        val cxr = assertThrows(IllegalArgumentException::class.java) { CxrSpriteAdapter.attach(null) }
        val glass3 = assertThrows(IllegalArgumentException::class.java) { Glass3EnterpriseAdapter.attach(null) }
        assertTrue(cxr.message.orEmpty().contains("CXR-S"))
        assertTrue(glass3.message.orEmpty().contains("separate SDK family"))
    }

    @Test
    fun reconnectWithNewSessionAcceptsLowerFrameAndRepeatedCueId() {
        val renderer = InspectableCueRenderer(
            MonotonicClock { 100L },
            audio = AudioCueOutput { _, _, _, _ -> true },
        )
        val transport = InProcessCueTransport()
        transport.connect(renderer::render)
        assertEquals(1, transport.deliver(result("session-one", "camera", cue("same", 20L))).size)
        transport.disconnect()

        transport.connect(renderer::render)
        assertEquals(1, transport.deliver(result("session-two", "camera", cue("same", 1L))).size)

        assertEquals(
            listOf(RenderDisposition.RENDERED, RenderDisposition.RENDERED),
            renderer.snapshot().map { it.disposition },
        )
    }

    private fun cue(id: String, frameId: Long): PerceptionCue = PerceptionCue.newBuilder()
        .setCueId(id)
        .setFrameId(frameId)
        .setCreatedMonotonicTimestampNs(100L)
        .setTtlMs(500)
        .setDescription(id)
        .setConfidence(1.0)
        .setDirection(Direction.DIRECTION_LEFT)
        .setEarcon(Earcon.newBuilder().setEarconId("obstacle").setGain(0.4f).setPitch(1f))
        .build()

    private fun result(sessionId: String, streamId: String, cue: PerceptionCue): PerceptionResult =
        PerceptionResult.newBuilder()
            .setSessionId(sessionId)
            .setStreamId(streamId)
            .addCues(cue)
            .build()
}
