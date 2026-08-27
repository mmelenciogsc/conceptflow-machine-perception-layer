// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BviClassCatalogTest {
    @Test
    fun fixedVocabularyIsUniqueBoundedAndHasTwoReferenceRecordsPerClass() {
        val classes = BviClassCatalog.bviClassesList
        assertEquals(330, classes.size)
        assertEquals(classes.size, classes.map { it.id }.toSet().size)
        assertEquals(classes.size, classes.map { it.prompt }.toSet().size)

        val table = KnownDimensionVectorTable()
        assertEquals(
            "f4d5aee2124ee9a65f337337004062b15273939ff0ce7f96740fc3cb28d6a9a6",
            MachineVisionModelProfiles.fixedVocabularySha256,
        )
        assertEquals(classes.size * 2, table.records.size)
        classes.forEach { definition ->
            val near = table.get(definition.id, ReferenceDistance.NEAR_TWO_FEET)
            val far = table.get(definition.id, ReferenceDistance.FAR_EIGHT_FEET)
            assertNotNull(near)
            assertNotNull(far)
            assertEquals(0.6096, near!!.referenceDistance.meters, 0.0)
            assertEquals(2.4384, far!!.referenceDistance.meters, 0.0)
            assertTrue(near.expectedHorizontalAngularExtentRadians > far.expectedHorizontalAngularExtentRadians)
            assertTrue(near.expectedVerticalAngularExtentRadians > far.expectedVerticalAngularExtentRadians)
        }
        assertEquals(660, table.records.size)
        assertTrue(classes.filter { it.dimensionBasis.startsWith("family default") }
            .all { it.calibrationWeight == 0.0 })
    }

    @Test
    fun exactVectorLookupReturnsItsOwnRecordFirst() {
        val table = KnownDimensionVectorTable()
        val expected = table.get("door", ReferenceDistance.FAR_EIGHT_FEET)!!
        assertEquals(expected, table.nearest(expected.vector, limit = 1).single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun vectorLookupRejectsWrongDimensions() {
        KnownDimensionVectorTable().nearest(listOf(1.0, 2.0))
    }
}
