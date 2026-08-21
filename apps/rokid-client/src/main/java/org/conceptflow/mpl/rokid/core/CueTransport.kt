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

interface CxrSpriteBridge {
    fun connect(receiver: (CueEnvelope) -> CueRenderEvent)
    fun disconnect()
    val connected: Boolean
}

class CxrSpriteAdapter private constructor(private val bridge: CxrSpriteBridge) : CueTransport {
    override val isConnected: Boolean get() = bridge.connected
    override fun connect(receiver: (CueEnvelope) -> CueRenderEvent) = bridge.connect(receiver)
    override fun disconnect() = bridge.disconnect()

    companion object {
        fun attach(bridge: CxrSpriteBridge?): CxrSpriteAdapter = CxrSpriteAdapter(
            requireNotNull(bridge) {
                "CXR-S bridge is unavailable; add the licensed vendor integration in a private build"
            },
        )
    }
}

interface Glass3EnterpriseBridge {
    fun connect(receiver: (CueEnvelope) -> CueRenderEvent)
    fun disconnect()
    val connected: Boolean
}

class Glass3EnterpriseAdapter private constructor(
    private val bridge: Glass3EnterpriseBridge,
) : CueTransport {
    override val isConnected: Boolean get() = bridge.connected
    override fun connect(receiver: (CueEnvelope) -> CueRenderEvent) = bridge.connect(receiver)
    override fun disconnect() = bridge.disconnect()

    companion object {
        fun attach(bridge: Glass3EnterpriseBridge?): Glass3EnterpriseAdapter = Glass3EnterpriseAdapter(
            requireNotNull(bridge) {
                "Rokid Glass 3 enterprise bridge is unavailable; it is a separate SDK family from CXR-S"
            },
        )
    }
}
