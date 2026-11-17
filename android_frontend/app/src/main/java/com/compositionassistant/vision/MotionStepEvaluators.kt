package com.compositionassistant.vision

import com.compositionassistant.arcore.CameraPoseSample
import com.compositionassistant.guidance.normalizedActionType
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

internal data class MotionBaseline(
    val stepKey: Int,
    val actionType: String,
    val direction: String,
    var initialDistance: Double,
    var initialPrimaryError: Double,
    var initialScaleError: Double,
    var framesSeen: Int = 0
)

/**
 * 保存 shift / level / zoom 共用的跨帧状态。
 * 评分器只负责判定，不再让 StepValidator 直接管理基线细节。
 */
internal class MotionValidationState {
    private var baseline: MotionBaseline? = null
    private var stableFrames: Int = 0

    fun ensureBaseline(
        stepKey: Int,
        actionType: String,
        direction: String,
        components: HomographyComponents
    ): MotionBaseline {
        val normalizedAction = actionType.normalizedActionType()
        val normalizedDirection = direction.lowercase()
        val current = baseline
        if (
            current != null &&
            current.stepKey == stepKey &&
            current.actionType == normalizedAction &&
            current.direction == normalizedDirection
        ) {
            if (current.framesSeen < 3) {
                current.initialDistance = minOf(current.initialDistance, components.translationDistance)
                current.initialPrimaryError = minOf(
                    current.initialPrimaryError,
                    StepValidationMath.primaryShiftError(
                        tx = components.translationX,
                        ty = components.translationY,
                        direction = direction
                    )
                )
                current.initialScaleError = minOf(
                    current.initialScaleError,
                    abs(1.0 - components.scaleFactor)
                )
                current.framesSeen += 1
            }
            return current
        }

        stableFrames = 0
        return MotionBaseline(
            stepKey = stepKey,
            actionType = normalizedAction,
            direction = normalizedDirection,
            initialDistance = components.translationDistance,
            initialPrimaryError = StepValidationMath.primaryShiftError(
                tx = components.translationX,
                ty = components.translationY,
                direction = direction
            ),
            initialScaleError = abs(1.0 - components.scaleFactor),
            framesSeen = 1
        ).also { baseline = it }
    }

    fun recordAlignment(aligned: Boolean): Int {
        stableFrames = if (aligned) stableFrames + 1 else 0
        return stableFrames
    }

    fun reset() {
        baseline = null
        stableFrames = 0
    }
}

internal object StepValidationMath {
    fun primaryShiftError(tx: Double, ty: Double, direction: String): Double {
        return when (direction.lowercase()) {
            "cw", "ccw", "rotate-cw", "rotate-ccw" -> 0.0
            "left", "right" -> abs(tx)
            "up", "down" -> abs(ty)
            else -> sqrt(tx * tx + ty * ty)
        }
    }

    fun stabilizeShiftVector(tx: Double, ty: Double): Pair<Double, Double> {
        val deadZone = 8.0
        val uiTx = if (abs(tx) < deadZone) 0.0 else tx
        val uiTy = if (abs(ty) < deadZone) 0.0 else ty
        return uiTx to uiTy
    }

    fun improvementScore(
        improvement: Double,
        baselineError: Double,
        targetThreshold: Double
    ): Float {
        val effectiveBaseline = (baselineError - targetThreshold)
            .coerceAtLeast(targetThreshold * 0.5)
        if (effectiveBaseline <= 1e-6) return 0f
        return (improvement / effectiveBaseline).toFloat().coerceIn(0f, 1f)
    }
}

