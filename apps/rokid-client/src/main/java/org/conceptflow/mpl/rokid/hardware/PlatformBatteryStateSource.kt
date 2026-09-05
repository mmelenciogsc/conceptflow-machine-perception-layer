// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.hardware

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.conceptflow.mpl.transport.LivePowerTelemetry
import org.conceptflow.mpl.v1.BatteryChargeState
import org.conceptflow.mpl.rokid.core.RokidBatteryState
import org.conceptflow.mpl.rokid.core.RokidBatteryStatus

/** Reads Android's sticky battery broadcast without registering a long-lived receiver. */
class PlatformBatteryStateSource(context: Context) {
    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)

    fun snapshot(): RokidBatteryState {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.percentage()
        val status = (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1).toBatteryStatus()
        val health = (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1).toHealthGood()
        val voltage = intent?.optionalNonNegative(BatteryManager.EXTRA_VOLTAGE)?.times(1_000L)
        val temperature = intent?.optionalInt(BatteryManager.EXTRA_TEMPERATURE)
        val externalPower = intent?.let { it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 }
        return RokidBatteryState(
            levelPercent = level,
            status = status,
            healthGood = health,
            voltageMicrovolts = voltage,
            currentMicroamps = batteryManager.intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            averageCurrentMicroamps = batteryManager.intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            chargeCounterMicroampHours = batteryManager.intProperty(
                BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER,
            )?.takeIf { it >= 0L },
            temperatureDeciCelsius = temperature,
            externalPowerConnected = externalPower,
            energyNanowattHours = batteryManager.longProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
                ?.takeIf { it >= 0L },
        )
    }

    fun telemetrySnapshot(): LivePowerTelemetry = snapshot().toLivePowerTelemetry()

    private fun Intent.percentage(): Int? {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return ((level.toLong() * 100L) / scale.toLong()).toInt().coerceIn(0, 100)
    }

    private fun Intent.optionalInt(name: String): Int? =
        getIntExtra(name, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }

    private fun Intent.optionalNonNegative(name: String): Long? =
        optionalInt(name)?.takeIf { it >= 0 }?.toLong()

    private fun BatteryManager?.intProperty(property: Int): Long? = this?.let {
        runCatching { getIntProperty(property) }.getOrNull()
            ?.takeUnless { value -> value == Int.MIN_VALUE }
            ?.toLong()
    }

    private fun BatteryManager?.longProperty(property: Int): Long? = this?.let {
        runCatching { getLongProperty(property) }.getOrNull()
            ?.takeUnless { value -> value == Long.MIN_VALUE }
    }

    private fun Int.toBatteryStatus(): RokidBatteryStatus = when (this) {
        BatteryManager.BATTERY_STATUS_CHARGING -> RokidBatteryStatus.CHARGING
        BatteryManager.BATTERY_STATUS_DISCHARGING -> RokidBatteryStatus.DISCHARGING
        BatteryManager.BATTERY_STATUS_FULL -> RokidBatteryStatus.FULL
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> RokidBatteryStatus.NOT_CHARGING
        else -> RokidBatteryStatus.UNKNOWN
    }

    private fun Int.toHealthGood(): Boolean? = when (this) {
        BatteryManager.BATTERY_HEALTH_GOOD -> true
        BatteryManager.BATTERY_HEALTH_UNKNOWN, -1 -> null
        else -> false
    }

    private fun RokidBatteryState.toLivePowerTelemetry(): LivePowerTelemetry = LivePowerTelemetry(
        levelPercent = levelPercent,
        chargeState = when (status) {
            RokidBatteryStatus.CHARGING -> BatteryChargeState.BATTERY_CHARGE_STATE_CHARGING
            RokidBatteryStatus.DISCHARGING -> BatteryChargeState.BATTERY_CHARGE_STATE_DISCHARGING
            RokidBatteryStatus.FULL -> BatteryChargeState.BATTERY_CHARGE_STATE_FULL
            RokidBatteryStatus.NOT_CHARGING -> BatteryChargeState.BATTERY_CHARGE_STATE_NOT_CHARGING
            RokidBatteryStatus.UNKNOWN -> BatteryChargeState.BATTERY_CHARGE_STATE_UNSPECIFIED
        },
        healthGood = healthGood,
        voltageMicrovolts = voltageMicrovolts,
        currentMicroamps = currentMicroamps,
        averageCurrentMicroamps = averageCurrentMicroamps,
        chargeCounterMicroampHours = chargeCounterMicroampHours,
        temperatureDeciCelsius = temperatureDeciCelsius,
        externalPowerConnected = externalPowerConnected,
        energyNanowattHours = energyNanowattHours,
    )
}
