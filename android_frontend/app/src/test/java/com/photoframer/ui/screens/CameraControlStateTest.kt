package com.photoframer.ui.screens

import com.photoframer.ui.components.AspectRatioOption
import com.photoframer.ui.components.CameraEntryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlStateTest {
    @Test
    fun `preview reset clears only transient capture state`() {
        val state = CameraControlState(
            initialGalleryPermission = true,
            initialLenses = emptyList()
        ).apply {
            activeEntryMode = CameraEntryMode.AI
            countdownValue = 3
            isBursting = true
            burstCount = 5
            gridEnabled = true
            selectedRatio = AspectRatioOption.RATIO_1_1
        }

        state.resetTransientCaptureState()

        assertEquals(CameraEntryMode.NONE, state.activeEntryMode)
        assertNull(state.countdownValue)
        assertFalse(state.isBursting)
        assertEquals(0, state.burstCount)
        assertTrue(state.gridEnabled)
        assertEquals(AspectRatioOption.RATIO_1_1, state.selectedRatio)
    }

    @Test
    fun `aspect ratio conversion preserves full frame as no crop`() {
        assertEquals(4f / 3f, AspectRatioOption.RATIO_4_3.toTargetAspectRatio() ?: 0f, 0.001f)
        assertEquals(1f, AspectRatioOption.RATIO_1_1.toTargetAspectRatio() ?: 0f, 0.001f)
        assertNull(AspectRatioOption.FULL.toTargetAspectRatio())
    }
}
