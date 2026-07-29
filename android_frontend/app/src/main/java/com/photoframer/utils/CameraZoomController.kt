package com.photoframer.utils

import kotlin.math.abs
import kotlin.math.pow

data class CameraZoomPreset(
    val lensIndex: Int,
    val label: String,
    val effectiveZoomRatio: Float
)

object CameraZoomController {
    const val MAX_EFFECTIVE_ZOOM_RATIO = 8f

    private const val MIN_CAMERA_REQUEST_ZOOM_RATIO = 0.1f
    private const val SNAP_TOLERANCE_RATIO = 0.12f
    private const val MIN_SNAP_DISTANCE = 0.08f
    private const val WHEEL_PIXELS_PER_ZOOM_STOP = 180f

    fun buildPresets(indexedLenses: List<Pair<Int, CameraLensOption>>): List<CameraZoomPreset> {
        return indexedLenses
            .map { (index, lens) ->
                CameraZoomPreset(
                    lensIndex = index,
                    label = lens.displayName,
                    effectiveZoomRatio = lens.targetZoomRatio ?: 1f
                )
            }
            .sortedBy { it.effectiveZoomRatio }
    }

    fun effectiveZoomRatio(lens: CameraLensOption?, rawZoomRatio: Float): Float {
        return (lens?.targetZoomRatio ?: 1f) * rawZoomRatio
    }

    fun cameraRequestZoomRatio(lens: CameraLensOption?, rawZoomRatio: Float): Float {
        return (lens?.requestZoomRatioBase ?: 1f) * rawZoomRatio
    }

    fun effectiveZoomRange(
        presets: List<CameraZoomPreset>,
        currentLens: CameraLensOption?,
        allowLensSwitch: Boolean
    ): ClosedFloatingPointRange<Float> {
        if (!allowLensSwitch) {
            val baseZoom = currentLens?.targetZoomRatio ?: 1f
            val maxZoom = maxOf(baseZoom, minOf(baseZoom * 8f, MAX_EFFECTIVE_ZOOM_RATIO))
            return baseZoom..maxZoom
        }

        val minPresetZoom = presets.minOfOrNull { it.effectiveZoomRatio } ?: 1f
        val maxPresetZoom = presets.maxOfOrNull { it.effectiveZoomRatio } ?: 1f
        val maxZoom = maxOf(maxPresetZoom, minOf(maxPresetZoom * 8f, MAX_EFFECTIVE_ZOOM_RATIO))
        return minPresetZoom..maxZoom
    }

    fun chooseLensForZoom(
        presets: List<CameraZoomPreset>,
        targetEffectiveZoom: Float
    ): CameraZoomPreset? {
        if (presets.isEmpty()) return null

        return presets
            .filter { it.effectiveZoomRatio <= targetEffectiveZoom + 0.001f }
            .maxByOrNull { it.effectiveZoomRatio }
            ?: presets.first()
    }

    fun computeRawZoomRatio(
        lens: CameraLensOption?,
        targetEffectiveZoom: Float
    ): Float {
        val baseZoom = lens?.targetZoomRatio ?: 1f
        val targetRawZoom = targetEffectiveZoom / baseZoom
        return targetRawZoom.coerceIn(MIN_CAMERA_REQUEST_ZOOM_RATIO, 8f)
    }

    fun snapZoomRatio(
        targetEffectiveZoom: Float,
        presets: List<CameraZoomPreset>
    ): Float {
        val nearest = presets.minByOrNull { abs(it.effectiveZoomRatio - targetEffectiveZoom) }
            ?: return targetEffectiveZoom
        val distance = abs(nearest.effectiveZoomRatio - targetEffectiveZoom)
        val tolerance = maxOf(nearest.effectiveZoomRatio * SNAP_TOLERANCE_RATIO, MIN_SNAP_DISTANCE)
        return if (distance <= tolerance) {
            nearest.effectiveZoomRatio
        } else {
            targetEffectiveZoom
        }
    }

    fun zoomRatioFromWheelDrag(
        startZoomRatio: Float,
        dragAmountPx: Float,
        range: ClosedFloatingPointRange<Float>
    ): Float {
        val zoomStops = -dragAmountPx / WHEEL_PIXELS_PER_ZOOM_STOP
        val scaledZoom = startZoomRatio * 2.0.pow(zoomStops.toDouble()).toFloat()
        return scaledZoom.coerceIn(range.start, range.endInclusive)
    }

    fun formatZoomRatio(zoomRatio: Float): String {
        val rounded = (zoomRatio * 10f).toInt() / 10f
        val normalized = if (abs(rounded - rounded.toInt()) < 0.05f) {
            rounded.toInt().toString()
        } else {
            String.format("%.1f", rounded)
        }
        return "${normalized}x"
    }

    fun findDefaultLensIndex(
        indexedLenses: List<Pair<Int, CameraLensOption>>
    ): Int? {
        return indexedLenses.minByOrNull { (_, lens) ->
            abs((lens.targetZoomRatio ?: 1f) - 1f)
        }?.first
    }
}
