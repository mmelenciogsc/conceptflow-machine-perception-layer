// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.focus

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.util.concurrent.atomic.AtomicReference
import org.conceptflow.mpl.host.realtime.TimedTouchEvent
import org.conceptflow.mpl.host.vision.InstanceMaskGeometry
import org.conceptflow.mpl.host.vision.LightweightTrackState
import org.conceptflow.mpl.host.vision.MetricVector3
import org.conceptflow.mpl.host.vision.TrackEstimateValidity

enum class SpatialFocusCommand { NEXT, PREVIOUS, ACTIVATE, BACK }
enum class SpatialFocusMode(val wireValue: Int) {
    INACTIVE(0), BROWSING(1), ACTION_MENU(2), VQA_PENDING(3), VQA_RESULT(4), BEACON_ACTIVE(5),
}
enum class SpatialFocusMenuOption { VQA, BEACON, BACK }
enum class SpatialFocusDwell { NONE, PENDING, READY }

enum class BeaconAnchorMode(val wireValue: Int) {
    NONE(0), WORLD_ANCHORED(1), ORIENTATION_STABILIZED_RELATIVE(2),
}

fun interface SpatialFocusTouchAdmission {
    /** Receives the complete raw event; production remains disabled until mappings are validated. */
    fun commandFor(event: TimedTouchEvent): SpatialFocusCommand?
}

object DisabledSpatialFocusTouchAdmission : SpatialFocusTouchAdmission {
    override fun commandFor(event: TimedTouchEvent): SpatialFocusCommand? = null
}

enum class BeaconQualityReason {
    ELIGIBLE,
    TRACK_EXPIRED,
    STALE_OBSERVATION,
    LOW_CONFIDENCE,
    DEPTH_NOT_FRESH,
    WORLD_ANCHOR_UNAVAILABLE,
    HEAD_VECTOR_UNAVAILABLE,
    HEAD_ORIENTATION_UNAVAILABLE,
    UNCERTAINTY_UNQUANTIFIED,
    UNCERTAINTY_TOO_HIGH,
}

data class BeaconQuality(
    val eligible: Boolean,
    val reason: BeaconQualityReason,
    val anchorMode: BeaconAnchorMode = BeaconAnchorMode.NONE,
    val worldAnchorMeters: MetricVector3? = null,
    val relativeHeadVectorMeters: MetricVector3? = null,
    val distanceUncertaintyMeters: Double? = null,
    val validUntilTimestampNanos: Long = 0L,
) {
    init {
        require(eligible == (anchorMode != BeaconAnchorMode.NONE))
        require((anchorMode == BeaconAnchorMode.WORLD_ANCHORED) == (worldAnchorMeters != null))
        require((anchorMode == BeaconAnchorMode.ORIENTATION_STABILIZED_RELATIVE) ==
            (relativeHeadVectorMeters != null && worldAnchorMeters == null))
        require(distanceUncertaintyMeters == null ||
            distanceUncertaintyMeters.isFinite() && distanceUncertaintyMeters >= 0.0)
        require(!eligible || validUntilTimestampNanos > 0L)
    }
}

/** Monotonic, glasses-side orientation sample used to freeze a relative bearing at activation. */
data class BeaconHeadOrientation(
    val timestampNanos: Long,
    val accuracy: Int,
    val w: Double,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(timestampNanos >= 0L && accuracy in 1..3)
        require(listOf(w, x, y, z).all(Double::isFinite))
        val normSquared = w * w + x * x + y * y + z * z
        require(abs(normSquared - 1.0) <= 0.001)
    }
}

/**
 * A bounded audio bearing. Relative mode deliberately follows the listener origin because
 * translation is unavailable; it preserves orientation only and is not a navigation anchor.
 */
data class SpatialBeacon(
    val activationId: Long,
    val stableTrackId: String,
    val classId: String,
    val anchorMode: BeaconAnchorMode,
    val activatedTimestampNanos: Long,
    val validUntilTimestampNanos: Long,
    val sourceFrameId: Long,
    val sourceCaptureTimestampNanos: Long,
    val confidence: Double,
    val distanceMeters: Double,
    val distanceUncertaintyMeters: Double?,
    val anchorVectorMeters: MetricVector3,
    val displayHeadVectorMeters: MetricVector3,
    val referenceHeadOrientation: BeaconHeadOrientation?,
) {
    init {
        require(activationId > 0L && stableTrackId.isNotBlank() && classId.isNotBlank())
        require(activatedTimestampNanos >= 0L && validUntilTimestampNanos > activatedTimestampNanos)
        require(sourceFrameId > 0L && sourceCaptureTimestampNanos >= 0L)
        require(sourceCaptureTimestampNanos <= activatedTimestampNanos)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(distanceMeters.isFinite() && distanceMeters > 0.0)
        require(distanceUncertaintyMeters == null ||
            distanceUncertaintyMeters.isFinite() && distanceUncertaintyMeters >= 0.0)
        require(anchorMode != BeaconAnchorMode.NONE)
        require((anchorMode == BeaconAnchorMode.ORIENTATION_STABILIZED_RELATIVE) ==
            (referenceHeadOrientation != null))
        require(referenceHeadOrientation == null ||
            referenceHeadOrientation.timestampNanos <= activatedTimestampNanos)
    }
}

