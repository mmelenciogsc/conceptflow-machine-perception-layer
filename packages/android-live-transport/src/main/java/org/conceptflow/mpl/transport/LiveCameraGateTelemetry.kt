// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

/**
 * Privacy-safe aggregate state from the existing Rokid camera gate.
 *
 * This is observation only: updating it cannot alter capture cadence or gate decisions.
 */
data class LiveCameraGateTelemetry(
    val framesAnalyzed: Long = 0L,
    val framesEmitted: Long = 0L,
    val relaxedTierSamples: Long = 0L,
    val motionTierSamples: Long = 0L,
    val framesDroppedDark: Long = 0L,
    val framesDroppedBlurry: Long = 0L,
    val framesDroppedCadence: Long = 0L,
    val currentTargetFramesPerSecond: Int = 0,
) {
    init {
        require(
            listOf(
                framesAnalyzed,
                framesEmitted,
                relaxedTierSamples,
                motionTierSamples,
                framesDroppedDark,
                framesDroppedBlurry,
                framesDroppedCadence,
            ).all { it >= 0L },
        ) { "camera gate counters cannot be negative" }
        require(Math.addExact(relaxedTierSamples, motionTierSamples) == framesAnalyzed) {
            "camera tier accounting must equal analyzed frames"
        }
        require(
            listOf(
                framesEmitted,
                framesDroppedDark,
                framesDroppedBlurry,
                framesDroppedCadence,
            ).fold(0L, Math::addExact) == framesAnalyzed,
        ) { "camera gate outcomes must equal analyzed frames" }
        require(currentTargetFramesPerSecond == 0 || currentTargetFramesPerSecond in 1..10) {
            "camera target FPS is outside its bound"
        }
    }
}
