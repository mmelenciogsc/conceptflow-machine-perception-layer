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
        assertTrue(classes.isNotEmpty())
        assertTrue(classes.size <= 48)
        assertEquals(classes.size, classes.map { it.id }.toSet().size)

        val table = KnownDimensionVectorTable()
        assertEquals(
            "2ca8ebc9d1b7914e1dfd1d288e517e78e1b24be75ad04cd6bc0df3e0455aca44",
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
