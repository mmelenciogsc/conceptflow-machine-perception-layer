// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.conceptflow.mpl.v1.BatteryChargeState

/** Privacy-safe, content-free power sample supplied by the glasses platform. */
data class LivePowerTelemetry(
    val levelPercent: Int? = null,
    val chargeState: BatteryChargeState = BatteryChargeState.BATTERY_CHARGE_STATE_UNSPECIFIED,
    val healthGood: Boolean? = null,
    val voltageMicrovolts: Long? = null,
    val currentMicroamps: Long? = null,
    val averageCurrentMicroamps: Long? = null,
    val chargeCounterMicroampHours: Long? = null,
    val temperatureDeciCelsius: Int? = null,
    val externalPowerConnected: Boolean? = null,
    val energyNanowattHours: Long? = null,
) {
    init {
        require(levelPercent == null || levelPercent in 0..100)
        require(voltageMicrovolts == null || voltageMicrovolts in 0L..MAX_VOLTAGE_MICROVOLTS)
        require(currentMicroamps == null || currentMicroamps in -MAX_CURRENT_MICROAMPS..MAX_CURRENT_MICROAMPS)
        require(
            averageCurrentMicroamps == null ||
                averageCurrentMicroamps in -MAX_CURRENT_MICROAMPS..MAX_CURRENT_MICROAMPS,
        )
        require(chargeCounterMicroampHours == null || chargeCounterMicroampHours >= 0L)
        require(temperatureDeciCelsius == null || temperatureDeciCelsius in -500..1_000)
        require(energyNanowattHours == null || energyNanowattHours >= 0L)
    }

    fun hasMeasurement(): Boolean = levelPercent != null ||
        chargeState != BatteryChargeState.BATTERY_CHARGE_STATE_UNSPECIFIED ||
        healthGood != null || voltageMicrovolts != null || currentMicroamps != null ||
        averageCurrentMicroamps != null || chargeCounterMicroampHours != null ||
        temperatureDeciCelsius != null || externalPowerConnected != null ||
        energyNanowattHours != null

    companion object {
        const val MAX_VOLTAGE_MICROVOLTS = 10_000_000L
        const val MAX_CURRENT_MICROAMPS = 10_000_000L
    }
}
