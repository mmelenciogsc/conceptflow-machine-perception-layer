// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidWifiDirectEndpointResolverTest {
    @Test
    fun `only exact project service identity is eligible for connection`() {
        assertTrue(
            AndroidWifiDirectEndpointResolver.matchesService(
                "CONCEPTFlow-MPL-AndroidNode",
                "_cf-mpl._tcp",
            ),
        )
        assertTrue(
            AndroidWifiDirectEndpointResolver.matchesService(
                "CONCEPTFlow-MPL-AndroidNode",
                "_cf-mpl._tcp.local.",
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.matchesService(
                "CONCEPTFlow-MPL-RokidNode",
                "_cf-mpl._tcp",
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.matchesService(
                "CONCEPTFlow-MPL-AndroidNode",
                "_http._tcp",
            ),
        )
    }

    @Test
    fun `retry cadence is bounded and nondecreasing`() {
        val delays = AndroidWifiDirectEndpointResolver.RETRY_DELAYS_MILLIS
        assertTrue(delays.isNotEmpty())
        assertTrue(delays.all { it in 1_000L..15_000L })
        assertTrue(delays.asList().zipWithNext().all { (previous, next) -> next >= previous })
    }

    @Test
    fun `both roles defer cold initialization until the channel looper can run`() {
        assertTrue(
            AndroidWifiDirectEndpointResolver.initializationAction(WifiDirectNodeRole.ROKID_CLIENT) ==
                WifiDirectInitializationAction.DEFER_TO_RETRY,
        )
        assertTrue(
            AndroidWifiDirectEndpointResolver.initializationAction(WifiDirectNodeRole.ANDROID_GROUP_OWNER) ==
                WifiDirectInitializationAction.DEFER_TO_RETRY,
        )
        assertTrue(AndroidWifiDirectEndpointResolver.CHANNEL_REINITIALIZATION_DELAY_MILLIS == 1_000L)
    }

    @Test
    fun `discovery cannot restart while group negotiation or a group is active`() {
        assertTrue(AndroidWifiDirectEndpointResolver.discoveryAllowed(WifiDirectPhase.IDLE))
        assertTrue(AndroidWifiDirectEndpointResolver.discoveryAllowed(WifiDirectPhase.STARTING))
        assertTrue(AndroidWifiDirectEndpointResolver.discoveryAllowed(WifiDirectPhase.DISCOVERING))
        assertFalse(AndroidWifiDirectEndpointResolver.discoveryAllowed(WifiDirectPhase.WAITING_FOR_RADIO))
        assertFalse(AndroidWifiDirectEndpointResolver.discoveryAllowed(WifiDirectPhase.CONNECTING))
        assertFalse(AndroidWifiDirectEndpointResolver.discoveryAllowed(WifiDirectPhase.GROUP_READY))
        assertFalse(AndroidWifiDirectEndpointResolver.discoveryAllowed(WifiDirectPhase.CLOSED))
        assertTrue(
            AndroidWifiDirectEndpointResolver.CONNECTION_FORMATION_TIMEOUT_MILLIS <
                AndroidWifiDirectEndpointResolver.MAXIMUM_RESOLUTION_TIMEOUT_MILLIS,
        )
    }

    @Test
    fun `platform failure reasons have stable diagnostic names`() {
        assertTrue(AndroidWifiDirectEndpointResolver.failureReasonName(0) == "ERROR")
        assertTrue(AndroidWifiDirectEndpointResolver.failureReasonName(1) == "P2P_UNSUPPORTED")
        assertTrue(AndroidWifiDirectEndpointResolver.failureReasonName(2) == "BUSY")
        assertTrue(AndroidWifiDirectEndpointResolver.failureReasonName(99) == "UNKNOWN")
    }

    @Test
    fun `radio readiness requires wifi and rejects an explicitly disabled p2p stack`() {
        assertTrue(AndroidWifiDirectEndpointResolver.radioAvailable(wifiEnabled = true, p2pEnabled = null))
        assertTrue(AndroidWifiDirectEndpointResolver.radioAvailable(wifiEnabled = true, p2pEnabled = true))
        assertFalse(AndroidWifiDirectEndpointResolver.radioAvailable(wifiEnabled = true, p2pEnabled = false))
        assertFalse(AndroidWifiDirectEndpointResolver.radioAvailable(wifiEnabled = false, p2pEnabled = null))
        assertFalse(AndroidWifiDirectEndpointResolver.radioAvailable(wifiEnabled = false, p2pEnabled = true))
    }

    @Test
    fun `first explicit p2p enabled state rearms optimistic cold startup`() {
        assertTrue(
            AndroidWifiDirectEndpointResolver.preparationRequiredAfterRadioUpdate(
                previousWifiEnabled = true,
                previousP2pEnabled = null,
                currentWifiEnabled = true,
                currentP2pEnabled = true,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.preparationRequiredAfterRadioUpdate(
                previousWifiEnabled = true,
                previousP2pEnabled = null,
                currentWifiEnabled = true,
                currentP2pEnabled = false,
            ),
        )
    }

    @Test
    fun `radio preparation only repeats on a real or first-known readiness edge`() {
        assertTrue(
            AndroidWifiDirectEndpointResolver.preparationRequiredAfterRadioUpdate(
                previousWifiEnabled = true,
                previousP2pEnabled = false,
                currentWifiEnabled = true,
                currentP2pEnabled = true,
            ),
        )
        assertTrue(
            AndroidWifiDirectEndpointResolver.preparationRequiredAfterRadioUpdate(
                previousWifiEnabled = false,
                previousP2pEnabled = true,
                currentWifiEnabled = true,
                currentP2pEnabled = true,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.preparationRequiredAfterRadioUpdate(
                previousWifiEnabled = true,
                previousP2pEnabled = true,
                currentWifiEnabled = true,
                currentP2pEnabled = true,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.preparationRequiredAfterRadioUpdate(
                previousWifiEnabled = true,
                previousP2pEnabled = true,
                currentWifiEnabled = false,
                currentP2pEnabled = true,
            ),
        )
    }

    @Test
    fun `only the current disconnected channel is reinitialized`() {
        assertTrue(
            AndroidWifiDirectEndpointResolver.shouldReinitializeChannel(
                closed = false,
                callbackEpoch = 4L,
                currentEpoch = 4L,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.shouldReinitializeChannel(
                closed = false,
                callbackEpoch = 3L,
                currentEpoch = 4L,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.shouldReinitializeChannel(
                closed = true,
                callbackEpoch = 4L,
                currentEpoch = 4L,
            ),
        )
    }

    @Test
    fun `silent DNS SD discovery has a bounded client-only watchdog`() {
        assertTrue(AndroidWifiDirectEndpointResolver.DISCOVERY_WATCHDOG_MILLIS in 5_000L..30_000L)
        assertTrue(
            AndroidWifiDirectEndpointResolver.DISCOVERY_WATCHDOG_MILLIS <
                AndroidWifiDirectEndpointResolver.CONNECTION_FORMATION_TIMEOUT_MILLIS,
        )
        assertTrue(
            AndroidWifiDirectEndpointResolver.discoveryWatchdogAllowed(
                WifiDirectNodeRole.ROKID_CLIENT,
                WifiDirectPhase.STARTING,
                hasEndpoint = false,
                completedRestarts = 0,
            ),
        )
        assertTrue(
            AndroidWifiDirectEndpointResolver.discoveryWatchdogAllowed(
                WifiDirectNodeRole.ROKID_CLIENT,
                WifiDirectPhase.DISCOVERING,
                hasEndpoint = false,
                completedRestarts = 0,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.discoveryWatchdogAllowed(
                WifiDirectNodeRole.ANDROID_GROUP_OWNER,
                WifiDirectPhase.DISCOVERING,
                hasEndpoint = false,
                completedRestarts = 0,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.discoveryWatchdogAllowed(
                WifiDirectNodeRole.ROKID_CLIENT,
                WifiDirectPhase.CONNECTING,
                hasEndpoint = false,
                completedRestarts = 0,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.discoveryWatchdogAllowed(
                WifiDirectNodeRole.ROKID_CLIENT,
                WifiDirectPhase.DISCOVERING,
                hasEndpoint = true,
                completedRestarts = 0,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.discoveryWatchdogAllowed(
                WifiDirectNodeRole.ROKID_CLIENT,
                WifiDirectPhase.DISCOVERING,
                hasEndpoint = false,
                completedRestarts = AndroidWifiDirectEndpointResolver.MAXIMUM_SILENT_DISCOVERY_RESTARTS,
            ),
        )
    }

    @Test
    fun `peer fallback starts only after bounded DNS SD silence`() {
        assertTrue(
            AndroidWifiDirectEndpointResolver.PEER_DISCOVERY_SETTLE_MILLIS <
                AndroidWifiDirectEndpointResolver.PEER_FALLBACK_TIMEOUT_MILLIS,
        )
        assertTrue(
            AndroidWifiDirectEndpointResolver.MAXIMUM_PEER_FALLBACK_ATTEMPTS <
                AndroidWifiDirectEndpointResolver.MAXIMUM_SILENT_DISCOVERY_RESTARTS,
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.peerFallbackAllowed(
                WifiDirectNodeRole.ROKID_CLIENT,
                WifiDirectPhase.DISCOVERING,
                hasEndpoint = false,
                completedSilentRestarts =
                    AndroidWifiDirectEndpointResolver.PEER_FALLBACK_AFTER_SILENT_RESTARTS - 1,
                completedFallbackAttempts = 0,
                fallbackPhase = WifiDirectPeerFallbackPhase.IDLE,
            ),
        )
        assertTrue(
            AndroidWifiDirectEndpointResolver.peerFallbackAllowed(
                WifiDirectNodeRole.ROKID_CLIENT,
                WifiDirectPhase.DISCOVERING,
                hasEndpoint = false,
                completedSilentRestarts =
                    AndroidWifiDirectEndpointResolver.PEER_FALLBACK_AFTER_SILENT_RESTARTS,
                completedFallbackAttempts = 0,
                fallbackPhase = WifiDirectPeerFallbackPhase.IDLE,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.peerFallbackAllowed(
                WifiDirectNodeRole.ROKID_CLIENT,
                WifiDirectPhase.DISCOVERING,
                hasEndpoint = false,
                completedSilentRestarts =
                    AndroidWifiDirectEndpointResolver.PEER_FALLBACK_AFTER_SILENT_RESTARTS,
                completedFallbackAttempts = AndroidWifiDirectEndpointResolver.MAXIMUM_PEER_FALLBACK_ATTEMPTS,
                fallbackPhase = WifiDirectPeerFallbackPhase.IDLE,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.peerFallbackAllowed(
                WifiDirectNodeRole.ROKID_CLIENT,
                WifiDirectPhase.DISCOVERING,
                hasEndpoint = false,
                completedSilentRestarts =
                    AndroidWifiDirectEndpointResolver.PEER_FALLBACK_AFTER_SILENT_RESTARTS,
                completedFallbackAttempts = 0,
                fallbackPhase = WifiDirectPeerFallbackPhase.DISCOVERING_PEERS,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.peerFallbackAllowed(
                WifiDirectNodeRole.ANDROID_GROUP_OWNER,
                WifiDirectPhase.DISCOVERING,
                hasEndpoint = false,
                completedSilentRestarts =
                    AndroidWifiDirectEndpointResolver.PEER_FALLBACK_AFTER_SILENT_RESTARTS,
                completedFallbackAttempts = 0,
                fallbackPhase = WifiDirectPeerFallbackPhase.IDLE,
            ),
        )
    }

    @Test
    fun `peer fallback prefers exactly one reported group owner`() {
        assertTrue(AndroidWifiDirectEndpointResolver.uniqueGroupOwnerIndex(emptyList()) == null)
        assertTrue(AndroidWifiDirectEndpointResolver.uniqueGroupOwnerIndex(listOf(false, false)) == null)
        assertTrue(AndroidWifiDirectEndpointResolver.uniqueGroupOwnerIndex(listOf(false, true, false)) == 1)
        assertTrue(AndroidWifiDirectEndpointResolver.uniqueGroupOwnerIndex(listOf(true, false, true)) == null)
        assertTrue(AndroidWifiDirectEndpointResolver.rendezvousCandidateIndex(listOf(false, true, false)) == 1)
    }

    @Test
    fun `peer fallback tolerates stale owner metadata only for a sole visible peer`() {
        assertTrue(AndroidWifiDirectEndpointResolver.rendezvousCandidateIndex(listOf(false)) == 0)
        assertTrue(AndroidWifiDirectEndpointResolver.rendezvousCandidateIndex(emptyList()) == null)
        assertTrue(AndroidWifiDirectEndpointResolver.rendezvousCandidateIndex(listOf(false, false)) == null)
    }

    @Test
    fun `peer fallback rejects multiple reported owners`() {
        assertTrue(AndroidWifiDirectEndpointResolver.rendezvousCandidateIndex(listOf(true, true)) == null)
        assertTrue(AndroidWifiDirectEndpointResolver.rendezvousCandidateIndex(listOf(true, false, true)) == null)
    }

    @Test
    fun `peer snapshot ignores stale broadcast until ordinary discovery starts`() {
        assertTrue(
            AndroidWifiDirectEndpointResolver.peerFallbackCallbackAllowed(
                closed = false,
                channelReady = true,
                callbackEpoch = 7L,
                currentEpoch = 7L,
                currentPhase = WifiDirectPeerFallbackPhase.STARTING_PEER_DISCOVERY,
                expectedPhase = WifiDirectPeerFallbackPhase.STARTING_PEER_DISCOVERY,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.peerFallbackCallbackAllowed(
                closed = false,
                channelReady = true,
                callbackEpoch = 7L,
                currentEpoch = 7L,
                currentPhase = WifiDirectPeerFallbackPhase.STARTING_PEER_DISCOVERY,
                expectedPhase = WifiDirectPeerFallbackPhase.DISCOVERING_PEERS,
            ),
        )
    }

    @Test
    fun `peer callback must belong to the current live channel attempt`() {
        assertTrue(
            AndroidWifiDirectEndpointResolver.peerFallbackCallbackAllowed(
                closed = false,
                channelReady = true,
                callbackEpoch = 7L,
                currentEpoch = 7L,
                currentPhase = WifiDirectPeerFallbackPhase.REQUESTING_PEERS,
                expectedPhase = WifiDirectPeerFallbackPhase.REQUESTING_PEERS,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.peerFallbackCallbackAllowed(
                closed = false,
                channelReady = true,
                callbackEpoch = 6L,
                currentEpoch = 7L,
                currentPhase = WifiDirectPeerFallbackPhase.REQUESTING_PEERS,
                expectedPhase = WifiDirectPeerFallbackPhase.REQUESTING_PEERS,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.peerFallbackCallbackAllowed(
                closed = true,
                channelReady = true,
                callbackEpoch = 7L,
                currentEpoch = 7L,
                currentPhase = WifiDirectPeerFallbackPhase.REQUESTING_PEERS,
                expectedPhase = WifiDirectPeerFallbackPhase.REQUESTING_PEERS,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.peerFallbackCallbackAllowed(
                closed = false,
                channelReady = false,
                callbackEpoch = 7L,
                currentEpoch = 7L,
                currentPhase = WifiDirectPeerFallbackPhase.REQUESTING_PEERS,
                expectedPhase = WifiDirectPeerFallbackPhase.REQUESTING_PEERS,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.peerFallbackCallbackAllowed(
                closed = false,
                channelReady = true,
                callbackEpoch = 7L,
                currentEpoch = 7L,
                currentPhase = WifiDirectPeerFallbackPhase.DISCOVERING_PEERS,
                expectedPhase = WifiDirectPeerFallbackPhase.REQUESTING_PEERS,
            ),
        )
    }

    @Test
    fun `owner visibility is bounded and starts only after owner endpoint is ready`() {
        assertTrue(AndroidWifiDirectEndpointResolver.OWNER_VISIBILITY_WINDOW_MILLIS in 30_000L..120_000L)
        assertTrue(
            AndroidWifiDirectEndpointResolver.OWNER_MEMBERSHIP_POLL_MILLIS <
                AndroidWifiDirectEndpointResolver.OWNER_VISIBILITY_WINDOW_MILLIS,
        )
        assertTrue(AndroidWifiDirectEndpointResolver.MAXIMUM_OWNER_VISIBILITY_DISCOVERY_ATTEMPTS in 1..3)
        assertTrue(AndroidWifiDirectEndpointResolver.MAXIMUM_OWNER_LISTEN_START_ATTEMPTS in 1..3)
        assertTrue(
            AndroidWifiDirectEndpointResolver.ownerVisibilityWindowAllowed(
                WifiDirectNodeRole.ANDROID_GROUP_OWNER,
                WifiDirectPhase.GROUP_READY,
                hasEndpoint = true,
                alreadyActive = false,
                clientAlreadyObserved = false,
                windowAlreadyConsumed = false,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerVisibilityWindowAllowed(
                WifiDirectNodeRole.ROKID_CLIENT,
                WifiDirectPhase.GROUP_READY,
                hasEndpoint = true,
                alreadyActive = false,
                clientAlreadyObserved = false,
                windowAlreadyConsumed = false,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerVisibilityWindowAllowed(
                WifiDirectNodeRole.ANDROID_GROUP_OWNER,
                WifiDirectPhase.CONNECTING,
                hasEndpoint = false,
                alreadyActive = false,
                clientAlreadyObserved = false,
                windowAlreadyConsumed = false,
            ),
        )
        assertTrue(
            AndroidWifiDirectEndpointResolver.ownerVisibilityWindowAllowed(
                WifiDirectNodeRole.ANDROID_GROUP_OWNER,
                WifiDirectPhase.DISCOVERING,
                hasEndpoint = false,
                alreadyActive = false,
                clientAlreadyObserved = false,
                windowAlreadyConsumed = false,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerVisibilityWindowAllowed(
                WifiDirectNodeRole.ANDROID_GROUP_OWNER,
                WifiDirectPhase.GROUP_READY,
                hasEndpoint = true,
                alreadyActive = true,
                clientAlreadyObserved = false,
                windowAlreadyConsumed = false,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerVisibilityWindowAllowed(
                WifiDirectNodeRole.ANDROID_GROUP_OWNER,
                WifiDirectPhase.GROUP_READY,
                hasEndpoint = true,
                alreadyActive = false,
                clientAlreadyObserved = true,
                windowAlreadyConsumed = false,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerVisibilityWindowAllowed(
                WifiDirectNodeRole.ANDROID_GROUP_OWNER,
                WifiDirectPhase.GROUP_READY,
                hasEndpoint = true,
                alreadyActive = false,
                clientAlreadyObserved = false,
                windowAlreadyConsumed = true,
            ),
        )
    }

    @Test
    fun `owner visibility callbacks require the current live channel window`() {
        assertTrue(
            AndroidWifiDirectEndpointResolver.ownerVisibilityCallbackAllowed(
                closed = false,
                channelReady = true,
                callbackEpoch = 11L,
                currentEpoch = 11L,
                active = true,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerVisibilityCallbackAllowed(
                closed = false,
                channelReady = true,
                callbackEpoch = 10L,
                currentEpoch = 11L,
                active = true,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerVisibilityCallbackAllowed(
                closed = true,
                channelReady = true,
                callbackEpoch = 11L,
                currentEpoch = 11L,
                active = true,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerVisibilityCallbackAllowed(
                closed = false,
                channelReady = false,
                callbackEpoch = 11L,
                currentEpoch = 11L,
                active = true,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerVisibilityCallbackAllowed(
                closed = false,
                channelReady = true,
                callbackEpoch = 11L,
                currentEpoch = 11L,
                active = false,
            ),
        )
    }

    @Test
    fun `owner listen API is gated to API 33 and later`() {
        assertFalse(AndroidWifiDirectEndpointResolver.ownerListeningAvailable(32))
        assertTrue(AndroidWifiDirectEndpointResolver.ownerListeningAvailable(33))
        assertTrue(AndroidWifiDirectEndpointResolver.ownerListeningAvailable(36))
        assertEquals(
            WifiDirectOwnerVisibilityStrategy.PEER_DISCOVERY,
            AndroidWifiDirectEndpointResolver.ownerVisibilityStrategy(32),
        )
        assertEquals(
            WifiDirectOwnerVisibilityStrategy.PLATFORM_LISTEN,
            AndroidWifiDirectEndpointResolver.ownerVisibilityStrategy(36),
        )
    }

    @Test
    fun `owner listen failure retries remain inside the active bounded window`() {
        assertTrue(
            AndroidWifiDirectEndpointResolver.ownerListeningAttemptAllowed(
                sdkInt = 36,
                windowActive = true,
                startRequested = false,
                completedAttempts = 1,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerListeningAttemptAllowed(
                sdkInt = 32,
                windowActive = true,
                startRequested = false,
                completedAttempts = 1,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerListeningAttemptAllowed(
                sdkInt = 36,
                windowActive = false,
                startRequested = false,
                completedAttempts = 1,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerListeningAttemptAllowed(
                sdkInt = 36,
                windowActive = true,
                startRequested = true,
                completedAttempts = 1,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerListeningAttemptAllowed(
                sdkInt = 36,
                windowActive = true,
                startRequested = false,
                completedAttempts = AndroidWifiDirectEndpointResolver.MAXIMUM_OWNER_LISTEN_START_ATTEMPTS,
            ),
        )
    }

    @Test
    fun `owner listen cleanup follows a dispatched supported start`() {
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerListeningCleanupRequired(
                sdkInt = 32,
                startRequested = true,
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerListeningCleanupRequired(
                sdkInt = 36,
                startRequested = false,
            ),
        )
        assertTrue(
            AndroidWifiDirectEndpointResolver.ownerListeningCleanupRequired(
                sdkInt = 36,
                startRequested = true,
            ),
        )
    }

    @Test
    fun `empty owner visibility restarts only after a completed timeout window`() {
        assertTrue(
            AndroidWifiDirectEndpointResolver.ownerVisibilityRestartAllowed(
                closed = false,
                role = WifiDirectNodeRole.ANDROID_GROUP_OWNER,
                phase = WifiDirectPhase.GROUP_READY,
                clientObserved = false,
                reason = "timeout",
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerVisibilityRestartAllowed(
                closed = false,
                role = WifiDirectNodeRole.ANDROID_GROUP_OWNER,
                phase = WifiDirectPhase.GROUP_READY,
                clientObserved = true,
                reason = "timeout",
            ),
        )
        assertFalse(
            AndroidWifiDirectEndpointResolver.ownerVisibilityRestartAllowed(
                closed = false,
                role = WifiDirectNodeRole.ANDROID_GROUP_OWNER,
                phase = WifiDirectPhase.GROUP_READY,
                clientObserved = false,
                reason = "client_joined",
            ),
        )
        assertTrue(
            AndroidWifiDirectEndpointResolver.ownerVisibilityRestartAllowed(
                closed = false,
                role = WifiDirectNodeRole.ANDROID_GROUP_OWNER,
                phase = WifiDirectPhase.DISCOVERING,
                clientObserved = false,
                reason = "timeout",
            ),
        )
        assertTrue(
            AndroidWifiDirectEndpointResolver.OWNER_VISIBILITY_PAUSE_MILLIS in 5_000L..60_000L,
        )
    }
}
