package com.photoframer.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Surface
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photoframer.arcore.ArCorePoseTracker
import com.photoframer.arcore.ArCoreSupport
import com.photoframer.guidance.isLevelAction
import com.photoframer.guidance.isViewpointAction
import com.photoframer.ui.components.AllStepsCompletedBanner
import com.photoframer.ui.components.AspectRatioOption
import com.photoframer.ui.components.CameraBottomBar
import com.photoframer.ui.components.CameraEntryMode
import com.photoframer.ui.components.CameraMode
import com.photoframer.ui.components.CameraTopBar
import com.photoframer.ui.components.CameraVisualStyle
import com.photoframer.ui.components.CameraZoomUiState
import com.photoframer.ui.components.CandidatesBottomPanel
import com.photoframer.ui.components.CaptureTimer
import com.photoframer.ui.components.FlashMode
import com.photoframer.ui.components.GridOverlay
import com.photoframer.ui.components.GuidanceOverlay
import com.photoframer.ui.components.GuidingBottomBar
import com.photoframer.ui.components.LoadingOverlay
import com.photoframer.ui.components.SideToolBar
import com.photoframer.ui.components.TopGuidanceBar
import com.photoframer.ui.state.CameraUiState
import com.photoframer.ui.theme.BackgroundDark
import com.photoframer.ui.theme.PurplePrimary
import com.photoframer.ui.theme.SurfaceDark
import com.photoframer.ui.theme.TextSecondary
import com.photoframer.utils.CameraLensManager
import com.photoframer.utils.CameraLensOption
import com.photoframer.utils.CameraZoomController
import com.photoframer.utils.GalleryPermissionUtils
import com.photoframer.utils.GalleryUtils
import com.photoframer.utils.ImageFileDecoder
import com.photoframer.utils.ImageSaver
import com.photoframer.viewmodel.CameraViewModel
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CameraScreen"
private const val ZOOM_RULER_AUTO_HIDE_DELAY_MS = 1500L
private const val ZOOM_DRAG_SENSITIVITY = 0.0075f

