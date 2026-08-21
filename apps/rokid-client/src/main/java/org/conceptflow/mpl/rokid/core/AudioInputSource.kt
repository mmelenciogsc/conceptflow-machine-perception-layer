// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

data class PcmAudioChunk(
    val chunkId: Long,
    val captureMonotonicTimestampNs: Long,
    val sampleRateHz: Int,
    val channelCount: Int,
    val pcm16LittleEndian: ByteArray,
)

interface AudioInputSource : AutoCloseable {
    interface Listener {
        fun onAudioChunk(chunk: PcmAudioChunk)
        fun onError(message: String)
    }

    val isRunning: Boolean
    fun start(listener: Listener)
    fun stop()
    override fun close() = stop()
}
