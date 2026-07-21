package com.photoframer.vision

import com.photoframer.arcore.CameraPoseSample
import com.photoframer.arcore.CameraPoseSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewChangeScoringTest {
    @Test
    fun `target gain increases when current frame resembles target more than source`() {
        val weakGain = ViewChangeScoring.targetGain(
            sourceSimilarityScore = 0.7f,
            targetSimilarityScore = 0.3f,
            perspectiveScore = 0.2f
        )
        val strongGain = ViewChangeScoring.targetGain(
            sourceSimilarityScore = 0.3f,
            targetSimilarityScore = 0.7f,
            perspectiveScore = 0.2f
        )

        assertTrue(strongGain > weakGain)
    }

    @Test
    fun `opposite geometry evidence lowers direction confidence`() {
        val geometry = ViewChangeGeometry(
            quality = 1f,
            orbitRightScore = 0.8f,
            orbitLeftScore = 0.1f
        )
        val right = ViewChangeScoring.geometryAction("orbit", "right", geometry)
        val opposite = ViewChangeScoring.oppositeGeometryAction("orbit", "right", geometry)
        val alignedScore = ViewChangeScoring.blendDirection(
            geometryScore = right,
            oppositeGeometryScore = opposite,
            sensorScore = 0.2f,
            temporalScore = 0.2f,
            geometry = geometry
        )
        val contradictedScore = ViewChangeScoring.blendDirection(
            geometryScore = opposite,
            oppositeGeometryScore = right,
            sensorScore = 0.2f,
            temporalScore = 0.2f,
            geometry = geometry
        )

        assertEquals(0.8f, right, 0.001f)
        assertTrue(alignedScore > contradictedScore)
    }

    @Test
    fun `temporal score reflects whether target similarity is improving`() {
        val improving = ViewChangeScoring.temporalDirectionScore(
            listOf(0.2f, 0.25f, 0.3f, 0.5f, 0.6f, 0.7f)
        )
        val degrading = ViewChangeScoring.temporalDirectionScore(
            listOf(0.7f, 0.6f, 0.5f, 0.3f, 0.25f, 0.2f)
        )

        assertTrue(improving > degrading)
    }

    @Test
    fun `wrist only movement requires arcore tilt without vertical translation`() {
        val wristOnly = pose(verticalMeters = 0.01f, pitchDeltaDegrees = 12f)
        val translated = pose(verticalMeters = 0.08f, pitchDeltaDegrees = 12f)

        assertTrue(ViewChangeScoring.isLikelyWristOnlyMotion("raisecamera", wristOnly))
        assertFalse(ViewChangeScoring.isLikelyWristOnlyMotion("raisecamera", translated))
        assertFalse(ViewChangeScoring.isLikelyWristOnlyMotion("orbit", wristOnly))
    }

    @Test
    fun `ui hint shrinks as view change approaches completion`() {
        val early = ViewChangeFeedbackFormatter.uiHint(
            actionType = "orbit",
            direction = "right",
            progress = 0.1f,
            subjectAssessment = null
        )
        val late = ViewChangeFeedbackFormatter.uiHint(
            actionType = "orbit",
            direction = "right",
            progress = 0.9f,
            subjectAssessment = null
        )

        assertTrue(early.first > late.first)
        assertEquals(0f, early.second, 0.001f)
    }

    @Test
    fun `completed feedback takes priority over intermediate corrections`() {
        val feedback = ViewChangeFeedbackFormatter.feedback(
            actionType = "orbit",
            direction = "right",
            targetSimilarityScore = 0f,
            targetGainScore = 0f,
            arrivalScore = 0f,
            directionScore = 0f,
            cameraPoseSample = null,
            subjectAssessment = SubjectAssessment(
                hasSubject = false,
                lockScore = 0f,
                targetAlignmentScore = 0f,
                sourceConsistencyScore = 0f,
                targetOffsetX = 0f,
                targetOffsetY = 0f,
                requiresSubjectGate = true,
                hasTargetAnchor = false
            ),
            nearTargetSatisfied = false,
            isCompleted = true
        )

        assertEquals("✓ 机位与主体都已接近参考图", feedback)
    }

    private fun pose(
        verticalMeters: Float,
        pitchDeltaDegrees: Float
    ): CameraPoseSample {
        return CameraPoseSample(
            source = CameraPoseSource.ARCORE,
            isTracking = true,
            lateralMeters = 0f,
            verticalMeters = verticalMeters,
            forwardMeters = 0f,
            yawDeltaDegrees = 0f,
            pitchDeltaDegrees = pitchDeltaDegrees,
            rollDeltaDegrees = 0f,
            confidence = 1f
        )
    }
}
