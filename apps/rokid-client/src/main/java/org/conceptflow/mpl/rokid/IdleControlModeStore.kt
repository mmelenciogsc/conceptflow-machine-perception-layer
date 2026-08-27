// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid

import android.content.Context

/** App-private persistence for the user's explicit idle-control choice. */
internal class IdleControlModeStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun armedBootCount(): Int? = if (preferences.contains(KEY_ARMED_BOOT_COUNT)) {
        preferences.getInt(KEY_ARMED_BOOT_COUNT, -1).takeIf { it >= 0 }
    } else {
        null
    }

    /** Persists the choice and, only when known, the boot in which the user explicitly armed it. */
    fun setEnabled(enabled: Boolean, armedBootCount: Int? = null): Boolean {
        val editor = preferences.edit().putBoolean(KEY_ENABLED, enabled)
        if (enabled && armedBootCount != null && armedBootCount >= 0) {
            editor.putInt(KEY_ARMED_BOOT_COUNT, armedBootCount)
        } else {
            editor.remove(KEY_ARMED_BOOT_COUNT)
        }
        return editor.commit()
    }

    companion object {
        const val PREFERENCES_NAME = "rokid_idle_control"
        const val KEY_ENABLED = "enabled"
        const val KEY_ARMED_BOOT_COUNT = "armed_boot_count"
    }
}
