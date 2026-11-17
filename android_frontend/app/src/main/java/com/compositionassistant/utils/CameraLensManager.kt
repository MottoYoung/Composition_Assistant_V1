package com.compositionassistant.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.SizeF
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

data class CameraLensOption(
    val logicalCameraId: String,
    val physicalCameraId: String? = null,
    val lensFacing: Int,
    val displayName: String,
    val targetZoomRatio: Float? = null,
    val requestZoomRatioBase: Float = targetZoomRatio ?: 1f,
    val focalLengthMm: Float? = null,
    val equivalentFocalLengthMm: Float? = null,
    val sensorPhysicalSize: SizeF? = null,
    val zoomRatioRange: ClosedFloatingPointRange<Float>? = null,
    val isDirectCamera: Boolean = true,
    val isLogicalCamera: Boolean = false
)

object CameraLensManager {
    private const val TAG = "CameraLensManager"
    private const val FULL_FRAME_DIAGONAL_MM = 43.2666f
    private const val DEFAULT_MAIN_PHYSICAL_FOCAL_MM = 6f
    private const val FOCAL_GROUP_RELATIVE_TOLERANCE = 0.08f
    private const val FOCAL_GROUP_ABSOLUTE_TOLERANCE_MM = 0.15f

    fun getAvailableLenses(context: Context): List<CameraLensOption> {
        val detection = detectLensOptions(context) ?: return emptyList()

        val deviceSignature = CameraLensDeviceSignature.current()
        val profile = CameraLensProfileRegistry.match(deviceSignature, detection)
        val lenses = (profile?.buildLensOptions(detection) ?: buildAutomaticLensOptions(detection))
            .disambiguateDuplicateBackLensLabels()

        Log.i(
            TAG,
            "Loaded lenses profile=${profile?.name ?: "automatic"} device=$deviceSignature: " +
                lenses.joinToString { it.debugSummary() }
        )
        return lenses
    }

    fun defaultLensIndex(lenses: List<CameraLensOption>): Int {
        val mainBackLensIndex = lenses.indexOfFirst { lens ->
            lens.lensFacing == CameraCharacteristics.LENS_FACING_BACK &&
                (lens.targetZoomRatio?.let { kotlin.math.abs(it - 1f) < 0.15f } == true)
        }
        if (mainBackLensIndex >= 0) {
            return mainBackLensIndex
        }

        val closestBackLensIndex = lenses.withIndex()
            .filter { (_, lens) -> lens.lensFacing == CameraCharacteristics.LENS_FACING_BACK }
            .minByOrNull { (_, lens) -> kotlin.math.abs((lens.targetZoomRatio ?: 1f) - 1f) }
            ?.index
        if (closestBackLensIndex != null) {
            return closestBackLensIndex
        }

        return lenses.withIndex()
            .minByOrNull { (_, lens) -> kotlin.math.abs((lens.targetZoomRatio ?: 1f) - 1f) }
            ?.index ?: 0
    }

    /** Keeps V2's logical-camera routing when this manager is used by V1's CameraX preview. */
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

    private fun detectLensOptions(context: Context): LensDetection? {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return null

        val publicCameraIds = cameraManager.cameraIdList.toSet()
        val cameraInfos = cameraManager.discoverCameraIds().mapNotNull { cameraId ->
            cameraManager.readCameraInfo(cameraId)
        }
        val rawOptions = mutableListOf<RawLensOption>()

        cameraInfos.forEach { info ->
            val lensFacing = info.lensFacing
            if (lensFacing != CameraCharacteristics.LENS_FACING_BACK &&
                lensFacing != CameraCharacteristics.LENS_FACING_FRONT
            ) {
                return@forEach
            }

            if (info.cameraId in publicCameraIds &&
                info.isLogicalMultiCamera &&
                info.physicalCameraIds.isNotEmpty()
            ) {
                info.physicalCameraIds.forEach { physicalId ->
                    // Some vendors expose physical cameras as public IDs too. They
                    // still need a logical-camera route here; otherwise a zoom preset
                    // selects that direct ID and bypasses HAL's automatic switching.
                    val physicalInfo = cameraInfos.firstOrNull { it.cameraId == physicalId }
                        ?: cameraManager.readCameraInfo(
                            cameraId = physicalId,
                            fallbackLensFacing = lensFacing
                        )
                    if (physicalInfo != null) {
                        rawOptions += physicalInfo.toRawLensOption(
                            openCameraId = info.cameraId,
                            physicalCameraId = physicalId,
                            isDirectCamera = false,
                            fallbackZoomRange = info.zoomRatioRange
                        )
                    }
                }
            }

            rawOptions += info.toRawLensOption(
                openCameraId = info.cameraId,
                physicalCameraId = null,
                isDirectCamera = true,
                fallbackZoomRange = null
            )
        }

        return rawOptions
            .sortedWith(
                compareBy<RawLensOption> { lensFacingPriority(it.lensFacing) }
                    .thenBy { it.equivalentFocalLength ?: it.focalLength ?: Float.MAX_VALUE }
                    .thenByDescending { it.isDirectCamera }
                    .thenBy { logicalCameraNumericId(it.logicalCameraId) }
                    .thenBy { it.physicalCameraId ?: "" }
            )
            .toLensDetection()
    }

