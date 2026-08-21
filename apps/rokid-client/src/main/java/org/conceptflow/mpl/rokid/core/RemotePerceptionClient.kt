// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import com.google.protobuf.Duration
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.StreamObserver
import org.conceptflow.mpl.v1.CapabilitySet
import org.conceptflow.mpl.v1.CueModality
import org.conceptflow.mpl.v1.ErrorCode
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImageEncoding
import org.conceptflow.mpl.v1.NegotiateRequest
import org.conceptflow.mpl.v1.NegotiateResponse
import org.conceptflow.mpl.v1.PerceptionResult
import org.conceptflow.mpl.v1.PerceptionServiceGrpc
import org.conceptflow.mpl.v1.ProtocolVersion
import org.conceptflow.mpl.v1.QualityOfService
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface RemoteCall {
    fun cancel()
}

interface TraceCallback {
    fun onSuccess(value: PerceptionResult)
    fun onFailure(error: Throwable)
}

interface RemotePerceptionClient : AutoCloseable {
    fun execute(frame: FramePayload, callback: TraceCallback): RemoteCall
}

data class GrpcEndpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it == '/' })
        require(port in 1..65_535)
    }
}

class GrpcRemotePerceptionClient private constructor(
    private val channel: ManagedChannel,
    private val deadlineMillis: Long,
) : RemotePerceptionClient {
    private val closed = AtomicBoolean(false)

    init {
        require(deadlineMillis in MIN_DEADLINE_MILLIS..MAX_DEADLINE_MILLIS)
    }

    override fun execute(frame: FramePayload, callback: TraceCallback): RemoteCall {
        val operation = GrpcTraceOperation(callback)
        if (closed.get()) {
            operation.fail(IllegalStateException("Remote perception client is closed"))
            return operation
        }
        val negotiation = buildNegotiationRequest(
            clientId = "rokid-direct-${UUID.randomUUID()}",
            deadlineMillis = deadlineMillis,
        )
        val call = newCall(PerceptionServiceGrpc.getNegotiateMethod())
        operation.replaceCall(call)
        ClientCalls.asyncUnaryCall(
            call,
            negotiation,
            SingleResponseObserver<NegotiateResponse>(
                onValue = { response: NegotiateResponse -> onNegotiated(operation, frame, response) },
                onFailure = operation::fail,
            ),
        )
        return operation
    }

    private fun onNegotiated(
        operation: GrpcTraceOperation,
        capturedFrame: FramePayload,
        response: NegotiateResponse,
    ) {
        if (operation.isDone) return
        if (response.error.code != ErrorCode.ERROR_CODE_UNSPECIFIED) {
            operation.fail(IllegalStateException("Negotiation rejected: ${response.error.code.name}"))
            return
        }
        if (!isSupportedNegotiation(response)) {
            operation.fail(IllegalStateException("Negotiation returned an invalid version or session"))
            return
        }
        val sessionId = response.identity.sessionId
        val frame = remapFrameForSession(
            capturedFrame,
            sessionId = sessionId,
            requestId = "physical-${UUID.randomUUID()}",
            deadlineMillis = deadlineMillis,
        )
        val call = newCall(PerceptionServiceGrpc.getProcessFrameMethod())
        operation.replaceCall(call)
        ClientCalls.asyncUnaryCall(
            call,
            frame,
            SingleResponseObserver<PerceptionResult>(
                onValue = { result: PerceptionResult ->
                    when {
                        !validateBoundedCorrelatedResult(frame, result) -> {
                            operation.fail(IllegalStateException("Perception result bounds or correlation failed"))
                        }
                        result.error.code != ErrorCode.ERROR_CODE_UNSPECIFIED -> {
                            operation.fail(IllegalStateException("Perception failed: ${result.error.code.name}"))
                        }
                        else -> operation.succeed(result)
                    }
                },
                onFailure = operation::fail,
            ),
        )
    }

    private fun <RequestT, ResponseT> newCall(
        method: MethodDescriptor<RequestT, ResponseT>,
    ): ClientCall<RequestT, ResponseT> = channel.newCall(
        method,
        CallOptions.DEFAULT.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS),
    )

    override fun close() {
        if (closed.compareAndSet(false, true)) channel.shutdownNow()
    }

    companion object {
        private const val MIN_DEADLINE_MILLIS = 100L
        private const val MAX_DEADLINE_MILLIS = 60_000L

        fun secure(endpoint: GrpcEndpoint, deadlineMillis: Long = 2_000L): GrpcRemotePerceptionClient {
            val channel = OkHttpChannelBuilder.forAddress(endpoint.host, endpoint.port)
                .maxInboundMessageSize(MAX_INBOUND_MESSAGE_BYTES)
                .useTransportSecurity()
                .build()
            return GrpcRemotePerceptionClient(channel, deadlineMillis)
        }

        fun adbReverseLoopback(
            port: Int = 50_051,
            deadlineMillis: Long = 2_000L,
        ): GrpcRemotePerceptionClient {
            val endpoint = GrpcEndpoint("127.0.0.1", port)
            check(isLiteralLoopbackHost(endpoint.host))
            val channel = OkHttpChannelBuilder.forAddress(endpoint.host, endpoint.port)
                .maxInboundMessageSize(MAX_INBOUND_MESSAGE_BYTES)
                .usePlaintext()
                .build()
            return GrpcRemotePerceptionClient(channel, deadlineMillis)
        }
    }
}

