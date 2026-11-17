package com.compositionassistant.vision

import com.compositionassistant.arcore.CameraPoseSample
import com.compositionassistant.guidance.canonicalViewDirection
import com.compositionassistant.guidance.normalizedActionType
import kotlin.math.abs

internal data class SubjectAssessment(
    val hasSubject: Boolean,
    val lockScore: Float,
    val targetAlignmentScore: Float,
    val sourceConsistencyScore: Float,
    val targetOffsetX: Float,
    val targetOffsetY: Float,
    val requiresSubjectGate: Boolean,
    val hasTargetAnchor: Boolean
)

/**
 * 将 view-change 的判定数据转换成 UI 提示，不参与图像检测或状态管理。
 */
internal object ViewChangeFeedbackFormatter {
    fun uiHint(
        actionType: String,
        direction: String,
        progress: Float,
        subjectAssessment: SubjectAssessment?
    ): Pair<Float, Float> {
        val decay = (1f - progress).coerceIn(0.1f, 1f)
        val magnitude = 120f * decay
        var hintX = when (actionType.normalizedActionType()) {
            "orbit" -> if (direction.canonicalViewDirection() == "right") magnitude else -magnitude
            else -> when (direction.canonicalViewDirection()) {
                "right" -> magnitude
                "left" -> -magnitude
                else -> 0f
            }
        }
        var hintY = when (actionType.normalizedActionType()) {
            "raisecamera" -> -magnitude
            "lowercamera" -> magnitude
            "step" -> if (direction.equals("backward", ignoreCase = true)) {
                -magnitude * 0.5f
            } else {
                magnitude * 0.5f
            }
            else -> when (direction.lowercase()) {
                "high-angle" -> -magnitude
                "low-angle" -> magnitude
                else -> 0f
            }
        }

        if (subjectAssessment != null && subjectAssessment.hasTargetAnchor) {
            val subjectWeight = (0.10f + subjectAssessment.lockScore * 0.28f)
                .coerceIn(0.10f, 0.38f)
            hintX += subjectAssessment.targetOffsetX * 180f * subjectWeight
            hintY += subjectAssessment.targetOffsetY * 200f * subjectWeight
        }
        return hintX.coerceIn(-150f, 150f) to hintY.coerceIn(-160f, 160f)
    }

    fun feedback(
        actionType: String,
        direction: String,
        targetSimilarityScore: Float,
        targetGainScore: Float,
        arrivalScore: Float,
        directionScore: Float,
        cameraPoseSample: CameraPoseSample?,
        subjectAssessment: SubjectAssessment,
        nearTargetSatisfied: Boolean,
        isCompleted: Boolean
    ): String {
        if (isCompleted) return "✓ 机位与主体都已接近参考图"
        if (nearTargetSatisfied) return "机位差不多了，准备微调构图"
        if (!subjectAssessment.hasSubject) return "主体快丢了，先把主体带回画面再继续"
        if (subjectAssessment.lockScore < 0.20f) return "先稳住主体，不要切到别的物体"
        if (subjectAssessment.hasTargetAnchor && subjectAssessment.targetAlignmentScore < 0.26f) {
            return subjectPositionCorrection(subjectAssessment)
        }
        if (ViewChangeScoring.isLikelyWristOnlyMotion(actionType, cameraPoseSample)) {
            return wristOnlyCorrection(actionType)
        }
        if (targetGainScore < 0.34f) return movementPrompt(actionType, direction, stronger = true)

        val normalizedActionType = actionType.normalizedActionType()
        if (
            normalizedActionType in setOf("raisecamera", "lowercamera") &&
            cameraPoseSample?.isTracking == true &&
            directionScore < 0.34f
        ) {
            return when (normalizedActionType) {
                "lowercamera" -> "把手机整体再放低一点，主体别跑出画面"
                "raisecamera" -> "把手机整体再抬高一点，主体别跑出画面"
                else -> movementPrompt(actionType, direction, stronger = true)
            }
        }
        if (directionScore < 0.40f) return movementDirectionCorrection(actionType, direction)
        if (subjectAssessment.lockScore < 0.42f) return "方向对了，先把主体稳在参考图位置附近"
        if (targetSimilarityScore < 0.55f || arrivalScore < 0.58f) {
            return movementPrompt(actionType, direction, stronger = false)
        }
        return "方向对了，继续微调到参考图位置"
    }

