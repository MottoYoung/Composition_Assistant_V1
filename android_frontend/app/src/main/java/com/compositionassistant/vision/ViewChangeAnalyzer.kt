package com.compositionassistant.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.compositionassistant.arcore.CameraPoseSample
import com.compositionassistant.data.api.ShotSpec
import com.compositionassistant.guidance.canonicalViewDirection
import com.compositionassistant.guidance.normalizedActionType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "ViewChangeAnalyzer"

data class ViewChangeAnalysisResult(
    val isCompleted: Boolean,
    val progress: Float,
    val feedbackText: String,
    val hasObject: Boolean,
    val targetAlignmentScore: Float,
    val directionScore: Float,
    val targetSimilarityScore: Float,
    val uiHintX: Float,
    val uiHintY: Float,
    val subjectConfidence: Float
)

/**
 * View-change 分析器（v3）
 *
 * 主信号：
 * 1. 取景器 source -> current 的单应性几何：判断真实画面的绕拍/升降/前后移动趋势
 * 2. ARCore/设备位姿：在可用时辅助确认真实位移，避免只转手腕误判
 * 3. ORB/pHash 到目标图的相似度：判断是否接近候选参考图，但不单独主导方向
 *
 * 安全约束：
 * 4. shot_spec + ML Kit 主体检测：防止主体丢失、切到别的主体，作为闭环安全门而非主评分项
 */
