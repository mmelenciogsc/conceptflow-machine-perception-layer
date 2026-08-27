// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import org.conceptflow.mpl.host.focus.SpatialFocusState
import org.conceptflow.mpl.host.vision.HeadPoseObservation
import org.conceptflow.mpl.host.vision.LiveMetricFusionResult
import org.conceptflow.mpl.host.vision.LiveMetricFusionReason
import org.conceptflow.mpl.host.vision.LightweightTrackState
import org.conceptflow.mpl.host.vision.MetricSemanticTrack
import org.conceptflow.mpl.host.vision.MetricVector3
import org.conceptflow.mpl.host.vision.QnnLiveFrameResult
import org.conceptflow.mpl.host.vision.TemporalMetricTrack
import org.conceptflow.mpl.host.vision.TrackEstimateValidity
import org.conceptflow.mpl.v1.RokidTouchAction
import org.conceptflow.mpl.v1.RokidTouchKey

enum class PerceptionCoordinateFrame(val wireValue: Int) {
    CAMERA(1),
    HEAD(2),
    WORLD(3),
}

enum class PerceptionValidityReason(val wireValue: Int) {
    SESSION_STARTING(1),
    SENSOR_STREAM_ACTIVE(2),
    PERCEPTION_READY(3),
    DISCONNECTED(4),
    STOPPED(5),
}

data class PerceptionEntityState(
    val trackId: String,
    val classId: String,
    val sourceFrameId: Long,
    val sourceCaptureTimestampNs: Long,
    val outputTimestampNs: Long,
    val confidence: Float,
    val coordinateFrame: PerceptionCoordinateFrame,
    val positionMeters: MetricVector3?,
    val distanceMeters: Float,
    val uncertaintyMeters: Float?,
    val propagated: Boolean,
) {
    init {
        require(trackId.isNotBlank() && trackId.encodeToByteArray().size <= MAXIMUM_STRING_BYTES)
        require(classId.isNotBlank() && classId.encodeToByteArray().size <= MAXIMUM_STRING_BYTES)
        require(sourceFrameId > 0L && sourceCaptureTimestampNs >= 0L && outputTimestampNs >= sourceCaptureTimestampNs)
        require(confidence.isFinite() && confidence in 0f..1f)
        require(distanceMeters.isFinite() && distanceMeters > 0f)
        require(uncertaintyMeters == null || (uncertaintyMeters.isFinite() && uncertaintyMeters >= 0f))
    }

    companion object {
        const val MAXIMUM_STRING_BYTES = 128
    }
}

data class PerceptionHeadState(
    val timestampNs: Long,
    val orientationAccuracy: Int,
    val w: Float,
    val x: Float,
    val y: Float,
    val z: Float,
) {
    init {
        require(timestampNs >= 0L)
        require(orientationAccuracy in 0..3)
        require(listOf(w, x, y, z).all(Float::isFinite))
    }
}

data class PerceptionHeadSnapshot(
    val revision: Long,
    val sessionGeneration: Long,
    val state: PerceptionHeadState,
) {
    init {
        require(revision > 0L && sessionGeneration > 0L)
        require(state.orientationAccuracy in 1..3)
        val normSquared = state.w * state.w + state.x * state.x + state.y * state.y + state.z * state.z
        require(kotlin.math.abs(normSquared - 1f) <= 0.001f)
    }
}

data class PerceptionWorldState(
    val revision: Long,
    val sessionGeneration: Long,
    val sourceFrameId: Long,
    val sourceCaptureTimestampNs: Long,
    val publishedTimestampNs: Long,
    val validUntilTimestampNs: Long,
    val depthProfileId: String,
    val validity: PerceptionValidityReason,
    val fusionReason: String,
    val head: PerceptionHeadState?,
    val entities: List<PerceptionEntityState>,
) {
    init {
        require(revision > 0L && sessionGeneration >= 0L)
        require(sourceFrameId >= 0L && sourceCaptureTimestampNs >= 0L)
        require(publishedTimestampNs >= 0L && validUntilTimestampNs >= publishedTimestampNs)
        require(depthProfileId.encodeToByteArray().size <= PerceptionEntityState.MAXIMUM_STRING_BYTES)
        require(fusionReason.encodeToByteArray().size <= PerceptionEntityState.MAXIMUM_STRING_BYTES)
        require(entities.size <= MAXIMUM_ENTITIES)
    }

    companion object {
        const val MAXIMUM_ENTITIES = 64
    }
}

