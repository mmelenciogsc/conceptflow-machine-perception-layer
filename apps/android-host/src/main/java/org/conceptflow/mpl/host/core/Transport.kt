// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.core

import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.StreamObserver
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.NegotiateRequest
import org.conceptflow.mpl.v1.NegotiateResponse
import org.conceptflow.mpl.v1.PerceptionCue
import org.conceptflow.mpl.v1.PerceptionResult
import org.conceptflow.mpl.v1.PerceptionServiceGrpc
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface CancellationHandle {
    fun cancel()
}

interface TransportCallback<T> {
    fun onSuccess(value: T)
    fun onFailure(error: Throwable)
}

interface HostPerceptionTransport : AutoCloseable {
    fun negotiate(request: NegotiateRequest, callback: TransportCallback<NegotiateResponse>): CancellationHandle
    fun process(frame: FramePayload, callback: TransportCallback<PerceptionResult>): CancellationHandle
}

class InProcessHostTransport(
    private val negotiation: (NegotiateRequest) -> NegotiateResponse,
    private val processor: (FramePayload) -> PerceptionResult,
) : HostPerceptionTransport {
    private val closed = AtomicBoolean(false)

    override fun negotiate(
        request: NegotiateRequest,
        callback: TransportCallback<NegotiateResponse>,
    ): CancellationHandle = runSynchronously(callback) { negotiation(request) }

    override fun process(
        frame: FramePayload,
        callback: TransportCallback<PerceptionResult>,
    ): CancellationHandle = runSynchronously(callback) { processor(frame) }

    private fun <T> runSynchronously(callback: TransportCallback<T>, block: () -> T): CancellationHandle {
        val cancelled = AtomicBoolean(false)
        if (closed.get()) {
            callback.onFailure(IllegalStateException("Transport is closed"))
        } else {
            runCatching(block).onSuccess {
                if (!cancelled.get()) callback.onSuccess(it)
            }.onFailure {
                if (!cancelled.get()) callback.onFailure(it)
            }
        }
        return CancellationHandle { cancelled.set(true) }
    }

    override fun close() {
        closed.set(true)
    }
}

data class GrpcEndpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it == '/' })
        require(port in 1..65_535)
    }
}

class GrpcPerceptionTransport private constructor(
    private val channel: ManagedChannel,
    private val deadlineMillis: Long,
) : HostPerceptionTransport {
    init {
        require(deadlineMillis in 100L..60_000L)
    }

    override fun negotiate(
        request: NegotiateRequest,
        callback: TransportCallback<NegotiateResponse>,
    ): CancellationHandle = unary(
        PerceptionServiceGrpc.getNegotiateMethod(),
        request,
        callback,
    )

    override fun process(
        frame: FramePayload,
        callback: TransportCallback<PerceptionResult>,
    ): CancellationHandle = unary(
        PerceptionServiceGrpc.getProcessFrameMethod(),
        frame,
        callback,
    )

    private fun <RequestT, ResponseT> unary(
        method: io.grpc.MethodDescriptor<RequestT, ResponseT>,
        request: RequestT,
        callback: TransportCallback<ResponseT>,
    ): CancellationHandle {
        val call: ClientCall<RequestT, ResponseT> = channel.newCall(
            method,
            CallOptions.DEFAULT.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS),
        )
        ClientCalls.asyncUnaryCall(call, request, object : StreamObserver<ResponseT> {
            override fun onNext(value: ResponseT) = callback.onSuccess(value)
            override fun onError(error: Throwable) = callback.onFailure(error)
            override fun onCompleted() = Unit
        })
        return CancellationHandle { call.cancel("Cancelled by host session", null) }
    }

    override fun close() {
        channel.shutdownNow()
    }

    companion object {
        fun secure(endpoint: GrpcEndpoint, deadlineMillis: Long = 2_000L): GrpcPerceptionTransport {
            val channel = OkHttpChannelBuilder.forAddress(endpoint.host, endpoint.port)
                .useTransportSecurity()
                .build()
            return GrpcPerceptionTransport(channel, deadlineMillis)
        }
    }
}

interface CueDispatchTransport : AutoCloseable {
    val connected: Boolean
    fun send(cue: PerceptionCue): Boolean
}

class InProcessCueDispatchTransport(
    private val glassesReceiver: (PerceptionCue) -> Boolean,
) : CueDispatchTransport {
    private val open = AtomicBoolean(true)
    override val connected: Boolean get() = open.get()

    override fun send(cue: PerceptionCue): Boolean {
        if (!open.get()) return false
        return runCatching { glassesReceiver(cue) }.getOrDefault(false)
    }

    override fun close() {
        open.set(false)
    }
}