    private fun List<RawLensOption>.toLensDetection(): LensDetection {
        val backOptions = filter { it.lensFacing == CameraCharacteristics.LENS_FACING_BACK }
        val frontOptions = filter { it.lensFacing == CameraCharacteristics.LENS_FACING_FRONT }
        return LensDetection(
            backGroups = buildBackLensGroups(backOptions),
            frontOptions = frontOptions
        )
    }

    private fun buildAutomaticLensOptions(detection: LensDetection): List<CameraLensOption> {
        return buildBackLensOptions(detection.backGroups) +
            buildFrontLensOptions(detection.frontOptions)
    }

    private fun buildBackLensOptions(
        groups: List<BackLensGroup>,
        displayZoomOverride: (index: Int, group: BackLensGroup) -> Float? = { _, _ -> null }
    ): List<CameraLensOption> {
        return groups.mapIndexed { index, group ->
            val option = group.representative
            val displayZoomRatio = displayZoomOverride(index, group) ?: group.displayZoomRatio
            val requestZoomRatioBase = when {
                option.physicalCameraId != null -> 1f
                option.isLogicalCamera -> displayZoomRatio
                else -> 1f
            }

            CameraLensOption(
                logicalCameraId = option.logicalCameraId,
                physicalCameraId = option.physicalCameraId,
                lensFacing = option.lensFacing,
                displayName = formatZoomLabel(displayZoomRatio),
                targetZoomRatio = displayZoomRatio,
                requestZoomRatioBase = requestZoomRatioBase,
                focalLengthMm = option.focalLength,
                equivalentFocalLengthMm = option.equivalentFocalLength,
                sensorPhysicalSize = option.sensorPhysicalSize,
                zoomRatioRange = option.zoomRatioRange,
                isDirectCamera = option.isDirectCamera,
                isLogicalCamera = option.isLogicalCamera
            )
        }
    }

    private fun buildFrontLensOptions(frontOptions: List<RawLensOption>): List<CameraLensOption> {
        return frontOptions.map { option ->
            CameraLensOption(
                logicalCameraId = option.logicalCameraId,
                physicalCameraId = option.physicalCameraId,
                lensFacing = option.lensFacing,
                displayName = buildFrontLensName(frontOptions, option),
                targetZoomRatio = 1f,
                requestZoomRatioBase = 1f,
                focalLengthMm = option.focalLength,
                equivalentFocalLengthMm = option.equivalentFocalLength,
                sensorPhysicalSize = option.sensorPhysicalSize,
                zoomRatioRange = option.zoomRatioRange,
                isDirectCamera = option.isDirectCamera,
                isLogicalCamera = option.isLogicalCamera
            )
        }
    }

    private fun buildBackLensGroups(options: List<RawLensOption>): List<BackLensGroup> {
        if (options.isEmpty()) return emptyList()

        val rawGroups = mutableListOf<MutableList<RawLensOption>>()
        options
            .sortedWith(
                compareBy<RawLensOption> { it.focalLength ?: it.equivalentFocalLength ?: Float.MAX_VALUE }
                    .thenBy { logicalCameraNumericId(it.logicalCameraId) }
                    .thenBy { it.physicalCameraId ?: "" }
            )
            .forEach { option ->
                val focalLength = option.focalLength ?: option.equivalentFocalLength
                val group = rawGroups.firstOrNull { existing ->
                    focalLength != null &&
                        areSameOpticalFocalLength(
                            existing.firstOrNull()?.focalLength ?: existing.firstOrNull()?.equivalentFocalLength,
                            focalLength
                        )
                }
                if (group != null) {
                    group += option
                } else {
                    rawGroups += mutableListOf(option)
                }
            }

        val groups = rawGroups.map { groupOptions ->
            val representative = groupOptions.minWith(
                compareBy<RawLensOption> { representativePriority(it) }
                    .thenBy { logicalCameraNumericId(it.logicalCameraId) }
                    .thenBy { it.physicalCameraId ?: "" }
            )
            BackLensGroup(
                members = groupOptions,
                representative = representative,
                focalLength = groupOptions.mapNotNull { it.focalLength }.averageOrNull()
                    ?: representative.focalLength
                    ?: representative.equivalentFocalLength
            )
        }.sortedBy { it.focalLength ?: Float.MAX_VALUE }

        val mainIndex = chooseMainBackGroupIndex(groups)
        val mainFocalLength = groups.getOrNull(mainIndex)?.focalLength

        return groups.mapIndexed { index, group ->
            val opticalRatio = group.focalLength?.let { focal ->
                mainFocalLength?.takeIf { it > 0f }?.let { focal / it }
            } ?: fallbackIndexedZoom(groups, group)
            val displayZoomRatio = snapCameraAppZoomRatio(opticalRatio)
            group.copy(displayZoomRatio = displayZoomRatio)
        }
    }