internal object StepFeedbackFormatter {
    fun shift(tx: Double, ty: Double, distance: Double): String {
        val absX = abs(tx)
        val absY = abs(ty)
        val distanceHint = when {
            distance > 150 -> " (较远)"
            distance > 80 -> ""
            else -> " (接近)"
        }
        val axisThreshold = 20.0
        val horizontalText = when {
            absX < axisThreshold -> null
            tx > 0 -> "向右"
            else -> "向左"
        }
        val verticalText = when {
            absY < axisThreshold -> null
            ty > 0 -> "向下"
            else -> "向上"
        }

        return when {
            horizontalText != null && verticalText != null ->
                "${horizontalText}${verticalText}移动$distanceHint"
            horizontalText != null -> "${horizontalText}移动$distanceHint"
            verticalText != null -> "${verticalText}移动$distanceHint"
            distance > 25 -> "微调位置$distanceHint"
            else -> "接近目标"
        }
    }

    fun rotation(rollAngle: Double, distance: Double, direction: String): String {
        return when {
            abs(rollAngle) > StepValidator.ROLL_THRESHOLD * 2 -> {
                if (direction == "rotate-cw" || direction == "cw") {
                    "手机稍微向右转"
                } else {
                    "手机稍微向左转"
                }
            }
            distance > StepValidator.SHIFT_THRESHOLD * 1.4 -> "角度差不多了，再微调位置"
            else -> "继续微调，让画面放平"
        }
    }
}

internal class FeedbackDebouncer(var minDurationMs: Long = 250L) {
    private var lastText: String = ""
    private var lastChangeTime: Long = 0

    fun debounce(newText: String): String {
        val now = System.currentTimeMillis()
        if (newText != lastText && now - lastChangeTime > minDurationMs) {
            lastText = newText
            lastChangeTime = now
        }
        return lastText
    }
}

internal class HomographySmoothingFilter(private val alpha: Float) {
    private var previous: HomographyComponents? = null

    fun filter(input: HomographyComponents): HomographyComponents {
        val last = previous
        if (last == null) {
            previous = input
            return input
        }

        val newTx = alpha * input.translationX + (1 - alpha) * last.translationX
        val newTy = alpha * input.translationY + (1 - alpha) * last.translationY
        val newScale = alpha * input.scaleFactor + (1 - alpha) * last.scaleFactor
        val deltaRotation = normalizeAngle(input.rotationAngle - last.rotationAngle)
        val result = HomographyComponents(
            translationX = newTx,
            translationY = newTy,
            translationDistance = sqrt(newTx * newTx + newTy * newTy),
            scaleFactor = newScale,
            rotationAngle = normalizeAngle(last.rotationAngle + alpha * deltaRotation)
        )
        previous = result
        return result
    }

    private fun normalizeAngle(angleDegrees: Double): Double {
        var normalized = angleDegrees
        while (normalized > 180.0) normalized -= 360.0
        while (normalized < -180.0) normalized += 360.0
        return normalized
    }
}

