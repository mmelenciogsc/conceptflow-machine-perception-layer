// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.vision

import org.conceptflow.mpl.transport.I420FidelityReport
import org.conceptflow.mpl.transport.PlaneFidelity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecFidelityGateTest {
    @Test
    fun passesPixelsAndEveryModelTensorAtBoundary() {
        val decision = CodecFidelityGate.evaluate(
            pixels(lumaPsnr = 30.0, chromaPsnr = 28.0),
            listOf(
                output(
                    cosine = CodecFidelityGate.MINIMUM_MODEL_COSINE_SIMILARITY,
                    nrmse = CodecFidelityGate.MAXIMUM_MODEL_NORMALIZED_RMSE,
                ),
            ),
        )
        assertTrue(decision.passed)
        assertTrue(decision.failures.isEmpty())
    }

    @Test
    fun reportsEveryFailedDimensionWithoutHidingAnotherFailure() {
        val report = FloatTensorFidelityReport(2, 1, 1, 1.0, 0.16, 0.97)
        val decision = CodecFidelityGate.evaluate(
            pixels(lumaPsnr = 29.9, chromaPsnr = 27.9),
            listOf(NamedTensorFidelity("depth_indoor", report)),
        )
        assertFalse(decision.passed)
        assertEquals(
            listOf(
                "luma_psnr",
                "chroma_psnr",
                "depth_indoor_nonfinite",
                "depth_indoor_cosine",
                "depth_indoor_nrmse",
            ),
            decision.failures,
        )
    }

    @Test
    fun rejectsUnstableRepeatedReferenceInference() {
        val unstable = output(cosine = 0.98, nrmse = 0.02)
        val decision = CodecFidelityGate.evaluate(
            pixels(lumaPsnr = 40.0, chromaPsnr = 40.0),
            modelOutputs = listOf(output(cosine = 1.0, nrmse = 0.0)),
            repeatedReferenceOutputs = listOf(unstable),
        )
        assertFalse(decision.passed)
        assertEquals(listOf("yolo_detection_repeatability"), decision.failures)
    }

    @Test
    fun rejectsARepresentativeFixtureWithoutReferenceInstances() {
        val decision = CodecFidelityGate.evaluate(
            pixels(lumaPsnr = 40.0, chromaPsnr = 40.0),
            modelOutputs = listOf(output(cosine = 1.0, nrmse = 0.0)),
            semanticInstances = SemanticInstanceFidelityReport(0, 0, 0, 1.0, 1.0, 0.0),
        )

        assertFalse(decision.passed)
        assertEquals(listOf("semantic_fixture_empty", "semantic_mean_iou"), decision.failures)
    }

    @Test
    fun acceptsStablePostprocessedSemanticInstances() {
        val decision = CodecFidelityGate.evaluate(
            pixels(lumaPsnr = 40.0, chromaPsnr = 40.0),
            modelOutputs = listOf(output(cosine = 1.0, nrmse = 0.0)),
            semanticInstances = SemanticInstanceFidelityReport(2, 2, 2, 1.0, 1.0, 0.95),
        )

        assertTrue(decision.passed)
    }

    private fun output(cosine: Double, nrmse: Double) = NamedTensorFidelity(
        "yolo_detection",
        FloatTensorFidelityReport(2, 2, 0, 0.0, nrmse, cosine),
    )

    private fun pixels(lumaPsnr: Double, chromaPsnr: Double) = I420FidelityReport(
        luma = PlaneFidelity(4, 0.0, lumaPsnr),
        chroma = PlaneFidelity(2, 0.0, chromaPsnr),
        overall = PlaneFidelity(6, 0.0, minOf(lumaPsnr, chromaPsnr)),
    )
}
