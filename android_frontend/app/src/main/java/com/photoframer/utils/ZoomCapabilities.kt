package com.photoframer.utils

import kotlin.math.roundToInt

data class ZoomStop(
    val zoomRatio: Float
)

data class ZoomCapabilities(
    val continuousRange: ClosedFloatingPointRange<Float>,
    val opticalStops: List<ZoomStop>
) {
    companion object {
        fun from(
            continuousRange: ClosedFloatingPointRange<Float>,
            vendorLensRatios: List<Float>
        ): ZoomCapabilities {
            val opticalStops = vendorLensRatios
                .filter { it > 0f && it in continuousRange }
                .distinctBy { (it * 1000f).roundToInt() }
                .sorted()
                .map(::ZoomStop)

            return ZoomCapabilities(
                continuousRange = continuousRange,
                opticalStops = opticalStops
            )
        }
    }
}