class BeaconQualityGate(
    private val maximumAgeNanos: Long = 500_000_000L,
    private val minimumConfidence: Double = 0.65,
    private val maximumAbsoluteUncertaintyMeters: Double = 0.75,
    private val maximumRelativeUncertainty: Double = 0.25,
) {
    init {
        require(maximumAgeNanos > 0L)
        require(minimumConfidence in 0.0..1.0)
        require(maximumAbsoluteUncertaintyMeters > 0.0)
        require(maximumRelativeUncertainty > 0.0)
    }

    fun evaluate(track: LightweightTrackState, nowNanos: Long): BeaconQuality {
        require(nowNanos >= 0L)
        if (nowNanos >= track.expiresAtTimestampNanos) return rejected(BeaconQualityReason.TRACK_EXPIRED)
        if (track.outputTimestampNanos > nowNanos ||
            nowNanos - track.outputTimestampNanos > maximumAgeNanos
        ) {
            return rejected(BeaconQualityReason.STALE_OBSERVATION)
        }
        if (track.confidence < minimumConfidence) return rejected(BeaconQualityReason.LOW_CONFIDENCE)
        if (!track.depthFresh) return rejected(BeaconQualityReason.DEPTH_NOT_FRESH)
        val head = track.headRelativeVectorMeters
        if (head == null ||
            track.coordinateValidity.headRelative == TrackEstimateValidity.UNAVAILABLE
        ) return rejected(BeaconQualityReason.HEAD_VECTOR_UNAVAILABLE)
        val distance = track.metricDepth?.distanceMeters
            ?: return rejected(BeaconQualityReason.UNCERTAINTY_UNQUANTIFIED)
        val validUntil = minOf(
            track.expiresAtTimestampNanos,
            Math.addExact(track.outputTimestampNanos, maximumAgeNanos),
        )
        val world = track.localWorldPositionMeters
        val worldUncertainty = track.covariance.localWorldVarianceMetersSquared?.let(::sqrt)
        val allowed = max(0.05, minOf(maximumAbsoluteUncertaintyMeters, distance * maximumRelativeUncertainty))
        if (track.coordinateValidity.localWorld == TrackEstimateValidity.TRANSLATION_EVIDENCE_PROPAGATED &&
            world != null && worldUncertainty != null && worldUncertainty <= allowed
        ) {
            return BeaconQuality(
                true,
                BeaconQualityReason.ELIGIBLE,
                BeaconAnchorMode.WORLD_ANCHORED,
                worldAnchorMeters = world,
                relativeHeadVectorMeters = head,
                distanceUncertaintyMeters = track.metricDepth.uncertaintyMeters,
                validUntilTimestampNanos = validUntil,
            )
        }
        // A relative bearing is useful without translation. Its origin follows the listener,
        // while activation-time orientation keeps the direction stable as the head turns.
        return BeaconQuality(
            true,
            BeaconQualityReason.ELIGIBLE,
            BeaconAnchorMode.ORIENTATION_STABILIZED_RELATIVE,
            worldAnchorMeters = null,
            relativeHeadVectorMeters = head,
            distanceUncertaintyMeters = track.metricDepth.uncertaintyMeters,
            validUntilTimestampNanos = validUntil,
        )
    }

    private fun rejected(reason: BeaconQualityReason) = BeaconQuality(false, reason)
}

data class SpatialFocusItem(
    val stableTrackId: String,
    val classId: String,
    val sourceFrameId: Long,
    val sourceCaptureTimestampNanos: Long,
    val expiresAtTimestampNanos: Long,
    val confidence: Double,
    val headVectorMeters: MetricVector3,
    val distanceMeters: Double,
    val uncertaintyMeters: Double?,
    val beaconQuality: BeaconQuality,
    val imageGeometry: InstanceMaskGeometry? = null,
)

data class SpatialFocusSnapshot(
    val snapshotId: Long,
    val sessionGeneration: Long,
    val sourceWorldRevision: Long,
    val createdTimestampNanos: Long,
    val items: List<SpatialFocusItem>,
)

data class SpatialFocusState(
    val revision: Long,
    val sessionGeneration: Long,
    val snapshotId: Long,
    val sourceWorldRevision: Long,
    val publishedTimestampNanos: Long,
    val validUntilTimestampNanos: Long,
    val focusGeneration: Long,
    val mode: SpatialFocusMode,
    val selectedIndex: Int,
    val itemCount: Int,
    val menuIndex: Int,
    val menuOption: SpatialFocusMenuOption?,
    val dwell: SpatialFocusDwell,
    val dwellStartedTimestampNanos: Long,
    val dwellDeadlineTimestampNanos: Long,
    val target: SpatialFocusItem?,
    val displayPhrase: String,
    val talkBackPhrase: String,
    val statusReason: String,
    val vqaAnswer: String = "",
    val operatorNotice: SpatialFocusOperatorNotice = SpatialFocusOperatorNotice.None,
    val beacon: SpatialBeacon? = null,
) {
    init {
        require((mode == SpatialFocusMode.BEACON_ACTIVE) == (beacon != null))
        require(beacon == null || target?.stableTrackId == beacon.stableTrackId)
        require(beacon == null || validUntilTimestampNanos == beacon.validUntilTimestampNanos)
    }
}

data class FocusedVqaCorrelation(
    val requestId: Long,
    val sessionGeneration: Long,
    val snapshotId: Long,
    val focusGeneration: Long,
    val stableTrackId: String,
    val sourceFrameId: Long,
)

