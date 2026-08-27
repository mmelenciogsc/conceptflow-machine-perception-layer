// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid

import android.content.Context

/** Explicit diagnostic fallback. Normal production transport is RAM-only. */
class LegacySensorSpoolStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean): Boolean =
        preferences.edit().putBoolean(KEY_ENABLED, enabled).commit() && isEnabled() == enabled

    private companion object {
        const val FILE_NAME = "legacy_sensor_spool"
        const val KEY_ENABLED = "enabled"
    }
}
