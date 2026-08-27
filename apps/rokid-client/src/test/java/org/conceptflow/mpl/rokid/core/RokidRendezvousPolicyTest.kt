// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.conceptflow.mpl.transport.LiveLinkDisconnectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RokidRendezvousPolicyTest {
    @Test
    fun failuresUseCooldownAndNormalSessionRotationIsImmediate() {
        assertEquals(
            RendezvousTerminalDecision.RETRY_AFTER_COOLDOWN,
            decision(LiveLinkCaptureStopReason.RETRY_LIMIT_REACHED, LiveLinkDisconnectReason.NETWORK),
        )
        assertEquals(
            RendezvousTerminalDecision.RETRY_IMMEDIATELY,
            decision(LiveLinkCaptureStopReason.REMOTE_COMPLETED, null),
        )
        assertEquals(
            RendezvousTerminalDecision.RETRY_IMMEDIATELY,
            decision(LiveLinkCaptureStopReason.TIME_LIMIT_REACHED, null),
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
    fun cooldownAlarmRequiresTheSameArmedProcessAndGeneration() {
        assertEquals(
            RendezvousAlarmDecision.START_EPOCH,
            alarmDecision(),
        )
        assertEquals(
            RendezvousAlarmDecision.REJECT,
            alarmDecision(processCapabilityMatches = false),
        )
        assertEquals(
            RendezvousAlarmDecision.REJECT,
            alarmDecision(generationMatches = false),
        )
        assertEquals(
            RendezvousAlarmDecision.REJECT,
            alarmDecision(idleEnabled = false),
        )
        assertEquals(
            RendezvousAlarmDecision.REJECT,
            alarmDecision(visibleArmEligible = false),
        )
        assertEquals(
            RendezvousAlarmDecision.REJECT,
            alarmDecision(serviceStopping = true),
        )
    }

    @Test
    fun duplicateAlarmCannotReplaceAnActiveEpoch() {
        assertEquals(
            RendezvousAlarmDecision.IGNORE_ACTIVE_EPOCH,
            alarmDecision(
                liveEpochActive = true,
                processCapabilityMatches = false,
                generationMatches = false,
            ),
        )
    }

    @Test
    fun staleAlarmCannotConsumeTheCurrentlyScheduledAlarm() {
        assertTrue(RendezvousAlarmPolicy.shouldConsumeScheduledAlarm(true, true))
        assertFalse(RendezvousAlarmPolicy.shouldConsumeScheduledAlarm(false, true))
        assertFalse(RendezvousAlarmPolicy.shouldConsumeScheduledAlarm(true, false))
    }

    @Test
    fun exactAlarmPrecisionIsCapabilityGatedOnAndroidTwelveAndLater() {
        assertEquals(
            RendezvousAlarmPrecision.EXACT_ALLOW_IDLE,
            RendezvousAlarmPolicy.precision(apiLevel = 30, exactAlarmAccess = false),
        )
        assertEquals(
            RendezvousAlarmPrecision.EXACT_ALLOW_IDLE,
            RendezvousAlarmPolicy.precision(apiLevel = 32, exactAlarmAccess = true),
        )
        assertEquals(
            RendezvousAlarmPrecision.INEXACT_ALLOW_IDLE,
            RendezvousAlarmPolicy.precision(apiLevel = 32, exactAlarmAccess = false),
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

    private fun alarmDecision(
        liveEpochActive: Boolean = false,
        processCapabilityMatches: Boolean = true,
        generationMatches: Boolean = true,
        idleEnabled: Boolean = true,
        visibleArmEligible: Boolean = true,
        serviceStopping: Boolean = false,
    ) = RendezvousAlarmPolicy.decide(
        liveEpochActive = liveEpochActive,
        processCapabilityMatches = processCapabilityMatches,
        generationMatches = generationMatches,
        idleEnabled = idleEnabled,
        visibleArmEligible = visibleArmEligible,
        serviceStopping = serviceStopping,
    )
}