data class PerceptionTouchInput(
    val eventId: Long,
    val hostObservedTimestampNs: Long,
    val sourceUptimeMs: Long,
    val key: RokidTouchKey,
    val action: RokidTouchAction,
    val scanCode: Int,
)

data class PerceptionBusStats(
    val latestRevision: Long,
    val publishedStates: Long,
    val touchEventsPublished: Long,
    val touchEventsRejected: Long,
    val touchQueueHighWater: Int,
)

/**
 * Single bounded owner for compact game-facing state. Raw camera/audio buffers never enter this
 * bus. Latest world state may be replaced; ordered touch events are rejected, never evicted, when
 * the bounded queue is full so an overflow remains an observable input-integrity fault.
 */
class PerceptionBus(
    private val touchCapacity: Int = 128,
    private val stateTtlNanos: Long = 500_000_000L,
) {
    private data class SelectedSpatialState(
        val coordinateFrame: PerceptionCoordinateFrame,
        val positionMeters: MetricVector3?,
        val uncertaintyMeters: Double?,
        val validity: TrackEstimateValidity,
    )

    private val nextRevision = AtomicLong(0L)
    private val latestState = AtomicReference<PerceptionWorldState?>(null)
    private val latestHead = AtomicReference<PerceptionHeadState?>(null)
    private val latestHeadSnapshot = AtomicReference<PerceptionHeadSnapshot?>(null)
    private val latestFocus = AtomicReference<SpatialFocusState?>(null)
    private val nextHeadRevision = AtomicLong(0L)
    private val nextFocusRevision = AtomicLong(0L)
    private val touchEvents = ArrayDeque<PerceptionTouchInput>()
    @Volatile private var sessionGeneration = 0L
    private var publishedStates = 0L
    private var touchEventsPublished = 0L
    private var touchEventsRejected = 0L
    private var touchQueueHighWater = 0

    init {
        require(touchCapacity in 1..512)
        require(stateTtlNanos in 1_000_000L..5_000_000_000L)
    }

    @Synchronized
    fun beginSession(generation: Long, nowNanos: Long) {
        require(generation > 0L && nowNanos >= 0L)
        sessionGeneration = generation
        touchEvents.clear()
        latestHead.set(null)
        latestHeadSnapshot.set(null)
        latestFocus.set(null)
        publishState(
            sourceFrameId = 0L,
            sourceCaptureTimestampNs = nowNanos,
            publishedTimestampNs = nowNanos,
            depthProfileId = "",
            validity = PerceptionValidityReason.SENSOR_STREAM_ACTIVE,
            fusionReason = "awaiting_perception",
            entities = emptyList(),
        )
    }

    fun publishHeadPose(pose: HeadPoseObservation) {
        val state = PerceptionHeadState(
                pose.hostMonotonicTimestampNanos,
                pose.orientationAccuracy,
                pose.worldFromHead.w.toFloat(),
                pose.worldFromHead.x.toFloat(),
                pose.worldFromHead.y.toFloat(),
                pose.worldFromHead.z.toFloat(),
            )
        latestHead.set(state)
        val generation = sessionGeneration
        if (generation > 0L && state.orientationAccuracy in 1..3) {
            latestHeadSnapshot.set(
                PerceptionHeadSnapshot(nextHeadRevision.incrementAndGet(), generation, state),
            )
        }
    }

    fun publishFocus(state: SpatialFocusState): SpatialFocusState {
        require(state.sessionGeneration == 0L || state.sessionGeneration == sessionGeneration)
        val published = state.copy(revision = nextFocusRevision.incrementAndGet())
        latestFocus.set(published)
        return published
    }

    fun latestHeadAfter(revision: Long): PerceptionHeadSnapshot? =
        latestHeadSnapshot.get()?.takeIf { it.revision > revision }

    /** Latest valid orientation for short-lived focus actions; never exposes mutable bus state. */
    fun latestHeadSnapshot(): PerceptionHeadSnapshot? = latestHeadSnapshot.get()

    fun latestFocusAfter(revision: Long, nowNanos: Long): SpatialFocusState? =
        latestFocus.get()?.takeIf { it.revision > revision && nowNanos <= it.validUntilTimestampNanos }

    @Synchronized
    fun publishPerception(
        result: QnnLiveFrameResult,
        fusion: LiveMetricFusionResult,
        sourceCaptureTimestampNs: Long,
        publishedTimestampNs: Long,
    ): PerceptionWorldState {
        require(sessionGeneration > 0L && sourceCaptureTimestampNs >= 0L)
        val entities = when {
            fusion.temporalTracks.isNotEmpty() -> fusion.temporalTracks.map(::fromTemporal)
            else -> fusion.metricTracks.map(::fromMetric)
        }.take(PerceptionWorldState.MAXIMUM_ENTITIES)
        return publishState(
            sourceFrameId = result.frameId,
            sourceCaptureTimestampNs = sourceCaptureTimestampNs,
            publishedTimestampNs = publishedTimestampNs,
            depthProfileId = result.selectedDepthProfileId,
            validity = PerceptionValidityReason.PERCEPTION_READY,
            fusionReason = fusion.reason.name,
            entities = entities,
        )
    }

    /** Publishes bounded measured or explicitly predicted state without changing its frame label. */
    @Synchronized
    fun publishTrackedPerception(
        sourceFrameId: Long,
        sourceCaptureTimestampNs: Long,
        publishedTimestampNs: Long,
        depthProfileId: String,
        reason: String,
        tracks: List<LightweightTrackState>,
        validity: PerceptionValidityReason = if (tracks.isEmpty()) {
            PerceptionValidityReason.SENSOR_STREAM_ACTIVE
        } else {
            PerceptionValidityReason.PERCEPTION_READY
        },
    ): PerceptionWorldState {
        require(sessionGeneration > 0L)
        require(sourceFrameId >= 0L && sourceCaptureTimestampNs >= 0L)
        require(tracks.size <= PerceptionWorldState.MAXIMUM_ENTITIES)
        require(validity == PerceptionValidityReason.SENSOR_STREAM_ACTIVE ||
            validity == PerceptionValidityReason.PERCEPTION_READY)
        val entities = tracks.mapNotNull(::fromMaintained)
        return publishState(
            sourceFrameId,
            sourceCaptureTimestampNs,
            publishedTimestampNs,
            depthProfileId,
            validity,
            reason,
            entities,
        )
    }

    @Synchronized
    fun publishTouch(event: TimedTouchEvent): Boolean {
        if (touchEvents.size == touchCapacity) {
            touchEventsRejected += 1L
            return false
        }
        val raw = event.event
        touchEvents.addLast(
            PerceptionTouchInput(
                raw.eventId,
                event.hostObservedTimestampNs,
                raw.sourceUptimeMs,
                raw.key,
                raw.action,
                raw.scanCode,
            ),
        )
        touchEventsPublished += 1L
        touchQueueHighWater = maxOf(touchQueueHighWater, touchEvents.size)
        return true
    }

    @Synchronized
    fun invalidate(reason: PerceptionValidityReason, nowNanos: Long) {
        require(reason == PerceptionValidityReason.DISCONNECTED || reason == PerceptionValidityReason.STOPPED)
        require(nowNanos >= 0L)
        touchEvents.clear()
        latestHead.set(null)
        latestHeadSnapshot.set(null)
        latestFocus.set(null)
        publishState(
            sourceFrameId = 0L,
            sourceCaptureTimestampNs = nowNanos,
            publishedTimestampNs = nowNanos,
            depthProfileId = "",
            validity = reason,
            fusionReason = reason.name.lowercase(),
            entities = emptyList(),
        )
    }

    fun latestAfter(revision: Long, nowNanos: Long): PerceptionWorldState? {
        require(revision >= 0L && nowNanos >= 0L)
        val value = latestState.get() ?: return null
        if (value.revision <= revision) return null
        return if (nowNanos <= value.validUntilTimestampNs ||
            value.validity != PerceptionValidityReason.PERCEPTION_READY
        ) value else value.copy(entities = emptyList(), validity = PerceptionValidityReason.SENSOR_STREAM_ACTIVE,
            fusionReason = "perception_state_expired")
    }

    @Synchronized
    fun drainTouch(maximum: Int): List<PerceptionTouchInput> {
        require(maximum in 1..touchCapacity)
        val result = ArrayList<PerceptionTouchInput>(minOf(maximum, touchEvents.size))
        repeat(minOf(maximum, touchEvents.size)) { result += touchEvents.removeFirst() }
        return result
    }

    @Synchronized
    fun stats() = PerceptionBusStats(
        nextRevision.get(),
        publishedStates,
        touchEventsPublished,
        touchEventsRejected,
        touchQueueHighWater,
    )

    private fun publishState(
        sourceFrameId: Long,
        sourceCaptureTimestampNs: Long,
        publishedTimestampNs: Long,
        depthProfileId: String,
        validity: PerceptionValidityReason,
        fusionReason: String,
        entities: List<PerceptionEntityState>,
    ): PerceptionWorldState {
        val state = PerceptionWorldState(
            nextRevision.incrementAndGet(),
            sessionGeneration,
            sourceFrameId,
            sourceCaptureTimestampNs,
            publishedTimestampNs,
            Math.addExact(publishedTimestampNs, stateTtlNanos),
            depthProfileId,
            validity,
            fusionReason,
            latestHead.get(),
            entities,
        )
        latestState.set(state)
        publishedStates += 1L
        return state
    }

    private fun fromTemporal(track: TemporalMetricTrack) = PerceptionEntityState(
        track.stableTrackId,
        track.classId,
        track.sourceFrameId,
        track.sourceCaptureMonotonicTimestampNanos,
        track.outputMonotonicTimestampNanos,
        track.confidence.toFloat(),
        PerceptionCoordinateFrame.CAMERA,
        track.cameraVectorMeters,
        track.distanceMeters.toFloat(),
        track.uncertaintyMeters.toFloat(),
        track.propagated,
    )

    private fun fromMetric(track: MetricSemanticTrack) = PerceptionEntityState(
        track.trackId,
        track.classId,
        track.frameId,
        track.sourceCaptureMonotonicTimestampNanos,
        track.sourceInferenceMonotonicTimestampNanos,
        track.confidence.toFloat(),
        PerceptionCoordinateFrame.CAMERA,
        track.cameraVectorMeters,
        track.representativeDistance.distanceMeters.toFloat(),
        track.representativeDistance.uncertaintyMeters?.toFloat(),
        false,
    )

    private fun fromMaintained(track: LightweightTrackState): PerceptionEntityState? {
        val depth = track.metricDepth ?: return null
        val spatial = when {
            track.coordinateValidity.localWorld == TrackEstimateValidity.TRANSLATION_EVIDENCE_PROPAGATED &&
                track.localWorldPositionMeters != null -> SelectedSpatialState(
                PerceptionCoordinateFrame.WORLD,
                track.localWorldPositionMeters,
                track.covariance.localWorldVarianceMetersSquared?.let(::sqrt),
                track.coordinateValidity.localWorld,
            )
            track.coordinateValidity.headRelative != TrackEstimateValidity.UNAVAILABLE &&
                track.headRelativeVectorMeters != null -> SelectedSpatialState(
                PerceptionCoordinateFrame.HEAD,
                track.headRelativeVectorMeters,
                track.covariance.depthVarianceMetersSquared?.let(::sqrt),
                track.coordinateValidity.headRelative,
            )
            track.coordinateValidity.cameraRelative != TrackEstimateValidity.UNAVAILABLE &&
                track.cameraRelativeVectorMeters != null -> SelectedSpatialState(
                PerceptionCoordinateFrame.CAMERA,
                track.cameraRelativeVectorMeters,
                track.covariance.depthVarianceMetersSquared?.let(::sqrt),
                track.coordinateValidity.cameraRelative,
            )
            else -> SelectedSpatialState(
                PerceptionCoordinateFrame.CAMERA,
                null,
                track.covariance.depthVarianceMetersSquared?.let(::sqrt),
                TrackEstimateValidity.UNAVAILABLE,
            )
        }
        return PerceptionEntityState(
            track.stableTrackId,
            track.classId,
            track.sourceFrameId,
            track.sourceCaptureTimestampNanos,
            track.outputTimestampNanos,
            track.confidence.toFloat(),
            spatial.coordinateFrame,
            spatial.positionMeters,
            depth.distanceMeters.toFloat(),
            spatial.uncertaintyMeters?.toFloat(),
            track.coordinateValidity.image2d == TrackEstimateValidity.MOTION_PREDICTED ||
                spatial.validity == TrackEstimateValidity.ORIENTATION_PROPAGATED ||
                spatial.validity == TrackEstimateValidity.TRANSLATION_EVIDENCE_PROPAGATED,
        )
    }
}