class ViewChangeAnalyzer(
    sourceBitmap: Bitmap,
    targetBitmap: Bitmap,
    private val targetShotSpec: ShotSpec? = null
) {
    private val objectDetector: ObjectDetector
    private val referenceDetector: ObjectDetector
    private var stableCompletionFrames: Int = 0
    private val targetSimHistory: ArrayDeque<Float> = ArrayDeque()

    private var sourceBoundingBox: NormalizedBoundingBox? = null
    private var targetBoundingBox: NormalizedBoundingBox? = null
    private var sourceReferenceDetectionSettled: Boolean = false
    private var targetReferenceDetectionSettled: Boolean = false
    private var targetReferenceDetected: Boolean = false
    private var lastTrackedSubjectBox: NormalizedBoundingBox? = null
    private var lastSubjectLockScore: Float = 0f
    private var consecutiveMissingSubjectFrames: Int = 0
    private val targetSpecCenter = targetShotSpec.parseTargetCenter()
    private val targetSpecSize = targetShotSpec.parseTargetSize()

    companion object {
        private const val HISTORY_CAPACITY = 8
        private const val NEAR_TARGET_SUBJECT_MARGIN = 0.06f
        private const val VERTICAL_SCENE_ARRIVAL_MARGIN = 0.06f
        private const val SUBJECT_MISS_GRACE_FRAMES = 4
    }

    init {
        val streamOptions = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        objectDetector = ObjectDetection.getClient(streamOptions)

        val singleOptions = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        referenceDetector = ObjectDetection.getClient(singleOptions)

        targetBoundingBox = applyShotSpecToBox(null)

        detectReferenceObject(sourceBitmap) { box ->
            sourceBoundingBox = box
            sourceReferenceDetectionSettled = true
            Log.d(TAG, "source subject=$box")
        }
        detectReferenceObject(targetBitmap) { detected ->
            targetReferenceDetected = detected != null
            targetBoundingBox = applyShotSpecToBox(detected)
            targetReferenceDetectionSettled = true
            Log.d(TAG, "target subject=$targetBoundingBox")
        }

        Log.d(TAG, "ViewChangeAnalyzer v3 初始化：全局特征 + 位姿 + 主体锁定")
    }

    fun analyze(
        currentFrame: Bitmap,
        actionType: String,
        direction: String,
        perspectiveScore: Float,
        sourceSimilarityScore: Float,
        targetSimilarityScore: Float,
        pHashSimilarity: Float,
        viewGeometry: ViewChangeGeometry = ViewChangeGeometry.Empty,
        cameraPoseSample: CameraPoseSample?,
        callback: (ViewChangeAnalysisResult) -> Unit
    ) {
        val normalizedActionType = actionType.normalizedActionType()
        val canonicalDirection = direction.canonicalViewDirection()
        val isVerticalCameraAction = normalizedActionType in setOf("raisecamera", "lowercamera")
        val isLowerCameraAction = normalizedActionType == "lowercamera"
        val isOrbitAction = normalizedActionType == "orbit"
        val isStepAction = normalizedActionType == "step"
        val isMovingPoseAction = isOrbitAction || isStepAction
        val profile = ViewChangeScoring.profile(normalizedActionType)
        val blendedTargetSimilarity = ViewChangeScoring.blendTargetSimilarity(
            targetSimilarityScore,
            pHashSimilarity
        )

        targetSimHistory.addLast(blendedTargetSimilarity)
        while (targetSimHistory.size > HISTORY_CAPACITY) targetSimHistory.removeFirst()

        val targetGainScore = ViewChangeScoring.targetGain(
            sourceSimilarityScore = sourceSimilarityScore,
            targetSimilarityScore = blendedTargetSimilarity,
            perspectiveScore = perspectiveScore
        )
        val geometryActionScore = ViewChangeScoring.geometryAction(
            normalizedActionType = normalizedActionType,
            direction = canonicalDirection,
            geometry = viewGeometry
        )
        val geometryOppositeScore = ViewChangeScoring.oppositeGeometryAction(
            normalizedActionType = normalizedActionType,
            direction = canonicalDirection,
            geometry = viewGeometry
        )
        val geometryDirectionMargin = geometryActionScore - geometryOppositeScore
        val baseArrivalScore = ViewChangeScoring.baseArrival(
            normalizedActionType = normalizedActionType,
            targetSimilarityScore = blendedTargetSimilarity,
            targetGainScore = targetGainScore,
            perspectiveScore = perspectiveScore,
            geometryActionScore = geometryActionScore
        )

        val poseScore = ViewChangeScoring.poseDirection(
            actionType,
            canonicalDirection,
            cameraPoseSample
        )
        val temporalScore = ViewChangeScoring.temporalDirectionScore(targetSimHistory)
        val sensorDirectionScore = if (poseScore != null) {
            if (isVerticalCameraAction) {
                max(
                    temporalScore,
                    (poseScore * 0.45f + temporalScore * 0.55f).coerceIn(0f, 1f)
                )
            } else {
                (poseScore * 0.75f + temporalScore * 0.25f).coerceIn(0f, 1f)
            }
        } else {
            temporalScore
        }
        val directionScore = ViewChangeScoring.blendDirection(
            geometryScore = geometryActionScore,
            oppositeGeometryScore = geometryOppositeScore,
            sensorScore = sensorDirectionScore,
            temporalScore = temporalScore,
            geometry = viewGeometry
        )
        val stepVisualDirectionSatisfied = isStepAction &&
            poseScore == null &&
            targetGainScore >= 0.36f &&
            blendedTargetSimilarity >= 0.52f &&
            baseArrivalScore >= (profile.arrivalThreshold - 0.05f)

        val wristOnlyMotion = ViewChangeScoring.isLikelyWristOnlyMotion(
            actionType,
            cameraPoseSample
        )

        val image = InputImage.fromBitmap(currentFrame, 0)
        objectDetector.process(image)
            .addOnSuccessListener { objects ->
                val currentBox = selectCurrentSubjectBox(
                    objects = objects,
                    frameWidth = currentFrame.width,
                    frameHeight = currentFrame.height,
                    actionType = actionType,
                    direction = canonicalDirection,
                    baseArrivalScore = baseArrivalScore
                )

                if (currentBox == null) {
                    if (canUseSceneOnlyValidation()) {
                        val sceneResult = validateSceneOnlyViewChange(
                            normalizedActionType = normalizedActionType,
                            actionType = actionType,
                            direction = canonicalDirection,
                            profile = profile,
                            baseArrivalScore = baseArrivalScore,
                            blendedTargetSimilarity = blendedTargetSimilarity,
                            targetGainScore = targetGainScore,
                            directionScore = directionScore,
                            poseScore = poseScore,
                            stepVisualDirectionSatisfied = stepVisualDirectionSatisfied,
                            wristOnlyMotion = wristOnlyMotion,
                            isMovingPoseAction = isMovingPoseAction,
                            isVerticalCameraAction = isVerticalCameraAction,
                            viewGeometry = viewGeometry,
                            geometryActionScore = geometryActionScore,
                            geometryDirectionMargin = geometryDirectionMargin,
                            cameraPoseSample = cameraPoseSample
                        )
                        callback(sceneResult)
                        return@addOnSuccessListener
                    }

                    consecutiveMissingSubjectFrames += 1
                    val hasRecentSubject =
                        lastTrackedSubjectBox != null &&
                            consecutiveMissingSubjectFrames <= SUBJECT_MISS_GRACE_FRAMES
                    stableCompletionFrames = if (hasRecentSubject) {
                        (stableCompletionFrames - 1).coerceAtLeast(0)
                    } else {
                        0
                    }
                    val reducedArrival = if (hasRecentSubject) {
                        (baseArrivalScore * 0.72f + lastSubjectLockScore * 0.10f).coerceIn(0f, 0.58f)
                    } else {
                        (baseArrivalScore * 0.48f).coerceIn(0f, 0.36f)
                    }
                    val uiHint = ViewChangeFeedbackFormatter.uiHint(
                        actionType = actionType,
                        direction = canonicalDirection,
                        progress = reducedArrival,
                        subjectAssessment = null
                    )
                    callback(
                        ViewChangeAnalysisResult(
                            isCompleted = false,
                            progress = reducedArrival,
                            feedbackText = if (hasRecentSubject) {
                                "保持当前移动方向，等主体识别稳定一下"
                            } else if (baseArrivalScore > 0.34f) {
                                "主体快丢了，先把主体带回画面再继续"
                            } else {
                                "先稳住主体，再开始改变机位"
                            },
                            hasObject = false,
                            targetAlignmentScore = reducedArrival,
                            directionScore = directionScore,
                            targetSimilarityScore = blendedTargetSimilarity,
                            uiHintX = uiHint.first,
                            uiHintY = uiHint.second,
                            subjectConfidence = 0f
                        )
                    )
                    return@addOnSuccessListener
                }

                consecutiveMissingSubjectFrames = 0
                val subjectAssessment = assessSubject(
                    currentBox = currentBox,
                    actionType = actionType,
                    direction = canonicalDirection,
                    baseArrivalScore = baseArrivalScore
                )
                lastTrackedSubjectBox = currentBox
                lastSubjectLockScore = subjectAssessment.lockScore
                val adjustedArrival = if (subjectAssessment.hasTargetAnchor) {
                    if (isOrbitAction) {
                        (baseArrivalScore * 0.86f + subjectAssessment.lockScore * 0.14f).coerceIn(0f, 1f)
                    } else {
                        (baseArrivalScore * 0.76f + subjectAssessment.lockScore * 0.24f).coerceIn(0f, 1f)
                    }
                } else {
                    if (isOrbitAction) {
                        (baseArrivalScore * 0.90f + subjectAssessment.lockScore * 0.10f).coerceIn(0f, 1f)
                    } else {
                        (baseArrivalScore * 0.84f + subjectAssessment.lockScore * 0.16f).coerceIn(0f, 1f)
                    }
                }
                val subjectGateScore = if (isMovingPoseAction) {
                    softenedMovingPoseSubjectScore(subjectAssessment)
                } else {
                    subjectAssessment.lockScore
                }
                val nearTargetSubjectThreshold =
                    (profile.subjectThreshold - NEAR_TARGET_SUBJECT_MARGIN).coerceAtLeast(0.18f)
                val requiresStrictSubjectGate = if (isMovingPoseAction || isVerticalCameraAction) {
                    targetReferenceDetected
                } else {
                    subjectAssessment.requiresSubjectGate
                }
                val movingPoseVisualReady = isMovingPoseAction &&
                    blendedTargetSimilarity >= profile.nearTargetSimilarityFloor &&
                    adjustedArrival >= profile.arrivalThreshold &&
                    directionScore >= profile.directionThreshold &&
                    subjectGateScore >= profile.subjectThreshold
                val geometryDirectionReliable = ViewChangeScoring.hasReliableGeometryDirection(
                    geometry = viewGeometry,
                    geometryActionScore = geometryActionScore,
                    geometryDirectionMargin = geometryDirectionMargin,
                    profile = profile
                ) &&
                    adjustedArrival >= (profile.arrivalThreshold - 0.04f) &&
                    directionScore >= profile.directionThreshold
                val targetResidualReady = blendedTargetSimilarity >= profile.targetSimilarityFloor
                val geometryAssistedResidualReady = geometryDirectionReliable &&
                    blendedTargetSimilarity >= profile.targetSimilarityFloor &&
                    targetGainScore >= 0.24f
                val residualReady = blendedTargetSimilarity >= profile.targetSimilarityFloor &&
                    (
                        !subjectAssessment.hasTargetAnchor ||
                            subjectAssessment.targetAlignmentScore >= profile.targetAlignmentFloor ||
                            movingPoseVisualReady ||
                            geometryAssistedResidualReady
                        )
                val effectiveDirectionThreshold = if (stepVisualDirectionSatisfied) {
                    0.12f
                } else {
                    profile.directionThreshold
                }
                val effectiveNearTargetDirectionFloor = if (stepVisualDirectionSatisfied) {
                    0.02f
                } else {
                    profile.nearTargetDirectionFloor
                }
                val orbitVisualDirectionSatisfied = isOrbitAction &&
                    targetGainScore >= 0.34f &&
                    blendedTargetSimilarity >= (profile.targetSimilarityFloor + 0.04f) &&
                    adjustedArrival >= (profile.arrivalThreshold + 0.08f)
                val normalDirectionSatisfied =
                    directionScore >= effectiveDirectionThreshold ||
                        stepVisualDirectionSatisfied ||
                        orbitVisualDirectionSatisfied
                val nearTargetSatisfied = (isMovingPoseAction || isVerticalCameraAction) &&
                    adjustedArrival >= profile.nearTargetArrivalThreshold &&
                    blendedTargetSimilarity >= profile.nearTargetSimilarityFloor &&
                    (
                        !subjectAssessment.hasTargetAnchor ||
                            subjectAssessment.targetAlignmentScore >= profile.nearTargetAlignmentFloor
                        ) &&
                    subjectGateScore >= nearTargetSubjectThreshold &&
                    (
                        directionScore >= effectiveNearTargetDirectionFloor ||
                            stepVisualDirectionSatisfied ||
                            orbitVisualDirectionSatisfied
                        )
                val directionSatisfied = normalDirectionSatisfied || nearTargetSatisfied
                val verticalPoseSatisfied = !isVerticalCameraAction ||
                    ViewChangeScoring.verticalPoseCompletionSatisfied(
                        poseScore = poseScore,
                        sample = cameraPoseSample,
                        geometry = viewGeometry,
                        geometryActionScore = geometryActionScore,
                        geometryDirectionMargin = geometryDirectionMargin
                    )
                val verticalSceneCompleted = isVerticalCameraAction &&
                    verticalPoseSatisfied &&
                    adjustedArrival >= (profile.arrivalThreshold - VERTICAL_SCENE_ARRIVAL_MARGIN) &&
                    targetResidualReady &&
                    directionSatisfied &&
                    !wristOnlyMotion &&
                    subjectGateScore >= nearTargetSubjectThreshold

                val rawCompleted = if (isVerticalCameraAction) {
                    verticalSceneCompleted
                } else {
                    adjustedArrival >= profile.arrivalThreshold &&
                        residualReady &&
                        directionSatisfied &&
                        !wristOnlyMotion &&
                        (!requiresStrictSubjectGate || subjectGateScore >= profile.subjectThreshold)
                }
                stableCompletionFrames = if (rawCompleted) {
                    stableCompletionFrames + 1
                } else {
                    0
                }
                val completed = stableCompletionFrames >= profile.requiredStableFrames
                val uiProgress = ViewChangeScoring.displayedArrivalProgress(
                    normalizedActionType = normalizedActionType,
                    baseArrivalScore = baseArrivalScore,
                    adjustedArrival = adjustedArrival,
                    targetSimilarityScore = blendedTargetSimilarity,
                    directionScore = directionScore,
                    poseScore = poseScore,
                    nearTargetSatisfied = nearTargetSatisfied,
                    isCompleted = completed
                )

                val uiHint = ViewChangeFeedbackFormatter.uiHint(
                    actionType = actionType,
                    direction = canonicalDirection,
                    progress = uiProgress,
                    subjectAssessment = subjectAssessment
                )

                Log.d(
                    TAG,
                    "action=%s arrival=%.2f->%.2f ui=%.2f dir=%.2f geom=%.2f opp=%.2f margin=%.2f gq=%.2f shift=(%.3f,%.3f) scale=%.2f persp=(%.3f,%.3f) proj=(%.3f,%.3f) targetSim=%.2f targetAlign=%.2f residual=%s subject=%.2f gate=%.2f strict=%s nearTarget=%s raw=%s stable=%d/%d".format(
                        normalizedActionType,
                        baseArrivalScore,
                        adjustedArrival,
                        uiProgress,
                        directionScore,
                        geometryActionScore,
                        geometryOppositeScore,
                        geometryDirectionMargin,
                        viewGeometry.quality,
                        viewGeometry.shiftXNorm,
                        viewGeometry.shiftYNorm,
                        viewGeometry.scaleRatio,
                        viewGeometry.horizontalPerspective,
                        viewGeometry.verticalPerspective,
                        viewGeometry.projectiveX,
                        viewGeometry.projectiveY,
                        blendedTargetSimilarity,
                        subjectAssessment.targetAlignmentScore,
                        residualReady,
                        subjectAssessment.lockScore,
                        subjectGateScore,
                        requiresStrictSubjectGate,
                        nearTargetSatisfied,
                        rawCompleted,
                        stableCompletionFrames,
                        profile.requiredStableFrames
                    )
                )

                callback(
                    ViewChangeAnalysisResult(
                        isCompleted = completed,
                        progress = uiProgress,
                        feedbackText = ViewChangeFeedbackFormatter.feedback(
                            actionType = actionType,
                            direction = canonicalDirection,
                            targetSimilarityScore = blendedTargetSimilarity,
                            targetGainScore = targetGainScore,
                            arrivalScore = adjustedArrival,
                            directionScore = directionScore,
                            cameraPoseSample = cameraPoseSample,
                            subjectAssessment = subjectAssessment,
                            nearTargetSatisfied = nearTargetSatisfied,
                            isCompleted = completed
                        ),
                        hasObject = true,
                        targetAlignmentScore = adjustedArrival,
                        directionScore = directionScore,
                        targetSimilarityScore = blendedTargetSimilarity,
                        uiHintX = uiHint.first,
                        uiHintY = uiHint.second,
                        subjectConfidence = subjectAssessment.lockScore
                    )
                )
            }
            .addOnFailureListener { error ->
                consecutiveMissingSubjectFrames += 1
                stableCompletionFrames = if (consecutiveMissingSubjectFrames <= SUBJECT_MISS_GRACE_FRAMES) {
                    (stableCompletionFrames - 1).coerceAtLeast(0)
                } else {
                    0
                }
                Log.e(TAG, "主体检测失败: ${error.message}")
                val reducedArrival = (baseArrivalScore * 0.42f).coerceIn(0f, 0.32f)
                val uiHint = ViewChangeFeedbackFormatter.uiHint(
                    actionType = actionType,
                    direction = canonicalDirection,
                    progress = reducedArrival,
                    subjectAssessment = null
                )
                callback(
                    ViewChangeAnalysisResult(
                        isCompleted = false,
                        progress = reducedArrival,
                        feedbackText = "正在重新识别主体，先稳住手机",
                        hasObject = false,
                        targetAlignmentScore = reducedArrival,
                        directionScore = directionScore,
                        targetSimilarityScore = blendedTargetSimilarity,
                        uiHintX = uiHint.first,
                        uiHintY = uiHint.second,
                        subjectConfidence = 0f
                    )
                )
            }
    }

    private fun canUseSceneOnlyValidation(): Boolean {
        return sourceReferenceDetectionSettled &&
            targetReferenceDetectionSettled &&
            targetSpecCenter == null &&
            targetSpecSize == null &&
            lastTrackedSubjectBox == null &&
            (
                !targetReferenceDetected ||
                    targetBoundingBox?.isWeakSceneProxyBox() == true
                )
    }

    private fun validateSceneOnlyViewChange(
        normalizedActionType: String,
        actionType: String,
        direction: String,
        profile: ViewChangeActionProfile,
        baseArrivalScore: Float,
        blendedTargetSimilarity: Float,
        targetGainScore: Float,
        directionScore: Float,
        poseScore: Float?,
        stepVisualDirectionSatisfied: Boolean,
        wristOnlyMotion: Boolean,
        isMovingPoseAction: Boolean,
        isVerticalCameraAction: Boolean,
        viewGeometry: ViewChangeGeometry,
        geometryActionScore: Float,
        geometryDirectionMargin: Float,
        cameraPoseSample: CameraPoseSample?
    ): ViewChangeAnalysisResult {
        consecutiveMissingSubjectFrames = 0
        val sceneArrival = baseArrivalScore
        val sceneResidualReady = blendedTargetSimilarity >= profile.targetSimilarityFloor
        val orbitVisualDirectionSatisfied = normalizedActionType == "orbit" &&
            targetGainScore >= 0.34f &&
            blendedTargetSimilarity >= (profile.targetSimilarityFloor + 0.04f) &&
            sceneArrival >= (profile.arrivalThreshold + 0.08f)
        val directionSatisfied = directionScore >= profile.directionThreshold ||
            stepVisualDirectionSatisfied ||
            orbitVisualDirectionSatisfied
        val nearTargetSatisfied = (isMovingPoseAction || isVerticalCameraAction) &&
            sceneArrival >= profile.nearTargetArrivalThreshold &&
            blendedTargetSimilarity >= profile.nearTargetSimilarityFloor &&
            (
                directionScore >= profile.nearTargetDirectionFloor ||
                    stepVisualDirectionSatisfied ||
                    orbitVisualDirectionSatisfied
                )
        val geometryDirectionReliable = ViewChangeScoring.hasReliableGeometryDirection(
            geometry = viewGeometry,
            geometryActionScore = geometryActionScore,
            geometryDirectionMargin = geometryDirectionMargin,
            profile = profile
        )
        val targetReady = sceneResidualReady &&
            (
                targetGainScore >= 0.24f ||
                    blendedTargetSimilarity >= profile.nearTargetSimilarityFloor
                )
        val rawCompleted = sceneArrival >= profile.arrivalThreshold &&
            targetReady &&
            geometryDirectionReliable &&
            (directionSatisfied || nearTargetSatisfied) &&
            (
                !isVerticalCameraAction ||
                    ViewChangeScoring.verticalPoseCompletionSatisfied(
                        poseScore = poseScore,
                        sample = cameraPoseSample,
                        geometry = viewGeometry,
                        geometryActionScore = geometryActionScore,
                        geometryDirectionMargin = geometryDirectionMargin
                    )
                ) &&
            !wristOnlyMotion
        val effectiveRawCompleted = if (isVerticalCameraAction) {
            sceneArrival >= (profile.arrivalThreshold - VERTICAL_SCENE_ARRIVAL_MARGIN) &&
                targetReady &&
                geometryDirectionReliable &&
                (directionSatisfied || nearTargetSatisfied) &&
                ViewChangeScoring.verticalPoseCompletionSatisfied(
                    poseScore = poseScore,
                    sample = cameraPoseSample,
                    geometry = viewGeometry,
                    geometryActionScore = geometryActionScore,
                    geometryDirectionMargin = geometryDirectionMargin
                ) &&
                !wristOnlyMotion
        } else {
            rawCompleted
        }

        stableCompletionFrames = if (effectiveRawCompleted) {
            stableCompletionFrames + 1
        } else {
            0
        }
        val completed = stableCompletionFrames >= profile.requiredStableFrames
        val uiProgress = ViewChangeScoring.displayedArrivalProgress(
            normalizedActionType = normalizedActionType,
            baseArrivalScore = baseArrivalScore,
            adjustedArrival = sceneArrival,
            targetSimilarityScore = blendedTargetSimilarity,
            directionScore = directionScore,
            poseScore = poseScore,
            nearTargetSatisfied = nearTargetSatisfied,
            isCompleted = completed
        )
        val uiHint = ViewChangeFeedbackFormatter.uiHint(
            actionType = actionType,
            direction = direction,
            progress = uiProgress,
            subjectAssessment = null
        )
        val feedback = ViewChangeFeedbackFormatter.sceneOnlyFeedback(
            actionType = actionType,
            direction = direction,
            targetGainScore = targetGainScore,
            directionScore = directionScore,
            nearTargetSatisfied = nearTargetSatisfied,
            isCompleted = completed,
            cameraPoseSample = cameraPoseSample
        )

        Log.d(
            TAG,
            "scene-only action=%s arrival=%.2f ui=%.2f dir=%.2f geom=%.2f margin=%.2f gq=%.2f shift=(%.3f,%.3f) scale=%.2f persp=(%.3f,%.3f) proj=(%.3f,%.3f) nearTarget=%s stable=%d/%d".format(
                normalizedActionType,
                sceneArrival,
                uiProgress,
                directionScore,
                geometryActionScore,
                geometryDirectionMargin,
                viewGeometry.quality,
                viewGeometry.shiftXNorm,
                viewGeometry.shiftYNorm,
                viewGeometry.scaleRatio,
                viewGeometry.horizontalPerspective,
                viewGeometry.verticalPerspective,
                viewGeometry.projectiveX,
                viewGeometry.projectiveY,
                nearTargetSatisfied,
                stableCompletionFrames,
                profile.requiredStableFrames
            )
        )

        return ViewChangeAnalysisResult(
            isCompleted = completed,
            progress = uiProgress,
            feedbackText = feedback,
            hasObject = false,
            targetAlignmentScore = sceneArrival,
            directionScore = directionScore,
            targetSimilarityScore = blendedTargetSimilarity,
            uiHintX = uiHint.first,
            uiHintY = uiHint.second,
            subjectConfidence = 0f
        )
    }

    private fun detectReferenceObject(
        bitmap: Bitmap,
        onDetected: (NormalizedBoundingBox?) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        referenceDetector.process(image)
            .addOnSuccessListener { objects ->
                val candidate = selectMostSalientObject(
                    objects = objects,
                    frameWidth = bitmap.width,
                    frameHeight = bitmap.height
                )
                onDetected(candidate)
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "reference subject detection failed: ${error.message}")
                onDetected(null)
            }
    }

    private fun selectCurrentSubjectBox(
        objects: List<DetectedObject>,
        frameWidth: Int,
        frameHeight: Int,
        actionType: String,
        direction: String,
        baseArrivalScore: Float
    ): NormalizedBoundingBox? {
        if (objects.isEmpty()) return null

        val boxes = objects.map { normalizeRect(it.boundingBox, frameWidth, frameHeight) }
        val phase = baseArrivalScore.coerceIn(0f, 1f)
        val previousBox = lastTrackedSubjectBox

        return boxes.maxByOrNull { box ->
            val targetScore = computeTargetAlignmentScore(box)
            val sourceScore = computeSourceConsistencyScore(box, actionType, direction)
            val salienceScore = (box.width * box.height / 0.18f).coerceIn(0f, 1f)
            val continuityScore = previousBox?.let { computeBoxContinuityScore(box, it) } ?: 0.55f
            val centerDistance = sqrt(
                (box.centerX - 0.5f).let { it * it } +
                    (box.centerY - 0.5f).let { it * it }
            )
            val centerScore = (1f - centerDistance / 0.72f).coerceIn(0f, 1f)

            val targetWeight = if (targetBoundingBox != null) {
                0.32f + phase * 0.28f
            } else {
                0.16f + phase * 0.10f
            }
            val sourceWeight = 0.40f - phase * 0.12f

            targetScore * targetWeight +
                sourceScore * sourceWeight +
                continuityScore * 0.16f +
                salienceScore * 0.20f +
                centerScore * 0.12f
        }
    }

    private fun computeBoxContinuityScore(
        currentBox: NormalizedBoundingBox,
        referenceBox: NormalizedBoundingBox
    ): Float {
        val centerDistance = sqrt(
            (currentBox.centerX - referenceBox.centerX).let { it * it } +
                (currentBox.centerY - referenceBox.centerY).let { it * it }
        )
        val referenceArea = (referenceBox.width * referenceBox.height).coerceAtLeast(1e-4f)
        val currentArea = (currentBox.width * currentBox.height).coerceAtLeast(1e-4f)
        val areaError = abs(currentArea - referenceArea) / max(currentArea, referenceArea)
        val centerScore = (1f - centerDistance / 0.26f).coerceIn(0f, 1f)
        val areaScore = (1f - areaError / 0.58f).coerceIn(0f, 1f)
        return (centerScore * 0.72f + areaScore * 0.28f).coerceIn(0f, 1f)
    }

    private fun selectMostSalientObject(
        objects: List<DetectedObject>,
        frameWidth: Int,
        frameHeight: Int
    ): NormalizedBoundingBox? {
        return objects.maxByOrNull { obj ->
            obj.boundingBox.width() * obj.boundingBox.height()
        }?.let { normalizeRect(it.boundingBox, frameWidth, frameHeight) }
    }

    private fun assessSubject(
        currentBox: NormalizedBoundingBox,
        actionType: String,
        direction: String,
        baseArrivalScore: Float
    ): SubjectAssessment {
        val targetAlignmentScore = computeTargetAlignmentScore(currentBox)
        val sourceConsistencyScore = computeSourceConsistencyScore(currentBox, actionType, direction)
        val target = targetBoundingBox
        val targetOffsetX = if (target != null) {
            (target.centerX - currentBox.centerX).coerceIn(-0.45f, 0.45f)
        } else {
            0f
        }
        val targetOffsetY = if (target != null) {
            (target.centerY - currentBox.centerY).coerceIn(-0.45f, 0.45f)
        } else {
            0f
        }

        val phase = baseArrivalScore.coerceIn(0f, 1f)
        val targetWeight = if (target != null) {
            0.30f + phase * 0.42f
        } else {
            0.18f + phase * 0.08f
        }
        val sourceWeight = 0.46f - phase * 0.18f
        val visibilityBoost = 0.14f
        val lockScore = (
            targetAlignmentScore * targetWeight +
                sourceConsistencyScore * sourceWeight +
                visibilityBoost
            ).coerceIn(0f, 1f)

        return SubjectAssessment(
            hasSubject = true,
            lockScore = lockScore,
            targetAlignmentScore = targetAlignmentScore,
            sourceConsistencyScore = sourceConsistencyScore,
            targetOffsetX = targetOffsetX,
            targetOffsetY = targetOffsetY,
            requiresSubjectGate = target != null || targetShotSpec?.viewpointRequired == true,
            hasTargetAnchor = target != null
        )
    }

    private fun computeTargetAlignmentScore(currentBox: NormalizedBoundingBox): Float {
        val target = targetBoundingBox ?: return 0.54f

        val centerError = sqrt(
            (target.centerX - currentBox.centerX).let { it * it } +
                (target.centerY - currentBox.centerY).let { it * it }
        )
        val targetArea = target.width * target.height
        val currentArea = currentBox.width * currentBox.height
        val sizeError = abs(targetArea - currentArea) / max(targetArea, currentArea)

        val centerScore = (1f - centerError / 0.38f).coerceIn(0f, 1f)
        val sizeScore = (1f - sizeError / 0.55f).coerceIn(0f, 1f)

        return (centerScore * 0.70f + sizeScore * 0.30f).coerceIn(0f, 1f)
    }

    private fun NormalizedBoundingBox.isWeakSceneProxyBox(): Boolean {
        val area = width * height
        val nearFullWidth = width > 0.72f
        val nearFullHeight = height > 0.48f
        val touchesHorizontalEdge = centerX - width / 2f < 0.06f || centerX + width / 2f > 0.94f
        val touchesVerticalEdge = centerY - height / 2f < 0.06f || centerY + height / 2f > 0.94f
        return area > 0.24f || (nearFullWidth && touchesHorizontalEdge) || (nearFullHeight && touchesVerticalEdge)
    }

    private fun computeSourceConsistencyScore(
        currentBox: NormalizedBoundingBox,
        actionType: String,
        direction: String
    ): Float {
        val source = sourceBoundingBox ?: return 0.52f

        val sourceAspect = (source.width / source.height.coerceAtLeast(1e-3f)).coerceAtLeast(1e-3f)
        val currentAspect = (currentBox.width / currentBox.height.coerceAtLeast(1e-3f)).coerceAtLeast(1e-3f)
        val aspectError = abs(sourceAspect - currentAspect) / max(sourceAspect, currentAspect)
        val aspectScore = (1f - aspectError / 0.55f).coerceIn(0f, 1f)

        val sourceArea = source.width * source.height
        val currentArea = currentBox.width * currentBox.height
        val areaRatio = currentArea / sourceArea.coerceAtLeast(1e-4f)
        val sizeScore = when (actionType.normalizedActionType()) {
            "step" -> {
                if (direction.equals("backward", ignoreCase = true)) {
                    when {
                        areaRatio in 0.20f..1.05f -> 1f
                        areaRatio < 0.20f -> (areaRatio / 0.20f).coerceIn(0f, 1f)
                        else -> (1.45f - areaRatio / 1.05f).coerceIn(0f, 1f)
                    }
                } else {
                    when {
                        areaRatio in 0.95f..3.20f -> 1f
                        areaRatio < 0.95f -> (areaRatio / 0.95f).coerceIn(0f, 1f)
                        else -> (1.90f - areaRatio / 3.20f).coerceIn(0f, 1f)
                    }
                }
            }
            else -> when {
                areaRatio in 0.45f..2.20f -> 1f
                areaRatio < 0.45f -> (areaRatio / 0.45f).coerceIn(0f, 1f)
                else -> (1.85f - areaRatio / 2.20f).coerceIn(0f, 1f)
            }
        }

        return (aspectScore * 0.62f + sizeScore * 0.38f).coerceIn(0f, 1f)
    }

    private fun softenedMovingPoseSubjectScore(subjectAssessment: SubjectAssessment): Float {
        val consistencyFloor = (subjectAssessment.sourceConsistencyScore * 0.78f + 0.18f)
            .coerceIn(0f, 1f)
        return max(subjectAssessment.lockScore, consistencyFloor)
    }

    private fun applyShotSpecToBox(detectedBox: NormalizedBoundingBox?): NormalizedBoundingBox? {
        if (targetSpecCenter == null && targetSpecSize == null) {
            return detectedBox
        }

        val fallbackCenter = targetSpecCenter
            ?: detectedBox?.let { it.centerX to it.centerY }
            ?: (0.5f to 0.5f)
        val baseBox = detectedBox ?: sourceBoundingBox
        val aspect = baseBox
            ?.let { (it.width / it.height.coerceAtLeast(1e-3f)).coerceIn(0.45f, 2.2f) }
            ?: 0.85f
        val longSide = targetSpecSize
            ?: baseBox?.let { max(it.width, it.height) }
            ?: 0.28f

        val clampedLongSide = longSide.coerceIn(0.10f, 0.82f)
        val width = if (aspect >= 1f) {
            clampedLongSide
        } else {
            (clampedLongSide * aspect).coerceIn(0.08f, 0.82f)
        }
        val height = if (aspect >= 1f) {
            (clampedLongSide / aspect).coerceIn(0.08f, 0.82f)
        } else {
            clampedLongSide
        }

        return NormalizedBoundingBox(
            centerX = fallbackCenter.first.coerceIn(0.08f, 0.92f),
            centerY = fallbackCenter.second.coerceIn(0.08f, 0.92f),
            width = width,
            height = height
        )
    }

    private fun normalizeRect(
        rect: android.graphics.Rect,
        imageWidth: Int,
        imageHeight: Int
    ): NormalizedBoundingBox {
        return NormalizedBoundingBox(
            centerX = (rect.left + rect.width() / 2f) / imageWidth,
            centerY = (rect.top + rect.height() / 2f) / imageHeight,
            width = rect.width().toFloat() / imageWidth,
            height = rect.height().toFloat() / imageHeight
        )
    }

    fun close() {
        objectDetector.close()
        referenceDetector.close()
    }

    fun resetStableFrames() {
        stableCompletionFrames = 0
        consecutiveMissingSubjectFrames = 0
        lastTrackedSubjectBox = null
        lastSubjectLockScore = 0f
        targetSimHistory.clear()
    }
}

private fun ShotSpec?.parseTargetCenter(): Pair<Float, Float>? {
    val center = this?.targetSubjectCenter ?: return null
    if (center.size < 2) return null
    val x = center[0]
    val y = center[1]
    if (!x.isFinite() || !y.isFinite()) return null
    return x.coerceIn(0.05f, 0.95f) to y.coerceIn(0.05f, 0.95f)
}

private fun ShotSpec?.parseTargetSize(): Float? {
    val size = this?.targetSubjectSize ?: return null
    if (!size.isFinite()) return null
    return size.coerceIn(0.08f, 0.82f)
}
