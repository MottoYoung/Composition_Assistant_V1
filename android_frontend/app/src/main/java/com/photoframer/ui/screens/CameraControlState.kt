package com.photoframer.ui.screens

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.photoframer.ui.components.CameraEntryMode
import com.photoframer.ui.components.CameraVisualStyle
import com.photoframer.ui.components.CaptureTimer
import com.photoframer.ui.components.FlashMode
import com.photoframer.utils.CameraLensManager
import com.photoframer.utils.CameraLensOption
import com.photoframer.utils.GalleryPermissionUtils

/**
 * 相机页面的用户可操作状态。
 *
 * CameraScreen 仍负责组装页面，但不再分散维护拍摄、变焦和工具栏开关。
 */
@Stable
internal class CameraControlState(
    initialGalleryPermission: Boolean,
    initialLenses: List<CameraLensOption>
) {
    var flashMode by mutableStateOf(FlashMode.OFF)
    var gridEnabled by mutableStateOf(false)
    var lastPhotoThumbnail by mutableStateOf<Bitmap?>(null)
    var hasGalleryReadPermission by mutableStateOf(initialGalleryPermission)
    var availableLenses by mutableStateOf(initialLenses)
    var selectedLensIndex by mutableIntStateOf(0)
    var desiredEffectiveZoom by mutableFloatStateOf(1f)
    var isZoomRulerVisible by mutableStateOf(false)
    var zoomInteractionVersion by mutableIntStateOf(0)

    var touchScreenPhotoEnabled by mutableStateOf(false)
    var selectedTimer by mutableStateOf(CaptureTimer.OFF)
    var countdownValue by mutableStateOf<Int?>(null)
    var burstCount by mutableIntStateOf(0)
    var isBursting by mutableStateOf(false)
    var activeEntryMode by mutableStateOf(CameraEntryMode.NONE)
    var visualStyle by mutableStateOf(CameraVisualStyle.DEFAULT)

    fun resetTransientCaptureState() {
        activeEntryMode = CameraEntryMode.NONE
        countdownValue = null
        isBursting = false
        burstCount = 0
    }
}

@Composable
internal fun rememberCameraControlState(context: Context): CameraControlState {
    return remember(context) {
        CameraControlState(
            initialGalleryPermission = GalleryPermissionUtils.hasGalleryReadPermission(context),
            initialLenses = CameraLensManager.getAvailableLenses(context)
        )
    }
}
