// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid

import android.content.Context
import org.conceptflow.mpl.rokid.core.BootBrandLinePolicy
import org.conceptflow.mpl.rokid.core.ProductLineRepeatPolicy

class BrandedAudioStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    /** Atomically reserves this rolling window before speech begins. */
    @Synchronized
    fun claimProductLine(nowEpochMillis: Long): Boolean {
        val lastClaim = if (preferences.contains(KEY_LAST_PRODUCT_LINE_EPOCH_MILLIS)) {
            preferences.getLong(KEY_LAST_PRODUCT_LINE_EPOCH_MILLIS, 0L)
        } else {
            null
        }
        if (!ProductLineRepeatPolicy.mayClaim(lastClaim, nowEpochMillis)) return false
        return preferences.edit()
            .putLong(KEY_LAST_PRODUCT_LINE_EPOCH_MILLIS, nowEpochMillis)
            .commit()
    }

    /** Null boot counts intentionally fall back to the caller's process gate. */
    @Synchronized
    fun claimBootBrandLine(currentBootCount: Int?): Boolean {
        val lastClaim = if (preferences.contains(KEY_LAST_BRAND_BOOT_COUNT)) {
            preferences.getInt(KEY_LAST_BRAND_BOOT_COUNT, -1)
        } else {
            null
        }
        if (!BootBrandLinePolicy.mayClaim(lastClaim, currentBootCount)) return false
        if (currentBootCount == null) return true
        return preferences.edit()
            .putInt(KEY_LAST_BRAND_BOOT_COUNT, currentBootCount)
            .commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "rokid_branded_audio_state_v1"
        const val KEY_LAST_PRODUCT_LINE_EPOCH_MILLIS = "last_product_line_epoch_millis"
        const val KEY_LAST_BRAND_BOOT_COUNT = "last_brand_boot_count"
    }
}
