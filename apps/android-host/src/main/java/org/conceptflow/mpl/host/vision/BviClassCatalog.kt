// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import kotlin.math.atan2
import kotlin.math.ln

enum class BviSemanticGroup {
    PERSON_OR_MOBILITY_AID,
    INTERIOR_STRUCTURE,
    PEDESTRIAN_INFRASTRUCTURE,
    STREET_OBJECT,
    VEHICLE,
    TEXT_REGION,
}

enum class CalibrationAxis {
    HEIGHT,
    WIDTH,
    BOTH,
    NONE,
}

data class PhysicalDimensionsMeters(
    val length: Double,
    val width: Double,
    val height: Double,
    val relativeUncertainty: Double,
) {
    init {
        require(length.isFinite() && length > 0.0)
        require(width.isFinite() && width > 0.0)
        require(height.isFinite() && height > 0.0)
        require(relativeUncertainty.isFinite() && relativeUncertainty in 0.05..1.0)
    }
}

data class BviClassDefinition(
    val id: String,
    val prompt: String,
    val group: BviSemanticGroup,
    val dimensions: PhysicalDimensionsMeters,
    val calibrationAxis: CalibrationAxis,
    val calibrationWeight: Double,
    val textAware: Boolean = false,
) {
    init {
        require(ID_PATTERN.matches(id))
        require(prompt.isNotBlank() && prompt.length <= 80)
        require(calibrationWeight.isFinite() && calibrationWeight in 0.0..1.0)
        require(calibrationAxis != CalibrationAxis.NONE || calibrationWeight == 0.0)
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z][a-z0-9_]{1,47}")
    }
}

/**
 * Closed vocabulary baked into a YOLOE export. It deliberately excludes
 * prompt-free discovery and runtime user prompts.
 *
 * Dimensions are representative priors with explicit uncertainty, never a
 * claim that every instance has the listed size. Immediate geometry must not
 * depend on these semantic priors.
 */
