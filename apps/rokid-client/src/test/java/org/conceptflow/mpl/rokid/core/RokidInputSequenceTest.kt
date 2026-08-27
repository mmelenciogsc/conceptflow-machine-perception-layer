// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RokidInputSequenceTest {
    private val hardwarePolicy = ExactRokidInputHardwarePolicy(
        expectedDeviceName = RokidCandidateInputProfile.DEVICE_NAME,
        expectedSource = RokidCandidateInputProfile.SOURCE_KEYBOARD,
        scanCodeByKey = RokidCandidateInputProfile.scanCodeByKey,
    )
    private val sequence = RokidInputSequenceStateMachine(hardwarePolicy)

    @Test
    fun forwardSwipeThenDoubleTapEnablesNode() {
        assertEquals(
            RokidLocalControlCommand.ENABLE_NODE,
            gesture(RokidInputKey.SWIPE_FORWARD, RokidInputKey.DOUBLE_TAP, nodeActive = false),
        )
    }

    @Test
    fun backwardSwipeThenDoubleTapDisablesNode() {
        assertEquals(
            RokidLocalControlCommand.DISABLE_NODE,
            gesture(RokidInputKey.SWIPE_BACKWARD, RokidInputKey.DOUBLE_TAP, nodeActive = true),
        )
    }

    @Test
    fun activeForwardAndBackwardSingleTapProduceMicrophoneIntents() {
        assertEquals(
            RokidLocalControlCommand.MICROPHONE_START_INTENT,
            gesture(RokidInputKey.SWIPE_FORWARD, RokidInputKey.SINGLE_TAP, nodeActive = true),
        )
        assertEquals(
            RokidLocalControlCommand.MICROPHONE_STOP_INTENT,
            gesture(
                RokidInputKey.SWIPE_BACKWARD,
                RokidInputKey.SINGLE_TAP,
                nodeActive = true,
                offset = 1_000L,
            ),
        )
    }

    @Test
    fun inactiveSingleTapSequencesFailClosed() {
        assertNull(gesture(RokidInputKey.SWIPE_FORWARD, RokidInputKey.SINGLE_TAP, nodeActive = false))
        assertNull(
            gesture(
                RokidInputKey.SWIPE_BACKWARD,
                RokidInputKey.SINGLE_TAP,
                nodeActive = false,
                offset = 1_000L,
            ),
        )
    }

    @Test
    fun repeatedSameDirectionVolumeStepsCollapseWithoutExtendingDeadline() {
        press(RokidInputKey.PREAMBLE, 0L, 20L)
        press(RokidInputKey.SWIPE_FORWARD, 40L, 60L)
        press(RokidInputKey.SWIPE_FORWARD, 100L, 120L)
        press(RokidInputKey.SWIPE_FORWARD, 180L, 200L)
        press(RokidInputKey.PREAMBLE, 220L, 240L)
        assertEquals(
            RokidLocalControlCommand.ENABLE_NODE,
            press(RokidInputKey.PREAMBLE, 250L, 260L),
        )
        assertNull(press(RokidInputKey.DOUBLE_TAP, 270L, 290L))
    }

    @Test
    fun oppositeOrLateRepeatedSwipeResetsSequence() {
        press(RokidInputKey.PREAMBLE, 0L, 10L)
        press(RokidInputKey.SWIPE_FORWARD, 20L, 30L)
        press(RokidInputKey.SWIPE_BACKWARD, 40L, 50L)
        press(RokidInputKey.PREAMBLE, 60L, 70L)
        assertNull(press(RokidInputKey.DOUBLE_TAP, 80L, 90L))

        press(RokidInputKey.PREAMBLE, 200L, 210L)
        press(RokidInputKey.SWIPE_FORWARD, 220L, 230L)
        press(RokidInputKey.SWIPE_FORWARD, 700L, 710L)
        press(RokidInputKey.PREAMBLE, 720L, 730L)
        assertNull(press(RokidInputKey.DOUBLE_TAP, 740L, 750L))
    }

    @Test
    fun missingOrExpiredPreamblesDoNotComplete() {
        press(RokidInputKey.SWIPE_FORWARD, 0L, 20L)
        press(RokidInputKey.PREAMBLE, 40L, 60L)
        assertNull(press(RokidInputKey.DOUBLE_TAP, 80L, 100L))

        press(RokidInputKey.PREAMBLE, 200L, 220L)
        press(RokidInputKey.SWIPE_FORWARD, 1_200L, 1_220L)
        press(RokidInputKey.PREAMBLE, 1_240L, 1_260L)
        assertNull(press(RokidInputKey.DOUBLE_TAP, 1_280L, 1_300L))
    }

    @Test
    fun expiredFollowupWindowDoesNotComplete() {
        press(RokidInputKey.PREAMBLE, 0L, 20L)
        press(RokidInputKey.SWIPE_FORWARD, 40L, 60L)
        press(RokidInputKey.PREAMBLE, 1_480L, 1_500L)
        press(RokidInputKey.PREAMBLE, 1_520L, 1_540L)

        assertNull(press(RokidInputKey.DOUBLE_TAP, 1_700L, 1_720L))
    }

    @Test
    fun doubleTapCompletesOnTwoPostSwipePreamblesBecauseYodaOsConsumesTerminator() {
        prepareForwardSwipe()
        assertNull(press(RokidInputKey.DOUBLE_TAP, 100L, 120L))

        press(RokidInputKey.PREAMBLE, 200L, 220L)
        press(RokidInputKey.SWIPE_FORWARD, 240L, 260L)
        press(RokidInputKey.PREAMBLE, 280L, 300L)
        assertEquals(
            RokidLocalControlCommand.ENABLE_NODE,
            press(RokidInputKey.PREAMBLE, 320L, 340L),
        )
        assertNull(press(RokidInputKey.SINGLE_TAP, 360L, 380L, nodeActive = true))
    }

    @Test
    fun longProg1PressNeverProducesACommand() {
        press(RokidInputKey.PREAMBLE, 0L, 20L)
        press(RokidInputKey.SWIPE_FORWARD, 40L, 60L)
        press(RokidInputKey.PREAMBLE, 80L, 100L)

        assertNull(press(RokidInputKey.SINGLE_TAP, 120L, 1_000L, nodeActive = true))
    }

    @Test
    fun repeatedDownMismatchedUpAndOtherActionsReset() {
        prepareForwardSwipe()
        assertNull(
            sequence.observe(
                event(
                    RokidInputKey.SINGLE_TAP,
                    RokidInputAction.DOWN,
                    100L,
                    repeatCount = 1,
                ),
                nodeActive = true,
            ),
        )
        assertNull(press(RokidInputKey.SINGLE_TAP, 120L, 140L, nodeActive = true))

        prepareForwardSwipe(200L)
        sequence.observe(
            event(RokidInputKey.SINGLE_TAP, RokidInputAction.DOWN, 300L),
            nodeActive = true,
        )
        assertNull(
            sequence.observe(
                event(RokidInputKey.DOUBLE_TAP, RokidInputAction.UP, 320L),
                nodeActive = true,
            ),
        )

        prepareForwardSwipe(400L)
        assertNull(
            sequence.observe(
                event(RokidInputKey.SINGLE_TAP, RokidInputAction.OTHER, 500L),
                nodeActive = true,
            ),
        )
        assertNull(press(RokidInputKey.SINGLE_TAP, 520L, 540L, nodeActive = true))
    }

    @Test
    fun unrelatedKeyAndNonMonotonicTimeReset() {
        prepareForwardSwipe()
        press(RokidInputKey.UNRELATED, 100L, 120L)
        assertNull(press(RokidInputKey.SINGLE_TAP, 140L, 160L, nodeActive = true))

        prepareForwardSwipe(200L)
        assertNull(
            sequence.observe(
                event(RokidInputKey.PREAMBLE, RokidInputAction.DOWN, 100L),
                nodeActive = true,
            ),
        )
        assertNull(press(RokidInputKey.SINGLE_TAP, 300L, 320L, nodeActive = true))
    }

    @Test
    fun hardwareGateChecksNameVirtualStateSourcesAndScanCodeButNotRuntimeDeviceId() {
        val accepted = event(RokidInputKey.PREAMBLE, RokidInputAction.DOWN, 0L)
        assertTrue(hardwarePolicy.accepts(accepted))
        assertTrue(
            hardwarePolicy.accepts(
                accepted.copy(device = accepted.device.copy(deviceId = 99)),
            ),
        )
        assertFalse(hardwarePolicy.accepts(accepted.copy(scanCode = 999)))
        assertFalse(
            hardwarePolicy.accepts(
                accepted.copy(device = accepted.device.copy(name = "other")),
            ),
        )
        assertFalse(
            hardwarePolicy.accepts(
                accepted.copy(device = accepted.device.copy(isVirtual = true)),
            ),
        )
        assertFalse(
            hardwarePolicy.accepts(
                accepted.copy(device = accepted.device.copy(source = 0)),
            ),
        )
        assertFalse(
            hardwarePolicy.accepts(
                accepted.copy(device = accepted.device.copy(deviceSources = 0)),
            ),
        )
    }

    @Test
    fun candidateAndroidKeycodesMatchTheApi32GenericKeylayoutTranslation() {
        assertEquals(
            mapOf(
                83 to RokidInputKey.PREAMBLE,
                24 to RokidInputKey.SWIPE_FORWARD,
                25 to RokidInputKey.SWIPE_BACKWARD,
                186 to RokidInputKey.SINGLE_TAP,
                185 to RokidInputKey.DOUBLE_TAP,
            ),
            RokidCandidateInputProfile.keyByAndroidKeyCode,
        )
    }

    @Test
    fun twoFingerLongPressProbeRequiresTheExactUnmappedSettingsKeyAndTouchController() {
        assertTrue(
            RokidCandidateInputProfile.isTwoFingerLongPressProbe(
                androidKeyCode = 176,
                scanCode = 149,
                device = TEST_PSOC_DEVICE,
            ),
        )
        assertFalse(
            RokidCandidateInputProfile.isTwoFingerLongPressProbe(
                androidKeyCode = 176,
                scanCode = 148,
                device = TEST_PSOC_DEVICE,
            ),
        )
        assertFalse(
            RokidCandidateInputProfile.isTwoFingerLongPressProbe(
                androidKeyCode = 176,
                scanCode = 149,
                device = TEST_PSOC_DEVICE.copy(name = "other"),
            ),
        )
        assertFalse(
            RokidCandidateInputProfile.isTwoFingerLongPressProbe(
                androidKeyCode = 186,
                scanCode = 149,
                device = TEST_PSOC_DEVICE,
            ),
        )
    }

    @Test
    fun canceledLongPressAndMidSequenceDeviceChangesReset() {
        prepareForwardSwipe()
        assertNull(
            sequence.observe(
                event(
                    RokidInputKey.SINGLE_TAP,
                    RokidInputAction.DOWN,
                    100L,
                    canceled = true,
                ),
                nodeActive = true,
            ),
        )
        assertNull(press(RokidInputKey.SINGLE_TAP, 120L, 140L, nodeActive = true))

        prepareForwardSwipe(200L)
        assertNull(
            sequence.observe(
                event(
                    RokidInputKey.SINGLE_TAP,
                    RokidInputAction.DOWN,
                    300L,
                    longPress = true,
                ),
                nodeActive = true,
            ),
        )
        assertNull(press(RokidInputKey.SINGLE_TAP, 320L, 340L, nodeActive = true))

        press(RokidInputKey.PREAMBLE, 400L, 420L)
        assertNull(
            sequence.observe(
                event(RokidInputKey.SWIPE_FORWARD, RokidInputAction.DOWN, 440L).copy(
                    device = TEST_PSOC_DEVICE.copy(deviceId = 4),
                ),
                nodeActive = true,
            ),
        )
        assertNull(press(RokidInputKey.SWIPE_FORWARD, 460L, 480L))
        assertNull(press(RokidInputKey.PREAMBLE, 500L, 520L))
        assertNull(press(RokidInputKey.SINGLE_TAP, 540L, 560L, nodeActive = true))
    }

    @Test
    fun internalActionsAreUniqueAndResolveExactly() {
        val actions = RokidLocalControlCommand.entries.map(RokidLocalControlCommand::action)

        assertEquals(actions.size, actions.toSet().size)
        assertTrue(actions.all { it.startsWith("org.conceptflow.mpl.rokid.internal.") })
        RokidLocalControlCommand.entries.forEach {
            assertEquals(it, RokidLocalControlCommand.fromAction(it.action))
        }
        assertNull(RokidLocalControlCommand.fromAction(null))
        assertNull(RokidLocalControlCommand.fromAction(""))
    }

    @Test
    fun commandDispatchIsObserveOnlyByDefaultAndRequiresExplicitOptIn() {
        var dispatched: RokidLocalControlCommand? = null

        assertFalse(
            RokidInputDispatchPolicy.dispatchIfEnabled(
                commandsEnabled = false,
                command = RokidLocalControlCommand.ENABLE_NODE,
            ) { dispatched = it },
        )
        assertNull(dispatched)
        assertTrue(
            RokidInputDispatchPolicy.dispatchIfEnabled(
                commandsEnabled = true,
                command = RokidLocalControlCommand.ENABLE_NODE,
            ) { dispatched = it },
        )
        assertEquals(RokidLocalControlCommand.ENABLE_NODE, dispatched)
    }

    private fun gesture(
        swipe: RokidInputKey,
        followup: RokidInputKey,
        nodeActive: Boolean,
        offset: Long = 0L,
    ): RokidLocalControlCommand? {
        press(RokidInputKey.PREAMBLE, offset, offset + 20L, nodeActive)
        press(swipe, offset + 40L, offset + 60L, nodeActive)
        press(RokidInputKey.PREAMBLE, offset + 80L, offset + 100L, nodeActive)
        if (followup == RokidInputKey.DOUBLE_TAP) {
            val command = press(RokidInputKey.PREAMBLE, offset + 120L, offset + 140L, nodeActive)
            assertNull(press(followup, offset + 160L, offset + 180L, nodeActive))
            return command
        }
        return press(followup, offset + 120L, offset + 140L, nodeActive)
    }

    private fun prepareForwardSwipe(offset: Long = 0L) {
        press(RokidInputKey.PREAMBLE, offset, offset + 20L)
        press(RokidInputKey.SWIPE_FORWARD, offset + 40L, offset + 60L)
        press(RokidInputKey.PREAMBLE, offset + 80L, offset + 90L)
    }

    private fun press(
        key: RokidInputKey,
        downTimeMillis: Long,
        upTimeMillis: Long,
        nodeActive: Boolean = false,
    ): RokidLocalControlCommand? {
        assertNull(
            sequence.observe(
                event(key, RokidInputAction.DOWN, downTimeMillis),
                nodeActive,
            ),
        )
        return sequence.observe(
            event(key, RokidInputAction.UP, upTimeMillis),
            nodeActive,
        )
    }

    private fun event(
        key: RokidInputKey,
        action: RokidInputAction,
        eventTimeMillis: Long,
        repeatCount: Int = 0,
        canceled: Boolean = false,
        longPress: Boolean = false,
    ): RokidInputEvent = RokidInputEvent(
        key = key,
        action = action,
        eventTimeMillis = eventTimeMillis,
        repeatCount = repeatCount,
        canceled = canceled,
        longPress = longPress,
        scanCode = RokidCandidateInputProfile.scanCodeByKey[key] ?: 0,
        device = TEST_PSOC_DEVICE,
    )

    private companion object {
        val TEST_PSOC_DEVICE = RokidInputDeviceIdentity(
            deviceId = 3,
            source = RokidCandidateInputProfile.SOURCE_KEYBOARD,
            deviceSources = RokidCandidateInputProfile.SOURCE_KEYBOARD,
            name = RokidCandidateInputProfile.DEVICE_NAME,
            isVirtual = false,
            vendorId = 0,
            productId = 0,
        )
    }
}