data class FocusedVqaRequest(
    val correlation: FocusedVqaCorrelation,
    val requestedTimestampNanos: Long,
    val sourceCaptureTimestampNanos: Long,
    val question: String,
    val imageGeometry: InstanceMaskGeometry? = null,
)

enum class FocusedVqaRejection { BUSY, COOLDOWN, STALE_FRAME, INVALID_REQUEST, UNAVAILABLE }
sealed interface SpatialFocusOperatorNotice {
    data object None : SpatialFocusOperatorNotice
    data class VqaRejected(val reason: FocusedVqaRejection) : SpatialFocusOperatorNotice
    data class BeaconRejected(val reason: BeaconQualityReason) : SpatialFocusOperatorNotice
}

/** De-duplicates explicit TalkBack announcements independently of high-rate state revisions. */
class SpatialFocusAnnouncementPolicy {
    private var lastToken: String? = null

    @Synchronized
    fun shouldAnnounce(state: SpatialFocusState?): Boolean {
        val token = meaningfulToken(state) ?: return false
        if (token == lastToken) return false
        lastToken = token
        return true
    }

    private fun meaningfulToken(state: SpatialFocusState?): String? = when {
        state == null || state.mode == SpatialFocusMode.INACTIVE -> "inactive"
        state.mode == SpatialFocusMode.BEACON_ACTIVE ->
            "beacon:${state.focusGeneration}:${state.beacon?.activationId ?: 0L}"
        state.itemCount == 0 -> "empty:${state.sessionGeneration}"
        state.operatorNotice !is SpatialFocusOperatorNotice.None ->
            "notice:${state.focusGeneration}:${state.operatorNotice}"
        state.mode == SpatialFocusMode.ACTION_MENU ->
            "menu:${state.focusGeneration}:${state.menuIndex}"
        state.mode == SpatialFocusMode.VQA_PENDING -> "vqa-pending:${state.focusGeneration}"
        state.mode == SpatialFocusMode.VQA_RESULT ->
            "vqa-result:${state.focusGeneration}:${state.vqaAnswer}"
        state.dwell == SpatialFocusDwell.READY -> "ready:${state.focusGeneration}"
        else -> null
    }
}

sealed interface FocusedVqaAdmission {
    data class Admitted(val request: FocusedVqaRequest) : FocusedVqaAdmission
    data class Rejected(val reason: FocusedVqaRejection) : FocusedVqaAdmission
}

interface FocusedVqaGateway {
    fun submit(request: FocusedVqaRequest): Boolean
    fun cancel(correlation: FocusedVqaCorrelation)
}

object UnavailableFocusedVqaGateway : FocusedVqaGateway {
    override fun submit(request: FocusedVqaRequest) = false
    override fun cancel(correlation: FocusedVqaCorrelation) = Unit
}

/** Allows the controller to attach/detach the session-owned VLM without rebuilding focus state. */
class ReplaceableFocusedVqaGateway : FocusedVqaGateway {
    private val delegate = AtomicReference<FocusedVqaGateway>(UnavailableFocusedVqaGateway)

    fun install(gateway: FocusedVqaGateway) {
        delegate.set(gateway)
    }

    fun clear(gateway: FocusedVqaGateway) {
        delegate.compareAndSet(gateway, UnavailableFocusedVqaGateway)
    }

    override fun submit(request: FocusedVqaRequest): Boolean = delegate.get().submit(request)

    override fun cancel(correlation: FocusedVqaCorrelation) = delegate.get().cancel(correlation)
}

class FocusedVqaAdmissionGate(
    private val maximumFrameAgeNanos: Long = 1_500_000_000L,
    private val cooldownNanos: Long = 2_000_000_000L,
) {
    private var active: FocusedVqaCorrelation? = null
    private var lastAdmissionNanos = Long.MIN_VALUE
    private var nextRequestId = 1L

    @Synchronized
    fun admit(
        sessionGeneration: Long,
        snapshotId: Long,
        focusGeneration: Long,
        track: SpatialFocusItem,
        nowNanos: Long,
        explicitlyRequested: Boolean,
    ): FocusedVqaAdmission {
        require(nowNanos >= 0L)
        if (!explicitlyRequested || sessionGeneration <= 0L || snapshotId <= 0L || focusGeneration <= 0L) {
            return FocusedVqaAdmission.Rejected(FocusedVqaRejection.INVALID_REQUEST)
        }
        if (active != null) return FocusedVqaAdmission.Rejected(FocusedVqaRejection.BUSY)
        if (track.sourceCaptureTimestampNanos > nowNanos ||
            nowNanos >= track.expiresAtTimestampNanos ||
            nowNanos - track.sourceCaptureTimestampNanos > maximumFrameAgeNanos
        ) return FocusedVqaAdmission.Rejected(FocusedVqaRejection.STALE_FRAME)
        if (lastAdmissionNanos != Long.MIN_VALUE && nowNanos - lastAdmissionNanos < cooldownNanos) {
            return FocusedVqaAdmission.Rejected(FocusedVqaRejection.COOLDOWN)
        }
        val correlation = FocusedVqaCorrelation(
            nextRequestId++, sessionGeneration, snapshotId, focusGeneration,
            track.stableTrackId, track.sourceFrameId,
        )
        val request = FocusedVqaRequest(
            correlation,
            nowNanos,
            track.sourceCaptureTimestampNanos,
            "Briefly describe the selected ${track.classId}. Do not infer safety or unseen details.",
            track.imageGeometry,
        )
        active = correlation
        lastAdmissionNanos = nowNanos
        return FocusedVqaAdmission.Admitted(request)
    }

    @Synchronized fun activeCorrelation(): FocusedVqaCorrelation? = active

    @Synchronized
    fun complete(correlation: FocusedVqaCorrelation): Boolean {
        if (active != correlation) return false
        active = null
        return true
    }

    @Synchronized
    fun cancel(): FocusedVqaCorrelation? = active.also { active = null }

    @Synchronized
    fun reset() {
        active = null
        lastAdmissionNanos = Long.MIN_VALUE
        nextRequestId = 1L
    }
}