object BviClassCatalog {
    val bviClassesList: List<BviClassDefinition> = listOf(
        entry("person", "person", BviSemanticGroup.PERSON_OR_MOBILITY_AID, 0.30, 0.55, 1.70, 0.22, CalibrationAxis.HEIGHT, 0.35),
        entry("guide_dog", "guide dog", BviSemanticGroup.PERSON_OR_MOBILITY_AID, 0.90, 0.30, 0.60, 0.25, CalibrationAxis.HEIGHT, 0.30),
        entry("wheelchair", "wheelchair", BviSemanticGroup.PERSON_OR_MOBILITY_AID, 1.10, 0.70, 1.00, 0.18, CalibrationAxis.BOTH, 0.55),
        entry("mobility_cane", "white mobility cane", BviSemanticGroup.PERSON_OR_MOBILITY_AID, 0.03, 0.03, 1.25, 0.40, CalibrationAxis.NONE, 0.0),
        entry("chair", "chair", BviSemanticGroup.INTERIOR_STRUCTURE, 0.55, 0.55, 0.85, 0.25, CalibrationAxis.HEIGHT, 0.40),
        entry("table", "table", BviSemanticGroup.INTERIOR_STRUCTURE, 0.80, 1.20, 0.75, 0.30, CalibrationAxis.HEIGHT, 0.35),
        entry("bench", "bench", BviSemanticGroup.INTERIOR_STRUCTURE, 0.55, 1.50, 0.85, 0.30, CalibrationAxis.HEIGHT, 0.35),
        entry("doorway", "doorway", BviSemanticGroup.INTERIOR_STRUCTURE, 0.20, 0.90, 2.05, 0.12, CalibrationAxis.BOTH, 0.80),
        entry("door", "door", BviSemanticGroup.INTERIOR_STRUCTURE, 0.05, 0.90, 2.05, 0.12, CalibrationAxis.BOTH, 0.80),
        entry("door_handle", "door handle", BviSemanticGroup.INTERIOR_STRUCTURE, 0.12, 0.05, 0.05, 0.45, CalibrationAxis.NONE, 0.0),
        entry("stairs", "stairs", BviSemanticGroup.INTERIOR_STRUCTURE, 2.50, 1.20, 1.50, 0.65, CalibrationAxis.NONE, 0.0),
        entry("escalator", "escalator", BviSemanticGroup.INTERIOR_STRUCTURE, 8.00, 1.20, 4.00, 0.70, CalibrationAxis.NONE, 0.0),
        entry("elevator_door", "elevator door", BviSemanticGroup.INTERIOR_STRUCTURE, 0.10, 1.10, 2.10, 0.12, CalibrationAxis.BOTH, 0.75),
        entry("wall", "wall", BviSemanticGroup.INTERIOR_STRUCTURE, 0.20, 3.00, 2.50, 0.80, CalibrationAxis.NONE, 0.0),
        entry("curb", "curb", BviSemanticGroup.PEDESTRIAN_INFRASTRUCTURE, 0.30, 0.50, 0.15, 0.35, CalibrationAxis.NONE, 0.0),
        entry("curb_ramp", "curb ramp", BviSemanticGroup.PEDESTRIAN_INFRASTRUCTURE, 1.20, 1.20, 0.15, 0.45, CalibrationAxis.NONE, 0.0),
        entry("crosswalk", "pedestrian crosswalk", BviSemanticGroup.PEDESTRIAN_INFRASTRUCTURE, 8.00, 3.00, 0.01, 0.90, CalibrationAxis.NONE, 0.0),
        entry("platform_edge", "transit platform edge", BviSemanticGroup.PEDESTRIAN_INFRASTRUCTURE, 8.00, 0.15, 0.20, 0.90, CalibrationAxis.NONE, 0.0),
        entry("drop_off_edge", "drop-off edge", BviSemanticGroup.PEDESTRIAN_INFRASTRUCTURE, 2.00, 1.00, 0.10, 0.90, CalibrationAxis.NONE, 0.0),
        entry("pothole", "pothole", BviSemanticGroup.PEDESTRIAN_INFRASTRUCTURE, 0.50, 0.50, 0.08, 0.75, CalibrationAxis.NONE, 0.0),
        entry("pole", "pole", BviSemanticGroup.STREET_OBJECT, 0.20, 0.20, 3.00, 0.30, CalibrationAxis.WIDTH, 0.60),
        entry("bollard", "bollard", BviSemanticGroup.STREET_OBJECT, 0.25, 0.25, 0.90, 0.20, CalibrationAxis.BOTH, 0.70),
        entry("traffic_cone", "traffic cone", BviSemanticGroup.STREET_OBJECT, 0.35, 0.35, 0.70, 0.15, CalibrationAxis.BOTH, 0.80),
        entry("barrier", "pedestrian barrier", BviSemanticGroup.STREET_OBJECT, 0.45, 1.80, 1.00, 0.30, CalibrationAxis.HEIGHT, 0.45),
        entry("fence", "fence", BviSemanticGroup.STREET_OBJECT, 0.15, 2.00, 1.20, 0.65, CalibrationAxis.NONE, 0.0),
        entry("tree_trunk", "tree trunk", BviSemanticGroup.STREET_OBJECT, 0.50, 0.50, 3.00, 0.65, CalibrationAxis.NONE, 0.0),
        entry("low_branch", "low tree branch", BviSemanticGroup.STREET_OBJECT, 1.00, 0.15, 0.15, 0.85, CalibrationAxis.NONE, 0.0),
        entry("overhead_obstacle", "overhead obstacle", BviSemanticGroup.STREET_OBJECT, 1.50, 1.50, 0.30, 0.90, CalibrationAxis.NONE, 0.0),
        entry("trash_bin", "trash bin", BviSemanticGroup.STREET_OBJECT, 0.65, 0.55, 1.00, 0.30, CalibrationAxis.HEIGHT, 0.45),
        entry("construction_scaffold", "construction scaffold", BviSemanticGroup.STREET_OBJECT, 2.00, 2.00, 3.00, 0.70, CalibrationAxis.NONE, 0.0),
        entry("car", "car", BviSemanticGroup.VEHICLE, 4.50, 1.80, 1.50, 0.18, CalibrationAxis.HEIGHT, 0.50),
        entry("bus", "bus", BviSemanticGroup.VEHICLE, 12.00, 2.55, 3.20, 0.25, CalibrationAxis.HEIGHT, 0.40),
        entry("bicycle", "bicycle", BviSemanticGroup.VEHICLE, 1.75, 0.60, 1.10, 0.25, CalibrationAxis.HEIGHT, 0.40),
        entry("motorcycle", "motorcycle", BviSemanticGroup.VEHICLE, 2.10, 0.80, 1.20, 0.22, CalibrationAxis.HEIGHT, 0.40),
        entry("traffic_light", "traffic light", BviSemanticGroup.PEDESTRIAN_INFRASTRUCTURE, 0.35, 0.35, 1.00, 0.35, CalibrationAxis.NONE, 0.0),
        entry("pedestrian_signal", "pedestrian crossing signal", BviSemanticGroup.PEDESTRIAN_INFRASTRUCTURE, 0.30, 0.30, 0.50, 0.30, CalibrationAxis.NONE, 0.0),
        entry("stop_sign", "stop sign", BviSemanticGroup.PEDESTRIAN_INFRASTRUCTURE, 0.05, 0.75, 0.75, 0.12, CalibrationAxis.BOTH, 0.80, textAware = true),
        entry("text_sign", "sign containing readable text", BviSemanticGroup.TEXT_REGION, 0.05, 0.60, 0.40, 0.80, CalibrationAxis.NONE, 0.0, textAware = true),
        entry("room_number_sign", "room number sign", BviSemanticGroup.TEXT_REGION, 0.02, 0.20, 0.12, 0.45, CalibrationAxis.NONE, 0.0, textAware = true),
        entry("information_display", "information display", BviSemanticGroup.TEXT_REGION, 0.08, 0.80, 0.50, 0.55, CalibrationAxis.NONE, 0.0, textAware = true),
    )

