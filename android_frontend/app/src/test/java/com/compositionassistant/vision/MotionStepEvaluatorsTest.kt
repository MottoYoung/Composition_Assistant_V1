package com.compositionassistant.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionStepEvaluatorsTest {
    @Test
    fun `primary shift error follows the requested axis`() {
        assertEquals(12.0, StepValidationMath.primaryShiftError(12.0, 30.0, "left"), 0.001)
        assertEquals(30.0, StepValidationMath.primaryShiftError(12.0, 30.0, "down"), 0.001)
        assertEquals(0.0, StepValidationMath.primaryShiftError(12.0, 30.0, "rotate-cw"), 0.001)
    }

    @Test
    fun `shift vector suppresses subpixel ui jitter`() {
        assertEquals(0.0 to 12.0, StepValidationMath.stabilizeShiftVector(4.0, 12.0))
        assertEquals(-9.0 to 0.0, StepValidationMath.stabilizeShiftVector(-9.0, 7.9))
    }

    @Test
    fun `zoom requires stable aligned frames before completion`() {
        val evaluator = ZoomStepEvaluator(
            state = MotionValidationState(),
            feedbackDebouncer = FeedbackDebouncer(minDurationMs = 0)
        )
        val aligned = components(scale = 1.0)

        val first = evaluator.validate(
            stepKey = 1,
            components = aligned,
            direction = "in",
            matchQuality = 0.3f,
            currentZoom = 1f,
            uiSpaceWidth = 360f,
            uiSpaceHeight = 640f
        )
        val second = evaluator.validate(
            stepKey = 1,
            components = aligned,
            direction = "in",
            matchQuality = 0.3f,
            currentZoom = 1f,
            uiSpaceWidth = 360f,
            uiSpaceHeight = 640f
        )

        assertFalse(first.isCompleted)
        assertTrue(second.isCompleted)
        assertEquals(1f, second.tx ?: 0f, 0.001f)
    }

    @Test
    fun `changing step resets stable frame state`() {
        val state = MotionValidationState()
        val evaluator = ShiftStepEvaluator(state, FeedbackDebouncer(minDurationMs = 0))
        val aligned = components()

        evaluator.validate(1, "shift", aligned, "left", 0.3f, uiSpaceWidth = 360f, uiSpaceHeight = 640f)
        val completed = evaluator.validate(
            1,
            "shift",
            aligned,
            "left",
            0.3f,
            uiSpaceWidth = 360f,
            uiSpaceHeight = 640f
        )
        val nextStep = evaluator.validate(
            2,
            "shift",
            aligned,
            "left",
            0.3f,
            uiSpaceWidth = 360f,
            uiSpaceHeight = 640f
        )

        assertTrue(completed.isCompleted)
        assertFalse(nextStep.isCompleted)
    }

    private fun components(
        tx: Double = 0.0,
        ty: Double = 0.0,
        scale: Double = 1.0,
        rotation: Double = 0.0
    ): HomographyComponents {
        return HomographyComponents(
            translationX = tx,
            translationY = ty,
            translationDistance = kotlin.math.sqrt(tx * tx + ty * ty),
            scaleFactor = scale,
            rotationAngle = rotation
        )
    }
}
