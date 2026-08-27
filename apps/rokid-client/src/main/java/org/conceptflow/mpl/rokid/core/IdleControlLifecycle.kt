// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

enum class RokidForegroundMode {
    STOPPED,
    IDLE,
    CAMERA_STARTING,
    CAMERA_ACTIVE,
    STOPPING,
}

data class IdleLifecycleTransition(
    val showIdleForeground: Boolean = false,
    val showCameraStartingForeground: Boolean = false,
    val showCameraActiveForeground: Boolean = false,
    val stopSources: Boolean = false,
    val requestLiveTerminal: Boolean = false,
    val stopService: Boolean = false,
    val persistenceSucceeded: Boolean = true,
)

/** Deterministic foreground/source ordering shared by Android lifecycle entry points. */
class IdleControlLifecycle {
    var mode: RokidForegroundMode = RokidForegroundMode.STOPPED
        private set

    fun onIdleEnabled(): IdleLifecycleTransition {
        if (mode == RokidForegroundMode.STOPPING) return IdleLifecycleTransition()
        if (mode == RokidForegroundMode.CAMERA_STARTING || mode == RokidForegroundMode.CAMERA_ACTIVE) {
            return IdleLifecycleTransition()
        }
        mode = RokidForegroundMode.IDLE
        return IdleLifecycleTransition(showIdleForeground = true)
    }

    /** Must be applied before opening either the live network connection or camera source. */
    fun beforeLiveCapture(): IdleLifecycleTransition {
        if (mode == RokidForegroundMode.CAMERA_STARTING ||
            mode == RokidForegroundMode.CAMERA_ACTIVE ||
            mode == RokidForegroundMode.STOPPING
        ) {
            return IdleLifecycleTransition()
        }
        mode = RokidForegroundMode.CAMERA_STARTING
        return IdleLifecycleTransition(showCameraStartingForeground = true)
    }

    fun onLiveProducersStarted(): IdleLifecycleTransition {
        if (mode != RokidForegroundMode.CAMERA_STARTING) return IdleLifecycleTransition()
        mode = RokidForegroundMode.CAMERA_ACTIVE
        return IdleLifecycleTransition(showCameraActiveForeground = true)
    }

    fun onLiveReconnecting(): IdleLifecycleTransition {
        if (mode != RokidForegroundMode.CAMERA_ACTIVE && mode != RokidForegroundMode.CAMERA_STARTING) {
            return IdleLifecycleTransition()
        }
        mode = RokidForegroundMode.IDLE
        return IdleLifecycleTransition(showIdleForeground = true)
    }

    fun disable(persistenceSucceeded: Boolean, liveCaptureActive: Boolean): IdleLifecycleTransition {
        if (mode == RokidForegroundMode.STOPPING) return IdleLifecycleTransition()
        mode = RokidForegroundMode.STOPPING
        return IdleLifecycleTransition(
            stopSources = true,
            requestLiveTerminal = liveCaptureActive,
            stopService = !liveCaptureActive,
            persistenceSucceeded = persistenceSucceeded,
        )
    }

    fun onLiveTerminal(persistedEnabled: Boolean): IdleLifecycleTransition {
        if (mode == RokidForegroundMode.STOPPING) {
            mode = RokidForegroundMode.STOPPED
            return IdleLifecycleTransition(stopService = true)
        }
        mode = if (persistedEnabled) RokidForegroundMode.IDLE else RokidForegroundMode.STOPPED
        return if (persistedEnabled) {
            IdleLifecycleTransition(showIdleForeground = true)
        } else {
            IdleLifecycleTransition(stopService = true)
        }
    }

    fun onShutdownWatchdog(): IdleLifecycleTransition {
        if (mode != RokidForegroundMode.STOPPING) return IdleLifecycleTransition()
        mode = RokidForegroundMode.STOPPED
        return IdleLifecycleTransition(stopService = true)
    }
}
