package com.photoframer.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import java.util.Locale
import kotlin.math.roundToInt

data class CameraLensOption(
    val logicalCameraId: String,
    val physicalCameraId: String? = null,
    val lensFacing: Int,
    val displayName: String,
    val targetZoomRatio: Float? = null
)

data class PhysicalLensMetadata(
    val logicalCameraId: String,
    val physicalCameraId: String,
    val focalLength: Float
)

object CameraLensManager {

    private fun opticalZoomRatios(
        logicalCameraId: String,
        physicalLenses: List<PhysicalLensMetadata>
    ): List<Float> {
        val focalLengths = physicalLenses
            .asSequence()
            .filter { it.logicalCameraId == logicalCameraId && it.focalLength > 0f }
            .map { it.focalLength }
            .distinct()
            .sorted()
            .toList()
        val referenceFocalLength = focalLengths.getOrNull((focalLengths.size - 1) / 2) ?: return emptyList()

        return focalLengths
            .map { normalizeOpticalZoomRatio(it / referenceFocalLength) }
            .distinct()
            .sorted()
    }

    fun buildZoomCapabilities(
        logicalCameraId: String,
        continuousRange: ClosedFloatingPointRange<Float>,
        physicalLenses: List<PhysicalLensMetadata>
    ): ZoomCapabilities {
        return ZoomCapabilities.from(
            continuousRange = continuousRange,
            vendorLensRatios = opticalZoomRatios(logicalCameraId, physicalLenses)
        )
    }

    fun getPhysicalLensMetadata(
        context: Context,
        logicalCameraId: String
    ): List<PhysicalLensMetadata> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        val logicalCharacteristics = runCatching {
            cameraManager.getCameraCharacteristics(logicalCameraId)
        }.getOrNull() ?: return emptyList()
        val capabilities = logicalCharacteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
            ?: return emptyList()
        if (!capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
            return emptyList()
        }

