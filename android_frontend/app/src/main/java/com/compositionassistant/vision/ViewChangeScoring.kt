package com.compositionassistant.vision

import com.compositionassistant.arcore.CameraPoseSample
import com.compositionassistant.arcore.CameraPoseSource
import com.compositionassistant.guidance.canonicalViewDirection
import com.compositionassistant.guidance.normalizedActionType
import kotlin.math.abs
import kotlin.math.max

internal data class ViewChangeActionProfile(
    val arrivalThreshold: Float,
    val directionThreshold: Float,
    val subjectThreshold: Float,
    val nearTargetArrivalThreshold: Float,
    val targetSimilarityFloor: Float,
    val targetAlignmentFloor: Float,
    val nearTargetSimilarityFloor: Float,
    val nearTargetAlignmentFloor: Float,
    val nearTargetDirectionFloor: Float,
    val requiredStableFrames: Int
)

/**
 * View-change 的纯计算策略。
 *
 * 这里不持有 ML Kit 检测器或跨帧状态，便于对阈值和融合公式做单元测试。
 */
internal object ViewChangeScoring {
    private const val GEOMETRY_COMPLETION_QUALITY_FLOOR = 0.45f
    private const val GEOMETRY_DIRECTION_MARGIN_FLOOR = 0.16f
    private const val ARCORE_VERTICAL_COMPLETION_FLOOR = 0.38f
    private const val DEVICE_VERTICAL_COMPLETION_FLOOR = 0.46f
    private const val NEAR_TARGET_ARRIVAL_ORBIT = 0.88f

    private val defaultProfile = ViewChangeActionProfile(
        arrivalThreshold = 0.52f,
        directionThreshold = 0.40f,
        subjectThreshold = 0.42f,
        nearTargetArrivalThreshold = 1f,
        targetSimilarityFloor = 0f,
        targetAlignmentFloor = 0f,
        nearTargetSimilarityFloor = 0f,
        nearTargetAlignmentFloor = 0f,
        nearTargetDirectionFloor = 0.05f,
        requiredStableFrames = 3
    )

    private val actionProfiles = mapOf(
        "raisecamera" to ViewChangeActionProfile(
            arrivalThreshold = 0.40f,
            directionThreshold = 0.28f,
            subjectThreshold = 0.36f,
            nearTargetArrivalThreshold = 0.66f,
            targetSimilarityFloor = 0.42f,
            targetAlignmentFloor = 0.32f,
            nearTargetSimilarityFloor = 0.48f,
            nearTargetAlignmentFloor = 0.36f,
            nearTargetDirectionFloor = 0.01f,
            requiredStableFrames = 2
        ),
        "lowercamera" to ViewChangeActionProfile(
            arrivalThreshold = 0.42f,
            directionThreshold = 0.20f,
            subjectThreshold = 0.32f,
            nearTargetArrivalThreshold = 0.60f,
            targetSimilarityFloor = 0.40f,
            targetAlignmentFloor = 0.22f,
            nearTargetSimilarityFloor = 0.45f,
            nearTargetAlignmentFloor = 0.26f,
            nearTargetDirectionFloor = 0.01f,
            requiredStableFrames = 3
        ),
        "orbit" to ViewChangeActionProfile(
            arrivalThreshold = 0.46f,
            directionThreshold = 0.22f,
            subjectThreshold = 0.28f,
            nearTargetArrivalThreshold = NEAR_TARGET_ARRIVAL_ORBIT,
            targetSimilarityFloor = 0.54f,
            targetAlignmentFloor = 0.42f,
            nearTargetSimilarityFloor = 0.62f,
            nearTargetAlignmentFloor = 0.46f,
            nearTargetDirectionFloor = 0.05f,
            requiredStableFrames = 2
        ),
        "step" to ViewChangeActionProfile(
            arrivalThreshold = 0.45f,
            directionThreshold = 0.22f,
            subjectThreshold = 0.24f,
            nearTargetArrivalThreshold = 0.86f,
            targetSimilarityFloor = 0.52f,
            targetAlignmentFloor = 0.40f,
            nearTargetSimilarityFloor = 0.60f,
            nearTargetAlignmentFloor = 0.44f,
            nearTargetDirectionFloor = 0.05f,
            requiredStableFrames = 2
        )
    )

    fun profile(normalizedActionType: String): ViewChangeActionProfile {
        return actionProfiles[normalizedActionType] ?: defaultProfile
    }

    fun targetGain(
        sourceSimilarityScore: Float,
        targetSimilarityScore: Float,
        perspectiveScore: Float
    ): Float {
        val similarityGap = targetSimilarityScore - sourceSimilarityScore
        val gapScore = ((similarityGap + 0.18f) / 0.45f).coerceIn(0f, 1f)
        val departureScore = (
            (1f - sourceSimilarityScore) * 0.55f + perspectiveScore * 0.45f
            ).coerceIn(0f, 1f)
        return (gapScore * 0.70f + departureScore * 0.30f).coerceIn(0f, 1f)
    }