internal class ShiftStepEvaluator(
    private val state: MotionValidationState,
    private val feedbackDebouncer: FeedbackDebouncer
) {
    fun validate(
        stepKey: Int,
        actionType: String,
        components: HomographyComponents,
        direction: String,
        matchQuality: Float,
        uiSpaceWidth: Float,
        uiSpaceHeight: Float
    ): StepValidationResult {
        val distance = components.translationDistance
        val tx = components.translationX
        val ty = components.translationY
        val rollAngle = components.rotationAngle
        val normalizedDirection = direction.lowercase()
        val isRotationStep = normalizedDirection in setOf("rotate-cw", "rotate-ccw", "cw", "ccw")
        val primaryError = StepValidationMath.primaryShiftError(tx, ty, direction)
        val uiVector = StepValidationMath.stabilizeShiftVector(tx, ty)
        val completionDistanceThreshold = if (isRotationStep) {
            StepValidator.SHIFT_THRESHOLD
        } else {
            StepValidator.SHIFT_THRESHOLD * 1.16
        }
        val completionRollThreshold = if (isRotationStep) {
            StepValidator.ROLL_THRESHOLD * 2
        } else {
            StepValidator.ROLL_THRESHOLD * 4
        }
        val alignedNow = distance < completionDistanceThreshold &&
            abs(rollAngle) < completionRollThreshold
        val rollProgress = 1.0 / (
            1.0 + exp((abs(rollAngle) - StepValidator.ROLL_THRESHOLD) / 2.0)
            )
        val shiftProgress = 1.0 / (
            1.0 + exp((distance - StepValidator.SHIFT_THRESHOLD) / 15.0)
            )
        val currentAlignmentProgress = if (isRotationStep) {
            ((rollProgress + shiftProgress) / 2.0).toFloat()
        } else {
            (shiftProgress * 0.82 + rollProgress * 0.18).toFloat()
        }

        val baseline = state.ensureBaseline(stepKey, actionType, direction, components)
        val primaryImprovement = baseline.initialPrimaryError - primaryError
        val distanceImprovement = baseline.initialDistance - distance
        val improvementScore = StepValidationMath.improvementScore(
            improvement = maxOf(primaryImprovement, distanceImprovement),
            baselineError = maxOf(baseline.initialPrimaryError, baseline.initialDistance),
            targetThreshold = completionDistanceThreshold
        )
        val movedTowardTarget = primaryImprovement > 8.0 ||
            distanceImprovement > 10.0 ||
            improvementScore >= 0.18f
        val strongAlignmentHold = alignedNow &&
            distance < completionDistanceThreshold * 0.34 &&
            abs(rollAngle) < completionRollThreshold * 0.62
        val nearPerfectHold = alignedNow &&
            distance < completionDistanceThreshold * 0.42 &&
            abs(rollAngle) < completionRollThreshold * 0.72 &&
            matchQuality >= 0.22f
        val alignedFrames = state.recordAlignment(alignedNow)
        val sustainedHold = alignedNow &&
            alignedFrames >= 4 &&
            (matchQuality >= 0.12f || strongAlignmentHold)
        val requiredStableFrames = when {
            strongAlignmentHold -> 2
            nearPerfectHold && !movedTowardTarget -> 3
            else -> 2
        }
        val rawCompleted = alignedNow && (movedTowardTarget || nearPerfectHold || sustainedHold)
        val isCompleted = rawCompleted &&
            (alignedFrames >= requiredStableFrames || sustainedHold)
        val progress = (currentAlignmentProgress * 0.74f + improvementScore * 0.26f)
            .coerceIn(0f, 1f)
        val feedbackText = if (isCompleted) {
            "✓ 位置已对齐"
        } else if (alignedNow && !movedTowardTarget && !nearPerfectHold) {
            when {
                strongAlignmentHold || sustainedHold -> "保持一下，正在确认对齐"
                isRotationStep -> "再轻轻转一点，不要停在原地"
                else -> "基本对齐了，再微调一点"
            }
        } else {
            when {
                isRotationStep -> StepFeedbackFormatter.rotation(
                    rollAngle,
                    distance,
                    normalizedDirection
                )
                abs(rollAngle) > StepValidator.ROLL_THRESHOLD * 4 ->
                    if (rollAngle > 0) "手机稍微向右转，把画面放平" else "手机稍微向左转，把画面放平"
                else -> StepFeedbackFormatter.shift(tx, ty, distance)
            }
        }

        return StepValidationResult(
            isCompleted = isCompleted,
            progress = progress,
            feedbackText = feedbackDebouncer.debounce(feedbackText),
            shiftDistance = distance.toFloat(),
            tx = uiVector.first.toFloat(),
            ty = uiVector.second.toFloat(),
            scaleFactor = components.scaleFactor.toFloat(),
            rotationAngle = rollAngle.toFloat(),
            matchQuality = matchQuality,
            uiSpaceWidth = uiSpaceWidth,
            uiSpaceHeight = uiSpaceHeight
        )
    }
}

