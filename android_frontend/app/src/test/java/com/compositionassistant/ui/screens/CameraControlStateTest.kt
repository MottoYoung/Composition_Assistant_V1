package com.compositionassistant.ui.screens

import com.compositionassistant.ui.components.CameraEntryMode
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
        }

        state.resetTransientCaptureState()

        assertEquals(CameraEntryMode.NONE, state.activeEntryMode)
        assertNull(state.countdownValue)
        assertFalse(state.isBursting)
        assertEquals(0, state.burstCount)
        assertTrue(state.gridEnabled)
    }
}