sealed interface SpatialFocusEffect {
    data object None : SpatialFocusEffect
    data class RequestVqa(val request: FocusedVqaRequest) : SpatialFocusEffect
    data class CancelVqa(val correlation: FocusedVqaCorrelation) : SpatialFocusEffect
    data class BeaconChanged(val active: Boolean, val quality: BeaconQuality) : SpatialFocusEffect
}

data class SpatialFocusTransition(val state: SpatialFocusState, val effect: SpatialFocusEffect)

object SpatialFocusSpeechFormatter {
    fun clockHour(vector: MetricVector3): Int {
        val degrees = atan2(vector.x, vector.z) * 180.0 / PI
        val sector = (degrees / 30.0).roundToInt().mod(12)
        return if (sector == 0) 12 else sector
    }

    fun display(track: SpatialFocusItem): String = phrase(track, talkBack = false)
    fun talkBack(track: SpatialFocusItem): String = phrase(track, talkBack = true)

    private fun phrase(track: SpatialFocusItem, talkBack: Boolean): String {
        val hour = clockHour(track.headVectorMeters)
        val clock = if (talkBack) "$hour o'clock" else "%d:00".format(hour)
        val feet = (track.distanceMeters * METERS_TO_FEET).roundToInt().coerceAtLeast(1)
        val unit = if (feet == 1) "foot" else "feet"
        return "${track.classId}. $clock. about $feet $unit away."
    }

    private const val METERS_TO_FEET = 3.280839895
}