internal class LevelStepEvaluator(
    private val state: MotionValidationState,
    private val feedbackDebouncer: FeedbackDebouncer
) {
    fun validate(
        stepKey: Int,
        components: HomographyComponents,
        direction: String,
        matchQuality: Float,
        cameraPoseSample: CameraPoseSample?,
        uiSpaceWidth: Float,
        uiSpaceHeight: Float
    ): StepValidationResult {
        val rollAngle = components.rotationAngle
        val rollError = abs(rollAngle)
        val translationDistance = components.translationDistance
        val alignedNow = rollError < StepValidator.LEVEL_ROTATION_THRESHOLD
        val rotationProgress = (
            1.0 / (1.0 + exp((rollError - StepValidator.LEVEL_ROTATION_THRESHOLD) / 0.95))
            ).toFloat()
        val deviceMotionScore = deviceMotionScore(direction, cameraPoseSample)
        val baseline = state.ensureBaseline(stepKey, "level", direction, components)
        val rotationImprovement = baseline.initialPrimaryError - rollError
        val improvementScore = StepValidationMath.improvementScore(
            improvement = rotationImprovement,
            baselineError = baseline.initialPrimaryError,
            targetThreshold = StepValidator.LEVEL_ROTATION_THRESHOLD
        )
        val movedTowardTarget = rotationImprovement > 0.6 || improvementScore >= 0.16f
        val strongAlignmentHold = alignedNow &&
            rollError < StepValidator.LEVEL_ROTATION_THRESHOLD * 0.55
        val nearPerfectHold = alignedNow && matchQuality >= 0.18f
        val alignedFrames = state.recordAlignment(alignedNow)
        val sustainedHold = alignedNow &&
            alignedFrames >= 3 &&
            (matchQuality >= 0.10f || strongAlignmentHold)
        val requiredStableFrames = if (strongAlignmentHold) 2 else 3
        val rawCompleted = alignedNow && (movedTowardTarget || nearPerfectHold || sustainedHold)
        val isCompleted = rawCompleted &&
            (alignedFrames >= requiredStableFrames || sustainedHold)
        val progress = if (deviceMotionScore != null) {
            (rotationProgress * 0.72f + improvementScore * 0.16f + deviceMotionScore * 0.12f)
                .coerceIn(0f, 1f)
        } else {
            (rotationProgress * 0.82f + improvementScore * 0.18f).coerceIn(0f, 1f)
        }
        val feedbackText = when {
            isCompleted -> "✓ 画面已放平"
            strongAlignmentHold || sustainedHold -> "保持一下，正在确认画面水平"
            else -> StepFeedbackFormatter.rotation(
                rollAngle,
                translationDistance,
                direction.lowercase()
            )
        }

        return StepValidationResult(
            isCompleted = isCompleted,
            progress = progress,
            feedbackText = feedbackDebouncer.debounce(feedbackText),
            shiftDistance = translationDistance.toFloat(),
            tx = 0f,
            ty = 0f,
            scaleFactor = components.scaleFactor.toFloat(),
            rotationAngle = rollAngle.toFloat(),
            matchQuality = matchQuality,
            directionConfidence = deviceMotionScore,
            uiSpaceWidth = uiSpaceWidth,
            uiSpaceHeight = uiSpaceHeight
        )
    }

    private fun deviceMotionScore(
        direction: String,
        cameraPoseSample: CameraPoseSample?
    ): Float? {
        val sample = cameraPoseSample ?: return null
        if (!sample.isTracking) return null
        val signedRollDelta = when (direction.lowercase()) {
            "cw", "rotate-cw" -> sample.rollDeltaDegrees
            "ccw", "rotate-ccw" -> -sample.rollDeltaDegrees
            else -> return null
        }
        return (0.18f + (signedRollDelta / 10f) * 0.82f).coerceIn(0f, 1f)
    }
}

