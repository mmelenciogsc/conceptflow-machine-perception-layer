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

enum class BviEnvironmentClass {
    INDOOR,
    OUTDOOR,
    TRANSITION,
    BOTH,
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
    val environmentClass: BviEnvironmentClass = BviEnvironmentClass.BOTH,
    val dimensionBasis: String,
) {
    init {
        require(ID_PATTERN.matches(id))
        require(prompt.isNotBlank() && prompt.length <= 80)
        require(calibrationWeight.isFinite() && calibrationWeight in 0.0..1.0)
        require(calibrationAxis != CalibrationAxis.NONE || calibrationWeight == 0.0)
        require(dimensionBasis.isNotBlank() && dimensionBasis.length <= 96)
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
    val bviClassesList: List<BviClassDefinition> = loadCatalog()

    private val byId = bviClassesList.associateBy(BviClassDefinition::id)

    init {
        require(byId.size == bviClassesList.size) { "BVI class identifiers must be unique" }
        require(bviClassesList.size == EXPECTED_FIXED_CLASSES) {
            "BVI vocabulary must contain exactly $EXPECTED_FIXED_CLASSES classes"
        }
    }

    fun find(id: String): BviClassDefinition? = byId[id]

    val prompts: List<String> = bviClassesList.map(BviClassDefinition::prompt)

    private fun loadCatalog(): List<BviClassDefinition> {
        val stream = BviClassCatalog::class.java.classLoader
            ?.getResourceAsStream(CATALOG_RESOURCE)
            ?: error("missing packaged BVI catalog: $CATALOG_RESOURCE")
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }
                .mapIndexed { expectedIndex, line -> parseCatalogRow(line, expectedIndex) }
                .toList()
        }.also { rows ->
            require(rows.map(BviClassDefinition::prompt).distinct().size == rows.size) {
                "BVI prompts must be unique"
            }
        }
    }

    private fun parseCatalogRow(line: String, expectedIndex: Int): BviClassDefinition {
        val fields = line.split('\t')
        require(fields.size == CATALOG_FIELD_COUNT) { "invalid BVI catalog row" }
        val index = fields[0].toIntOrNull() ?: error("invalid BVI catalog index")
        require(index in 0 until EXPECTED_FIXED_CLASSES) { "BVI catalog index out of range" }
        require(index == expectedIndex) { "BVI catalog indices must be contiguous and ordered" }
        return BviClassDefinition(
            id = fields[1],
            prompt = fields[2],
            group = BviSemanticGroup.valueOf(fields[3]),
            dimensions = PhysicalDimensionsMeters(
                fields[4].toDouble(),
                fields[5].toDouble(),
                fields[6].toDouble(),
                fields[7].toDouble(),
            ),
            calibrationAxis = CalibrationAxis.valueOf(fields[8]),
            calibrationWeight = fields[9].toDouble(),
            textAware = fields[10].toBooleanStrict(),
            environmentClass = BviEnvironmentClass.valueOf(fields[11]),
            dimensionBasis = fields[12],
        )
    }

    private const val CATALOG_RESOURCE = "bvi_catalog.tsv"
    private const val CATALOG_FIELD_COUNT = 13
    private const val EXPECTED_FIXED_CLASSES = 330
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
