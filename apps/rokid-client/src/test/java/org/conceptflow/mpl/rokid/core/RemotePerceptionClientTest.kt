// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import com.google.protobuf.ByteString
import org.conceptflow.mpl.v1.CoordinateFrame
import org.conceptflow.mpl.v1.ErrorCode
import org.conceptflow.mpl.v1.EphemeralIdentity
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImageDescriptor
import org.conceptflow.mpl.v1.ImageEncoding
import org.conceptflow.mpl.v1.PerceptionResult
import org.conceptflow.mpl.v1.Pose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePerceptionClientTest {
    @Test
    fun loopbackPredicateAcceptsOnlyIntentionalIpv4Names() {
        assertTrue(isLiteralLoopbackHost("127.0.0.1"))
        assertTrue(isLiteralLoopbackHost("localhost"))
        assertTrue(isLiteralLoopbackHost("LOCALHOST"))
        assertFalse(isLiteralLoopbackHost("::1"))
        assertFalse(isLiteralLoopbackHost("127.0.0.2"))
        assertFalse(isLiteralLoopbackHost("localhost.example"))
        assertFalse(isLiteralLoopbackHost("127.0.0.1.example"))
    }

    @Test
    fun endpointValidationRejectsMalformedTargets() {
        assertThrows(IllegalArgumentException::class.java) { GrpcEndpoint("", 50_051) }
        assertThrows(IllegalArgumentException::class.java) { GrpcEndpoint("host/name", 50_051) }
        assertThrows(IllegalArgumentException::class.java) { GrpcEndpoint("host name", 50_051) }
        assertThrows(IllegalArgumentException::class.java) { GrpcEndpoint("localhost", 0) }
        assertThrows(IllegalArgumentException::class.java) { GrpcEndpoint("localhost", 65_536) }
    }

    @Test
    fun negotiationIsBoundedAndDeclaresOnlySupportedCapabilities() {
        val request = buildNegotiationRequest("rokid-direct-test", 2_500L)

        assertEquals(1, request.supportedVersionsCount)
        assertEquals(1, request.supportedVersionsList.single().major)
        assertEquals(listOf(ImageEncoding.IMAGE_ENCODING_JPEG), request.capabilities.imageEncodingsList)
        assertTrue(request.capabilities.supportsPose)
        assertTrue(request.capabilities.supportsCancellation)
        assertEquals(1, request.requestedQos.maxInFlight)
        assertEquals(1, request.requestedQos.targetFramesPerSecond)
        assertTrue(request.requestedQos.allowFrameDrop)
        assertEquals(2L, request.requestedQos.resultDeadline.seconds)
        assertEquals(500_000_000, request.requestedQos.resultDeadline.nanos)
    }

    @Test
    fun negotiationAcceptanceRequiresSupportedMajorAndBoundedSessionIdentity() {
        val valid = org.conceptflow.mpl.v1.NegotiateResponse.newBuilder()
            .setSelectedVersion(org.conceptflow.mpl.v1.ProtocolVersion.newBuilder().setMajor(1))
            .setIdentity(EphemeralIdentity.newBuilder().setSessionId("session-1"))
            .build()

        assertTrue(isSupportedNegotiation(valid))
        assertFalse(
            isSupportedNegotiation(
                valid.toBuilder().setSelectedVersion(
                    org.conceptflow.mpl.v1.ProtocolVersion.newBuilder().setMajor(2),
                ).build(),
            ),
        )
        assertFalse(
            isSupportedNegotiation(
                valid.toBuilder().setIdentity(
                    EphemeralIdentity.newBuilder().setSessionId("bad/session"),
                ).build(),
            ),
        )
    }

    @Test
    fun remapPreservesCapturedPayloadAndPoseButReplacesRoutingIdentity() {
        val original = frame()
        val remapped = remapFrameForSession(original, "session-new", "request-new", 1_250L)

        assertEquals("session-new", remapped.sessionId)
        assertEquals("request-new", remapped.requestId)
        assertEquals(original.streamId, remapped.streamId)
        assertEquals(original.frameId, remapped.frameId)
        assertEquals(original.captureMonotonicTimestampNs, remapped.captureMonotonicTimestampNs)
        assertEquals(original.frameData, remapped.frameData)
        assertEquals(original.image, remapped.image)
        assertEquals(original.pose, remapped.pose)
        assertEquals(1L, remapped.processingDeadline.seconds)
        assertEquals(250_000_000, remapped.processingDeadline.nanos)
    }

    @Test
    fun resultCorrelationRequiresEveryRoutingField() {
        val frame = remapFrameForSession(frame(), "session-new", "request-new", 2_000L)
        val exact = PerceptionResult.newBuilder()
            .setRequestId(frame.requestId)
            .setSessionId(frame.sessionId)
            .setStreamId(frame.streamId)
            .setFrameId(frame.frameId)
            .build()
        assertTrue(validateBoundedCorrelatedResult(frame, exact))
        assertFalse(validateBoundedCorrelatedResult(frame, exact.toBuilder().setRequestId("other").build()))
        assertFalse(validateBoundedCorrelatedResult(frame, exact.toBuilder().setSessionId("other").build()))
        assertFalse(validateBoundedCorrelatedResult(frame, exact.toBuilder().setStreamId("other").build()))
        assertFalse(validateBoundedCorrelatedResult(frame, exact.toBuilder().setFrameId(8L).build()))
        val tooManyCues = exact.toBuilder()
        repeat(5) {
            tooManyCues.addCues(org.conceptflow.mpl.v1.PerceptionCue.newBuilder().setCueId("cue-$it"))
        }
        assertFalse(validateBoundedCorrelatedResult(frame, tooManyCues.build()))
        assertEquals(ErrorCode.ERROR_CODE_UNSPECIFIED, exact.error.code)
    }

    private fun frame(): FramePayload {
        val bytes = ByteString.copyFrom(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        return FramePayload.newBuilder()
            .setRequestId("camera-7")
            .setSessionId("camera-session")
            .setStreamId("camera2-jpeg")
            .setFrameId(7L)
            .setCaptureMonotonicTimestampNs(99_000_000L)
            .setImage(
                ImageDescriptor.newBuilder()
                    .setWidth(1)
                    .setHeight(1)
                    .setEncoding(ImageEncoding.IMAGE_ENCODING_JPEG)
                    .setMediaType("image/jpeg")
                    .setPayloadBytes(bytes.size().toLong())
                    .setSha256(ByteString.copyFromUtf8("digest")),
            )
            .setPose(
                Pose.newBuilder()
                    .setReferenceFrame(CoordinateFrame.COORDINATE_FRAME_HEAD)
                    .setMonotonicTimestampNs(98_000_000L),
            )
            .setFrameData(bytes)
            .build()
    }
}
