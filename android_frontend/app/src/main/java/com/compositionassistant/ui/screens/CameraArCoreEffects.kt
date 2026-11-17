package com.compositionassistant.ui.screens

import android.content.Context
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.compositionassistant.arcore.ArCorePoseTracker
import com.compositionassistant.arcore.ArCoreRuntimeState
import com.compositionassistant.arcore.ArCoreStatus
import com.compositionassistant.arcore.ArCoreSupport
import com.compositionassistant.arcore.findActivity
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun CameraResourceDisposalEffect(
    cameraExecutor: ExecutorService,
    poseTracker: ArCorePoseTracker
) {
    DisposableEffect(cameraExecutor, poseTracker) {
        onDispose {
            poseTracker.close()
            cameraExecutor.shutdownNow()
        }
    }
}

/**
 * 管理 ARCore 可用性、安装请求与生命周期恢复。
 */
@Composable
internal fun rememberArCoreStatus(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    poseTracker: ArCorePoseTracker,
    needsPoseTracking: Boolean,
    isViewpointStep: Boolean,
    shouldUseArCore: Boolean,
    stepOrder: Int?
): ArCoreStatus {
    val scope = rememberCoroutineScope()
    var status by remember(context) { mutableStateOf(ArCoreSupport.idleStatus()) }
    var hasRequestedInstall by remember { mutableStateOf(false) }

    DisposableEffect(
        lifecycleOwner,
        needsPoseTracking,
        shouldUseArCore,
        stepOrder
    ) {
        if (!needsPoseTracking) {
            status = ArCoreSupport.idleStatus()
            hasRequestedInstall = false
            poseTracker.stop()
            return@DisposableEffect onDispose {}
        }
        if (!isViewpointStep || !shouldUseArCore) {
            status = ArCoreSupport.disabledStatus()
            hasRequestedInstall = false
            return@DisposableEffect onDispose {}
        }

        var checkingJob: Job? = null

        fun refreshStatus() {
            val activity = context.findActivity()
            status = if (activity != null) {
                ArCoreSupport.resolveForActivity(
                    activity = activity,
                    requestInstall = !hasRequestedInstall
                ).also { resolved ->
                    if (resolved.state == ArCoreRuntimeState.Installing) {
                        hasRequestedInstall = true
                    }
                }
            } else {
                ArCoreStatus(
                    state = ArCoreRuntimeState.Failed,
                    message = "无法启动 ARCore",
                    detail = "当前继续使用视觉估计"
                )
            }
        }

        fun scheduleCheckingPoll() {
            checkingJob?.cancel()
            val activity = context.findActivity() ?: return
            checkingJob = scope.launch {
                repeat(6) { attempt ->
                    if (status.state != ArCoreRuntimeState.Checking) return@launch
                    delay(1200)
                    refreshStatus()
                    if (status.state != ArCoreRuntimeState.Checking) return@launch
                    if (attempt == 5) {
                        status = ArCoreSupport.checkingTimeoutStatus(activity)
                    }
                }
            }
        }

        refreshStatus()
        if (status.state == ArCoreRuntimeState.Checking) scheduleCheckingPoll()

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatus()
                if (status.state == ArCoreRuntimeState.Checking) {
                    scheduleCheckingPoll()
                } else {
                    checkingJob?.cancel()
                    checkingJob = null
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            checkingJob?.cancel()
            lifecycleOwner.lifecycle.removeObserver(observer)
            poseTracker.stop()
        }
    }

    return status
}

@Composable
internal fun CameraPoseTrackingEffect(
    context: Context,
    poseTracker: ArCorePoseTracker,
    previewView: PreviewView?,
    needsPoseTracking: Boolean,
    shouldUseArCore: Boolean,
    stepOrder: Int?,
    arCoreStatus: ArCoreStatus,
    selectedFacing: Int
) {
    LaunchedEffect(
        needsPoseTracking,
        shouldUseArCore,
        stepOrder,
        arCoreStatus.state,
        selectedFacing
    ) {
        if (!needsPoseTracking || selectedFacing == CameraSelector.LENS_FACING_FRONT) {
            poseTracker.stop()
            return@LaunchedEffect
        }

        poseTracker.start(
            activity = context.findActivity(),
            preferArCore = shouldUseArCore && arCoreStatus.isReady,
            viewportWidth = previewView?.width ?: 0,
            viewportHeight = previewView?.height ?: 0,
            displayRotation = previewView?.display?.rotation ?: Surface.ROTATION_0,
            resetBaseline = true
        )
    }
}