    private fun areSameOpticalFocalLength(left: Float?, right: Float?): Boolean {
        if (left == null || right == null) return false
        val absoluteDiff = abs(left - right)
        val relativeDiff = absoluteDiff / maxOf(left, right)
        return absoluteDiff <= FOCAL_GROUP_ABSOLUTE_TOLERANCE_MM ||
            relativeDiff <= FOCAL_GROUP_RELATIVE_TOLERANCE
    }

    private fun representativePriority(option: RawLensOption): Int {
        return when {
            // Prefer the logical-camera route for every optical preset. The physical
            // ID is metadata for the preset only; CameraScreen keeps the logical
            // camera open and lets HAL select this sensor at the requested zoom.
            option.physicalCameraId != null -> 0
            option.isDirectCamera && !option.isLogicalCamera -> 1
            option.isDirectCamera -> 2
            else -> 3
        }
    }

    private fun chooseMainBackGroupIndex(groups: List<BackLensGroup>): Int {
        val logicalMainIndex = groups.indexOfFirst { group ->
            group.members.any { option ->
                option.isLogicalCamera &&
                    option.zoomRatioRange?.let { it.start < 0.9f && it.endInclusive > 3f } == true
            }
        }
        if (logicalMainIndex >= 0) {
            return logicalMainIndex
        }

        if (groups.size >= 3) {
            val firstFocal = groups.getOrNull(0)?.focalLength
            val secondFocal = groups.getOrNull(1)?.focalLength
            if (firstFocal != null && secondFocal != null && firstFocal / secondFocal < 0.75f) {
                return 1
            }
        }

        return groups.withIndex()
            .minByOrNull { (_, group) ->
                abs((group.focalLength ?: DEFAULT_MAIN_PHYSICAL_FOCAL_MM) - DEFAULT_MAIN_PHYSICAL_FOCAL_MM)
            }
            ?.index ?: 0
    }

    private fun snapCameraAppZoomRatio(ratio: Float): Float {
        val candidates = listOf(0.5f, 0.6f, 0.7f, 1f, 2f, 3f, 5f, 6f, 10f)
        val nearest = candidates.minByOrNull { abs(it - ratio) } ?: return normalizeZoomRatio(ratio)
        val relativeDistance = abs(nearest - ratio) / maxOf(nearest, ratio)
        return if (relativeDistance <= 0.22f) nearest else normalizeZoomRatio(ratio)
    }

