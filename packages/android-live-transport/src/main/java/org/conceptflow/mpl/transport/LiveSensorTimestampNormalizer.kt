// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.conceptflow.mpl.v1.LiveLinkEnvelope
import org.conceptflow.mpl.v1.LiveTransportLane

internal object LiveSensorTimestampNormalizer {
    fun normalize(
        envelope: LiveLinkEnvelope,
        state: LiveConnectionState,
        receiveNs: Long,
    ): LiveSensorDelivery {
        if (!envelope.hasSensor()) reject(LiveLinkDiagnosticCode.SENSOR_ENVELOPE_REJECTED)
        val sensor = envelope.sensor
        val sendStream = when (envelope.lane) {
            LiveTransportLane.LIVE_TRANSPORT_LANE_CAMERA -> RemoteClockStream.CAMERA_SEND
            LiveTransportLane.LIVE_TRANSPORT_LANE_REALTIME_CONTROL -> RemoteClockStream.REALTIME_CONTROL_SEND
            else -> reject(LiveLinkDiagnosticCode.SENSOR_LANE_REJECTED)
        }
        if (envelope.sentMonotonicTimestampNs <= 0L) {
            reject(LiveLinkDiagnosticCode.SENSOR_SEND_TIMESTAMP_REJECTED)
        }
        val normalizedSend = state.normalize(sendStream, envelope.sentMonotonicTimestampNs)
        val cameraCapture = if (sensor.hasCameraChunk() &&
            sensor.cameraChunk.chunkIndex == 0 &&
            sensor.cameraChunk.hasFrameMetadata()
        ) {
            if (sensor.cameraChunk.frameMetadata.captureMonotonicTimestampNs <= 0L) {
                reject(LiveLinkDiagnosticCode.SENSOR_CAMERA_TIMESTAMP_REJECTED)
            }
            state.normalize(
                RemoteClockStream.CAMERA_CAPTURE,
                sensor.cameraChunk.frameMetadata.captureMonotonicTimestampNs,
            )
        } else {
            null
        }
        val imuBatchCreated = if (sensor.hasImuBatch()) {
            if (sensor.imuBatch.createdMonotonicTimestampNs <= 0L) {
                reject(LiveLinkDiagnosticCode.SENSOR_IMU_BATCH_TIMESTAMP_REJECTED)
            }
            state.normalize(
                RemoteClockStream.IMU_BATCH_CREATED,
                sensor.imuBatch.createdMonotonicTimestampNs,
            )
        } else {
            null
        }
        val imu = if (sensor.hasImuBatch()) {
            sensor.imuBatch.samplesList.mapIndexed { index, sample ->
                if (!(sample.pose.monotonicTimestampNs > 0L &&
                    sample.angularVelocityMonotonicTimestampNs > 0L &&
                    sample.linearAccelerationMonotonicTimestampNs > 0L
                )) {
                    reject(LiveLinkDiagnosticCode.SENSOR_IMU_SAMPLE_TIMESTAMP_REJECTED)
                }
                NormalizedImuSampleTiming(
                    sampleIndex = index,
                    poseTimestamp = state.normalize(
                        RemoteClockStream.IMU_POSE,
                        sample.pose.monotonicTimestampNs,
                    ),
                    angularVelocityTimestamp = state.normalize(
                        RemoteClockStream.IMU_ANGULAR_VELOCITY,
                        sample.angularVelocityMonotonicTimestampNs,
                    ),
                    linearAccelerationTimestamp = state.normalize(
                        RemoteClockStream.IMU_LINEAR_ACCELERATION,
                        sample.linearAccelerationMonotonicTimestampNs,
                    ),
                )
            }
        } else {
            emptyList()
        }
        val microphoneCapture = if (sensor.hasMicrophoneChunk()) {
            if (sensor.microphoneChunk.captureMonotonicTimestampNs <= 0L) {
                reject(LiveLinkDiagnosticCode.SENSOR_SEND_TIMESTAMP_REJECTED)
            }
            state.normalize(
                RemoteClockStream.MICROPHONE_CAPTURE,
                sensor.microphoneChunk.captureMonotonicTimestampNs,
            )
        } else null
        val touchObserved = if (sensor.hasTouchEvent()) {
            if (sensor.touchEvent.observedMonotonicTimestampNs <= 0L) {
                reject(LiveLinkDiagnosticCode.SENSOR_SEND_TIMESTAMP_REJECTED)
            }
            state.normalize(RemoteClockStream.TOUCH_OBSERVED, sensor.touchEvent.observedMonotonicTimestampNs)
        } else null
        return LiveSensorDelivery(
            sensor,
            receiveNs,
            normalizedSend,
            cameraCapture,
            imuBatchCreated,
            imu,
            microphoneCapture,
            touchObserved,
        )
    }

    private fun reject(code: LiveLinkDiagnosticCode): Nothing =
        throw LiveSensorValidationException(code)
}
