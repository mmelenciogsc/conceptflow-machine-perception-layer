// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import android.media.AudioTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformCueOutputsTest {
    @Test
    fun staticTrackAcceptsInitializedAndNoStaticDataStates() {
        assertTrue(audioTrackStateCanAcceptStaticData(AudioTrack.STATE_INITIALIZED))
        assertTrue(audioTrackStateCanAcceptStaticData(AudioTrack.STATE_NO_STATIC_DATA))
        assertFalse(audioTrackStateCanAcceptStaticData(AudioTrack.STATE_UNINITIALIZED))
    }
}