    private fun fallbackIndexedZoom(groups: List<BackLensGroup>, target: BackLensGroup): Float {
        return (groups.indexOf(target).coerceAtLeast(0) + 1).toFloat()
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

    private fun List<RawLensOption>.indexOfOption(target: RawLensOption): Int {
        return indexOfFirst {
            it.logicalCameraId == target.logicalCameraId &&
                it.physicalCameraId == target.physicalCameraId
        }.coerceAtLeast(0)
    }

    private fun formatZoomLabel(ratio: Float): String {
        val rounded = normalizeZoomRatio(ratio)
        val normalized = if (abs(rounded - rounded.toInt()) < 0.05f) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
        return "${normalized}x"
    }

    private fun normalizeZoomRatio(ratio: Float): Float {
        return ((ratio * 10f).toInt() / 10f).coerceAtLeast(0.1f)
    }

    private fun List<CameraLensOption>.disambiguateDuplicateBackLensLabels(): List<CameraLensOption> {
        val labelCounts = groupingBy { option ->
            if (option.lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                option.displayName.lowercase(Locale.US)
            } else {
                "__non_back_${option.logicalCameraId}_${option.physicalCameraId}"
            }
        }.eachCount()
        val seenLabelIndexes = mutableMapOf<String, Int>()

        return map { option ->
            if (option.lensFacing != CameraCharacteristics.LENS_FACING_BACK) {
                return@map option
            }
            val key = option.displayName.lowercase(Locale.US)
            if ((labelCounts[key] ?: 0) <= 1) {
                return@map option
            }
            val nextIndex = seenLabelIndexes.getOrDefault(key, 0) + 1
            seenLabelIndexes[key] = nextIndex
            option.copy(displayName = "${option.displayName}.$nextIndex")
        }
    }

    private interface CameraLensProfile {
        val name: String

        fun matches(
            signature: CameraLensDeviceSignature,
            detection: LensDetection
        ): Boolean

        fun buildLensOptions(detection: LensDetection): List<CameraLensOption>
    }

    private object CameraLensProfileRegistry {
        private val profiles = listOf<CameraLensProfile>(
            OplusVisibleLensLabelProfile
        )

        fun match(
            signature: CameraLensDeviceSignature,
            detection: LensDetection
        ): CameraLensProfile? {
            return profiles.firstOrNull { profile ->
                profile.matches(signature, detection)
            }
        }
    }

    private object OplusVisibleLensLabelProfile : CameraLensProfile {
        override val name: String = "oplus_visible_lens_labels"

        override fun matches(
            signature: CameraLensDeviceSignature,
            detection: LensDetection
        ): Boolean {
            if (!signature.isOplusFamily) return false
            if (detection.backGroups.size != OPLUS_VISIBLE_BACK_LABELS.size) return false

            val mainGroup = detection.backGroups.minByOrNull { group ->
                abs(group.displayZoomRatio - 1f)
            } ?: return false
            val mainFocalLength = mainGroup.focalLength ?: return false
            if (mainFocalLength <= 0f) return false

            val focalRatios = detection.backGroups.mapNotNull { group ->
                group.focalLength?.let { focal -> focal / mainFocalLength }
            }
            if (focalRatios.size != detection.backGroups.size) return false

            val hasExpectedPhysicalShape = focalRatios[0] < 0.75f &&
                focalRatios[1] in 0.85f..1.15f &&
                focalRatios[2] in 1.6f..3.3f
            val hasLogicalWideRoute = mainGroup.members.any { option ->
                option.isLogicalCamera &&
                    option.zoomRatioRange?.let { range ->
                        range.start <= 0.65f && range.endInclusive >= 3f
                    } == true
            }

            return hasExpectedPhysicalShape && hasLogicalWideRoute
        }

        override fun buildLensOptions(detection: LensDetection): List<CameraLensOption> {
            val backOptions = buildBackLensOptions(detection.backGroups) { index, _ ->
                OPLUS_VISIBLE_BACK_LABELS.getOrNull(index)
            }
            return backOptions + buildFrontLensOptions(detection.frontOptions)
        }
    }

    private fun CameraCharacteristics.focalLengthOrNull(): Float? {
        return this[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.minOrNull()
    }

    private fun CameraCharacteristics.sensorPhysicalSizeOrNull(): SizeF? {
        return this[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]
    }

    private fun CameraCharacteristics.zoomRatioRangeOrNull(): ClosedFloatingPointRange<Float>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val range = this[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] ?: return null
        return range.lower..range.upper
    }

    private fun CameraManager.discoverCameraIds(): List<String> {
        return (cameraIdList.toList() + (0..12).map { it.toString() }).distinct()
    }

    private fun CameraManager.readCameraInfo(
        cameraId: String,
        fallbackLensFacing: Int? = null
    ): CameraDeviceInfo? {
        val characteristics = runCatching {
            getCameraCharacteristics(cameraId)
        }.getOrNull() ?: return null
        val lensFacing = characteristics[CameraCharacteristics.LENS_FACING]
            ?: fallbackLensFacing
            ?: return null
        val focalLength = characteristics.focalLengthOrNull()
        val sensorPhysicalSize = characteristics.sensorPhysicalSizeOrNull()
        val equivalentFocalLength = equivalentFocalLength(focalLength, sensorPhysicalSize)
        val capabilities =
            characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: intArrayOf()
        val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            characteristics.physicalCameraIds
        } else {
            emptySet()
        }
        return CameraDeviceInfo(
            cameraId = cameraId,
            lensFacing = lensFacing,
            focalLength = focalLength,
            equivalentFocalLength = equivalentFocalLength,
            sensorPhysicalSize = sensorPhysicalSize,
            zoomRatioRange = characteristics.zoomRatioRangeOrNull(),
            capabilities = capabilities,
            physicalCameraIds = physicalIds
        )
    }