    private val byId = bviClassesList.associateBy(BviClassDefinition::id)

    init {
        require(byId.size == bviClassesList.size) { "BVI class identifiers must be unique" }
        require(bviClassesList.size <= MAX_FIXED_CLASSES) { "BVI vocabulary must remain bounded" }
    }

    fun find(id: String): BviClassDefinition? = byId[id]

    val prompts: List<String> = bviClassesList.map(BviClassDefinition::prompt)

    private fun entry(
        id: String,
        prompt: String,
        group: BviSemanticGroup,
        length: Double,
        width: Double,
        height: Double,
        uncertainty: Double,
        axis: CalibrationAxis,
        weight: Double,
        textAware: Boolean = false,
    ) = BviClassDefinition(
        id,
        prompt,
        group,
        PhysicalDimensionsMeters(length, width, height, uncertainty),
        axis,
        weight,
        textAware,
    )

    private const val MAX_FIXED_CLASSES = 48
}

enum class ReferenceDistance(val meters: Double) {
    NEAR_TWO_FEET(0.6096),
    FAR_EIGHT_FEET(2.4384),
}

data class DimensionVectorRecord(
    val classId: String,
    val referenceDistance: ReferenceDistance,
    val dimensions: PhysicalDimensionsMeters,
    val expectedHorizontalAngularExtentRadians: Double,
    val expectedVerticalAngularExtentRadians: Double,
    val calibrationWeight: Double,
    val vector: List<Double>,
)

/**
 * Tiny immutable vector table. At this cardinality a process-local exact scan
 * is faster and more power efficient than shipping a database extension.
 */
class KnownDimensionVectorTable(
    classes: List<BviClassDefinition> = BviClassCatalog.bviClassesList,
) {
    val records: List<DimensionVectorRecord> = classes.flatMap { definition ->
        ReferenceDistance.entries.map { distance -> record(definition, distance) }
    }
    private val byKey = records.associateBy { it.classId to it.referenceDistance }

    init {
        require(classes.isNotEmpty())
        require(byKey.size == classes.size * ReferenceDistance.entries.size) {
            "every BVI class must have exactly one two-foot and one eight-foot vector"
        }
    }

    fun get(classId: String, distance: ReferenceDistance): DimensionVectorRecord? = byKey[classId to distance]

    fun nearest(vector: List<Double>, limit: Int = 3): List<DimensionVectorRecord> {
        require(vector.size == VECTOR_SIZE && vector.all(Double::isFinite))
        require(limit in 1..records.size)
        return records.sortedBy { candidate ->
            candidate.vector.indices.sumOf { index ->
                val delta = candidate.vector[index] - vector[index]
                delta * delta
            }
        }.take(limit)
    }

    private fun record(definition: BviClassDefinition, reference: ReferenceDistance): DimensionVectorRecord {
        val dimensions = definition.dimensions
        val distance = reference.meters
        val horizontal = 2.0 * atan2(dimensions.width, 2.0 * distance)
        val vertical = 2.0 * atan2(dimensions.height, 2.0 * distance)
        return DimensionVectorRecord(
            classId = definition.id,
            referenceDistance = reference,
            dimensions = dimensions,
            expectedHorizontalAngularExtentRadians = horizontal,
            expectedVerticalAngularExtentRadians = vertical,
            calibrationWeight = definition.calibrationWeight,
            vector = listOf(
                ln(dimensions.length),
                ln(dimensions.width),
                ln(dimensions.height),
                horizontal,
                vertical,
                ln(distance),
                dimensions.relativeUncertainty,
                definition.calibrationWeight,
            ),
        )
    }

    private companion object {
        const val VECTOR_SIZE = 8
    }
}
