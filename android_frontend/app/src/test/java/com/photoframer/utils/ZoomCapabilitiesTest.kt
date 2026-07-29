package com.photoframer.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomCapabilitiesTest {

    @Test
    fun `keeps only unique vendor lens stops within the active continuous range`() {
        val capabilities = ZoomCapabilities.from(
            continuousRange = 0.8f..4f,
            vendorLensRatios = listOf(0.6f, 1f, 2.9f, 2.9f, 5f)
        )

        assertEquals(0.8f, capabilities.continuousRange.start, 0.001f)
        assertEquals(4f, capabilities.continuousRange.endInclusive, 0.001f)
        assertEquals(listOf(1f, 2.9f), capabilities.opticalStops.map { it.zoomRatio })
    }

    @Test
    fun `derives stops from physical lenses belonging to the active logical camera only`() {
        val capabilities = CameraLensManager.buildZoomCapabilities(
            logicalCameraId = "0",
            continuousRange = 0.5f..4f,
            physicalLenses = listOf(
                PhysicalLensMetadata(logicalCameraId = "0", physicalCameraId = "wide", focalLength = 1.8f),
                PhysicalLensMetadata(logicalCameraId = "0", physicalCameraId = "main", focalLength = 3f),
                PhysicalLensMetadata(logicalCameraId = "0", physicalCameraId = "tele", focalLength = 9f),
                PhysicalLensMetadata(logicalCameraId = "1", physicalCameraId = "front", focalLength = 2.6f)
            )
        )

        assertEquals(listOf(0.6f, 1f, 3f), capabilities.opticalStops.map { it.zoomRatio })
    }

    @Test
    fun `builds capabilities from the active logical camera and its actual range`() {
        val capabilities = CameraLensManager.buildZoomCapabilities(
            logicalCameraId = "0",
            continuousRange = 0.8f..2f,
            physicalLenses = listOf(
                PhysicalLensMetadata(logicalCameraId = "0", physicalCameraId = "wide", focalLength = 1.8f),
                PhysicalLensMetadata(logicalCameraId = "0", physicalCameraId = "main", focalLength = 3f),
                PhysicalLensMetadata(logicalCameraId = "0", physicalCameraId = "tele", focalLength = 9f)
            )
        )

        assertEquals(listOf(1f), capabilities.opticalStops.map { it.zoomRatio })
    }

    @Test
    fun `uses the active logical camera minimum zoom for a physical ultra wide stop`() {
        val capabilities = CameraLensManager.buildZoomCapabilities(
            logicalCameraId = "0",
            continuousRange = 0.6f..20f,
            physicalLenses = listOf(
                PhysicalLensMetadata(logicalCameraId = "0", physicalCameraId = "wide", focalLength = 3.23f),
                PhysicalLensMetadata(logicalCameraId = "0", physicalCameraId = "main", focalLength = 6.06f),
                PhysicalLensMetadata(logicalCameraId = "0", physicalCameraId = "tele", focalLength = 13.85f)
            )
        )

        assertEquals(listOf(0.6f, 1f, 2.3f), capabilities.opticalStops.map { it.zoomRatio })
    }

    @Test
    fun `prefers manufacturer reported multi camera stops over focal length estimates`() {
        val capabilities = CameraLensManager.buildZoomCapabilities(
            logicalCameraId = "0",
            continuousRange = 0.6f..20f,
            physicalLenses = listOf(
                PhysicalLensMetadata(logicalCameraId = "0", physicalCameraId = "wide", focalLength = 3.23f),
                PhysicalLensMetadata(logicalCameraId = "0", physicalCameraId = "main", focalLength = 6.06f),
                PhysicalLensMetadata(logicalCameraId = "0", physicalCameraId = "tele", focalLength = 13.85f)
            ),
            manufacturerZoomStops = listOf(0.6f, 1f, 2f)
        )

        assertEquals(listOf(0.6f, 1f, 2f), capabilities.opticalStops.map { it.zoomRatio })
    }
}
