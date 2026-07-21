package com.photoframer.vision

import android.graphics.Bitmap
import android.util.Log
import com.photoframer.arcore.CameraPoseSample
import com.photoframer.data.api.ShotSpec
import com.photoframer.guidance.isViewpointActionType
import com.photoframer.guidance.normalizedActionType
import com.photoframer.inframe.InFrameGuideValidationConfig
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "StepValidator"

/**
 * 步骤验证器
 */
class StepValidator(
    targetBitmap: Bitmap,
    private val inFrameGuideConfig: InFrameGuideValidationConfig? = null,
    private val shotSpec: ShotSpec? = null
) {
    private val featureMatcher = FeatureMatcher(FeatureMatcherProfile.REALTIME_ORB)
    private val homographyAnalyzer = HomographyAnalyzer()
    private val targetViewMatcher = FeatureMatcher(FeatureMatcherProfile.REALTIME_ORB)
    private val targetReferenceBitmap: Bitmap
    private var viewChangeSourceMatcher: FeatureMatcher? = null
    private var viewChangeAnalyzer: ViewChangeAnalyzer? = null
    private var viewChangeBaselineStepKey: Int? = null
    private var targetPHash: Long = 0L
    private var cachedCurrentPHash: Long = 0L
    private var pHashFrameCounter: Int = 0
    
    // 阈值设定
    companion object {
        const val VALIDATION_FRAME_WIDTH = 360
        const val SHIFT_THRESHOLD = 25.0       // 平移阈值 (25px on 360px frame ≈ 7% 帧宽)
        const val ZOOM_THRESHOLD = 0.10        // 缩放阈值 (10%，更严格)
        const val LEVEL_ROTATION_THRESHOLD = 2.4 // 水平校正阈值（度）
        const val SCALE_GUIDANCE_THRESHOLD = 0.15 // 触发缩放建议的阈值
        const val ROTATION_THRESHOLD = 5.0     // 旋转阈值 (5度，用于 View-change)
        const val ROLL_THRESHOLD = 3.0         // Roll 水平校正阈值 (3度，用于 Shift)
        const val MIN_MATCH_QUALITY = 6        // 最小匹配点数
        private const val PHASH_COMPUTE_INTERVAL = 3
    }
    
    init {
        // 将目标图片缩放到与分析帧相同的固定宽度坐标系
        // 确保 Scale=1.0 代表完美匹配，消除分辨率差异带来的伪误差
        val scaledTarget = if (targetBitmap.width != VALIDATION_FRAME_WIDTH) {
            ImageConverter.scaleBitmapToWidth(targetBitmap, VALIDATION_FRAME_WIDTH)
        } else {
            targetBitmap
        }
        
        targetReferenceBitmap = scaledTarget
        val sharpenedTarget = sharpenForMatching(scaledTarget)
        featureMatcher.setTargetImage(sharpenedTarget)
        targetViewMatcher.setTargetImage(sharpenedTarget)
        targetPHash = PerceptualHashMatcher.computeHash(targetReferenceBitmap)
        
        Log.d(TAG, "StepValidator 初始化完成 (Target Resized to ${scaledTarget.width}px)")
    }
    
    // 状态保持
    private var lastValidComponents: HomographyComponents? = null
    private var lastValidTime: Long = 0
    private val PERSISTENCE_DURATION = 280L // 丢失目标后短暂保持，避免引导“黏住”
    
    // 平滑滤波器
    private val smoothingFilter = HomographySmoothingFilter(alpha = 0.80f)
    private val feedbackDebouncer = FeedbackDebouncer()
    private val motionValidationState = MotionValidationState()
    private val shiftEvaluator = ShiftStepEvaluator(motionValidationState, feedbackDebouncer)
    private val levelEvaluator = LevelStepEvaluator(motionValidationState, feedbackDebouncer)
    private val zoomEvaluator = ZoomStepEvaluator(motionValidationState, feedbackDebouncer)

    private fun uiSpaceWidth(): Float = targetReferenceBitmap.width.toFloat()

    private fun uiSpaceHeight(): Float = targetReferenceBitmap.height.toFloat()

    /**
     * 验证当前帧是否完成指定步骤
     */
    fun validateStep(
        currentFrame: Bitmap,
        stepKey: Int,
        actionType: String,
        direction: String,
        currentZoomRatio: Float = 1.0f,
        cameraPoseSample: CameraPoseSample? = null
    ): StepValidationResult {
        Log.d(TAG, "开始验证: actionType=$actionType, direction=$direction, zoom=$currentZoomRatio")
        val normalizedActionType = actionType.normalizedActionType()
        val isInFrameZoomStep = inFrameGuideConfig != null && normalizedActionType == "zoom"
        feedbackDebouncer.minDurationMs = if (normalizedActionType.isViewpointActionType()) 300L else 180L
        val pHashSimilarity = getOrComputePHash(currentFrame)
        
        // 计算单应性矩阵
        val result = featureMatcher.computeHomography(currentFrame)

        try {
            Log.d(TAG, "匹配结果: matchCount=${result.matchCount}, message=${result.message}")

            val currentTime = System.currentTimeMillis()
            var components: HomographyComponents? = null
            var matchQuality = 0f
            var usingFallbackComponents = false

            if (result.homography != null && result.matchCount >= MIN_MATCH_QUALITY) {
                // 分解单应性矩阵
                components = homographyAnalyzer.decompose(result.homography)
                val orbQuality = computeOrbQuality(result.matchCount)
                matchQuality = (orbQuality * 0.6f + pHashSimilarity * 0.4f).coerceIn(0f, 1f)
            } else {
                matchQuality = (pHashSimilarity * 0.55f).coerceIn(0f, 1f)
            }

            // 策略：如果当前帧识别失败，但在有效期内，则使用上一次的有效数据
            if (components == null) {
                if (isInFrameZoomStep) {
                    return validateInFrameZoom(direction, matchQuality, currentZoomRatio)
                }
                if (pHashSimilarity > 0.75f && lastValidComponents != null) {
                    components = lastValidComponents
                    usingFallbackComponents = true
                    matchQuality = (pHashSimilarity * 0.5f).coerceIn(0f, 1f)
                    Log.d(TAG, "ORB 匹配不足，使用 pHash 兜底缓存")
                } else if (currentTime - lastValidTime < PERSISTENCE_DURATION && lastValidComponents != null) {
                    // 使用缓存数据，但稍微降低匹配质量
                    components = lastValidComponents
                    usingFallbackComponents = true
                    matchQuality = maxOf(matchQuality, pHashSimilarity * 0.35f, 0.24f)
                    Log.d(TAG, "处于保持期，使用缓存数据")
                } else {
                    // 确实丢失了
                    return StepValidationResult(
                        isCompleted = false,
                        progress = 0f,
                        feedbackText = "", // 丢失时返回空，UI显示静态引导
                        shiftDistance = null,
                        tx = null,
                        ty = null,
                        scaleFactor = null,
                        rotationAngle = null,
                        matchQuality = 0f,
                        uiSpaceWidth = uiSpaceWidth(),
                        uiSpaceHeight = uiSpaceHeight()
                    )
                }
            } else {
                // 当前帧有效，更新缓存和时间
                // 应用平滑滤波
                val smoothed = smoothingFilter.filter(components)
                components = smoothed

                lastValidComponents = smoothed
                lastValidTime = currentTime
            }

            Log.d(TAG, "验证数据: tx=${components!!.translationX}, ty=${components.translationY}, " +
                    "scale=${components.scaleFactor}, rotation=${components.rotationAngle}")

            // 根据操作类型判定
            return when (normalizedActionType) {
                "shift" -> {
                    if (inFrameGuideConfig != null) {
                        validateInFrameShift(
                            components = components,
                            direction = direction,
                            matchQuality = matchQuality
                        )
                    } else {
                        shiftEvaluator.validate(
                            stepKey = stepKey,
                            actionType = normalizedActionType,
                            components = components,
                            direction = direction,
                            matchQuality = matchQuality,
                            usingFallbackComponents = usingFallbackComponents,
                            uiSpaceWidth = uiSpaceWidth(),
                            uiSpaceHeight = uiSpaceHeight()
                        )
                    }
                }
                "level" -> levelEvaluator.validate(
                    stepKey = stepKey,
                    components = components,
                    direction = direction,
                    matchQuality = matchQuality,
                    cameraPoseSample = cameraPoseSample,
                    uiSpaceWidth = uiSpaceWidth(),
                    uiSpaceHeight = uiSpaceHeight()
                )
                "zoom" -> {
                    if (inFrameGuideConfig != null) {
                        validateInFrameZoom(direction, matchQuality, currentZoomRatio)
                    } else {
                        zoomEvaluator.validate(
                            stepKey = stepKey,
                            components = components,
                            direction = direction,
                            matchQuality = matchQuality,
                            currentZoom = currentZoomRatio,
                            uiSpaceWidth = uiSpaceWidth(),
                            uiSpaceHeight = uiSpaceHeight()
                        )
                    }
                }
                else -> {
                    if (normalizedActionType.isViewpointActionType()) {
                        validateViewChange(components, actionType, direction, matchQuality)
                    } else {
                        shiftEvaluator.validate(
                            stepKey = stepKey,
                            actionType = normalizedActionType,
                            components = components,
                            direction = direction,
                            matchQuality = matchQuality,
                            usingFallbackComponents = usingFallbackComponents,
                            uiSpaceWidth = uiSpaceWidth(),
                            uiSpaceHeight = uiSpaceHeight()
                        )
                    }
                }
            }
        } finally {
            result.release()
        }
    }
    
    /**
     * 画面内构图的平移验证：
     * 使用“原始整图 -> 当前帧”的相似变换，把推荐裁切中心投影到当前帧，
     * 再判断它与画面中心的偏差。
     */
    private fun validateInFrameShift(
        components: HomographyComponents,
        direction: String,
        matchQuality: Float
    ): StepValidationResult {
        val guide = inFrameGuideConfig ?: return shiftEvaluator.validate(
            stepKey = -1,
            actionType = "shift",
            components = components,
            direction = direction,
            matchQuality = matchQuality,
            uiSpaceWidth = uiSpaceWidth(),
            uiSpaceHeight = uiSpaceHeight()
        )

        val projectedTargetCenter = projectNormalizedPoint(
            xNorm = guide.targetCenterXNormalized,
            yNorm = guide.targetCenterYNormalized,
            components = components
        )

        val frameCenterX = targetReferenceBitmap.width / 2.0
        val frameCenterY = targetReferenceBitmap.height / 2.0
        val offsetX = projectedTargetCenter.first - frameCenterX
        val offsetY = projectedTargetCenter.second - frameCenterY
        val distance = sqrt(offsetX * offsetX + offsetY * offsetY)
        val rollAngle = components.rotationAngle

        val isCompleted = distance < SHIFT_THRESHOLD &&
            abs(rollAngle) < ROLL_THRESHOLD * 2

        val rollProgress = (1.0 / (1.0 + exp((abs(rollAngle) - ROLL_THRESHOLD) / 2.0)))
        val shiftProgress = (1.0 / (1.0 + exp((distance - SHIFT_THRESHOLD) / 15.0)))
        val progress = ((rollProgress + shiftProgress) / 2.0).toFloat()

        val feedbackText = if (isCompleted) {
            "✓ 中心已对齐"
        } else if (abs(rollAngle) > ROLL_THRESHOLD * 2) {
            if (rollAngle > 0) "手机稍微向右转，把画面放平" else "手机稍微向左转，把画面放平"
        } else {
            StepFeedbackFormatter.shift(offsetX, offsetY, distance)
        }

        return StepValidationResult(
            isCompleted = isCompleted,
            progress = progress,
            feedbackText = feedbackDebouncer.debounce(feedbackText),
            shiftDistance = distance.toFloat(),
            tx = offsetX.toFloat(),
            ty = offsetY.toFloat(),
            scaleFactor = components.scaleFactor.toFloat(),
            rotationAngle = rollAngle.toFloat(),
            matchQuality = matchQuality,
            uiSpaceWidth = uiSpaceWidth(),
            uiSpaceHeight = uiSpaceHeight()
        )
    }
    
    /**
     * 画面内构图的缩放验证：
     * 直接比较当前系统变焦与推荐裁切所需的目标变焦，避免裁切框带来的几何耦合。
     */
    private fun validateInFrameZoom(
        direction: String,
        matchQuality: Float,
        currentZoom: Float
    ): StepValidationResult {
        val guide = inFrameGuideConfig ?: return StepValidationResult(
            isCompleted = false,
            progress = 0f,
            feedbackText = "正在准备变焦参考...",
            shiftDistance = null,
            tx = null,
            ty = null,
            scaleFactor = null,
            rotationAngle = null,
            matchQuality = matchQuality
        )

        val targetZoom = guide.targetZoomRatio.coerceIn(1.0f, 8.0f)
        val zoomRatio = (currentZoom / targetZoom).coerceAtLeast(1e-3f)
        val relativeError = abs(1.0f - zoomRatio)

        val isCompleted = relativeError < ZOOM_THRESHOLD
        val progress = (1.0 / (1.0 + exp((relativeError - ZOOM_THRESHOLD) / 0.1))).toFloat()

        val feedbackText = if (isCompleted) {
            "✓ 焦距已对齐"
        } else {
            val needZoomIn = direction.lowercase() == "in"
            when {
                needZoomIn && currentZoom < targetZoom - 0.05f ->
                    "放大变焦至 %.1fx".format(targetZoom)
                needZoomIn && currentZoom > targetZoom + 0.05f ->
                    "已超过目标，缩回至 %.1fx".format(targetZoom)
                needZoomIn -> "再放大一点"
                !needZoomIn && currentZoom > targetZoom + 0.05f ->
                    "缩小变焦至 %.1fx".format(targetZoom)
                !needZoomIn && currentZoom < targetZoom - 0.05f ->
                    "已超过目标，放大至 %.1fx".format(targetZoom)
                else -> "微调焦距"
            }
        }

        return StepValidationResult(
            isCompleted = isCompleted,
            progress = progress,
            feedbackText = feedbackDebouncer.debounce(feedbackText),
            shiftDistance = null,
            tx = zoomRatio,
            ty = null,
            scaleFactor = targetZoom,
            rotationAngle = null,
            matchQuality = matchQuality,
            uiSpaceWidth = uiSpaceWidth(),
            uiSpaceHeight = uiSpaceHeight()
        )
    }
    
    /**
     * 验证视角变换步骤
     * 真正完成判定由 validateViewChangeAsync 中的异步融合逻辑给出
     */
    private fun validateViewChange(
        components: HomographyComponents,
        actionType: String,
        direction: String,
        matchQuality: Float
    ): StepValidationResult {
        // View-change 验证通过 validateViewChangeAsync 异步处理
        // 此处返回基于 Homography 的初始结果作为备用
        val rotation = components.rotationAngle
        val rotationError = abs(rotation)
        
        // Homography 作为辅助判断
        val progress = (1.0 / (1.0 + exp((rotationError - ROTATION_THRESHOLD) / 5.0))).toFloat()
        
        return StepValidationResult(
            isCompleted = false,  // 需要异步验证确认
            progress = progress * 0.5f,  // 初始进度减半，等待主体/位姿融合信号
            feedbackText = initialViewChangeFeedback(actionType, direction),
            shiftDistance = components.translationDistance.toFloat(),
            tx = null, // View-change 不使用矢量UI
            ty = null,
            scaleFactor = components.scaleFactor.toFloat(),
            rotationAngle = rotation.toFloat(),
            matchQuality = matchQuality,
            uiSpaceWidth = uiSpaceWidth(),
            uiSpaceHeight = uiSpaceHeight()
        )
    }
    
    /**
     * 异步验证 View-change 步骤
     * 融合全局特征、透视变化、ARCore 位姿与主体连续性
     */
    fun validateViewChangeAsync(
        currentFrame: Bitmap,
        stepKey: Int,
        actionType: String,
        direction: String,
        cameraPoseSample: CameraPoseSample? = null,
        callback: (StepValidationResult) -> Unit
    ) {
        feedbackDebouncer.minDurationMs = 300L
        val baselineJustInitialized = ensureViewChangeBaseline(stepKey, currentFrame)

        if (baselineJustInitialized) {
            callback(
                StepValidationResult(
                    isCompleted = false,
                    progress = 0.05f,
                    feedbackText = initialViewChangeFeedback(actionType, direction),
                    shiftDistance = null,
                    tx = 0.05f,
                    ty = null,
                    scaleFactor = null,
                    rotationAngle = null,
                    matchQuality = 0f,
                    subjectConfidence = 0f,
                    uiSpaceWidth = currentFrame.width.toFloat(),
                    uiSpaceHeight = currentFrame.height.toFloat()
                )
            )
            return
        }

        val sourceMatcher = viewChangeSourceMatcher
        val analyzer = viewChangeAnalyzer
        if (sourceMatcher == null || analyzer == null) {
            callback(
                StepValidationResult(
                    isCompleted = false,
                    progress = 0f,
                    feedbackText = "正在初始化视角参考...",
                    shiftDistance = null,
                    tx = 0f,
                    ty = null,
                    scaleFactor = null,
                    rotationAngle = null,
                    matchQuality = 0f,
                    subjectConfidence = 0f,
                    uiSpaceWidth = currentFrame.width.toFloat(),
                    uiSpaceHeight = currentFrame.height.toFloat()
                )
            )
            return
        }

        val sourcePerspectiveResult = sourceMatcher.computePerspectiveHomography(currentFrame)
        val targetPerspectiveResult = targetViewMatcher.computePerspectiveHomography(currentFrame)
        val perspectiveDepartureScore: Float
        val targetFeatureScore: Float

        try {
            perspectiveDepartureScore = computePerspectiveDepartureScore(
                result = sourcePerspectiveResult,
                frameWidth = currentFrame.width,
                frameHeight = currentFrame.height
            )
            val viewGeometry = computeViewChangeGeometry(
                result = sourcePerspectiveResult,
                frameWidth = currentFrame.width,
                frameHeight = currentFrame.height
            )
            targetFeatureScore = (targetPerspectiveResult.matchCount.toFloat() / 28f).coerceIn(0f, 1f)
            val sourceFeatureScore = (sourcePerspectiveResult.matchCount.toFloat() / 28f).coerceIn(0f, 1f)
            val pHashSimilarity = getOrComputePHash(currentFrame)

            analyzer.analyze(
                currentFrame = currentFrame,
                actionType = actionType,
                direction = direction,
                perspectiveScore = perspectiveDepartureScore,
                sourceSimilarityScore = sourceFeatureScore,
                targetSimilarityScore = targetFeatureScore,
                pHashSimilarity = pHashSimilarity,
                viewGeometry = viewGeometry,
                cameraPoseSample = cameraPoseSample
            ) { result ->
                callback(
                    StepValidationResult(
                        isCompleted = result.isCompleted,
                        progress = result.progress,
                        feedbackText = feedbackDebouncer.debounce(result.feedbackText),
                        shiftDistance = null,
                        tx = null,
                        ty = null,
                        scaleFactor = result.targetAlignmentScore,
                        rotationAngle = null,
                        matchQuality = result.targetSimilarityScore,
                        uiHintX = result.uiHintX,
                        uiHintY = result.uiHintY,
                        directionConfidence = result.directionScore,
                        subjectConfidence = result.subjectConfidence,
                        hasSubject = result.hasObject,
                        uiSpaceWidth = currentFrame.width.toFloat(),
                        uiSpaceHeight = currentFrame.height.toFloat()
                    )
                )
            }
        } finally {
            sourcePerspectiveResult.release()
            targetPerspectiveResult.release()
        }
    }

    private fun ensureViewChangeBaseline(stepKey: Int, currentFrame: Bitmap): Boolean {
        if (viewChangeBaselineStepKey == stepKey && viewChangeSourceMatcher != null && viewChangeAnalyzer != null) {
            return false
        }

        viewChangeBaselineStepKey = stepKey
        viewChangeSourceMatcher?.close()
        viewChangeSourceMatcher = FeatureMatcher(FeatureMatcherProfile.REALTIME_ORB).apply {
            setTargetImage(currentFrame)
        }
        viewChangeAnalyzer?.close()
        viewChangeAnalyzer = ViewChangeAnalyzer(
            sourceBitmap = currentFrame,
            targetBitmap = targetReferenceBitmap,
            targetShotSpec = shotSpec
        )
        return true
    }

    private fun computeOrbQuality(matchCount: Int): Float {
        val baseQuality = (matchCount.toFloat() / 50f).coerceIn(0f, 1f)
        return if (matchCount < 10) {
            (baseQuality * 0.6f).coerceIn(0f, 1f)
        } else {
            baseQuality
        }
    }

    private fun getOrComputePHash(currentFrame: Bitmap): Float {
        pHashFrameCounter += 1
        if (pHashFrameCounter % PHASH_COMPUTE_INTERVAL == 1 || cachedCurrentPHash == 0L) {
            cachedCurrentPHash = PerceptualHashMatcher.computeHash(currentFrame)
        }
        return PerceptualHashMatcher.similarity(cachedCurrentPHash, targetPHash)
    }

    private fun sharpenForMatching(bitmap: Bitmap): Bitmap {
        val src = Mat()
        val blurred = Mat()
        val sharpened = Mat()
        return try {
            Utils.bitmapToMat(bitmap, src)
            Imgproc.GaussianBlur(src, blurred, Size(0.0, 0.0), 3.0)
            Core.addWeighted(src, 1.5, blurred, -0.5, 0.0, sharpened)
            Bitmap.createBitmap(
                bitmap.width,
                bitmap.height,
                bitmap.config ?: Bitmap.Config.ARGB_8888
            ).also { result ->
                Utils.matToBitmap(sharpened, result)
            }
        } catch (_: Exception) {
            bitmap
        } finally {
            src.release()
            blurred.release()
            sharpened.release()
        }
    }

    private fun initialViewChangeFeedback(actionType: String, direction: String): String {
        return when (actionType.normalizedActionType()) {
            "orbit" -> if (direction.equals("right", ignoreCase = true)) {
                "保持主体稳定，开始向右绕拍"
            } else {
                "保持主体稳定，开始向左绕拍"
            }
            "raisecamera" -> "保持主体稳定，开始抬高机位"
            "lowercamera" -> "保持主体稳定，先把手机整体放低"
            "step" -> if (direction.equals("backward", ignoreCase = true)) {
                "保持主体稳定，开始后退一点"
            } else {
                "保持主体稳定，开始靠近主体"
            }
            else -> when (direction.lowercase()) {
                "high-angle" -> "保持主体稳定，开始抬高机位俯拍"
                "low-angle" -> "保持主体稳定，开始降低机位仰拍"
                "side-view-left" -> "保持主体稳定，开始绕主体向左移动"
                "side-view-right" -> "保持主体稳定，开始绕主体向右移动"
                else -> "开始围绕主体改变视角"
            }
        }
    }

    private fun computePerspectiveDepartureScore(
        result: HomographyResult,
        frameWidth: Int,
        frameHeight: Int
    ): Float {
        val homography = result.homography ?: return 0f
        if (homography.rows() != 3 || homography.cols() != 3 || homography.empty()) {
            return 0f
        }

        val h22 = homography.get(2, 2)[0].takeIf { abs(it) > 1e-6 } ?: 1.0
        val h20 = homography.get(2, 0)[0] / h22
        val h21 = homography.get(2, 1)[0] / h22

        val projectiveMagnitude = kotlin.math.sqrt(h20 * h20 + h21 * h21)
        val projectiveScore = (projectiveMagnitude * frameWidth * 12.0).toFloat().coerceIn(0f, 1f)

        val corners = arrayOf(
            doubleArrayOf(0.0, 0.0),
            doubleArrayOf(frameWidth.toDouble(), 0.0),
            doubleArrayOf(frameWidth.toDouble(), frameHeight.toDouble()),
            doubleArrayOf(0.0, frameHeight.toDouble())
        ).map { point ->
            projectPoint(homography, point[0], point[1], h22)
        }

        val topWidth = distance(corners[0], corners[1])
        val bottomWidth = distance(corners[3], corners[2])
        val leftHeight = distance(corners[0], corners[3])
        val rightHeight = distance(corners[1], corners[2])

        val horizontalSkew = (abs(topWidth - bottomWidth) / maxOf(topWidth, bottomWidth, 1e-3)).toFloat()
        val verticalSkew = (abs(leftHeight - rightHeight) / maxOf(leftHeight, rightHeight, 1e-3)).toFloat()
        val trapezoidScore = ((horizontalSkew + verticalSkew) * 1.4f).coerceIn(0f, 1f)

        val inlierScore = (result.matchCount.toFloat() / 35f).coerceIn(0f, 1f)

        return (
            projectiveScore * 0.50f +
                trapezoidScore * 0.35f +
                inlierScore * 0.15f
            ).coerceIn(0f, 1f)
    }

    private fun computeViewChangeGeometry(
        result: HomographyResult,
        frameWidth: Int,
        frameHeight: Int
    ): ViewChangeGeometry {
        val homography = result.homography ?: return ViewChangeGeometry.Empty
        if (homography.rows() != 3 || homography.cols() != 3 || homography.empty()) {
            return ViewChangeGeometry.Empty
        }

        val h22 = homography.get(2, 2)[0].takeIf { abs(it) > 1e-6 } ?: 1.0
        val h20 = homography.get(2, 0)[0] / h22
        val h21 = homography.get(2, 1)[0] / h22

        val corners = arrayOf(
            doubleArrayOf(0.0, 0.0),
            doubleArrayOf(frameWidth.toDouble(), 0.0),
            doubleArrayOf(frameWidth.toDouble(), frameHeight.toDouble()),
            doubleArrayOf(0.0, frameHeight.toDouble())
        ).map { point ->
            projectPoint(homography, point[0], point[1], h22)
        }

        val projectedCenter = projectPoint(
            homography = homography,
            x = frameWidth / 2.0,
            y = frameHeight / 2.0,
            h22 = h22
        )
        val centerShiftXNorm = ((projectedCenter.first - frameWidth / 2.0) / frameWidth)
            .toFloat()
            .coerceIn(-1f, 1f)
        val centerShiftYNorm = ((projectedCenter.second - frameHeight / 2.0) / frameHeight)
            .toFloat()
            .coerceIn(-1f, 1f)

        val topWidth = distance(corners[0], corners[1])
        val bottomWidth = distance(corners[3], corners[2])
        val leftHeight = distance(corners[0], corners[3])
        val rightHeight = distance(corners[1], corners[2])
        val projectedArea = polygonArea(corners)
        val frameArea = (frameWidth * frameHeight).toDouble().coerceAtLeast(1.0)
        val scaleRatio = kotlin.math.sqrt((projectedArea / frameArea).coerceAtLeast(1e-4))
            .toFloat()
            .coerceIn(0.25f, 4f)

        val horizontalPerspective = (
            (rightHeight - leftHeight) / maxOf(leftHeight, rightHeight, 1e-3)
            ).toFloat().coerceIn(-1f, 1f)
        val verticalPerspective = (
            (bottomWidth - topWidth) / maxOf(topWidth, bottomWidth, 1e-3)
            ).toFloat().coerceIn(-1f, 1f)

        val projectiveX = (h20 * frameWidth * 12.0).toFloat().coerceIn(-1f, 1f)
        val projectiveY = (h21 * frameHeight * 12.0).toFloat().coerceIn(-1f, 1f)
        val quality = (result.matchCount.toFloat() / 35f).coerceIn(0f, 1f)
        val perspectiveScore = computePerspectiveDepartureScore(result, frameWidth, frameHeight)

        val orbitRightRaw = maxOf(horizontalPerspective, projectiveX, centerShiftXNorm * 1.8f)
        val orbitLeftRaw = maxOf(-horizontalPerspective, -projectiveX, -centerShiftXNorm * 1.8f)
        val raiseRaw = maxOf(-centerShiftYNorm * 1.7f, -projectiveY, verticalPerspective * 0.8f)
        val lowerRaw = maxOf(centerShiftYNorm * 1.7f, projectiveY, -verticalPerspective * 0.8f)
        val forwardRaw = maxOf(scaleRatio - 1f, perspectiveScore * 0.35f)
        val backwardRaw = maxOf(1f - scaleRatio, perspectiveScore * 0.25f)

        return ViewChangeGeometry(
            quality = quality,
            perspectiveScore = perspectiveScore,
            shiftXNorm = centerShiftXNorm,
            shiftYNorm = centerShiftYNorm,
            scaleRatio = scaleRatio,
            horizontalPerspective = horizontalPerspective,
            verticalPerspective = verticalPerspective,
            projectiveX = projectiveX,
            projectiveY = projectiveY,
            orbitLeftScore = directionalGeometryScore(orbitLeftRaw, quality),
            orbitRightScore = directionalGeometryScore(orbitRightRaw, quality),
            raiseScore = directionalGeometryScore(raiseRaw, quality),
            lowerScore = directionalGeometryScore(lowerRaw, quality),
            forwardScore = directionalGeometryScore(forwardRaw, quality, normalizer = 0.22f),
            backwardScore = directionalGeometryScore(backwardRaw, quality, normalizer = 0.20f)
        )
    }

    private fun directionalGeometryScore(
        raw: Float,
        quality: Float,
        normalizer: Float = 0.18f
    ): Float {
        if (raw <= 0f || quality <= 0f) return 0f
        val normalized = (raw / normalizer).coerceIn(0f, 1f)
        return (normalized * (0.35f + quality * 0.65f)).coerceIn(0f, 1f)
    }

    private fun polygonArea(points: List<Pair<Double, Double>>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            sum += current.first * next.second - next.first * current.second
        }
        return abs(sum) * 0.5
    }

    private fun projectPoint(
        homography: org.opencv.core.Mat,
        x: Double,
        y: Double,
        h22: Double
    ): Pair<Double, Double> {
        val h00 = homography.get(0, 0)[0] / h22
        val h01 = homography.get(0, 1)[0] / h22
        val h02 = homography.get(0, 2)[0] / h22
        val h10 = homography.get(1, 0)[0] / h22
        val h11 = homography.get(1, 1)[0] / h22
        val h12 = homography.get(1, 2)[0] / h22
        val h20 = homography.get(2, 0)[0] / h22
        val h21 = homography.get(2, 1)[0] / h22

        val denominator = (h20 * x + h21 * y + 1.0).takeIf { abs(it) > 1e-6 } ?: 1.0
        val px = (h00 * x + h01 * y + h02) / denominator
        val py = (h10 * x + h11 * y + h12) / denominator

        return px to py
    }

    private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = a.first - b.first
        val dy = a.second - b.second
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun projectNormalizedPoint(
        xNorm: Float,
        yNorm: Float,
        components: HomographyComponents
    ): Pair<Double, Double> {
        val sourceX = xNorm.toDouble() * targetReferenceBitmap.width.toDouble()
        val sourceY = yNorm.toDouble() * targetReferenceBitmap.height.toDouble()
        val radians = Math.toRadians(components.rotationAngle)
        val scale = components.scaleFactor

        val projectedX = scale * cos(radians) * sourceX -
            scale * sin(radians) * sourceY +
            components.translationX
        val projectedY = scale * sin(radians) * sourceX +
            scale * cos(radians) * sourceY +
            components.translationY

        return projectedX to projectedY
    }
    
    /**
     * 释放资源
     */
    fun close() {
        featureMatcher.close()
        targetViewMatcher.close()
        viewChangeSourceMatcher?.close()
        viewChangeSourceMatcher = null
        viewChangeAnalyzer?.close()
        viewChangeAnalyzer = null
        motionValidationState.reset()
        pHashFrameCounter = 0
        cachedCurrentPHash = 0L
    }

    fun resetStableFrames() {
        motionValidationState.reset()
        pHashFrameCounter = 0
        cachedCurrentPHash = 0L
        viewChangeAnalyzer?.resetStableFrames()
    }
}