@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val validationResult by viewModel.validationResult.collectAsStateWithLifecycle()
    val stepCompleted by viewModel.stepCompleted.collectAsStateWithLifecycle()
    val allStepsCompleted by viewModel.allStepsCompleted.collectAsStateWithLifecycle()
    val showStepSkip by viewModel.showStepSkip.collectAsStateWithLifecycle()
    val postCapturePrompt by viewModel.postCapturePrompt.collectAsStateWithLifecycle()
    val newCandidatePrompt by viewModel.newCandidatePrompt.collectAsStateWithLifecycle()

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var cameraInfo by remember { mutableStateOf<CameraInfo?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var rawZoomRatio by remember { mutableFloatStateOf(1f) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val arCorePoseTracker = remember(context.applicationContext) {
        ArCorePoseTracker(context.applicationContext)
    }

    val controls = rememberCameraControlState(context)
    var flashMode by controls::flashMode
    var gridEnabled by controls::gridEnabled
    var lastPhotoThumbnail by controls::lastPhotoThumbnail
    var hasGalleryReadPermission by controls::hasGalleryReadPermission
    var availableLenses by controls::availableLenses
    var selectedLensIndex by controls::selectedLensIndex
    var desiredEffectiveZoom by controls::desiredEffectiveZoom
    var isZoomRulerVisible by controls::isZoomRulerVisible
    var zoomInteractionVersion by controls::zoomInteractionVersion
    var touchScreenPhotoEnabled by controls::touchScreenPhotoEnabled
    var selectedRatio by controls::selectedRatio
    var selectedTimer by controls::selectedTimer
    var countdownValue by controls::countdownValue
    var burstCount by controls::burstCount
    var isBursting by controls::isBursting
    var activeEntryMode by controls::activeEntryMode
    var visualStyle by controls::visualStyle

    val frameCounter = remember { AtomicInteger(0) }
    val lastAnalysisTime = remember { AtomicLong(0L) }
    var arExperimentEnabled by remember { mutableStateOf(ArCoreSupport.isEnabled(context)) }

    val guidingStep = (uiState as? CameraUiState.Guiding)?.currentStep
    val needsPoseTracking = guidingStep?.let { it.isViewpointAction() || it.isLevelAction() } == true
    val isViewpointStep = guidingStep?.isViewpointAction() == true
    val shouldUseArCore = isViewpointStep && arExperimentEnabled
    val analyzeEveryNFrames = if (needsPoseTracking) 6 else 4
    val minAnalysisInterval = if (needsPoseTracking) 90L else 66L
    val canOpenGallery = remember(context) {
        GalleryUtils.canOpenGallery(context)
    }
    val selectedLens = availableLenses.getOrNull(selectedLensIndex)
    val selectedFacing = selectedLens?.lensFacing ?: CameraSelector.LENS_FACING_BACK
    val arCoreStatus = rememberArCoreStatus(
        context = context,
        lifecycleOwner = lifecycleOwner,
        poseTracker = arCorePoseTracker,
        needsPoseTracking = needsPoseTracking,
        isViewpointStep = isViewpointStep,
        shouldUseArCore = shouldUseArCore,
        stepOrder = guidingStep?.stepOrder
    )
    CameraResourceDisposalEffect(cameraExecutor, arCorePoseTracker)
    CameraPoseTrackingEffect(
        context = context,
        poseTracker = arCorePoseTracker,
        previewView = previewView,
        needsPoseTracking = needsPoseTracking,
        shouldUseArCore = shouldUseArCore,
        stepOrder = guidingStep?.stepOrder,
        arCoreStatus = arCoreStatus,
        selectedFacing = selectedFacing
    )
    val currentFacingLenses = availableLenses.mapIndexedNotNull { index, lens ->
        if (lens.lensFacing == selectedFacing) {
            index to lens
        } else {
            null
        }
    }
    val zoomPresets = CameraZoomController.buildPresets(currentFacingLenses)
    // Physical lens entries are UI presets; zoom control stays on the logical
    // camera so CameraX/HAL can switch sensors without rebinding the preview.
    val zoomControlLens = selectedLens.takeUnless { it?.physicalCameraId != null }
    val actualEffectiveZoom = CameraZoomController.effectiveZoomRatio(zoomControlLens, rawZoomRatio)
    val displayedZoom = if (isZoomRulerVisible) desiredEffectiveZoom else actualEffectiveZoom
    val zoomUiState = CameraZoomUiState(
        presets = zoomPresets,
        selectedLensIndex = selectedLensIndex,
        displayedZoomText = CameraZoomController.formatZoomRatio(displayedZoom),
        isRulerVisible = isZoomRulerVisible
    )
    fun refreshLatestThumbnail(canReadGallery: Boolean = hasGalleryReadPermission) {
        if (!canReadGallery) return
        scope.launch {
            val latestThumbnail = withContext(Dispatchers.IO) {
                GalleryUtils.loadLatestThumbnail(context)
            }
            if (latestThumbnail != null) {
                lastPhotoThumbnail = latestThumbnail
            }
        }
    }

    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val granted = GalleryPermissionUtils.hasGalleryReadPermission(context)
        hasGalleryReadPermission = granted
        refreshLatestThumbnail(canReadGallery = granted)
    }

    fun performHaptic(constant: Int) {
        view.performHapticFeedback(constant)
    }

    fun performCompletionVibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            vibrator?.vibrate(VibrationEffect.createOneShot(180, 160))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VibrationEffect.createOneShot(180, 160))
        }
    }

    fun defaultLensIndex(lenses: List<CameraLensOption>): Int {
        val mainBackLensIndex = lenses.indexOfFirst { lens ->
            lens.lensFacing == CameraSelector.LENS_FACING_BACK &&
                (lens.targetZoomRatio?.let { kotlin.math.abs(it - 1f) < 0.15f } == true)
        }
        if (mainBackLensIndex >= 0) {
            return mainBackLensIndex
        }

        return lenses.indexOfFirst { lens ->
            lens.lensFacing == CameraSelector.LENS_FACING_BACK
        }.takeIf { it >= 0 } ?: 0
    }

    fun openGallery() {
        GalleryUtils.openGallery(context)
    }

    fun refreshZoomAutoHide() {
        zoomInteractionVersion += 1
    }

    fun supportedEffectiveZoomRange(
        lens: CameraLensOption?,
        info: CameraInfo?
    ): ClosedFloatingPointRange<Float> {
        return CameraZoomController.effectiveZoomRangeForLens(lens, info)
    }

    fun applyTargetEffectiveZoom(
        targetZoom: Float,
        revealRuler: Boolean = false
    ) {
        if (zoomPresets.isEmpty()) return

        val zoomRange = supportedEffectiveZoomRange(zoomControlLens, cameraInfo)
        val clampedZoom = targetZoom.coerceIn(zoomRange.start, zoomRange.endInclusive)

        desiredEffectiveZoom = clampedZoom
        if (revealRuler) {
            isZoomRulerVisible = true
            refreshZoomAutoHide()
        }

        val targetPreset = CameraZoomController.chooseLensForZoom(zoomPresets, clampedZoom)
        if (targetPreset != null && targetPreset.lensIndex != selectedLensIndex) {
            selectedLensIndex = targetPreset.lensIndex
        }

        val rawTargetZoom = CameraZoomController.computeRawZoomRatio(
            lens = zoomControlLens,
            targetEffectiveZoom = clampedZoom,
            cameraInfo = cameraInfo
        )
        cameraControl?.setZoomRatio(rawTargetZoom)
    }

    fun revealZoomRuler() {
        desiredEffectiveZoom = actualEffectiveZoom
        isZoomRulerVisible = true
        refreshZoomAutoHide()
    }

    fun finishZoomInteraction() {
        val snappedZoom = CameraZoomController.snapZoomRatio(desiredEffectiveZoom, zoomPresets)
        applyTargetEffectiveZoom(snappedZoom, revealRuler = true)
    }

    fun switchCameraFacing() {
        val targetFacing = if (selectedFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val targetLenses = availableLenses.mapIndexedNotNull { index, lens ->
            if (lens.lensFacing == targetFacing) {
                index to lens
            } else {
                null
            }
        }
        val targetIndex = CameraZoomController.findDefaultLensIndex(targetLenses) ?: return
        selectedLensIndex = targetIndex
        desiredEffectiveZoom = availableLenses.getOrNull(targetIndex)?.targetZoomRatio ?: 1f
        isZoomRulerVisible = false
    }

    LaunchedEffect(context) {
        availableLenses = CameraLensManager.getAvailableLenses(context)
        selectedLensIndex = defaultLensIndex(availableLenses)
        desiredEffectiveZoom =
            availableLenses.getOrNull(selectedLensIndex)?.targetZoomRatio ?: 1f
        val granted = GalleryPermissionUtils.hasGalleryReadPermission(context)
        hasGalleryReadPermission = granted
        if (granted) {
            refreshLatestThumbnail(canReadGallery = true)
        } else {
            galleryPermissionLauncher.launch(GalleryPermissionUtils.permissionsToRequest())
        }
    }

    LaunchedEffect(selectedLensIndex, cameraInfo, desiredEffectiveZoom) {
        if (selectedLens == null) return@LaunchedEffect
        val rawTargetZoom = CameraZoomController.computeRawZoomRatio(
            lens = zoomControlLens,
            targetEffectiveZoom = desiredEffectiveZoom,
            cameraInfo = cameraInfo
        )
        cameraControl?.setZoomRatio(rawTargetZoom)
    }

    LaunchedEffect(isZoomRulerVisible, zoomInteractionVersion) {
        if (!isZoomRulerVisible) {
            return@LaunchedEffect
        }

        delay(ZOOM_RULER_AUTO_HIDE_DELAY_MS)
        isZoomRulerVisible = false
    }

    LaunchedEffect(uiState) {
        if (uiState is CameraUiState.Preview) {
            controls.resetTransientCaptureState()
        }
    }

    LaunchedEffect((uiState as? CameraUiState.Guiding)?.composition?.technique) {
        if (uiState is CameraUiState.Guiding) {
            performHaptic(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    LaunchedEffect(stepCompleted) {
        if (stepCompleted) {
            performHaptic(HapticFeedbackConstants.CONFIRM)
        }
    }

    LaunchedEffect(allStepsCompleted) {
        if (allStepsCompleted) {
            performCompletionVibration()
        }
    }

    fun saveCapturedPhoto(file: File, namePrefix: String) {
        scope.launch {
            val adjustedFile = withContext(Dispatchers.IO) {
                applyAspectRatioToCapturedFile(context, file, selectedRatio)
            }
            val thumbnail = withContext(Dispatchers.IO) {
                ImageFileDecoder.decodeThumbnailRespectingExif(adjustedFile, maxDimension = 512)
            }
            if (thumbnail != null) {
                lastPhotoThumbnail = thumbnail
            }
            ImageSaver.saveImageFileToGallery(context, adjustedFile, namePrefix)
        }
    }

    fun capturePreviewPhoto() {
        captureAndAnalyze(context, imageCapture, cameraExecutor) { file ->
            saveCapturedPhoto(file, "photoframer")
        }
    }

    fun startCountdownCapture() {
        if (countdownValue != null) return
        val timerSeconds = selectedTimer.seconds
        if (timerSeconds <= 0) {
            performHaptic(HapticFeedbackConstants.LONG_PRESS)
            capturePreviewPhoto()
            return
        }

        countdownValue = timerSeconds
        scope.launch {
            while ((countdownValue ?: 0) > 0) {
                delay(1000)
                val next = (countdownValue ?: 0) - 1
                countdownValue = if (next > 0) next else null
            }
            if (uiState is CameraUiState.Preview) {
                capturePreviewPhoto()
            }
        }
    }

    val startBurst = startBurst@{
        if (isBursting) {
            return@startBurst
        }
        isBursting = true
        burstCount = 0
        countdownValue = null
        scope.launch {
            while (isBursting) {
                captureAndAnalyze(context, imageCapture, cameraExecutor) { file ->
                    saveCapturedPhoto(file, "photoframer_burst")
                }
                burstCount += 1
                delay(200)
            }
        }
    }

    val stopBurst = {
        isBursting = false
    }

    val captureGuidedPhoto = {
        performHaptic(HapticFeedbackConstants.LONG_PRESS)
        captureAndAnalyze(context, imageCapture, cameraExecutor) { file ->
            saveCapturedPhoto(file, "photoframer_final")
            scope.launch {
                viewModel.handleGuidedCaptureCompleted()
            }
        }
    }

    val startInFrameComposition = {
        activeEntryMode = CameraEntryMode.IN_FRAME
        countdownValue = null
        captureAndAnalyze(context, imageCapture, cameraExecutor) { file ->
            val adjustedFile = applyAspectRatioToCapturedFile(context, file, selectedRatio)
            viewModel.analyzeInFrameComposition(adjustedFile)
        }
    }

    val startAiAnalysis = {
        activeEntryMode = CameraEntryMode.AI
        countdownValue = null
        captureAndAnalyze(context, imageCapture, cameraExecutor) { file ->
            val adjustedFile = applyAspectRatioToCapturedFile(context, file, selectedRatio)
            viewModel.analyzeImage(adjustedFile)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (uiState !is CameraUiState.Guiding) {
            CameraTopBar(
                flashMode = flashMode,
                onFlashModeChange = { newMode ->
                    flashMode = newMode
                    imageCapture?.flashMode = newMode.toImageCaptureFlashMode()
                },
                gridEnabled = gridEnabled,
                onGridToggle = { gridEnabled = !gridEnabled },
                visualStyle = visualStyle,
                onVisualStyleChange = { visualStyle = it },
                activeEntryMode = activeEntryMode,
                showInFrameButton = uiState !is CameraUiState.Analyzing,
                onInFrameClick = startInFrameComposition,
                showAiButton = uiState !is CameraUiState.Analyzing,
                onAiClick = startAiAnalysis
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val currentZoom = desiredEffectiveZoom
                        val zoomRange = supportedEffectiveZoomRange(zoomControlLens, cameraInfo)
                        val newZoom = (currentZoom * zoom).coerceIn(
                            zoomRange.start,
                            zoomRange.endInclusive
                        )
                        if (kotlin.math.abs(newZoom - currentZoom) > 0.001f) {
                            applyTargetEffectiveZoom(newZoom)
                        }
                    }
                }
                .pointerInput(touchScreenPhotoEnabled, uiState, countdownValue, isBursting) {
                    if (touchScreenPhotoEnabled &&
                        uiState is CameraUiState.Preview &&
                        countdownValue == null &&
                        !isBursting
                    ) {
                        detectTapGestures {
                            startCountdownCapture()
                        }
                    }
                }
        ) {
            key(selectedLens?.logicalCameraId, selectedLens?.lensFacing, flashMode) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            previewView = this
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER

                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = surfaceProvider
                                }
                                imageCapture = ImageCapture.Builder()
                                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                    .setFlashMode(flashMode.toImageCaptureFlashMode())
                                    .build()

                                val imageAnalyzer = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also { analysis ->
                                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                            try {
                                                val currentState = viewModel.uiState.value
                                                if (currentState is CameraUiState.Guiding) {
                                                    val currentFrameCount = frameCounter.incrementAndGet()
                                                    val currentTime = System.currentTimeMillis()
                                                    val previousAnalysisTime = lastAnalysisTime.get()
                                                    if (currentFrameCount % analyzeEveryNFrames == 0 &&
                                                        currentTime - previousAnalysisTime > minAnalysisInterval &&
                                                        lastAnalysisTime.compareAndSet(previousAnalysisTime, currentTime)
                                                    ) {
                                                        val bitmap =
                                                            com.photoframer.vision.ImageConverter.imageProxyToBitmap(
                                                                imageProxy
                                                            )
                                                        if (bitmap != null) {
                                                            val croppedBitmap =
                                                                selectedRatio.toTargetAspectRatio()?.let { targetRatio ->
                                                                    com.photoframer.vision.ImageConverter.centerCropToAspectRatio(
                                                                        bitmap,
                                                                        targetRatio
                                                                    )
                                                                } ?: bitmap
                                                            val scaledBitmap =
                                                                com.photoframer.vision.ImageConverter.scaleBitmapToWidth(
                                                                    croppedBitmap,
                                                                    com.photoframer.vision.StepValidator.VALIDATION_FRAME_WIDTH
                                                                )
                                                            val currentPreview = previewView
                                                            arCorePoseTracker.updateViewport(
                                                                width = currentPreview?.width ?: scaledBitmap.width,
                                                                height = currentPreview?.height ?: scaledBitmap.height,
                                                                rotation = currentPreview?.display?.rotation
                                                                    ?: Surface.ROTATION_0
                                                            )
                                                            val poseSample = arCorePoseTracker.latestPoseSample()
                                                            viewModel.validateCurrentFrame(
                                                                currentFrame = scaledBitmap,
                                                                currentZoomRatio = CameraZoomController.effectiveZoomRatio(
                                                                    zoomControlLens,
                                                                    rawZoomRatio
                                                                ),
                                                                cameraPoseSample = poseSample
                                                            )
                                                        }
                                                    }
                                                }
                                            } catch (error: Exception) {
                                                Log.e(TAG, "相机帧分析失败", error)
                                            } finally {
                                                imageProxy.close()
                                            }
                                        }
                                    }

                                val cameraSelector = selectedLens?.let { lens ->
                                    CameraLensManager.buildCameraSelector(
                                        lens = lens,
                                        bindPhysicalCamera = false
                                    )
                                } ?: CameraSelector.DEFAULT_BACK_CAMERA

                                try {
                                    cameraProvider.unbindAll()
                                    val camera = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        preview,
                                        imageCapture,
                                        imageAnalyzer
                                    )
                                    cameraControl = camera.cameraControl
                                    cameraInfo = camera.cameraInfo
                                    camera.cameraInfo.zoomState.observe(lifecycleOwner) { state ->
                                        rawZoomRatio = state.zoomRatio
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "相机绑定失败", e)
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    },
                    update = { previewView = it },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (gridEnabled) {
                GridOverlay(modifier = Modifier.fillMaxSize())
            }

            if (selectedRatio != AspectRatioOption.FULL) {
                AspectRatioMaskOverlay(
                    selectedRatio = selectedRatio,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (uiState is CameraUiState.Guiding) {
                val guidingState = uiState as CameraUiState.Guiding

                if (!allStepsCompleted) {
                    guidingState.currentStep?.let { step ->
                        GuidanceOverlay(
                            step = step,
                            validationResult = validationResult
                        )
                    }
                }

                if (!allStepsCompleted) {
                    guidingState.currentStep?.let { step ->
                        TopGuidanceBar(
                            step = step,
                            currentStepIndex = guidingState.currentStepIndex,
                            totalSteps = guidingState.totalSteps,
                            onPreviousStep = { viewModel.previousStep() },
                            onNextStep = { viewModel.nextStep() },
                            isFirstStep = guidingState.isFirstStep,
                            isLastStep = guidingState.isLastStep,
                            validationResult = validationResult,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 16.dp)
                        )
                    }
                } else {
                    AllStepsCompletedBanner(
                        onTakePhoto = captureGuidedPhoto,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 80.dp)
                    )
                }

                if (allStepsCompleted && postCapturePrompt != null) {
                    PostCaptureChoiceOverlay(
                        message = postCapturePrompt?.message.orEmpty(),
                        onViewCandidates = { viewModel.viewOtherCandidatesAfterCapture() },
                        onDismiss = { viewModel.dismissPostCapturePrompt() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 20.dp, end = 20.dp, bottom = 156.dp)
                    )
                }

                if (!allStepsCompleted && showStepSkip) {
                    StepSkipOverlay(
                        onSkip = { viewModel.skipCurrentStep() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 156.dp)
                    )
                }

                if (!allStepsCompleted && newCandidatePrompt != null) {
                    NewCandidateOverlay(
                        message = newCandidatePrompt?.message.orEmpty(),
                        onViewCandidates = { viewModel.viewUpdatedCandidates() },
                        onDismiss = { viewModel.dismissNewCandidatePrompt() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 20.dp, end = 20.dp, bottom = 96.dp)
                    )
                }

                val targetBitmap = viewModel.getCachedImage(guidingState.composition.technique)

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 80.dp)
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = targetBitmap != null,
                        enter = fadeIn() + scaleIn(initialScale = 0.8f)
                    ) {
                        if (targetBitmap != null) {
                            val aspectRatio =
                                targetBitmap.width.toFloat() / targetBitmap.height.toFloat()
                            val thumbnailHeight = 120.dp
                            val thumbnailWidth = (120 * aspectRatio).dp

                            Card(
                                modifier = Modifier
                                    .width(thumbnailWidth)
                                    .height(thumbnailHeight)
                                    .border(1.5.dp, PurplePrimary, RoundedCornerShape(12.dp)),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = SurfaceDark.copy(alpha = 0.8f)
                                )
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Image(
                                        bitmap = targetBitmap.asImageBitmap(),
                                        contentDescription = "参考构图",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )

                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .background(Color.Black.copy(alpha = 0.5f))
                                            .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "参考图",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (uiState is CameraUiState.Analyzing) {
                LoadingOverlay()
            } else if (uiState is CameraUiState.Error) {
                val errorState = uiState as CameraUiState.Error
                ErrorOverlay(
                    title = errorState.title,
                    message = errorState.message,
                    actionText = errorState.actionText,
                    onBack = { viewModel.handleErrorAction() }
                )
            }

            countdownValue?.let { value ->
                Box(
                    modifier = Modifier.align(Alignment.Center),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = value.toString(),
                        color = Color.White,
                        style = MaterialTheme.typography.displayLarge
                    )
                }
            }

            if (uiState is CameraUiState.Preview) {
                SideToolBar(
                    selectedRatio = selectedRatio,
                    onRatioChange = { selectedRatio = it },
                    selectedTimer = selectedTimer,
                    onTimerChange = { selectedTimer = it },
                    touchScreenPhotoEnabled = touchScreenPhotoEnabled,
                    onTouchScreenPhotoToggle = { touchScreenPhotoEnabled = !touchScreenPhotoEnabled },
                    arExperimentEnabled = arExperimentEnabled,
                    onArExperimentToggle = {
                        val newValue = !arExperimentEnabled
                        arExperimentEnabled = newValue
                        ArCoreSupport.setEnabled(context, newValue)
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp, top = 32.dp, bottom = 96.dp)
                )
            }

        }

        AnimatedContent(
            targetState = uiState,
            transitionSpec = {
                (slideInVertically { it / 3 } + fadeIn())
                    .togetherWith(slideOutVertically { it / 3 } + fadeOut())
            },
            contentKey = { it::class },
            label = "bottom_bar"
        ) { state ->
            when (state) {
                is CameraUiState.Preview -> {
                    CameraBottomBar(
                        currentMode = CameraMode.PHOTO,
                        visualStyle = visualStyle,
                        zoomUiState = zoomUiState,
                        onZoomPresetClick = { index ->
                            val preset = zoomPresets.firstOrNull { it.lensIndex == index }
                            if (preset != null) {
                                isZoomRulerVisible = false
                                applyTargetEffectiveZoom(preset.effectiveZoomRatio)
                            }
                        },
                        onZoomRulerReveal = { revealZoomRuler() },
                        onZoomRulerDrag = { dragAmount ->
                            if (!isZoomRulerVisible) {
                                revealZoomRuler()
                            }
                            val zoomRange = supportedEffectiveZoomRange(zoomControlLens, cameraInfo)
                            val nextZoom = (desiredEffectiveZoom + dragAmount * ZOOM_DRAG_SENSITIVITY)
                                .coerceIn(zoomRange.start, zoomRange.endInclusive)
                            applyTargetEffectiveZoom(nextZoom, revealRuler = true)
                        },
                        onZoomRulerDragEnd = { finishZoomInteraction() },
                        onShutterClick = { startCountdownCapture() },
                        lastPhotoThumbnail = lastPhotoThumbnail,
                        isGalleryAvailable = canOpenGallery,
                        onGalleryClick = ::openGallery,
                        canSwitchFacing = availableLenses.any { it.lensFacing != selectedFacing },
                        onFacingSwitch = ::switchCameraFacing,
                        onLongPressStart = startBurst,
                        onLongPressEnd = stopBurst,
                        burstCount = burstCount
                    )
                }

                is CameraUiState.Analyzing -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black)
                            .navigationBarsPadding()
                            .padding(top = 20.dp, bottom = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        TextButton(
                            onClick = { viewModel.cancelAnalysis() },
                            modifier = Modifier.padding(bottom = 24.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "取消",
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 48.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(48.dp))
                            Box(
                                modifier = Modifier.size(72.dp),
                                contentAlignment = Alignment.Center
                            ) {}
                            Box(modifier = Modifier.size(48.dp))
                        }
                    }
                }

                is CameraUiState.Candidates -> {
                    CandidatesBottomPanel(
                        totalTechniques = state.totalTechniques,
                        completedCount = state.completedCount,
                        applicableCount = state.applicableCount,
                        totalTimeMs = state.totalTimeMs,
                        compositions = state.compositions,
                        postCaptureHint = state.postCaptureHint,
                        onStartGuidance = { composition -> viewModel.selectComposition(composition) },
                        getCompositionBitmap = { technique -> viewModel.getCachedImage(technique) },
                        onSaveComposition = { technique ->
                            val bitmap = viewModel.getCachedImage(technique)
                            if (bitmap != null) {
                                scope.launch {
                                    ImageSaver.saveImageToGallery(context, bitmap, "ai_ref_$technique")
                                }
                            }
                        },
                        onRescan = { viewModel.backToPreview() }
                    )
                }

                is CameraUiState.Guiding -> {
                    GuidingBottomBar(
                        onCaptureClick = captureGuidedPhoto,
                        onBackClick = { viewModel.backToCandidates() },
                        isCaptureEnabled = allStepsCompleted
                    )
                }

                else -> {
                    CameraBottomBar(
                        currentMode = CameraMode.PHOTO,
                        visualStyle = visualStyle,
                        zoomUiState = zoomUiState,
                        onZoomPresetClick = { index ->
                            val preset = zoomPresets.firstOrNull { it.lensIndex == index }
                            if (preset != null) {
                                isZoomRulerVisible = false
                                applyTargetEffectiveZoom(preset.effectiveZoomRatio)
                            }
                        },
                        onZoomRulerReveal = { revealZoomRuler() },
                        onZoomRulerDrag = { dragAmount ->
                            if (!isZoomRulerVisible) {
                                revealZoomRuler()
                            }
                            val zoomRange = supportedEffectiveZoomRange(zoomControlLens, cameraInfo)
                            val nextZoom = (desiredEffectiveZoom + dragAmount * ZOOM_DRAG_SENSITIVITY)
                                .coerceIn(zoomRange.start, zoomRange.endInclusive)
                            applyTargetEffectiveZoom(nextZoom, revealRuler = true)
                        },
                        onZoomRulerDragEnd = { finishZoomInteraction() },
                        onShutterClick = {},
                        lastPhotoThumbnail = lastPhotoThumbnail,
                        isGalleryAvailable = canOpenGallery,
                        onGalleryClick = ::openGallery,
                        canSwitchFacing = availableLenses.any { it.lensFacing != selectedFacing },
                        onFacingSwitch = ::switchCameraFacing
                    )
                }
            }
        }
    }
}
