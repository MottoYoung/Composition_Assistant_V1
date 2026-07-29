package com.photoframer.utils

import kotlin.math.abs
import kotlin.math.pow

object CameraZoomController {
    private const val SNAP_TOLERANCE_RATIO = 0.12f
    private const val MIN_SNAP_DISTANCE = 0.08f
    private const val WHEEL_PIXELS_PER_ZOOM_STOP = 180f

    fun snapToOpticalStop(
        targetZoomRatio: Float,
        opticalStops: List<ZoomStop>
    ): Float {
        val nearest = opticalStops.minByOrNull { abs(it.zoomRatio - targetZoomRatio) }
            ?: return targetZoomRatio
        val distance = abs(nearest.zoomRatio - targetZoomRatio)
        val tolerance = maxOf(nearest.zoomRatio * SNAP_TOLERANCE_RATIO, MIN_SNAP_DISTANCE)
        return if (distance <= tolerance) {
            nearest.zoomRatio
        } else {
            targetZoomRatio
        }
    }

    fun zoomRatioFromWheelDrag(
        startZoomRatio: Float,
        dragAmountPx: Float,
        range: ClosedFloatingPointRange<Float>
    ): Float {
        val zoomStops = -dragAmountPx / WHEEL_PIXELS_PER_ZOOM_STOP
        return (startZoomRatio * 2.0.pow(zoomStops.toDouble()).toFloat())
            .coerceIn(range.start, range.endInclusive)
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
