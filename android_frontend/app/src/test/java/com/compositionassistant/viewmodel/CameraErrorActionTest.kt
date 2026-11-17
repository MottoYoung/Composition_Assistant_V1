package com.compositionassistant.viewmodel

import com.compositionassistant.ui.state.CameraUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraErrorActionTest {
    @Test
    fun `analysis errors use return action`() {
        assertEquals(
            "返回",
            CameraUiState.Error("分析超时，请重试").actionText
        )
    }
}
