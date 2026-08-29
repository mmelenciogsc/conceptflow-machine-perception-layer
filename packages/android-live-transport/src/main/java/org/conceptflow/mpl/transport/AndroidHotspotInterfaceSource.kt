// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import android.annotation.TargetApi
import android.content.Context
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.Build
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Content-free view of Android's currently tethered Wi-Fi downstream interfaces. */
internal interface HotspotInterfaceSource : Closeable {
    fun wifiInterfaceNames(): Set<String>

    companion object {
        private const val PUBLIC_TETHERING_CALLBACK_API = 36

        fun create(context: Context, enabled: Boolean): HotspotInterfaceSource {
            if (!enabled || Build.VERSION.SDK_INT < PUBLIC_TETHERING_CALLBACK_API) return Empty
            return runCatching { AndroidHotspotInterfaceSource(context.applicationContext) }
                .getOrDefault(Empty)
        }
    }
}

private object Empty : HotspotInterfaceSource {
    override fun wifiInterfaceNames(): Set<String> = emptySet()
    override fun close() = Unit
}

@TargetApi(36)
private class AndroidHotspotInterfaceSource(context: Context) : HotspotInterfaceSource {
    private val manager = requireNotNull(context.getSystemService(TetheringManager::class.java))
    private val names = AtomicReference<Set<String>>(emptySet())
    private val registered = AtomicBoolean(false)
    private val callback = object : TetheringManager.TetheringEventCallback {
        override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
            names.set(
                interfaces.asSequence()
                    .filter { it.type == TetheringManager.TETHERING_WIFI }
                    .map(TetheringInterface::getInterface)
                    .filter(::validInterfaceName)
                    .toSet(),
            )
        }
    }

    init {
        manager.registerTetheringEventCallback(context.mainExecutor, callback)
        registered.set(true)
    }

    override fun wifiInterfaceNames(): Set<String> = names.get()

    override fun close() {
        if (registered.compareAndSet(true, false)) {
            runCatching { manager.unregisterTetheringEventCallback(callback) }
        }
        names.set(emptySet())
    }

    private companion object {
        private val INTERFACE_NAME = Regex("[A-Za-z0-9_.:-]{1,64}")
        fun validInterfaceName(value: String): Boolean = INTERFACE_NAME.matches(value)
    }
}