internal fun isLiteralLoopbackHost(host: String): Boolean =
    host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)

internal fun isSupportedNegotiation(response: NegotiateResponse): Boolean =
    response.selectedVersion.major == SUPPORTED_PROTOCOL_MAJOR &&
        response.identity.sessionId.matches(ROUTING_ID_PATTERN)

internal fun buildNegotiationRequest(clientId: String, deadlineMillis: Long): NegotiateRequest {
    require(clientId.matches(ROUTING_ID_PATTERN))
    require(deadlineMillis in 100L..60_000L)
    return NegotiateRequest.newBuilder()
        .setClientInstanceId(clientId)
        .addSupportedVersions(ProtocolVersion.newBuilder().setMajor(1).setMinor(0).setPatch(0))
        .setCapabilities(
            CapabilitySet.newBuilder()
                .addImageEncodings(ImageEncoding.IMAGE_ENCODING_JPEG)
                .addCueModalities(CueModality.CUE_MODALITY_EARCON)
                .addCueModalities(CueModality.CUE_MODALITY_HAPTIC)
                .setMaxWidth(1_280)
                .setMaxHeight(720)
                .setMaxFrameBytes(768L * 1_024L)
                .setSupportsCancellation(true)
                .setSupportsSupersession(true)
                .setSupportsPose(true),
        )
        .setRequestedQos(
            QualityOfService.newBuilder()
                .setMaxInFlight(1)
                .setTargetFramesPerSecond(1)
                .setResultDeadline(protobufDuration(deadlineMillis))
                .setAllowFrameDrop(true)
                .setMaxCuesPerResult(MAX_CUES_PER_RESULT),
        )
        .build()
}

internal fun remapFrameForSession(
    frame: FramePayload,
    sessionId: String,
    requestId: String,
    deadlineMillis: Long,
): FramePayload {
    require(sessionId.matches(ROUTING_ID_PATTERN))
    require(requestId.matches(ROUTING_ID_PATTERN))
    require(deadlineMillis in 100L..60_000L)
    return frame.toBuilder()
        .setSessionId(sessionId)
        .setRequestId(requestId)
        .setProcessingDeadline(protobufDuration(deadlineMillis))
        .build()
}

internal fun validateBoundedCorrelatedResult(frame: FramePayload, result: PerceptionResult): Boolean =
    result.cuesCount <= MAX_CUES_PER_RESULT &&
        result.requestId == frame.requestId &&
        result.sessionId == frame.sessionId &&
        result.streamId == frame.streamId &&
        result.frameId == frame.frameId

private fun protobufDuration(millis: Long): Duration = Duration.newBuilder()
    .setSeconds(millis / 1_000L)
    .setNanos(((millis % 1_000L) * 1_000_000L).toInt())
    .build()

private const val SUPPORTED_PROTOCOL_MAJOR = 1
private const val MAX_CUES_PER_RESULT = 4
private const val MAX_INBOUND_MESSAGE_BYTES = 1 * 1_024 * 1_024
private val ROUTING_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")

private class GrpcTraceOperation(private val callback: TraceCallback) : RemoteCall {
    private val done = AtomicBoolean(false)
    private val lock = Any()
    private var currentCall: ClientCall<*, *>? = null

    val isDone: Boolean get() = done.get()

    fun replaceCall(call: ClientCall<*, *>) {
        synchronized(lock) {
            if (done.get()) {
                call.cancel("Physical trace is no longer active", null)
            } else {
                currentCall = call
            }
        }
    }

    fun succeed(result: PerceptionResult) {
        if (done.compareAndSet(false, true)) callback.onSuccess(result)
    }

    fun fail(error: Throwable) {
        if (done.compareAndSet(false, true)) callback.onFailure(error)
    }

    override fun cancel() {
        if (!done.compareAndSet(false, true)) return
        synchronized(lock) {
            currentCall?.cancel("Physical trace cancelled", null)
            currentCall = null
        }
    }
}

private class SingleResponseObserver<T>(
    private val onValue: (T) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : StreamObserver<T> {
    private var received = false

    override fun onNext(value: T) {
        if (received) return
        received = true
        onValue(value)
    }

    override fun onError(error: Throwable) = onFailure(error)

    override fun onCompleted() {
        if (!received) onFailure(IllegalStateException("gRPC call completed without a response"))
    }
}
