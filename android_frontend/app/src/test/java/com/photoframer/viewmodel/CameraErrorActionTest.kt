package com.photoframer.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraErrorActionTest {
    @Test
    fun `service unavailable errors return instead of retry`() {
        assertEquals(
            "返回",
            analysisErrorActionText("分析服务暂时不可用，请稍后重试")
        )
        assertEquals(
            "返回",
            analysisErrorActionText("画面内分析服务暂时不可用，请稍后重试")
        )
    }

    @Test
    fun `transient non-service errors keep retry action`() {
        assertEquals(
            "重试",
            analysisErrorActionText("分析超时，请重试")
        )
    }
}