/** Explicit big-endian ABI consumed by the Unity C# bridge. */
object PerceptionBusBinaryCodec {
    private const val WORLD_MAGIC = 0x43465753 // CFWS
    private const val TOUCH_MAGIC = 0x43465442 // CFTB
    private const val FOCUS_MAGIC = 0x43464653 // CFFS
    private const val HEAD_MAGIC = 0x43464850 // CFHP
    private const val VERSION = 1
    private const val FOCUS_VERSION = 2

    fun encodeWorld(state: PerceptionWorldState): ByteArray = output { data ->
        data.writeInt(WORLD_MAGIC)
        data.writeShort(VERSION)
        data.writeShort(state.validity.wireValue)
        data.writeLong(state.revision)
        data.writeLong(state.sessionGeneration)
        data.writeLong(state.sourceFrameId)
        data.writeLong(state.sourceCaptureTimestampNs)
        data.writeLong(state.publishedTimestampNs)
        data.writeLong(state.validUntilTimestampNs)
        writeString(data, state.depthProfileId)
        writeString(data, state.fusionReason)
        val head = state.head
        data.writeBoolean(head != null)
        if (head != null) {
            data.writeLong(head.timestampNs)
            data.writeInt(head.orientationAccuracy)
            data.writeFloat(head.w)
            data.writeFloat(head.x)
            data.writeFloat(head.y)
            data.writeFloat(head.z)
        }
        data.writeShort(state.entities.size)
        state.entities.forEach { entity ->
            writeString(data, entity.trackId)
            writeString(data, entity.classId)
            data.writeByte(entity.coordinateFrame.wireValue)
            var flags = 0
            if (entity.positionMeters != null) flags = flags or 1
            if (entity.uncertaintyMeters != null) flags = flags or 2
            if (entity.propagated) flags = flags or 4
            data.writeByte(flags)
            data.writeShort(0)
            data.writeLong(entity.sourceFrameId)
            data.writeLong(entity.sourceCaptureTimestampNs)
            data.writeLong(entity.outputTimestampNs)
            data.writeFloat(entity.confidence)
            data.writeFloat(entity.distanceMeters)
            data.writeFloat(entity.uncertaintyMeters ?: 0f)
            val position = entity.positionMeters
            data.writeFloat(position?.x?.toFloat() ?: 0f)
            data.writeFloat(position?.y?.toFloat() ?: 0f)
            data.writeFloat(position?.z?.toFloat() ?: 0f)
        }
    }

