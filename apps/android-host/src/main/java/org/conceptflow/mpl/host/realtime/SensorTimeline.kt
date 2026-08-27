// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime

import java.util.ArrayDeque
import org.conceptflow.mpl.transport.LiveSensorDelivery
import org.conceptflow.mpl.v1.FramePayload
import org.conceptflow.mpl.v1.ImuReading
import org.conceptflow.mpl.v1.MicrophoneChunk
import org.conceptflow.mpl.v1.RokidTouchEvent

data class SensorTimelineLimits(
    val maximumImuSamples: Int = 512,
    val maximumAudioBlocks: Int = 16,
    val maximumTouchEvents: Int = 128,
    // Physical Rokid p99 request->image + gate/resize + RGB transform is about 0.90 s before
    // transport. Keep a bounded 1.20 s ingress window; the separate 1.50 s post-inference gate
    // still prevents an old frame from becoming current perception.
    val cameraFreshnessNanos: Long = 1_200_000_000L,
    val imuFreshnessNanos: Long = 250_000_000L,
) {
    init {
        require(maximumImuSamples in 8..4_096)
        require(maximumAudioBlocks in 1..128)
        require(maximumTouchEvents in 1..512)
        require(cameraFreshnessNanos > 0L && imuFreshnessNanos > 0L)
    }
}

data class TimedCameraFrame(
    val frame: FramePayload,
    val hostCaptureTimestampNs: Long,
    val receiveTimestampNs: Long,
    val clockUncertaintyNs: Long,
)

data class TimedImuReading(
    val reading: ImuReading,
    val hostPoseTimestampNs: Long,
    val clockUncertaintyNs: Long,
)

data class TimedAudioBlock(
    val block: MicrophoneChunk,
    val hostCaptureTimestampNs: Long,
    val clockUncertaintyNs: Long,
)

data class TimedTouchEvent(
    val event: RokidTouchEvent,
    val hostObservedTimestampNs: Long,
    val clockUncertaintyNs: Long,
)

data class SensorTimelineSnapshot(
    val camera: TimedCameraFrame?,
    val imu: List<TimedImuReading>,
    val audio: List<TimedAudioBlock>,
    val touch: List<TimedTouchEvent>,
    val staleCameraRejected: Long,
    val staleImuRejected: Long,
    val audioOverflow: Long,
    val touchOverflow: Long,
)

/**
 * One owner for cross-device normalized sensor time. It stores no wall-clock timestamps and all
 * collections are hard bounded. Camera is latest-state; audio/touch are ordered event streams.
 */
class SensorTimeline(private val limits: SensorTimelineLimits = SensorTimelineLimits()) {
    private var camera: TimedCameraFrame? = null
    private val imu = ArrayDeque<TimedImuReading>()
    private val audio = ArrayDeque<TimedAudioBlock>()
    private val touch = ArrayDeque<TimedTouchEvent>()
    private var staleCameraRejected = 0L
    private var staleImuRejected = 0L
    private var audioOverflow = 0L
    private var touchOverflow = 0L

    @Synchronized
    fun acceptCamera(
        frame: FramePayload,
        hostCaptureTimestampNs: Long,
        receiveTimestampNs: Long,
        clockUncertaintyNs: Long,
    ): Boolean {
        if (isStale(hostCaptureTimestampNs, receiveTimestampNs, limits.cameraFreshnessNanos)) {
            staleCameraRejected += 1L
            return false
        }
        val previous = camera
        if (previous != null && hostCaptureTimestampNs <= previous.hostCaptureTimestampNs) return false
        camera = TimedCameraFrame(
            frame,
            hostCaptureTimestampNs,
            receiveTimestampNs,
            clockUncertaintyNs,
        )
        return true
    }

    @Synchronized
    fun acceptImu(delivery: LiveSensorDelivery): List<TimedImuReading> {
        if (!delivery.sensor.hasImuBatch() ||
            delivery.normalizedImuSamples.size != delivery.sensor.imuBatch.samplesCount
        ) return emptyList()
        val newlyAccepted = ArrayList<TimedImuReading>(delivery.normalizedImuSamples.size)
        delivery.sensor.imuBatch.samplesList.forEachIndexed { index, reading ->
            val normalized = delivery.normalizedImuSamples[index].poseTimestamp
            if (isStale(normalized.hostMonotonicNs, delivery.receiveMonotonicNs, limits.imuFreshnessNanos)) {
                staleImuRejected += 1L
                return@forEachIndexed
            }
            if (imu.isNotEmpty() && normalized.hostMonotonicNs <= imu.last().hostPoseTimestampNs) {
                staleImuRejected += 1L
                return@forEachIndexed
            }
            if (imu.size == limits.maximumImuSamples) imu.removeFirst()
            val timed = TimedImuReading(reading, normalized.hostMonotonicNs, normalized.uncertaintyNs)
            imu.addLast(timed)
            newlyAccepted += timed
        }
        return newlyAccepted
    }

    @Synchronized
    fun acceptAudio(delivery: LiveSensorDelivery): Boolean {
        val normalized = delivery.normalizedMicrophoneCapture ?: return false
        if (audio.size == limits.maximumAudioBlocks) {
            audioOverflow += 1L
            return false
        }
        audio.addLast(TimedAudioBlock(delivery.sensor.microphoneChunk, normalized.hostMonotonicNs, normalized.uncertaintyNs))
        return true
    }

    @Synchronized
    fun acceptTouch(delivery: LiveSensorDelivery): TimedTouchEvent? {
        val normalized = delivery.normalizedTouchObserved ?: return null
        if (touch.size == limits.maximumTouchEvents) {
            touchOverflow += 1L
            return null
        }
        val timed = TimedTouchEvent(
            delivery.sensor.touchEvent,
            normalized.hostMonotonicNs,
            normalized.uncertaintyNs,
        )
        touch.addLast(timed)
        return timed
    }

    @Synchronized
    fun snapshotAround(hostTimestampNs: Long, imuWindowNanos: Long = 100_000_000L): SensorTimelineSnapshot {
        require(hostTimestampNs >= 0L && imuWindowNanos >= 0L)
        val selectedImu = imu.filter {
            kotlin.math.abs(it.hostPoseTimestampNs - hostTimestampNs) <= imuWindowNanos
        }
        return SensorTimelineSnapshot(
            camera,
            selectedImu,
            audio.toList(),
            touch.toList(),
            staleCameraRejected,
            staleImuRejected,
            audioOverflow,
            touchOverflow,
        )
    }

    @Synchronized fun drainAudio(): List<TimedAudioBlock> = audio.toList().also { audio.clear() }
    @Synchronized fun drainTouch(): List<TimedTouchEvent> = touch.toList().also { touch.clear() }

    @Synchronized
    fun reset() {
        camera = null
        imu.clear()
        audio.clear()
        touch.clear()
    }

    private fun isStale(sourceNs: Long, receiveNs: Long, freshnessNs: Long): Boolean =
        receiveNs >= sourceNs && receiveNs - sourceNs > freshnessNs
}