class SpatialFocusManager(
    private val beaconGate: BeaconQualityGate = BeaconQualityGate(),
    private val vqaGate: FocusedVqaAdmissionGate = FocusedVqaAdmissionGate(),
    private val vqaGateway: FocusedVqaGateway = UnavailableFocusedVqaGateway,
    private val dwellNanos: Long = 750_000_000L,
    private val stateTtlNanos: Long = 1_500_000_000L,
    private val beaconTtlNanos: Long = 30_000_000_000L,
    private val maximumBeaconHeadPoseAgeNanos: Long = 250_000_000L,
    private val vqaPendingTtlNanos: Long = 9_000_000_000L,
    private val vqaResultTtlNanos: Long = 10_000_000_000L,
) {
    private var revision = 0L
    private var snapshotCounter = 0L
    private var focusGeneration = 0L
    private var snapshot: SpatialFocusSnapshot? = null
    private var mode = SpatialFocusMode.INACTIVE
    private var selectedIndex = -1
    private var menuIndex = 0
    private var dwell = SpatialFocusDwell.NONE
    private var dwellStartedNs = 0L
    private var dwellDeadlineNs = 0L
    private var latestState: SpatialFocusState? = null
    private var vqaAnswer = ""
    private var currentSessionGeneration = 0L
    private var currentSourceWorldRevision = 0L
    private var operatorNotice: SpatialFocusOperatorNotice = SpatialFocusOperatorNotice.None
    private var activeBeacon: SpatialBeacon? = null
    private var beaconActivationCounter = 0L
    private var vqaStateUntilNs = 0L

    init {
        require(dwellNanos == 750_000_000L)
        require(stateTtlNanos >= dwellNanos)
        require(beaconTtlNanos in 1_000_000_000L..300_000_000_000L)
        require(maximumBeaconHeadPoseAgeNanos in 1_000_000L..1_000_000_000L)
        require(vqaPendingTtlNanos in 1_000_000_000L..30_000_000_000L)
        require(vqaResultTtlNanos in 1_000_000_000L..60_000_000_000L)
    }

    @Synchronized
    fun updateTracks(
        sessionGeneration: Long,
        sourceWorldRevision: Long,
        nowNanos: Long,
        tracks: List<LightweightTrackState>,
    ): SpatialFocusState {
        require(sessionGeneration > 0L && sourceWorldRevision > 0L && nowNanos >= 0L)
        val candidates = tracks.asSequence()
            .filter { nowNanos < it.expiresAtTimestampNanos }
            .mapNotNull { toItem(it, nowNanos) }
            .associateBy(SpatialFocusItem::stableTrackId)
        val prior = snapshot
        if (prior != null && prior.sessionGeneration != sessionGeneration) clearSelection()
        if (activeBeacon?.let { nowNanos >= it.validUntilTimestampNanos } == true) clearSelection()
        currentSourceWorldRevision = sourceWorldRevision
        if (prior != null && prior.sessionGeneration == sessionGeneration &&
            (mode == SpatialFocusMode.VQA_PENDING || mode == SpatialFocusMode.VQA_RESULT) &&
            nowNanos < vqaStateUntilNs
        ) {
            // Explicit VQA owns a bounded, immutable source-frame correlation. Keep that
            // structured target while the live detector moves on; retaining it does not make
            // expired geometry current or feed it back into perception.
            snapshot = prior.copy(sourceWorldRevision = sourceWorldRevision)
            return publish(nowNanos, "vqa_source_held")
        }
        if ((mode == SpatialFocusMode.VQA_PENDING || mode == SpatialFocusMode.VQA_RESULT) &&
            nowNanos >= vqaStateUntilNs
        ) {
            vqaGate.cancel()?.let { correlation -> runCatching { vqaGateway.cancel(correlation) } }
            clearSelection()
        }
        if (prior == null || prior.sessionGeneration != sessionGeneration) {
            currentSessionGeneration = sessionGeneration
            snapshotCounter += 1L
            snapshot = SpatialFocusSnapshot(
                snapshotCounter, sessionGeneration, sourceWorldRevision, nowNanos,
                candidates.values.sortedWith(ITEM_ORDER),
            )
            if (snapshot!!.items.isEmpty() && mode != SpatialFocusMode.BEACON_ACTIVE) {
                resetInternal(sessionGeneration)
            } else if (mode != SpatialFocusMode.INACTIVE) {
                select(0, nowNanos)
            }
        } else {
            val survivorIds = prior.items.map(SpatialFocusItem::stableTrackId).filter(candidates::containsKey)
            val appended = candidates.values.filter { it.stableTrackId !in survivorIds }.sortedWith(ITEM_ORDER)
            snapshot = prior.copy(
                sourceWorldRevision = sourceWorldRevision,
                items = survivorIds.map(candidates::getValue) + appended,
            )
            val targetId = prior.items.getOrNull(selectedIndex)?.stableTrackId
            val retainedIndex = targetId?.let { id -> snapshot!!.items.indexOfFirst { it.stableTrackId == id } }
            when {
                snapshot!!.items.isEmpty() && mode != SpatialFocusMode.BEACON_ACTIVE -> resetInternal(sessionGeneration)
                targetId != null && retainedIndex == -1 && mode != SpatialFocusMode.BEACON_ACTIVE -> clearSelection()
                mode == SpatialFocusMode.BEACON_ACTIVE && retainedIndex == -1 -> selectedIndex = -1
                retainedIndex != null -> selectedIndex = retainedIndex
                else -> selectedIndex = -1
            }
        }
        return publish(nowNanos, "tracks_updated")
    }

    @Synchronized
    fun command(
        command: SpatialFocusCommand,
        nowNanos: Long,
        headOrientation: BeaconHeadOrientation? = null,
    ): SpatialFocusTransition {
        if (pruneExpired(nowNanos)) {
            return SpatialFocusTransition(
                publish(nowNanos, "focus_target_expired"),
                SpatialFocusEffect.None,
            )
        }
        val items = snapshot?.items.orEmpty()
        if (items.isEmpty() && mode != SpatialFocusMode.BEACON_ACTIVE) {
            return SpatialFocusTransition(publish(nowNanos, "no_spatial_targets"), SpatialFocusEffect.None)
        }
        var effect: SpatialFocusEffect = SpatialFocusEffect.None
        var reason = command.name.lowercase()
        operatorNotice = SpatialFocusOperatorNotice.None
        when (command) {
            SpatialFocusCommand.NEXT -> when (mode) {
                SpatialFocusMode.ACTION_MENU -> menuIndex = (menuIndex + 1).coerceAtMost(MENU.lastIndex)
                SpatialFocusMode.INACTIVE -> { mode = SpatialFocusMode.BROWSING; select(0, nowNanos) }
                SpatialFocusMode.VQA_PENDING -> Unit
                SpatialFocusMode.BEACON_ACTIVE -> {
                    val prior = requireNotNull(activeBeacon)
                    effect = SpatialFocusEffect.BeaconChanged(false, beaconQuality(prior))
                    activeBeacon = null
                    if (items.isEmpty()) clearSelection() else {
                        mode = SpatialFocusMode.BROWSING
                        select((selectedIndex.coerceAtLeast(-1) + 1).coerceAtMost(items.lastIndex), nowNanos)
                    }
                }
                SpatialFocusMode.VQA_RESULT -> {
                    mode = SpatialFocusMode.BROWSING
                    vqaAnswer = ""
                    select((selectedIndex + 1).coerceAtMost(items.lastIndex), nowNanos)
                }
                SpatialFocusMode.BROWSING -> select(
                    (selectedIndex + 1).coerceAtMost(items.lastIndex),
                    nowNanos,
                )
            }
            SpatialFocusCommand.PREVIOUS -> when (mode) {
                SpatialFocusMode.ACTION_MENU -> menuIndex = (menuIndex - 1).coerceAtLeast(0)
                SpatialFocusMode.INACTIVE -> { mode = SpatialFocusMode.BROWSING; select(0, nowNanos) }
                SpatialFocusMode.VQA_PENDING -> Unit
                SpatialFocusMode.BEACON_ACTIVE -> {
                    val prior = requireNotNull(activeBeacon)
                    effect = SpatialFocusEffect.BeaconChanged(false, beaconQuality(prior))
                    activeBeacon = null
                    if (items.isEmpty()) clearSelection() else {
                        mode = SpatialFocusMode.BROWSING
                        select((if (selectedIndex < 0) items.lastIndex else selectedIndex - 1).coerceAtLeast(0), nowNanos)
                    }
                }
                SpatialFocusMode.VQA_RESULT -> {
                    mode = SpatialFocusMode.BROWSING
                    vqaAnswer = ""
                    select((selectedIndex - 1).coerceAtLeast(0), nowNanos)
                }
                SpatialFocusMode.BROWSING -> select((selectedIndex - 1).coerceAtLeast(0), nowNanos)
            }
            SpatialFocusCommand.ACTIVATE -> when (mode) {
                SpatialFocusMode.INACTIVE -> { mode = SpatialFocusMode.BROWSING; select(0, nowNanos) }
                SpatialFocusMode.BROWSING, SpatialFocusMode.VQA_RESULT -> {
                    cancelDwell(); mode = SpatialFocusMode.ACTION_MENU; menuIndex = 0
                }
                SpatialFocusMode.ACTION_MENU -> when (MENU[menuIndex]) {
                    SpatialFocusMenuOption.VQA -> {
                        val target = items[selectedIndex]
                        when (val admission = vqaGate.admit(
                            snapshot!!.sessionGeneration, snapshot!!.snapshotId, focusGeneration,
                            target, nowNanos, explicitlyRequested = true,
                        )) {
                            is FocusedVqaAdmission.Admitted -> {
                                if (runCatching { vqaGateway.submit(admission.request) }.getOrDefault(false)) {
                                    mode = SpatialFocusMode.VQA_PENDING
                                    vqaStateUntilNs = Math.addExact(nowNanos, vqaPendingTtlNanos)
                                    effect = SpatialFocusEffect.RequestVqa(admission.request)
                                    reason = "vqa_requested"
                                } else {
                                    vqaGate.cancel()
                                    mode = SpatialFocusMode.ACTION_MENU
                                    operatorNotice = SpatialFocusOperatorNotice.VqaRejected(
                                        FocusedVqaRejection.UNAVAILABLE,
                                    )
                                    reason = "vqa_rejected_unavailable"
                                }
                            }
                            is FocusedVqaAdmission.Rejected -> {
                                operatorNotice = SpatialFocusOperatorNotice.VqaRejected(admission.reason)
                                reason = "vqa_rejected_${admission.reason.name.lowercase()}"
                            }
                        }
                    }
                    SpatialFocusMenuOption.BEACON -> {
                        val item = items[selectedIndex]
                        var quality = when {
                            !item.beaconQuality.eligible -> item.beaconQuality
                            nowNanos <= item.beaconQuality.validUntilTimestampNanos -> item.beaconQuality
                            else -> BeaconQuality(false, BeaconQualityReason.STALE_OBSERVATION)
                        }
                        val beacon = if (quality.eligible) {
                            createBeacon(item, quality, headOrientation, nowNanos)
                        } else null
                        if (quality.eligible && beacon == null) {
                            quality = BeaconQuality(false, BeaconQualityReason.HEAD_ORIENTATION_UNAVAILABLE)
                        }
                        if (quality.eligible && beacon != null) {
                            activeBeacon = beacon
                            mode = SpatialFocusMode.BEACON_ACTIVE
                        }
                        if (!quality.eligible) {
                            operatorNotice = SpatialFocusOperatorNotice.BeaconRejected(quality.reason)
                        }
                        reason = if (quality.eligible) {
                            "beacon_started"
                        } else {
                            "beacon_rejected_${quality.reason.name.lowercase()}"
                        }
                        effect = SpatialFocusEffect.BeaconChanged(quality.eligible, quality)
                    }
                    SpatialFocusMenuOption.BACK -> mode = SpatialFocusMode.BROWSING
                }
                SpatialFocusMode.VQA_PENDING, SpatialFocusMode.BEACON_ACTIVE -> Unit
            }
            SpatialFocusCommand.BACK -> {
                val activeVqa = vqaGate.cancel()
                if (activeVqa != null) {
                    runCatching { vqaGateway.cancel(activeVqa) }
                    effect = SpatialFocusEffect.CancelVqa(activeVqa)
                } else if (mode == SpatialFocusMode.BEACON_ACTIVE) {
                    val prior = requireNotNull(activeBeacon)
                    effect = SpatialFocusEffect.BeaconChanged(false, beaconQuality(prior))
                    activeBeacon = null
                }
                when (mode) {
                    SpatialFocusMode.INACTIVE -> Unit
                    SpatialFocusMode.BROWSING -> resetInternal(snapshot!!.sessionGeneration)
                    else -> mode = SpatialFocusMode.BROWSING
                }
                cancelDwell()
            }
        }
        return SpatialFocusTransition(publish(nowNanos, reason), effect)
    }

    @Synchronized
    fun advance(nowNanos: Long): SpatialFocusState {
        val previousSnapshot = snapshot
        if (pruneExpired(nowNanos)) return publish(nowNanos, "focus_target_expired")
        if (previousSnapshot != snapshot) return publish(nowNanos, "snapshot_expired")
        if (dwell == SpatialFocusDwell.PENDING && nowNanos >= dwellDeadlineNs) {
            dwell = SpatialFocusDwell.READY
            return publish(nowNanos, "dwell_ready")
        }
        return latestState ?: publish(nowNanos, "idle")
    }

    @Synchronized
    fun completeVqa(correlation: FocusedVqaCorrelation, answer: String, nowNanos: Long): Boolean {
        val normalized = answer.filter { !it.isISOControl() || it.isWhitespace() }
            .trim().replace(Regex("\\s+"), " ").take(512)
        if (normalized.isBlank()) return false
        if (nowNanos >= vqaStateUntilNs) return false
        if (!vqaGate.complete(correlation) || mode != SpatialFocusMode.VQA_PENDING) return false
        vqaAnswer = normalized
        mode = SpatialFocusMode.VQA_RESULT
        vqaStateUntilNs = Math.addExact(nowNanos, vqaResultTtlNanos)
        publish(nowNanos, "vqa_complete")
        return true
    }

    @Synchronized
    fun failVqa(
        correlation: FocusedVqaCorrelation,
        reason: FocusedVqaRejection,
        nowNanos: Long,
    ): Boolean {
        if (!vqaGate.complete(correlation) || mode != SpatialFocusMode.VQA_PENDING) return false
        mode = SpatialFocusMode.ACTION_MENU
        vqaStateUntilNs = 0L
        menuIndex = 0
        operatorNotice = SpatialFocusOperatorNotice.VqaRejected(reason)
        publish(nowNanos, "vqa_rejected_${reason.name.lowercase()}")
        return true
    }

    @Synchronized
    fun reset(
        sessionGeneration: Long,
        nowNanos: Long,
        sourceWorldRevision: Long = 0L,
    ): SpatialFocusState {
        require(sessionGeneration >= 0L && nowNanos >= 0L && sourceWorldRevision >= 0L)
        vqaGate.cancel()?.let { correlation -> runCatching { vqaGateway.cancel(correlation) } }
        vqaGate.reset()
        beaconActivationCounter = 0L
        currentSourceWorldRevision = sourceWorldRevision
        resetInternal(sessionGeneration)
        return publish(nowNanos, "reset")
    }

    @Synchronized fun current(): SpatialFocusState? = latestState

    private fun select(index: Int, nowNanos: Long) {
        val bounded = index.coerceIn(0, snapshot!!.items.lastIndex)
        if (selectedIndex == bounded && mode == SpatialFocusMode.BROWSING && dwell != SpatialFocusDwell.NONE) return
        selectedIndex = bounded
        focusGeneration += 1L
        dwell = SpatialFocusDwell.PENDING
        dwellStartedNs = nowNanos
        dwellDeadlineNs = Math.addExact(nowNanos, dwellNanos)
        vqaAnswer = ""
        vqaStateUntilNs = 0L
    }

    private fun cancelDwell() {
        dwell = SpatialFocusDwell.NONE
        dwellStartedNs = 0L
        dwellDeadlineNs = 0L
    }

    /** Returns true only when the selected target expired, so a command cannot retarget silently. */
    private fun pruneExpired(nowNanos: Long): Boolean {
        val current = snapshot ?: return false
        if ((mode == SpatialFocusMode.VQA_PENDING || mode == SpatialFocusMode.VQA_RESULT) &&
            nowNanos < vqaStateUntilNs
        ) return false
        val beacon = activeBeacon
        if (mode == SpatialFocusMode.BEACON_ACTIVE && beacon != null &&
            nowNanos >= beacon.validUntilTimestampNanos
        ) {
            activeBeacon = null
            clearSelection()
            return true
        }
        val selectedId = current.items.getOrNull(selectedIndex)?.stableTrackId
        val survivors = current.items.filter { nowNanos < it.expiresAtTimestampNanos }
        if (survivors.size == current.items.size) return false
        snapshot = current.copy(items = survivors)
        if (survivors.isEmpty() && mode != SpatialFocusMode.BEACON_ACTIVE) {
            resetInternal(current.sessionGeneration)
            return selectedId != null
        }
        if (selectedId != null && survivors.none { it.stableTrackId == selectedId } &&
            mode != SpatialFocusMode.BEACON_ACTIVE
        ) {
            clearSelection()
            return true
        }
        selectedIndex = selectedId?.let { id -> survivors.indexOfFirst { it.stableTrackId == id } } ?: -1
        return false
    }

    private fun resetInternal(sessionGeneration: Long) {
        currentSessionGeneration = sessionGeneration.coerceAtLeast(0L)
        snapshot = null
        activeBeacon = null
        clearSelection()
    }

    private fun clearSelection() {
        vqaGate.cancel()?.let { correlation -> runCatching { vqaGateway.cancel(correlation) } }
        mode = SpatialFocusMode.INACTIVE
        selectedIndex = -1
        menuIndex = 0
        cancelDwell()
        vqaAnswer = ""
        vqaStateUntilNs = 0L
        operatorNotice = SpatialFocusOperatorNotice.None
        activeBeacon = null
        focusGeneration += 1L
    }

    private fun publish(nowNanos: Long, reason: String): SpatialFocusState {
        val current = snapshot
        val beacon = activeBeacon?.takeIf { mode == SpatialFocusMode.BEACON_ACTIVE }
        val target = if (beacon != null) {
            current?.items?.firstOrNull { it.stableTrackId == beacon.stableTrackId }
                ?: beacon.asFocusItem()
        } else {
            current?.items?.getOrNull(selectedIndex)
        }
        val state = SpatialFocusState(
            revision = ++revision,
            sessionGeneration = current?.sessionGeneration ?: currentSessionGeneration,
            snapshotId = current?.snapshotId ?: 0L,
            sourceWorldRevision = current?.sourceWorldRevision ?: currentSourceWorldRevision,
            publishedTimestampNanos = nowNanos,
            validUntilTimestampNanos = when {
                beacon != null -> beacon.validUntilTimestampNanos
                (mode == SpatialFocusMode.VQA_PENDING || mode == SpatialFocusMode.VQA_RESULT) &&
                    vqaStateUntilNs > nowNanos -> vqaStateUntilNs
                target != null -> minOf(
                    Math.addExact(nowNanos, stateTtlNanos),
                    target.expiresAtTimestampNanos,
                )
                else -> Math.addExact(nowNanos, stateTtlNanos)
            },
            focusGeneration = focusGeneration,
            mode = mode,
            selectedIndex = selectedIndex,
            itemCount = current?.items?.size ?: 0,
            menuIndex = if (mode == SpatialFocusMode.ACTION_MENU) menuIndex else -1,
            menuOption = if (mode == SpatialFocusMode.ACTION_MENU) MENU[menuIndex] else null,
            dwell = dwell,
            dwellStartedTimestampNanos = dwellStartedNs,
            dwellDeadlineTimestampNanos = dwellDeadlineNs,
            target = target,
            displayPhrase = target?.let(SpatialFocusSpeechFormatter::display).orEmpty(),
            talkBackPhrase = target?.let(SpatialFocusSpeechFormatter::talkBack).orEmpty(),
            statusReason = reason,
            vqaAnswer = vqaAnswer,
            operatorNotice = operatorNotice,
            beacon = beacon,
        )
        latestState = state
        return state
    }

    private fun toItem(track: LightweightTrackState, nowNanos: Long): SpatialFocusItem? {
        val head = track.headRelativeVectorMeters ?: return null
        val depth = track.metricDepth ?: return null
        return SpatialFocusItem(
            track.stableTrackId, track.classId, track.sourceFrameId,
            track.sourceCaptureTimestampNanos, track.expiresAtTimestampNanos,
            track.confidence, head, depth.distanceMeters, depth.uncertaintyMeters,
            beaconGate.evaluate(track, nowNanos),
            track.imageGeometry,
        )
    }

    private fun createBeacon(
        item: SpatialFocusItem,
        quality: BeaconQuality,
        headOrientation: BeaconHeadOrientation?,
        nowNanos: Long,
    ): SpatialBeacon? {
        val anchor = when (quality.anchorMode) {
            BeaconAnchorMode.WORLD_ANCHORED -> quality.worldAnchorMeters
            BeaconAnchorMode.ORIENTATION_STABILIZED_RELATIVE -> quality.relativeHeadVectorMeters
            BeaconAnchorMode.NONE -> null
        } ?: return null
        val reference = if (quality.anchorMode == BeaconAnchorMode.ORIENTATION_STABILIZED_RELATIVE) {
            headOrientation?.takeIf {
                it.timestampNanos <= nowNanos &&
                    nowNanos - it.timestampNanos <= maximumBeaconHeadPoseAgeNanos
            } ?: return null
        } else null
        val expiry = Math.addExact(nowNanos, beaconTtlNanos)
        return SpatialBeacon(
            activationId = ++beaconActivationCounter,
            stableTrackId = item.stableTrackId,
            classId = item.classId,
            anchorMode = quality.anchorMode,
            activatedTimestampNanos = nowNanos,
            validUntilTimestampNanos = expiry,
            sourceFrameId = item.sourceFrameId,
            sourceCaptureTimestampNanos = item.sourceCaptureTimestampNanos,
            confidence = item.confidence,
            distanceMeters = item.distanceMeters,
            distanceUncertaintyMeters = quality.distanceUncertaintyMeters,
            anchorVectorMeters = anchor,
            displayHeadVectorMeters = item.headVectorMeters,
            referenceHeadOrientation = reference,
        )
    }

    private fun beaconQuality(beacon: SpatialBeacon) = BeaconQuality(
        eligible = true,
        reason = BeaconQualityReason.ELIGIBLE,
        anchorMode = beacon.anchorMode,
        worldAnchorMeters = beacon.anchorVectorMeters.takeIf {
            beacon.anchorMode == BeaconAnchorMode.WORLD_ANCHORED
        },
        relativeHeadVectorMeters = beacon.anchorVectorMeters.takeIf {
            beacon.anchorMode == BeaconAnchorMode.ORIENTATION_STABILIZED_RELATIVE
        },
        distanceUncertaintyMeters = beacon.distanceUncertaintyMeters,
        validUntilTimestampNanos = beacon.validUntilTimestampNanos,
    )

    private fun SpatialBeacon.asFocusItem() = SpatialFocusItem(
        stableTrackId = stableTrackId,
        classId = classId,
        sourceFrameId = sourceFrameId,
        sourceCaptureTimestampNanos = sourceCaptureTimestampNanos,
        expiresAtTimestampNanos = validUntilTimestampNanos,
        confidence = confidence,
        headVectorMeters = displayHeadVectorMeters,
        distanceMeters = distanceMeters,
        uncertaintyMeters = distanceUncertaintyMeters,
        beaconQuality = beaconQuality(this),
    )

    private companion object {
        val MENU = SpatialFocusMenuOption.entries
        val ITEM_ORDER = compareBy<SpatialFocusItem> { it.distanceMeters }
            .thenBy { SpatialFocusSpeechFormatter.clockHour(it.headVectorMeters) }
            .thenBy(SpatialFocusItem::classId)
            .thenBy(SpatialFocusItem::stableTrackId)
    }
}