        return logicalCharacteristics.physicalCameraIds.mapNotNull { physicalCameraId ->
            val focalLength = runCatching {
                cameraManager.getCameraCharacteristics(physicalCameraId).focalLengthOrNull()
            }.getOrNull() ?: return@mapNotNull null
            PhysicalLensMetadata(
                logicalCameraId = logicalCameraId,
                physicalCameraId = physicalCameraId,
                focalLength = focalLength
            )
        }
    }

    fun getAvailableLenses(context: Context): List<CameraLensOption> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()

        val rawOptions = mutableListOf<RawLensOption>()

        cameraManager.cameraIdList.forEach { cameraId ->
            val characteristics = runCatching {
                cameraManager.getCameraCharacteristics(cameraId)
            }.getOrNull() ?: return@forEach

            val lensFacing = characteristics[CameraCharacteristics.LENS_FACING] ?: return@forEach
            if (lensFacing != CameraCharacteristics.LENS_FACING_BACK &&
                lensFacing != CameraCharacteristics.LENS_FACING_FRONT
            ) {
                return@forEach
            }

            val capabilities =
                characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: intArrayOf()
            val isLogicalMultiCamera = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                )

            if (isLogicalMultiCamera) {
                val physicalIds = characteristics.physicalCameraIds
                if (physicalIds.isNotEmpty()) {
                    physicalIds.forEach { physicalId ->
                        val physicalCharacteristics = runCatching {
                            cameraManager.getCameraCharacteristics(physicalId)
                        }.getOrNull() ?: return@forEach

                        rawOptions += RawLensOption(
                            logicalCameraId = cameraId,
                            physicalCameraId = physicalId,
                            lensFacing = lensFacing,
                            focalLength = physicalCharacteristics.focalLengthOrNull()
                        )
                    }
                    return@forEach
                }
            }

            rawOptions += RawLensOption(
                logicalCameraId = cameraId,
                physicalCameraId = null,
                lensFacing = lensFacing,
                focalLength = characteristics.focalLengthOrNull()
            )
        }

        return rawOptions
            .sortedWith(
                compareBy<RawLensOption> { lensFacingPriority(it.lensFacing) }
                    .thenBy { logicalCameraNumericId(it.logicalCameraId) }
                    .thenBy { it.focalLength ?: Float.MAX_VALUE }
                    .thenBy { it.physicalCameraId ?: it.logicalCameraId }
            )
            .toLensOptions()
            .removeDuplicateBackLensLabels()
    }

    fun buildCameraSelector(lens: CameraLensOption): CameraSelector {
        return CameraSelector.Builder()
            .requireLensFacing(lens.lensFacing)
            .addCameraFilter { cameraInfos ->
                cameraInfos.filter { cameraInfo ->
                    Camera2CameraInfo.from(cameraInfo).cameraId == lens.logicalCameraId
                }
            }
            .build()
    }

    private fun List<RawLensOption>.toLensOptions(): List<CameraLensOption> {
        val backOptions = filter { it.lensFacing == CameraCharacteristics.LENS_FACING_BACK }
        val frontOptions = filter { it.lensFacing == CameraCharacteristics.LENS_FACING_FRONT }

        return map { option ->
            val backLensInfo = if (option.lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                buildBackLensInfo(backOptions, option)
            } else {
                null
            }
            CameraLensOption(
                logicalCameraId = option.logicalCameraId,
                physicalCameraId = option.physicalCameraId,
                lensFacing = option.lensFacing,
                displayName = when (option.lensFacing) {
                    CameraCharacteristics.LENS_FACING_BACK -> backLensInfo?.first ?: "1x"
                    CameraCharacteristics.LENS_FACING_FRONT -> buildFrontLensName(frontOptions, option)
                    else -> "1x"
                },
                targetZoomRatio = when (option.lensFacing) {
                    CameraCharacteristics.LENS_FACING_BACK -> backLensInfo?.second
                    CameraCharacteristics.LENS_FACING_FRONT -> 1f
                    else -> null
                }
            )
        }
    }

    private fun buildBackLensInfo(
        sameFacingOptions: List<RawLensOption>,
        target: RawLensOption
    ): Pair<String, Float?> {
        val focalLengths = sameFacingOptions.mapNotNull { it.focalLength }
        val targetFocalLength = target.focalLength
        val baseFocalLength = focalLengths
            .sorted()
            .getOrNull(((focalLengths.size - 1) / 2).coerceAtLeast(0))

        if (targetFocalLength == null || baseFocalLength == null || baseFocalLength <= 0f) {
            return if (sameFacingOptions.size <= 1) {
                "1x" to 1f
            } else {
                val fallbackZoom = (sameFacingOptions.indexOfOption(target) + 1).toFloat()
                fallbackIndexedName(sameFacingOptions, target) to fallbackZoom
            }
        }

        val ratio = targetFocalLength / baseFocalLength
        return formatZoomLabel(ratio) to normalizeZoomRatio(ratio)
    }

    private fun buildFrontLensName(
        sameFacingOptions: List<RawLensOption>,
        target: RawLensOption
    ): String {
        return if (sameFacingOptions.size <= 1) {
            "前置"
        } else {
            "前置 ${sameFacingOptions.indexOfOption(target) + 1}"
        }
    }

    private fun fallbackIndexedName(
        sameFacingOptions: List<RawLensOption>,
        target: RawLensOption
    ): String {
        return "${sameFacingOptions.indexOfOption(target) + 1}x"
    }

    private fun List<RawLensOption>.indexOfOption(target: RawLensOption): Int {
        return indexOfFirst {
            it.logicalCameraId == target.logicalCameraId &&
                it.physicalCameraId == target.physicalCameraId
        }.coerceAtLeast(0)
    }

    private fun formatZoomLabel(ratio: Float): String {
        val rounded = normalizeZoomRatio(ratio)
        val normalized = if (kotlin.math.abs(rounded - rounded.toInt()) < 0.05f) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
        return "${normalized}x"
    }

    private fun normalizeZoomRatio(ratio: Float): Float {
        return (ratio * 10f).toInt() / 10f
    }

    private fun normalizeOpticalZoomRatio(ratio: Float): Float {
        return (ratio * 10f).roundToInt() / 10f
    }

    private fun List<CameraLensOption>.removeDuplicateBackLensLabels(): List<CameraLensOption> {
        val preferredBackLensByLabel = filter {
            it.lensFacing == CameraCharacteristics.LENS_FACING_BACK
        }
            .groupBy { it.displayName.lowercase(Locale.US) }
            .mapValues { (_, options) ->
                options.firstOrNull { it.physicalCameraId != null } ?: options.last()
            }

        return filter { option ->
            if (option.lensFacing != CameraCharacteristics.LENS_FACING_BACK) {
                return@filter true
            }

            val key = option.displayName.lowercase(Locale.US)
            preferredBackLensByLabel[key] == option
        }
    }

    private fun CameraCharacteristics.focalLengthOrNull(): Float? {
        return this[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.minOrNull()
    }

    private fun lensFacingPriority(lensFacing: Int): Int {
        return when (lensFacing) {
            CameraCharacteristics.LENS_FACING_BACK -> 0
            CameraCharacteristics.LENS_FACING_FRONT -> 1
            else -> 2
        }
    }

    private fun logicalCameraNumericId(cameraId: String): Int {
        return cameraId.toIntOrNull() ?: Int.MAX_VALUE
    }

    private data class RawLensOption(
        val logicalCameraId: String,
        val physicalCameraId: String?,
        val lensFacing: Int,
        val focalLength: Float?
    )
}