    private fun CameraDeviceInfo.toRawLensOption(
        openCameraId: String,
        physicalCameraId: String?,
        isDirectCamera: Boolean,
        fallbackZoomRange: ClosedFloatingPointRange<Float>?
    ): RawLensOption {
        return RawLensOption(
            logicalCameraId = openCameraId,
            physicalCameraId = physicalCameraId,
            lensFacing = lensFacing,
            focalLength = focalLength,
            equivalentFocalLength = equivalentFocalLength,
            sensorPhysicalSize = sensorPhysicalSize,
            zoomRatioRange = zoomRatioRange ?: fallbackZoomRange,
            isDirectCamera = isDirectCamera,
            isLogicalCamera = isLogicalMultiCamera && physicalCameraId == null
        )
    }

    private fun equivalentFocalLength(
        focalLength: Float?,
        sensorPhysicalSize: SizeF?
    ): Float? {
        if (focalLength == null || sensorPhysicalSize == null) return null
        val diagonal = sqrt(
            sensorPhysicalSize.width * sensorPhysicalSize.width +
                sensorPhysicalSize.height * sensorPhysicalSize.height
        )
        if (diagonal <= 0f) return null
        return focalLength * FULL_FRAME_DIAGONAL_MM / diagonal
    }

    private fun CameraLensOption.debugSummary(): String {
        return "$displayName(id=$logicalCameraId physical=$physicalCameraId " +
            "f=${focalLengthMm?.format1()} eq=${equivalentFocalLengthMm?.format1()} " +
            "zoom=${targetZoomRatio?.format1()} requestBase=${requestZoomRatioBase.format1()} " +
            "range=${zoomRatioRange?.start?.format1()}..${zoomRatioRange?.endInclusive?.format1()} " +
            "direct=$isDirectCamera logical=$isLogicalCamera)"
    }

    private fun Float.format1(): String = String.format(Locale.US, "%.1f", this)

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
        val focalLength: Float?,
        val equivalentFocalLength: Float?,
        val sensorPhysicalSize: SizeF?,
        val zoomRatioRange: ClosedFloatingPointRange<Float>?,
        val isDirectCamera: Boolean,
        val isLogicalCamera: Boolean
    )

    private data class BackLensGroup(
        val members: List<RawLensOption>,
        val representative: RawLensOption,
        val focalLength: Float?,
        val displayZoomRatio: Float = 1f
    )

    private data class LensDetection(
        val backGroups: List<BackLensGroup>,
        val frontOptions: List<RawLensOption>
    )

    private data class CameraLensDeviceSignature(
        val manufacturer: String,
        val brand: String,
        val model: String,
        val product: String,
        val device: String
    ) {
        val isOplusFamily: Boolean
            get() = listOf(manufacturer, brand, product, device).any { value ->
                val normalized = value.lowercase(Locale.US)
                normalized.contains("oppo") ||
                    normalized.contains("oneplus") ||
                    normalized.contains("oplus")
            }

        override fun toString(): String {
            return "manufacturer=$manufacturer brand=$brand model=$model product=$product device=$device"
        }

        companion object {
            fun current(): CameraLensDeviceSignature {
                return CameraLensDeviceSignature(
                    manufacturer = Build.MANUFACTURER.orEmpty(),
                    brand = Build.BRAND.orEmpty(),
                    model = Build.MODEL.orEmpty(),
                    product = Build.PRODUCT.orEmpty(),
                    device = Build.DEVICE.orEmpty()
                )
            }
        }
    }

    private data class CameraDeviceInfo(
        val cameraId: String,
        val lensFacing: Int,
        val focalLength: Float?,
        val equivalentFocalLength: Float?,
        val sensorPhysicalSize: SizeF?,
        val zoomRatioRange: ClosedFloatingPointRange<Float>?,
        val capabilities: IntArray,
        val physicalCameraIds: Set<String>
    ) {
        val isLogicalMultiCamera: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                )
    }

    private fun Iterable<Float>.averageOrNull(): Float? {
        var count = 0
        var sum = 0f
        for (value in this) {
            count += 1
            sum += value
        }
        return if (count > 0) sum / count else null
    }

    private val OPLUS_VISIBLE_BACK_LABELS = listOf(0.6f, 1f, 3f)
}
