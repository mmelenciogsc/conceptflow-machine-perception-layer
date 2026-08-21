// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.core

import com.google.protobuf.ByteString
import org.conceptflow.mpl.v1.CueCategory
import org.conceptflow.mpl.v1.Direction
import org.conceptflow.mpl.v1.Earcon
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImageDescriptor
import org.conceptflow.mpl.v1.ImageEncoding
import org.conceptflow.mpl.v1.PerceptionCue
import org.conceptflow.mpl.v1.PerceptionResult
import org.conceptflow.mpl.v1.Urgency
import org.conceptflow.mpl.protocol.SyntheticImageFixtures
import java.security.MessageDigest
import java.util.Base64

internal class MutableHostClock(var now: Long = 0L) : HostClock {
    override fun nowNanos(): Long = now
}

internal fun testFrame(
    frameId: Long = 1L,
    requestId: String = "request-$frameId",
    sessionId: String = "session",
    streamId: String = "camera",
    captureMonotonicTimestampNs: Long = frameId * 10L,
    bytes: ByteArray = ONE_PIXEL_JPEG,
    width: Int = 1,
    height: Int = 1,
    rowStrideBytes: Int = 0,
    encoding: ImageEncoding = ImageEncoding.IMAGE_ENCODING_JPEG,
    mediaType: String = "image/jpeg",
): FramePayload {
    val descriptor = ImageDescriptor.newBuilder()
        .setWidth(width)
        .setHeight(height)
        .setRowStrideBytes(rowStrideBytes)
        .setEncoding(encoding)
        .setMediaType(mediaType)
        .setPayloadBytes(bytes.size.toLong())
        .setSha256(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(bytes)))
        .build()
    return FramePayload.newBuilder()
        .setRequestId(requestId)
        .setSessionId(sessionId)
        .setStreamId(streamId)
        .setFrameId(frameId)
        .setCaptureMonotonicTimestampNs(captureMonotonicTimestampNs)
        .setImage(descriptor)
        .setFrameData(ByteString.copyFrom(bytes))
        .setSynthetic(true)
        .build()
}

internal val ONE_PIXEL_PNG: ByteArray = Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
)

internal val ONE_PIXEL_JPEG: ByteArray = SyntheticImageFixtures.onePixelJpeg()

internal fun testCue(
    id: String,
    priority: Int = 50,
    created: Long = 1_000L,
    ttlMs: Int = 1_000,
    confidence: Double = 0.9,
    description: String = id,
    urgency: Urgency = Urgency.URGENCY_NORMAL,
): PerceptionCue = PerceptionCue.newBuilder()
    .setCueId(id)
    .setFrameId(priority.toLong())
    .setCreatedMonotonicTimestampNs(created)
    .setTtlMs(ttlMs)
    .setCategory(CueCategory.CUE_CATEGORY_OBSTACLE)
    .setDescription(description)
    .setConfidence(confidence)
    .setPriority(priority)
    .setDirection(Direction.DIRECTION_LEFT)
    .setUrgency(urgency)
    .setEarcon(Earcon.newBuilder().setEarconId("tone").setGain(0.4f).setPitch(1f))
    .build()

internal fun testResult(frame: FramePayload, cue: PerceptionCue? = null): PerceptionResult =
    PerceptionResult.newBuilder()
        .setResultId("result-${frame.frameId}")
        .setRequestId(frame.requestId)
        .setSessionId(frame.sessionId)
        .setStreamId(frame.streamId)
        .setFrameId(frame.frameId)
        .setCaptureMonotonicTimestampNs(frame.captureMonotonicTimestampNs)
        .setCompletedMonotonicTimestampNs(frame.captureMonotonicTimestampNs + 1L)
        .apply { if (cue != null) addCues(cue) }
        .build()
