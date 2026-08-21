// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.v1.PerceptionCue
import org.conceptflow.mpl.v1.PerceptionResult
import java.util.concurrent.atomic.AtomicBoolean

data class CueEnvelope(
    val sessionId: String,
    val streamId: String,
    val cue: PerceptionCue,
)

interface CueTransport : AutoCloseable {
    val isConnected: Boolean
    fun connect(receiver: (CueEnvelope) -> CueRenderEvent)
    fun disconnect()
    override fun close() = disconnect()
}

class InProcessCueTransport : CueTransport {
    private val connected = AtomicBoolean(false)
    @Volatile
    private var receiver: ((CueEnvelope) -> CueRenderEvent)? = null
    override val isConnected: Boolean get() = connected.get()

    @Synchronized
    override fun connect(receiver: (CueEnvelope) -> CueRenderEvent) {
        check(!connected.get()) { "Cue transport is already connected" }
        this.receiver = receiver
        connected.set(true)
    }

    @Synchronized
    fun deliver(envelope: CueEnvelope): CueRenderEvent? {
        if (!connected.get()) return null
        val target = receiver ?: return null
        return target(envelope)
    }

    @Synchronized
    fun deliver(result: PerceptionResult): List<CueRenderEvent> {
        if (!connected.get()) return emptyList()
        val target = receiver ?: return emptyList()
        return result.cuesList.map { cue -> target(CueEnvelope(result.sessionId, result.streamId, cue)) }
    }

    @Synchronized
    override fun disconnect() {
        connected.set(false)
        receiver = null
    }
}
