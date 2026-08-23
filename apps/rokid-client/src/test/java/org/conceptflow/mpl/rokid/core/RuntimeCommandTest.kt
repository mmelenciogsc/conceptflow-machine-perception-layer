// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCommandTest {
    @Test
    fun knownActionsResolveExactly() {
        RuntimeCommand.entries.forEach { command ->
            assertEquals(command, RuntimeCommand.fromAction(command.action))
        }
    }

    @Test
    fun missingOrUnrecognizedActionsFailClosed() {
        assertNull(RuntimeCommand.fromAction(null))
        assertNull(RuntimeCommand.fromAction(""))
        assertNull(RuntimeCommand.fromAction("org.conceptflow.mpl.rokid.action.START"))
    }

    @Test
    fun actionsArePackageScopedAndUnique() {
        val actions = RuntimeCommand.entries.map(RuntimeCommand::action)
        assertEquals(actions.size, actions.toSet().size)
        assertTrue(actions.all { it.startsWith("org.conceptflow.mpl.rokid.action.") })
    }

    @Test
    fun liveLinkProvisionStartAndStopActionsAreExplicit() {
        assertEquals(
            RuntimeCommand.INITIALIZE_LIVE_IDENTITY,
            RuntimeCommand.fromAction("org.conceptflow.mpl.rokid.action.INITIALIZE_LIVE_IDENTITY"),
        )
        assertEquals(
            RuntimeCommand.START_LIVE_LINK_TEST,
            RuntimeCommand.fromAction("org.conceptflow.mpl.rokid.action.START_LIVE_LINK_TEST"),
        )
        assertEquals(
            RuntimeCommand.STOP_LIVE_LINK_TEST,
            RuntimeCommand.fromAction("org.conceptflow.mpl.rokid.action.STOP_LIVE_LINK_TEST"),
        )
    }

    @Test
    fun liveLinkAdbCommandsFailClosedOutsideDebuggableBuilds() {
        val liveCommands = listOf(
            RuntimeCommand.INITIALIZE_LIVE_IDENTITY,
            RuntimeCommand.START_LIVE_LINK_TEST,
            RuntimeCommand.STOP_LIVE_LINK_TEST,
        )
        liveCommands.forEach { command ->
            assertTrue(RuntimeCommandAuthorization.isAllowed(command, debuggable = true))
            assertTrue(!RuntimeCommandAuthorization.isAllowed(command, debuggable = false))
        }
        assertTrue(RuntimeCommandAuthorization.isAllowed(RuntimeCommand.PLAY_LEFT_CUE, debuggable = false))
    }
}
