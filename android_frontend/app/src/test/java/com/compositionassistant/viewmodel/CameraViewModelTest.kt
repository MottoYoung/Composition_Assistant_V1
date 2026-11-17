package com.compositionassistant.viewmodel

import com.compositionassistant.data.api.CompositionResult
import com.compositionassistant.ui.state.CameraUiState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun cancelAiAnalysisDuringPreparation_staysInPreview() = runTest {
        val entered = CompletableDeferred<Unit>()
        val viewModel = CameraViewModel { _, requireBitmap ->
            assertEquals(false, requireBitmap)
            entered.complete(Unit)
            awaitCancellation()
        }
        viewModel.analyzeImage(File("unused.jpg"))
        entered.await()

        viewModel.cancelAnalysis()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is CameraUiState.Preview)
    }

    @Test
    fun cancelInFrameAnalysisDuringPreparation_staysInPreview() = runTest {
        val entered = CompletableDeferred<Unit>()
        val viewModel = CameraViewModel { _, requireBitmap ->
            assertEquals(true, requireBitmap)
            entered.complete(Unit)
            awaitCancellation()
        }
        viewModel.analyzeInFrameComposition(File("unused.jpg"))
        entered.await()

        viewModel.cancelAnalysis()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is CameraUiState.Preview)
    }

    @Test
    fun replacingAnalysisDuringPreparation_keepsNewAnalysisActive() = runTest {
        var preparations = 0
        val viewModel = CameraViewModel { _, _ ->
            preparations++
            awaitCancellation()
        }
        viewModel.analyzeImage(File("first.jpg"))
        viewModel.analyzeImage(File("second.jpg"))
        advanceUntilIdle()

        assertEquals(2, preparations)
        assertTrue(viewModel.uiState.value is CameraUiState.Analyzing)
        viewModel.cancelAnalysis()
        advanceUntilIdle()
    }

    @Test
    fun selectComposition_withoutReferenceImage_doesNotEnterGuiding() {
        val viewModel = CameraViewModel()
        val composition = CompositionResult(
            technique = "rule_of_thirds",
            techniqueName = "三分构图",
            aestheticDesc = "desc",
            steps = emptyList(),
            imageBase64 = null
        )

        viewModel.selectComposition(composition)

        assertTrue(viewModel.uiState.value is CameraUiState.Error)
    }
}
