// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.transport.LiveLinkDisconnectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RokidRendezvousPolicyTest {
    @Test
    fun transientFailuresAndNormalSessionRotationUseCooldown() {
        assertEquals(
            RendezvousTerminalDecision.RETRY_AFTER_COOLDOWN,
            decision(LiveLinkCaptureStopReason.RETRY_LIMIT_REACHED, LiveLinkDisconnectReason.NETWORK),
        )
        assertEquals(
            RendezvousTerminalDecision.RETRY_AFTER_COOLDOWN,
            decision(LiveLinkCaptureStopReason.REMOTE_COMPLETED, null),
        )
        assertEquals(
            RendezvousTerminalDecision.RETRY_AFTER_COOLDOWN,
            decision(LiveLinkCaptureStopReason.TIME_LIMIT_REACHED, null),
        )
        assertEquals(
            RendezvousTerminalDecision.RETRY_AFTER_COOLDOWN,
            decision(LiveLinkCaptureStopReason.LEASE_EXPIRED, null),
        )
        assertEquals(
            RendezvousTerminalDecision.RETRY_AFTER_COOLDOWN,
            decision(LiveLinkCaptureStopReason.SOURCE_FAILURE, null),
        )
        assertEquals(
            RendezvousTerminalDecision.RETRY_AFTER_COOLDOWN,
            decision(LiveLinkCaptureStopReason.RENDEZVOUS_TIMEOUT, null),
        )
    }

    @Test
    fun authenticationConfigurationAndProtocolFailuresFailClosed() {
        listOf(
            LiveLinkDisconnectReason.AUTHENTICATION,
            LiveLinkDisconnectReason.CONFIGURATION,
            LiveLinkDisconnectReason.PROTOCOL,
        ).forEach { reason ->
            assertEquals(
                RendezvousTerminalDecision.FAIL_CLOSED,
                decision(LiveLinkCaptureStopReason.RETRY_LIMIT_REACHED, reason),
            )
        }
    }

    @Test
    fun disabledOrRestoredWithoutVisibleArmEligibilityNeverRetries() {
        assertEquals(
            RendezvousTerminalDecision.STOP,
            RokidRendezvousPolicy.afterTerminal(
                LiveLinkCaptureStopReason.REMOTE_COMPLETED,
                null,
                idleEnabled = false,
                visibleArmEligible = true,
            ),
        )
        assertEquals(
            RendezvousTerminalDecision.STOP,
            RokidRendezvousPolicy.afterTerminal(
                LiveLinkCaptureStopReason.REMOTE_COMPLETED,
                null,
                idleEnabled = true,
                visibleArmEligible = false,
            ),
        )
    }

    @Test
    fun generationRejectsStaleCooldownCallbacks() {
        val generation = RendezvousGeneration()
        val first = generation.next()
        assertTrue(generation.isCurrent(first))
        val second = generation.next()
        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
        generation.invalidate()
        assertFalse(generation.isCurrent(second))
    }

    @Test
    fun cooldownCallbackRequiresTheSameArmedGeneration() {
        assertEquals(
            RendezvousRetryDecision.START_EPOCH,
            retryDecision(),
        )
        assertEquals(
            RendezvousRetryDecision.REJECT,
            retryDecision(generationMatches = false),
        )
        assertEquals(
            RendezvousRetryDecision.REJECT,
            retryDecision(idleEnabled = false),
        )
        assertEquals(
            RendezvousRetryDecision.REJECT,
            retryDecision(visibleArmEligible = false),
        )
        assertEquals(
            RendezvousRetryDecision.REJECT,
            retryDecision(serviceStopping = true),
        )
    }

    @Test
    fun duplicateCallbackCannotReplaceAnActiveEpoch() {
        assertEquals(
            RendezvousRetryDecision.IGNORE_ACTIVE_EPOCH,
            retryDecision(
                liveEpochActive = true,
                generationMatches = false,
            ),
        )
    }

    @Test
    fun cooldownEscalatesAcrossFailedEpochsAndOnlyAuthenticationResetsIt() {
        val backoff = RendezvousBackoff(jitterUnitSample = { 0.5 })

        assertEquals(15_000L, backoff.nextDelayMillis())
        assertEquals(30_000L, backoff.nextDelayMillis())
        assertEquals(60_000L, backoff.nextDelayMillis())
        assertEquals(60_000L, backoff.nextDelayMillis())

        backoff.resetAfterAuthenticatedSession()
        assertEquals(15_000L, backoff.nextDelayMillis())
    }

    @Test
    fun jitterStaysWithinTenPercentAndSteadyRetryFitsListenerWindow() {
        val minimum = RendezvousBackoff(
            delaysMillis = listOf(60_000L),
            jitterUnitSample = { 0.0 },
        )
        val maximum = RendezvousBackoff(
            delaysMillis = listOf(60_000L),
            jitterUnitSample = { 1.0 },
        )

        assertEquals(54_000L, minimum.nextDelayMillis())
        assertEquals(66_000L, maximum.nextDelayMillis())
        assertEquals(66_000L, maximum.maximumPossibleDelayMillis)
        assertTrue(
            maximum.maximumPossibleDelayMillis +
                PreAuthenticationRendezvousDeadlineGate.DEFAULT_TIMEOUT_MILLIS <= 246_000L,
        )
    }

    @Test
    fun preAuthenticationDeadlineExpiresOnceAndNeverRestartsAfterAuthentication() {
        assertEquals(182_000L, PreAuthenticationRendezvousDeadlineGate.MAXIMUM_WAKE_LEASE_MILLIS)
        assertTrue(
            PreAuthenticationRendezvousDeadlineGate.MAXIMUM_WAKE_LEASE_MILLIS >
                PreAuthenticationRendezvousDeadlineGate.DEFAULT_TIMEOUT_MILLIS,
        )
        val timedOut = PreAuthenticationRendezvousDeadlineGate()
        timedOut.begin()
        assertFalse(timedOut.observe(sessionsReady = 0L))
        assertTrue(timedOut.expireIfWaiting(0L, LiveLinkCaptureState.CONNECTING))
        assertFalse(timedOut.expireIfWaiting(0L, LiveLinkCaptureState.CONNECTING))

        val authenticated = PreAuthenticationRendezvousDeadlineGate()
        authenticated.begin()
        assertTrue(authenticated.observe(sessionsReady = 1L))
        assertFalse(authenticated.expireIfWaiting(1L, LiveLinkCaptureState.CONNECTING))
        assertFalse(authenticated.expireIfWaiting(1L, LiveLinkCaptureState.STREAMING))
    }

    private fun decision(
        stopReason: LiveLinkCaptureStopReason,
        disconnectReason: LiveLinkDisconnectReason?,
    ) = RokidRendezvousPolicy.afterTerminal(
        stopReason,
        disconnectReason,
        idleEnabled = true,
        visibleArmEligible = true,
    )

    private fun retryDecision(
        liveEpochActive: Boolean = false,
        generationMatches: Boolean = true,
        idleEnabled: Boolean = true,
        visibleArmEligible: Boolean = true,
        serviceStopping: Boolean = false,
    ) = RendezvousRetryPolicy.decide(
        liveEpochActive = liveEpochActive,
        generationMatches = generationMatches,
        idleEnabled = idleEnabled,
        visibleArmEligible = visibleArmEligible,
        serviceStopping = serviceStopping,
    )
}
