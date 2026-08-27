// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid

import android.content.Context

class RokidInputCommandGateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean): Boolean = preferences.edit()
        .putBoolean(KEY_ENABLED, enabled)
        .commit()

    private companion object {
        const val PREFERENCES_NAME = "rokid_input_command_gate"
        const val KEY_ENABLED = "enabled"
    }
}
