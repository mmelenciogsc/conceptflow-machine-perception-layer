// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.androidhost

import org.conceptflow.mpl.host.LiveMachineVisionPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveLinkDebugMicrophoneControlTest {
    @Test
    fun `shell microphone control queues only for an authenticated streaming phase`() {
        assertEquals(
            DebugMicrophoneCommandAdmission.QUEUED,
            microphoneCommandAdmission(LiveMachineVisionPhase.STREAMING),
        )
        assertEquals(
            DebugMicrophoneCommandAdmission.NO_AUTHENTICATED_SESSION,
            microphoneCommandAdmission(LiveMachineVisionPhase.LISTENING),
        )
        assertEquals(
            DebugMicrophoneCommandAdmission.NO_AUTHENTICATED_SESSION,
            microphoneCommandAdmission(null),
        )
    }
}
