// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.transport

import org.conceptflow.mpl.v1.SensorStreamEnvelope

data class LiveLinkSession(
    val binding: LiveSessionBinding,
    val clockEstimate: ClockOffsetEstimate?,
    val lease: NegotiatedLiveLease,
) {
    override fun toString(): String =
        "LiveLinkSession(binding=<redacted>,clockSynchronized=${clockEstimate != null})"
}

data class NegotiatedLiveLease(
    val expiresAtMonotonicNs: Long,
    val cameraRelaxedFps: Int,
    val cameraMotionFps: Int,
    val imuMaximumBatchDelayMs: Int,
    val imuMaximumSilenceMs: Int,
) {
    init {
        require(expiresAtMonotonicNs > 0)
        require(cameraRelaxedFps == 3 && cameraMotionFps == 5)
        require(imuMaximumBatchDelayMs in 1..20 && imuMaximumSilenceMs in 1..1_000)
    }
}

data class NormalizedImuSampleTiming(
    val sampleIndex: Int,
    val poseTimestamp: NormalizedMonotonicTimestamp,
    val angularVelocityTimestamp: NormalizedMonotonicTimestamp,
    val linearAccelerationTimestamp: NormalizedMonotonicTimestamp,
)

data class LiveSensorDelivery(
    val sensor: SensorStreamEnvelope,
    val receiveMonotonicNs: Long,
    val normalizedRemoteSend: NormalizedMonotonicTimestamp,
    val normalizedCameraCapture: NormalizedMonotonicTimestamp?,
    val normalizedImuBatchCreated: NormalizedMonotonicTimestamp?,
    val normalizedImuSamples: List<NormalizedImuSampleTiming>,
) {
    override fun toString(): String =
        "LiveSensorDelivery(payload=${sensor.payloadCase},receiveMonotonicNs=<redacted>,timestamps=<redacted>)"
}

enum class LiveLinkDisconnectReason {
    STOPPED,
    REMOTE_COMPLETED,
    AUTHENTICATION,
    CONFIGURATION,
    PROTOCOL,
    TIMEOUT,
    LEASE_EXPIRED,
    NETWORK,
    INTERNAL,
}

/** Fixed, content-free lane labels for terminal close evidence. */
enum class LiveLinkFailureLane {
    NONE,
    REALTIME_CONTROL,
    CAMERA,
}

/** Fixed, privacy-safe reason a locally initiated authenticated close request was not written. */
enum class LiveLinkCloseRequestFailure {
    NONE,
    CALLER_THREAD_NETWORK_POLICY,
    TRANSPORT_IO,
    INTERNAL,
    SHUTDOWN_DEADLINE_EXCEEDED,
}

/** Privacy-safe close-phase evidence; it contains no identifiers, addresses, or timing values. */
data class LiveLinkCloseEvidence(
    val clientCloseAttempted: Boolean = false,
    val clientCloseRequestWritten: Boolean = false,
    val clientWritersDrained: Boolean = false,
    val clientAcknowledgementReceived: Boolean = false,
    val clientRequestFailure: LiveLinkCloseRequestFailure = LiveLinkCloseRequestFailure.NONE,
    val hostAuthenticatedCloseSeen: Boolean = false,
    val hostFailureLane: LiveLinkFailureLane = LiveLinkFailureLane.NONE,
)

interface PocoLiveLinkObserver {
    fun onSessionReady(session: LiveLinkSession)
    fun onSensor(delivery: LiveSensorDelivery)
    fun onCloseEvidence(evidence: LiveLinkCloseEvidence) = Unit
    fun onDiagnostic(code: LiveLinkDiagnosticCode) = Unit
    fun onDisconnected(reason: LiveLinkDisconnectReason)
}

interface RokidLiveLinkObserver {
    /** Capture must start only after this callback supplies the active session/lease binding. */
    fun onSessionReady(session: LiveLinkSession)
    fun onDiagnostic(code: LiveLinkDiagnosticCode) = Unit
    fun onDisconnected(reason: LiveLinkDisconnectReason)
}
