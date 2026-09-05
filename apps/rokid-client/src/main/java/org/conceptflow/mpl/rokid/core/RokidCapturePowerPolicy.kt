// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid.core

/** Privacy-safe battery facts used to protect an untethered sensor epoch. */
data class RokidBatteryState(
    val levelPercent: Int?,
    val status: RokidBatteryStatus,
    val healthGood: Boolean?,
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
    }
}

enum class RokidBatteryStatus {
    CHARGING,
    DISCHARGING,
    FULL,
    NOT_CHARGING,
    UNKNOWN,
}

enum class RokidCapturePowerDecision {
    ALLOW,
    REJECT_START_LOW_BATTERY,
    STOP_ACTIVE_LOW_BATTERY,
    REJECT_UNHEALTHY_BATTERY,
}

/**
 * Fail-safe boundary for the high-draw camera/radio path.
 *
 * Charging state alone is deliberately insufficient: the target data cable can report external
 * power while the battery continues to discharge under camera load. An explicit re-arm above the
 * start threshold is required after the guard disables idle control.
 */
class RokidCapturePowerPolicy(
    private val minimumStartPercent: Int = DEFAULT_MINIMUM_START_PERCENT,
    private val criticalStopPercent: Int = DEFAULT_CRITICAL_STOP_PERCENT,
) {
    init {
        require(criticalStopPercent in 1..99)
        require(minimumStartPercent in (criticalStopPercent + 1)..100)
    }

    fun beforeStart(state: RokidBatteryState): RokidCapturePowerDecision = when {
        state.healthGood == false -> RokidCapturePowerDecision.REJECT_UNHEALTHY_BATTERY
        state.levelPercent != null && state.levelPercent < minimumStartPercent ->
            RokidCapturePowerDecision.REJECT_START_LOW_BATTERY
        else -> RokidCapturePowerDecision.ALLOW
    }

    fun whileActive(state: RokidBatteryState): RokidCapturePowerDecision = when {
        state.healthGood == false -> RokidCapturePowerDecision.REJECT_UNHEALTHY_BATTERY
        state.levelPercent != null && state.levelPercent <= criticalStopPercent ->
            RokidCapturePowerDecision.STOP_ACTIVE_LOW_BATTERY
        else -> RokidCapturePowerDecision.ALLOW
    }

    companion object {
        const val DEFAULT_MINIMUM_START_PERCENT = 50
        const val DEFAULT_CRITICAL_STOP_PERCENT = 25
        const val ACTIVE_SAMPLE_INTERVAL_MILLIS = 5_000L
    }
}
