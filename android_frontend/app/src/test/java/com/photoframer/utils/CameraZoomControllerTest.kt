package com.photoframer.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraZoomControllerTest {

    @Test
    fun `wheel drag changes zoom logarithmically and clamps to the real camera range`() {
        val range = 0.8f..4f

        assertEquals(
            2f,
            CameraZoomController.zoomRatioFromWheelDrag(
                startZoomRatio = 1f,
                dragAmountPx = -180f,
                range = range
            ),
            0.001f
        )
        assertEquals(
            0.8f,
            CameraZoomController.zoomRatioFromWheelDrag(
                startZoomRatio = 1f,
                dragAmountPx = 720f,
                range = range
            ),
            0.001f
        )
    }

    @Test
    fun `release snaps only to a discovered optical stop`() {
        val stops = listOf(ZoomStop(0.6f), ZoomStop(1f), ZoomStop(2.9f))

        assertEquals(2.9f, CameraZoomController.snapToOpticalStop(2.82f, stops), 0.001f)
        assertEquals(2f, CameraZoomController.snapToOpticalStop(2f, stops), 0.001f)
    }
}