internal class ZoomStepEvaluator(
    private val state: MotionValidationState,
    private val feedbackDebouncer: FeedbackDebouncer
) {
    fun validate(
        stepKey: Int,
        components: HomographyComponents,
        direction: String,
        matchQuality: Float,
        currentZoom: Float,
        uiSpaceWidth: Float,
        uiSpaceHeight: Float
    ): StepValidationResult {
        val visualScale = components.scaleFactor.coerceAtLeast(1e-3)
        val scaleError = abs(1.0 - visualScale)
        val alignedNow = scaleError < StepValidator.ZOOM_THRESHOLD
        val currentAlignmentProgress = (
            1.0 / (1.0 + exp((scaleError - StepValidator.ZOOM_THRESHOLD) / 0.1))
            ).toFloat()
        val baseline = state.ensureBaseline(stepKey, "zoom", direction, components)
        val scaleImprovement = baseline.initialScaleError - scaleError
        val improvementScore = StepValidationMath.improvementScore(
            improvement = scaleImprovement,
            baselineError = baseline.initialScaleError,
            targetThreshold = StepValidator.ZOOM_THRESHOLD
        )
        val movedTowardTarget = scaleImprovement > 0.035 || improvementScore >= 0.20f
        val strongAlignmentHold = alignedNow && scaleError < StepValidator.ZOOM_THRESHOLD * 0.28
        val nearPerfectHold = alignedNow &&
            scaleError < StepValidator.ZOOM_THRESHOLD * 0.45 &&
            matchQuality >= 0.22f
        val alignedFrames = state.recordAlignment(alignedNow)
        val sustainedHold = alignedNow &&
            alignedFrames >= 4 &&
            (matchQuality >= 0.12f || strongAlignmentHold)
        val requiredStableFrames = when {
            strongAlignmentHold -> 2
            nearPerfectHold && !movedTowardTarget -> 3
            else -> 2
        }
        val rawCompleted = alignedNow && (movedTowardTarget || nearPerfectHold || sustainedHold)
        val isCompleted = rawCompleted &&
            (alignedFrames >= requiredStableFrames || sustainedHold)
        val progress = (currentAlignmentProgress * 0.72f + improvementScore * 0.28f)
            .coerceIn(0f, 1f)
        val targetZoom = (currentZoom / visualScale).coerceIn(1.0, 8.0)
        val feedbackText = if (isCompleted) {
            "✓ 焦距已对齐"
        } else if (alignedNow && !movedTowardTarget && !nearPerfectHold) {
            when {
                strongAlignmentHold || sustainedHold -> "保持一下，正在确认焦距"
                direction.lowercase() == "in" -> "继续放大一点，别停在原地"
                else -> "继续缩小一点，别停在原地"
            }
        } else {
            val needZoomIn = direction.lowercase() == "in"
            when {
                needZoomIn && visualScale < 1.0 - StepValidator.ZOOM_THRESHOLD ->
                    "放大变焦至 %.1fx".format(targetZoom)
                needZoomIn && visualScale > 1.0 + StepValidator.ZOOM_THRESHOLD ->
                    "已超过目标，缩回至 %.1fx".format(targetZoom)
                needZoomIn -> "再放大一点"
                visualScale > 1.0 + StepValidator.ZOOM_THRESHOLD ->
                    "缩小变焦至 %.1fx".format(targetZoom)
                visualScale < 1.0 - StepValidator.ZOOM_THRESHOLD ->
                    "已超过目标，放大至 %.1fx".format(targetZoom)
                else -> "再缩小一点"
            }
        }

        return StepValidationResult(
            isCompleted = isCompleted,
            progress = progress,
            feedbackText = feedbackDebouncer.debounce(feedbackText),
            shiftDistance = components.translationDistance.toFloat(),
            tx = visualScale.toFloat(),
            ty = null,
            scaleFactor = components.scaleFactor.toFloat(),
            rotationAngle = components.rotationAngle.toFloat(),
            matchQuality = matchQuality,
            uiSpaceWidth = uiSpaceWidth,
            uiSpaceHeight = uiSpaceHeight
        )
    }
}