    fun geometryAction(
        normalizedActionType: String,
        direction: String,
        geometry: ViewChangeGeometry
    ): Float {
        return when (normalizedActionType) {
            "orbit" -> if (direction == "right") geometry.orbitRightScore else geometry.orbitLeftScore
            "raisecamera" -> geometry.raiseScore
            "lowercamera" -> geometry.lowerScore
            "step" -> if (direction == "backward") geometry.backwardScore else geometry.forwardScore
            else -> 0f
        }.coerceIn(0f, 1f)
    }

    fun oppositeGeometryAction(
        normalizedActionType: String,
        direction: String,
        geometry: ViewChangeGeometry
    ): Float {
        return when (normalizedActionType) {
            "orbit" -> if (direction == "right") geometry.orbitLeftScore else geometry.orbitRightScore
            "raisecamera" -> geometry.lowerScore
            "lowercamera" -> geometry.raiseScore
            "step" -> if (direction == "backward") geometry.forwardScore else geometry.backwardScore
            else -> 0f
        }.coerceIn(0f, 1f)
    }

    fun blendDirection(
        geometryScore: Float,
        oppositeGeometryScore: Float,
        sensorScore: Float,
        temporalScore: Float,
        geometry: ViewChangeGeometry
    ): Float {
        if (geometry.quality <= 0.08f) return sensorScore
        val signedGeometryScore = (geometryScore - oppositeGeometryScore * 0.70f)
            .coerceIn(0f, 1f)
        val geometryWeight = (0.45f + geometry.quality * 0.35f).coerceIn(0.45f, 0.80f)
        return (
            signedGeometryScore * geometryWeight +
                sensorScore * (1f - geometryWeight) * 0.75f +
                temporalScore * (1f - geometryWeight) * 0.25f
            ).coerceIn(0f, 1f)
    }

    fun hasReliableGeometryDirection(
        geometry: ViewChangeGeometry,
        geometryActionScore: Float,
        geometryDirectionMargin: Float,
        profile: ViewChangeActionProfile
    ): Boolean {
        return geometry.quality >= GEOMETRY_COMPLETION_QUALITY_FLOOR &&
            geometryActionScore >= profile.directionThreshold &&
            geometryDirectionMargin >= GEOMETRY_DIRECTION_MARGIN_FLOOR
    }

    fun blendTargetSimilarity(targetSimilarityScore: Float, pHashSimilarity: Float): Float {
        return (targetSimilarityScore * 0.6f + pHashSimilarity * 0.4f).coerceIn(0f, 1f)
    }

    fun baseArrival(
        normalizedActionType: String,
        targetSimilarityScore: Float,
        targetGainScore: Float,
        perspectiveScore: Float,
        geometryActionScore: Float
    ): Float {
        return when (normalizedActionType) {
            "raisecamera", "lowercamera" -> (
                geometryActionScore * 0.34f +
                    perspectiveScore * 0.18f +
                    targetGainScore * 0.26f +
                    targetSimilarityScore * 0.22f
                ).coerceIn(0f, 1f)
            "orbit" -> (
                geometryActionScore * 0.36f +
                    perspectiveScore * 0.16f +
                    targetGainScore * 0.26f +
                    targetSimilarityScore * 0.22f
                ).coerceIn(0f, 1f)
            "step" -> (
                geometryActionScore * 0.34f +
                    targetGainScore * 0.30f +
                    targetSimilarityScore * 0.26f +
                    perspectiveScore * 0.10f
                ).coerceIn(0f, 1f)
            else -> (
                targetSimilarityScore * 0.55f +
                    targetGainScore * 0.30f +
                    perspectiveScore * 0.15f
                ).coerceIn(0f, 1f)
        }
    }

    fun temporalDirectionScore(samples: Collection<Float>): Float {
        if (samples.size < 4) return 0.22f
        val values = samples.toList()
        val windowSize = minOf(3, values.size / 2)
        val earlyMean = values.take(windowSize).average().toFloat()
        val recentMean = values.takeLast(windowSize).average().toFloat()
        return (0.5f + (recentMean - earlyMean) / 0.20f).coerceIn(0f, 1f)
    }

