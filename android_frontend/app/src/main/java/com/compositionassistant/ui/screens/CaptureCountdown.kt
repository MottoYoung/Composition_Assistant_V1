package com.compositionassistant.ui.screens

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class CaptureCountdown(
    private val scope: CoroutineScope,
    private val onTick: (Int?) -> Unit
) {
    private var job: Job? = null

    val isActive: Boolean
        get() = job?.isActive == true

    fun start(seconds: Int, onCapture: () -> Unit) {
        require(seconds > 0)
        cancel()
        job = scope.launch {
            for (remaining in seconds downTo 1) {
                onTick(remaining)
                delay(1_000)
            }
            onTick(null)
            onCapture()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        onTick(null)
    }
}
