package com.compositionassistant.ui.screens

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureCountdownTest {
    @Test
    fun cancelDuringCountdown_doesNotTakePhoto() = runTest {
        var displayed: Int? = null
        var photos = 0
        val countdown = CaptureCountdown(this) { displayed = it }
        countdown.start(3) { photos++ }
        runCurrent()
        advanceTimeBy(500)

        countdown.cancel()
        advanceUntilIdle()

        assertNull(displayed)
        assertFalse(countdown.isActive)
        assertEquals(0, photos)
    }

    @Test
    fun restart_doesNotReuseOldDeadlineOrTakeTwoPhotos() = runTest {
        var displayed: Int? = null
        var photos = 0
        val countdown = CaptureCountdown(this) { displayed = it }
        countdown.start(3) { photos++ }
        advanceTimeBy(500)

        countdown.start(3) { photos++ }
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(0, photos)
        assertEquals(1, displayed)

        advanceUntilIdle()
        assertEquals(1, photos)
        assertNull(displayed)
    }
}