    fun displayedArrivalProgress(
        normalizedActionType: String,
        baseArrivalScore: Float,
        adjustedArrival: Float,
        targetSimilarityScore: Float,
        directionScore: Float,
        poseScore: Float?,
        nearTargetSatisfied: Boolean,
        isCompleted: Boolean
    ): Float {
        if (isCompleted) return 1f
        if (normalizedActionType != "orbit") return adjustedArrival

        val poseAwareScore = if (poseScore != null) {
            (
                baseArrivalScore * 0.50f +
                    adjustedArrival * 0.22f +
                    targetSimilarityScore * 0.12f +
                    poseScore * 0.16f
                ).coerceIn(0f, 1f)
        } else {
            (
                baseArrivalScore * 0.68f +
                    adjustedArrival * 0.22f +
                    targetSimilarityScore * 0.10f
                ).coerceIn(0f, 1f)
        }
        val normalized = ((poseAwareScore - 0.12f) / (NEAR_TARGET_ARRIVAL_ORBIT - 0.12f))
            .coerceIn(0f, 1f)
        val progress = max(normalized, adjustedArrival * 0.92f) +
            (directionScore * 0.08f).coerceIn(0f, 0.08f)
        return if (nearTargetSatisfied) {
            progress.coerceAtLeast(0.94f).coerceIn(0f, 0.99f)
        } else {
            progress.coerceIn(0f, 0.98f)
        }
    }

    fun poseDirection(
        actionType: String,
        direction: String,
        sample: CameraPoseSample?
    ): Float? {
        sample ?: return null
        return when (actionType.normalizedActionType()) {
            "raisecamera" -> verticalPose(sample, shouldRaise = true)
            "lowercamera" -> verticalPose(sample, shouldRaise = false)
            "step" -> stepPose(sample, direction)
            "orbit" -> orbitPose(sample, direction)
            else -> null
        }
    }

    fun verticalPoseCompletionSatisfied(
        poseScore: Float?,
        sample: CameraPoseSample?,
        geometry: ViewChangeGeometry,
        geometryActionScore: Float,
        geometryDirectionMargin: Float
    ): Boolean {
        val geometrySatisfied = geometry.quality >= GEOMETRY_COMPLETION_QUALITY_FLOOR &&
            geometryActionScore >= 0.20f &&
            geometryDirectionMargin >= GEOMETRY_DIRECTION_MARGIN_FLOOR
        val poseSatisfied = when {
            poseScore == null -> false
            sample?.source == CameraPoseSource.ARCORE && sample.isTracking ->
                poseScore >= ARCORE_VERTICAL_COMPLETION_FLOOR
            else -> poseScore >= DEVICE_VERTICAL_COMPLETION_FLOOR
        }
        return poseSatisfied || geometrySatisfied
    }

    fun isLikelyWristOnlyMotion(
        actionType: String,
        sample: CameraPoseSample?
    ): Boolean {
        sample ?: return false
        if (actionType.normalizedActionType() !in setOf("raisecamera", "lowercamera")) {
            return false
        }
        val tiltMagnitude = max(abs(sample.pitchDeltaDegrees), abs(sample.rollDeltaDegrees))
        return sample.isTracking &&
            sample.source == CameraPoseSource.ARCORE &&
            abs(sample.verticalMeters) < 0.03f &&
            tiltMagnitude > 8f
    }

    private fun verticalPose(sample: CameraPoseSample, shouldRaise: Boolean): Float {
        if (!sample.isTracking) return 0f
        if (sample.source != CameraPoseSource.ARCORE) {
            val signedPitchDelta = if (shouldRaise) {
                -sample.pitchDeltaDegrees
            } else {
                sample.pitchDeltaDegrees
            }
            return (0.12f + (signedPitchDelta / 14f) * 0.48f).coerceIn(0f, 0.55f)
        }
        val verticalProgress = if (shouldRaise) {
            sample.verticalMeters / 0.07f
        } else {
            -sample.verticalMeters / 0.06f
        }
        return (0.18f + verticalProgress * 0.82f).coerceIn(0f, 1f)
    }

    private fun stepPose(sample: CameraPoseSample, direction: String): Float? {
        if (sample.source != CameraPoseSource.ARCORE || !sample.isTracking) return null
        val forwardProgress = if (direction.canonicalViewDirection() == "backward") {
            -sample.forwardMeters / 0.16f
        } else {
            sample.forwardMeters / 0.16f
        }
        return (0.18f + forwardProgress * 0.82f).coerceIn(0f, 1f)
    }

    private fun orbitPose(sample: CameraPoseSample, direction: String): Float {
        val moveRight = direction.canonicalViewDirection() == "right"
        val signedYawDelta = if (moveRight) -sample.yawDeltaDegrees else sample.yawDeltaDegrees
        val yawProgress = (0.08f + (signedYawDelta / 24f) * 0.42f).coerceIn(0f, 0.50f)
        if (sample.source != CameraPoseSource.ARCORE || !sample.isTracking) return yawProgress

        val lateralProgress = if (moveRight) {
            sample.lateralMeters / 0.14f
        } else {
            -sample.lateralMeters / 0.14f
        }
        val lateralScore = (0.15f + lateralProgress * 0.85f).coerceIn(0f, 1f)
        return max(
            lateralScore * 0.88f + yawProgress * 0.12f,
            lateralScore
        ).coerceIn(0f, 1f)
    }
}
