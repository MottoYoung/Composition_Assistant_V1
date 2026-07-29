package com.photoframer.utils

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraZoomControllerTest {
    private val currentLens = CameraLensOption(
        logicalCameraId = "0",
        lensFacing = CameraCharacteristics.LENS_FACING_BACK,
        displayName = "1x",
        targetZoomRatio = 1f
    )

    private val presets = listOf(
        CameraZoomPreset(lensIndex = 0, label = "0.5x", effectiveZoomRatio = 0.5f),
        CameraZoomPreset(lensIndex = 1, label = "1x", effectiveZoomRatio = 1f),
        CameraZoomPreset(lensIndex = 2, label = "3x", effectiveZoomRatio = 3f)
    )

    @Test
    fun `interaction range uses every lens preset and caps continuous zoom at eight x`() {
        val range = CameraZoomController.effectiveZoomRange(
            presets = presets,
            currentLens = currentLens,
            allowLensSwitch = true
        )

        assertEquals(0.5f, range.start, 0.0001f)
        assertEquals(8f, range.endInclusive, 0.0001f)
    }

    @Test
    fun `interaction range stays on current lens when lens switching is locked`() {
        val range = CameraZoomController.effectiveZoomRange(
            presets = presets,
            currentLens = currentLens,
            allowLensSwitch = false
        )

        assertEquals(1f, range.start, 0.0001f)
        assertEquals(8f, range.endInclusive, 0.0001f)
    }

    @Test
    fun `default lens prefers the back camera closest to one x`() {
        val lenses = listOf(
            CameraLensOption(
                logicalCameraId = "4",
                lensFacing = CameraCharacteristics.LENS_FACING_BACK,
                displayName = "0.5x",
                targetZoomRatio = 0.5f
            ),
            CameraLensOption(
                logicalCameraId = "0",
                lensFacing = CameraCharacteristics.LENS_FACING_BACK,
                displayName = "1x",
                targetZoomRatio = 1f
            ),
            CameraLensOption(
                logicalCameraId = "3",
                lensFacing = CameraCharacteristics.LENS_FACING_BACK,
                displayName = "3x",
                targetZoomRatio = 3f
            )
        )

        assertEquals(1, CameraLensManager.defaultLensIndex(lenses))
    }

    @Test
    fun `zoom selection stays on lower lens before the next optical preset`() {
        val preset = CameraZoomController.chooseLensForZoom(presets, 2.9f)

        assertEquals(1, preset?.lensIndex)
    }

    @Test
    fun `zoom selection switches lens at the optical preset boundary`() {
        val preset = CameraZoomController.chooseLensForZoom(presets, 3f)

        assertEquals(2, preset?.lensIndex)
    }

    @Test
    fun `wheel drag scales zoom proportionally and clamps to range`() {
        val range = 0.6f..8f

        assertEquals(
            2f,
            CameraZoomController.zoomRatioFromWheelDrag(
                startZoomRatio = 1f,
                dragAmountPx = -180f,
                range = range
            ),
            0.0001f
        )
        assertEquals(
            0.6f,
            CameraZoomController.zoomRatioFromWheelDrag(
                startZoomRatio = 1f,
                dragAmountPx = 900f,
                range = range
            ),
            0.0001f
        )
        assertEquals(
            8f,
            CameraZoomController.zoomRatioFromWheelDrag(
                startZoomRatio = 3f,
                dragAmountPx = -360f,
                range = range
            ),
            0.0001f
        )
    }

    @Test
    fun `logical camera request preserves sub one x zoom`() {
        assertEquals(
            0.6f,
            CameraZoomController.computeRawZoomRatio(
                lens = null,
                targetEffectiveZoom = 0.6f
            ),
            0.0001f
        )
    }

    @Test
    fun `direct physical lens requests raw zoom instead of effective zoom`() {
        val teleLens = CameraLensOption(
            logicalCameraId = "3",
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            displayName = "3.1x",
            targetZoomRatio = 3.1f,
            requestZoomRatioBase = 1f,
            isDirectCamera = true
        )

        assertEquals(3.1f, CameraZoomController.effectiveZoomRatio(teleLens, 1f), 0.0001f)
        assertEquals(1f, CameraZoomController.cameraRequestZoomRatio(teleLens, 1f), 0.0001f)
    }

    @Test
    fun `logical physical lens requests raw zoom because physical output is pinned`() {
        val hiddenTeleLens = CameraLensOption(
            logicalCameraId = "0",
            physicalCameraId = "3",
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            displayName = "3.1x",
            targetZoomRatio = 3.1f,
            requestZoomRatioBase = 1f,
            isDirectCamera = false
        )

        assertEquals(3.1f, CameraZoomController.effectiveZoomRatio(hiddenTeleLens, 1f), 0.0001f)
        assertEquals(1f, CameraZoomController.cameraRequestZoomRatio(hiddenTeleLens, 1f), 0.0001f)
    }

    @Test
    fun `logical lens can request base zoom when no physical output is pinned`() {
        val logicalZoomLens = CameraLensOption(
            logicalCameraId = "0",
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            displayName = "2x",
            targetZoomRatio = 2f,
            requestZoomRatioBase = 2f,
            isDirectCamera = true,
            isLogicalCamera = true
        )

        assertEquals(2f, CameraZoomController.effectiveZoomRatio(logicalZoomLens, 1f), 0.0001f)
        assertEquals(2f, CameraZoomController.cameraRequestZoomRatio(logicalZoomLens, 1f), 0.0001f)
    }
}