    fun encodeTouchBatch(events: List<PerceptionTouchInput>): ByteArray = output { data ->
        require(events.size <= 128)
        data.writeInt(TOUCH_MAGIC)
        data.writeShort(VERSION)
        data.writeShort(events.size)
        events.forEach { event ->
            data.writeLong(event.eventId)
            data.writeLong(event.hostObservedTimestampNs)
            data.writeLong(event.sourceUptimeMs)
            data.writeInt(event.key.number)
            data.writeInt(event.action.number)
            data.writeInt(event.scanCode)
        }
    }

    fun encodeHead(snapshot: PerceptionHeadSnapshot): ByteArray = output { data ->
        data.writeInt(HEAD_MAGIC)
        data.writeShort(VERSION)
        data.writeShort(0)
        data.writeLong(snapshot.revision)
        data.writeLong(snapshot.sessionGeneration)
        data.writeLong(snapshot.state.timestampNs)
        data.writeInt(snapshot.state.orientationAccuracy)
        data.writeFloat(snapshot.state.w)
        data.writeFloat(snapshot.state.x)
        data.writeFloat(snapshot.state.y)
        data.writeFloat(snapshot.state.z)
    }

    fun encodeFocus(state: SpatialFocusState): ByteArray = output { data ->
        data.writeInt(FOCUS_MAGIC)
        data.writeShort(FOCUS_VERSION)
        var flags = 0
        if (state.target != null) flags = flags or 1
        if (state.beacon != null) flags = flags or 2
        data.writeShort(flags)
        data.writeLong(state.revision)
        data.writeLong(state.sessionGeneration)
        data.writeLong(state.sourceWorldRevision)
        data.writeLong(state.publishedTimestampNanos)
        data.writeLong(state.validUntilTimestampNanos)
        writeString(data, state.target?.stableTrackId.orEmpty())
        data.writeByte(state.mode.wireValue)
        val beacon = state.beacon
        data.writeByte(beacon?.anchorMode?.wireValue ?: 0)
        var beaconFlags = 0
        if (beacon?.distanceUncertaintyMeters != null) beaconFlags = beaconFlags or 1
        if (beacon?.referenceHeadOrientation != null) beaconFlags = beaconFlags or 2
        data.writeShort(beaconFlags)
        if (beacon != null) {
            writeString(data, beacon.stableTrackId)
            writeString(data, beacon.classId)
            data.writeLong(beacon.activationId)
            data.writeLong(beacon.activatedTimestampNanos)
            data.writeLong(beacon.validUntilTimestampNanos)
            data.writeLong(beacon.sourceFrameId)
            data.writeLong(beacon.sourceCaptureTimestampNanos)
            data.writeFloat(beacon.confidence.toFloat())
            data.writeFloat(beacon.distanceMeters.toFloat())
            data.writeFloat(beacon.distanceUncertaintyMeters?.toFloat() ?: 0f)
            data.writeFloat(beacon.anchorVectorMeters.x.toFloat())
            data.writeFloat(beacon.anchorVectorMeters.y.toFloat())
            data.writeFloat(beacon.anchorVectorMeters.z.toFloat())
            beacon.referenceHeadOrientation?.let { reference ->
                data.writeLong(reference.timestampNanos)
                data.writeInt(reference.accuracy)
                data.writeFloat(reference.w.toFloat())
                data.writeFloat(reference.x.toFloat())
                data.writeFloat(reference.y.toFloat())
                data.writeFloat(reference.z.toFloat())
            }
        }
    }

    private inline fun output(block: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream(4_096)
        DataOutputStream(bytes).use(block)
        return bytes.toByteArray()
    }

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= PerceptionEntityState.MAXIMUM_STRING_BYTES)
        output.writeShort(bytes.size)
        output.write(bytes)
    }
}