    fun sceneOnlyFeedback(
        actionType: String,
        direction: String,
        targetGainScore: Float,
        directionScore: Float,
        nearTargetSatisfied: Boolean,
        isCompleted: Boolean,
        cameraPoseSample: CameraPoseSample?
    ): String {
        if (isCompleted) return "✓ 机位已接近参考视角"
        if (nearTargetSatisfied) return "机位差不多了，准备微调构图"
        if (ViewChangeScoring.isLikelyWristOnlyMotion(actionType, cameraPoseSample)) {
            return wristOnlyCorrection(actionType)
        }
        if (targetGainScore < 0.34f) return movementPrompt(actionType, direction, stronger = true)
        if (directionScore < 0.40f) return movementDirectionCorrection(actionType, direction)
        return movementPrompt(actionType, direction, stronger = false)
    }

    private fun wristOnlyCorrection(actionType: String): String {
        return when (actionType.normalizedActionType()) {
            "lowercamera" -> "不是只低头拍，把手机整体放低一点"
            "raisecamera" -> "不是只仰头拍，把手机整体抬高一点"
            else -> "继续带着手机整体移动，不是只转手腕"
        }
    }

    private fun subjectPositionCorrection(subjectAssessment: SubjectAssessment): String {
        val absX = abs(subjectAssessment.targetOffsetX)
        val absY = abs(subjectAssessment.targetOffsetY)
        return when {
            absX > absY && subjectAssessment.targetOffsetX > 0.04f -> "保持机位方向，同时让主体再往右一点"
            absX > absY && subjectAssessment.targetOffsetX < -0.04f -> "保持机位方向，同时让主体再往左一点"
            absY >= absX && subjectAssessment.targetOffsetY > 0.04f -> "保持机位方向，同时让主体再往下稳一点"
            absY >= absX && subjectAssessment.targetOffsetY < -0.04f -> "保持机位方向，同时让主体再往上稳一点"
            else -> "继续移动，但别让主体跑偏"
        }
    }

    private fun movementPrompt(actionType: String, direction: String, stronger: Boolean): String {
        return when (actionType.normalizedActionType()) {
            "orbit" -> if (direction.canonicalViewDirection() == "right") {
                if (stronger) "继续向右绕主体移动" else "方向对了，再向右绕一点"
            } else {
                if (stronger) "继续向左绕主体移动" else "方向对了，再向左绕一点"
            }
            "raisecamera" -> if (stronger) {
                "先把手机整体抬高，再轻轻下压镜头"
            } else {
                "再抬高一点，保持主体别跑掉"
            }
            "lowercamera" -> if (stronger) {
                "先把手机整体放低，不是只把镜头往下压"
            } else {
                "再放低一点，并轻轻上扬镜头"
            }
            "step" -> if (direction.equals("backward", ignoreCase = true)) {
                if (stronger) "继续后退一点" else "再后退半步"
            } else {
                if (stronger) "继续靠近主体" else "再靠近一点"
            }
            else -> "继续改变机位，逼近参考图"
        }
    }

    private fun movementDirectionCorrection(actionType: String, direction: String): String {
        return when (actionType.normalizedActionType()) {
            "orbit" -> if (direction.canonicalViewDirection() == "right") {
                "方向还不对，再往主体右侧绕"
            } else {
                "方向还不对，再往主体左侧绕"
            }
            "raisecamera" -> "不是只仰头拍，把手机整体抬高一点"
            "lowercamera" -> "不是只低头拍，把手机整体放低一点"
            "step" -> if (direction.equals("backward", ignoreCase = true)) {
                "方向还不对，带着主体再后退一点"
            } else {
                "方向还不对，带着主体再靠近一点"
            }
            else -> "方向还不对，再调整机位"
        }
    }
}
