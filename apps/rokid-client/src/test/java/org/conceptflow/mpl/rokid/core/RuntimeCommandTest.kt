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
}
