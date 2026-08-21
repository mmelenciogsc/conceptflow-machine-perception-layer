// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.core

import org.conceptflow.mpl.v1.EphemeralIdentity
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.NegotiateRequest
import org.conceptflow.mpl.v1.NegotiateResponse
import org.conceptflow.mpl.v1.PerceptionCue
import org.conceptflow.mpl.v1.PerceptionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticEndToEndTest {
    @Test
    fun canonicalFrameProducesCorrelatedScheduledGlassesCue() {
        val now = 50_000L
        val clock = MutableHostClock(now)
        val frame = testFrame(frameId = 7L)
        val preprocessor = BoundedFramePreprocessor(PreprocessingLimits())
        assertTrue(preprocessor.prepare(frame) is PreprocessingResult.Ready)
        assertEquals(
            ProcessingRoute.GRPC,
            RoutingPolicy().choose(frame, RouteEnvironment(false, true, false)).route,
        )

        val cue = testCue("glasses-cue", priority = 80, created = now, urgency = org.conceptflow.mpl.v1.Urgency.URGENCY_HIGH)
        val transport = InProcessHostTransport(
            negotiation = {
                NegotiateResponse.newBuilder()
                    .setIdentity(EphemeralIdentity.newBuilder().setSessionId("session"))
                    .build()
            },
            processor = { testResult(it, cue) },
        )
        val correlator = ResultCorrelator(clock)
        val scheduler = CueScheduler(CueSchedulingPolicy())
        val glassesEvents = mutableListOf<PerceptionCue>()
        val glasses = InProcessCueDispatchTransport(glassesEvents::add)
        correlator.register(frame)

        var callbackResult: PerceptionResult? = null
        transport.process(frame, object : TransportCallback<PerceptionResult> {
            override fun onSuccess(value: PerceptionResult) {
                callbackResult = value
            }

            override fun onFailure(error: Throwable) {
                throw AssertionError(error)
            }
        })
        val result = callbackResult ?: throw AssertionError("No deterministic result")
        assertTrue(correlator.correlate(result) is CorrelationResult.Accepted)
        result.cuesList.forEach { scheduler.submit(it, now) }
        val scheduled = scheduler.next(now) ?: throw AssertionError("No scheduled cue")
        assertTrue(glasses.send(scheduled))
        assertEquals("glasses-cue", glassesEvents.single().cueId)
        glasses.close()
        assertTrue(!glasses.send(scheduled))
        transport.close()
    }

    @Test
    fun closedInProcessTransportFailsDeterministically() {
        val transport = InProcessHostTransport(
            negotiation = { NegotiateResponse.getDefaultInstance() },
            processor = { testResult(it) },
        )
        transport.close()
        var failure: Throwable? = null
        transport.process(testFrame(), object : TransportCallback<PerceptionResult> {
            override fun onSuccess(value: PerceptionResult) = Unit
            override fun onFailure(error: Throwable) {
                failure = error
            }
        })
        assertTrue(failure is IllegalStateException)
    }
}
