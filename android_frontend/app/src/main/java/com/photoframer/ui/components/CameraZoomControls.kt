package com.photoframer.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoframer.utils.CameraZoomController
import com.photoframer.utils.CameraZoomPreset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

private val ZoomAccent = Color(0xFFFF8A14)
private val WheelSurface = Color(0xCC3A3836)
private val WheelRimShadow = Color(0x4D171716)
private val WheelRim = Color(0x3DFFFFFF)
private val WheelText = Color.White.copy(alpha = 0.92f)
private val WheelSecondaryText = Color.White.copy(alpha = 0.45f)
private val WheelFineTick = Color(0x38FFFFFF)
private val WheelMediumTick = Color(0x66FFFFFF)
private val WheelMajorTick = Color(0xD9FFFFFF)

data class CameraZoomUiState(
    val presets: List<CameraZoomPreset>,
    val selectedLensIndex: Int?,
    val displayedZoomRatio: Float,
    val displayedZoomText: String,
    val zoomRange: ClosedFloatingPointRange<Float>,
    val isWheelVisible: Boolean
)

@Composable
fun CameraZoomControls(
    state: CameraZoomUiState,
    onZoomTargetClick: (Float) -> Unit,
    onWheelReveal: () -> Unit,
    onWheelScrub: (Float) -> Unit,
    onWheelDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.presets.isEmpty()) return

    Box(
        modifier = modifier
            .zoomWheelDragInput(
                state = state,
                onWheelReveal = onWheelReveal,
                onWheelScrub = onWheelScrub,
                onWheelDragEnd = onWheelDragEnd
            )
            .fillMaxWidth()
            .height(if (state.isWheelVisible) 286.dp else 68.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        if (state.isWheelVisible) {
            ReferenceZoomWheel(state = state)
        } else {
            ZoomPresetButtons(
                targets = state.compactTargets(),
                displayedZoomRatio = state.displayedZoomRatio,
                onZoomTargetClick = onZoomTargetClick,
                onSelectedPresetClick = onWheelReveal
            )
        }
    }
}

@Composable
private fun Modifier.zoomWheelDragInput(
    state: CameraZoomUiState,
    onWheelReveal: () -> Unit,
    onWheelScrub: (Float) -> Unit,
    onWheelDragEnd: () -> Unit
): Modifier {
    val latestState by rememberUpdatedState(state)
    val latestReveal by rememberUpdatedState(onWheelReveal)
    val latestScrub by rememberUpdatedState(onWheelScrub)
    val latestDragEnd by rememberUpdatedState(onWheelDragEnd)

    return pointerInput(state.zoomRange.start, state.zoomRange.endInclusive) {
        var startZoomRatio = 1f
        var accumulatedDragPx = 0f

        detectHorizontalDragGestures(
            onDragStart = {
                startZoomRatio = latestState.displayedZoomRatio
                accumulatedDragPx = 0f
                latestReveal()
            },
            onHorizontalDrag = { _, dragAmount ->
                accumulatedDragPx += dragAmount
                latestScrub(
                    CameraZoomController.zoomRatioFromWheelDrag(
                        startZoomRatio = startZoomRatio,
                        dragAmountPx = accumulatedDragPx,
                        range = latestState.zoomRange
                    )
                )
            },
            onDragEnd = latestDragEnd,
            onDragCancel = latestDragEnd
        )
    }
}

@Composable
private fun ZoomPresetButtons(
    targets: List<ZoomQuickTarget>,
    displayedZoomRatio: Float,
    onZoomTargetClick: (Float) -> Unit,
    onSelectedPresetClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .wrapContentWidth()
            .height(44.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        targets.forEach { target ->
            val selected = target.isSelected(displayedZoomRatio)
            ZoomPresetButton(
                label = if (abs(target.zoomRatio - 1f) < 0.01f) "1x" else target.label,
                selected = selected,
                onClick = {
                    if (selected) onSelectedPresetClick() else onZoomTargetClick(target.zoomRatio)
                }
            )
        }
    }
}

@Composable
private fun ZoomPresetButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(shape)
            .background(if (selected) Color(0xC4322921) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent,
                shape = shape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) ZoomAccent else WheelText,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 17.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        )
    }
}

@Composable
private fun ReferenceZoomWheel(
    state: CameraZoomUiState,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(286.dp)
    ) {
        val centerX = size.width / 2f
        val radius = size.width * 0.51f
        // This Canvas is bottom-aligned inside the 3:4 preview, so its lower edge is the
        // wheel's geometry anchor. No screen-level offset is used here.
        val centerY = size.height + radius * 0.37f
        val wheelTop = centerY - radius
        val tickAngleDegrees = 2f
        val ticksPerStop = 16f
        val zoomStep = ln(2f) / ticksPerStop
        val currentTick = ln(state.displayedZoomRatio.coerceAtLeast(0.01f)) / zoomStep
        val minTick = ln(state.zoomRange.start.coerceAtLeast(0.01f)) / zoomStep
        val maxTick = ln(state.zoomRange.endInclusive.coerceAtLeast(0.01f)) / zoomStep
        val rimRadius = radius * 0.965f
        val outerTickRadius = radius * 0.955f

        drawCircle(
            color = WheelSurface,
            radius = radius,
            center = Offset(centerX, centerY)
        )
        drawArc(
            color = WheelRimShadow,
            startAngle = 198f,
            sweepAngle = 144f,
            useCenter = false,
            topLeft = Offset(centerX - rimRadius, centerY - rimRadius),
            size = Size(rimRadius * 2f, rimRadius * 2f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = radius * 0.03f)
        )
        drawArc(
            color = WheelRim,
            startAngle = 198f,
            sweepAngle = 144f,
            useCenter = false,
            topLeft = Offset(centerX - rimRadius, centerY - rimRadius),
            size = Size(rimRadius * 2f, rimRadius * 2f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = radius * 0.0022f)
        )
        val firstTick = floor(minTick).toInt()
        val lastTick = ceil(maxTick).toInt()
        for (globalTick in firstTick..lastTick) {
            if (globalTick < minTick - 0.001f || globalTick > maxTick + 0.001f) continue

            val angleDegrees = -90f + (globalTick - currentTick) * tickAngleDegrees
            if (angleDegrees < -150f || angleDegrees > -30f) continue

            val tickInStop = ((globalTick % ticksPerStop.toInt()) + ticksPerStop.toInt()) % ticksPerStop.toInt()
            val isMajorTick = tickInStop == 0
            val isMediumTick = tickInStop % 4 == 0
            val angle = angleDegrees.toRadians()
            val tickLength = when {
                isMajorTick -> radius * 0.060f
                isMediumTick -> radius * 0.037f
                else -> radius * 0.019f
            }
            val edgeFade = 1f - (abs(angleDegrees + 90f) / 60f).coerceIn(0f, 1f) * 0.34f
            val tickColor = when {
                isMajorTick -> WheelMajorTick
                isMediumTick -> WheelMediumTick
                else -> WheelFineTick
            }.copy(alpha = when {
                isMajorTick -> 0.94f * edgeFade
                isMediumTick -> 0.68f * edgeFade
                else -> 0.40f * edgeFade
            })
            drawLine(
                color = tickColor,
                start = Offset(
                    centerX + cos(angle) * (outerTickRadius - tickLength),
                    centerY + sin(angle) * (outerTickRadius - tickLength)
                ),
                end = Offset(
                    centerX + cos(angle) * outerTickRadius,
                    centerY + sin(angle) * outerTickRadius
                ),
                strokeWidth = when {
                    isMajorTick -> radius * 0.0045f
                    isMediumTick -> radius * 0.0028f
                    else -> radius * 0.0017f
                }
            )
        }

        state.wheelTargets().forEach { target ->
            val targetTick = ln(target.zoomRatio.coerceAtLeast(0.01f)) / zoomStep
            val angleDegrees = -90f + (targetTick - currentTick) * tickAngleDegrees
            if (angleDegrees < -143f || angleDegrees > -37f) return@forEach

            val angle = angleDegrees.toRadians()
            val nearPointer = abs(targetTick - currentTick) < 0.45f
            if (!nearPointer) {
                drawLine(
                    color = WheelText,
                    start = Offset(
                        centerX + cos(angle) * (outerTickRadius - radius * 0.065f),
                        centerY + sin(angle) * (outerTickRadius - radius * 0.065f)
                    ),
                    end = Offset(
                        centerX + cos(angle) * outerTickRadius,
                        centerY + sin(angle) * outerTickRadius
                    ),
                    strokeWidth = radius * 0.005f
                )

                val labelRadius = radius * 0.70f
                val labelCenter = Offset(
                    x = centerX + cos(angle) * labelRadius,
                    y = centerY + sin(angle) * labelRadius
                )
                drawWheelLabel(
                    label = target.label,
                    subtitle = target.focalLengthLabel,
                    center = labelCenter,
                    rotationDegrees = angleDegrees + 90f,
                    labelTextSize = radius * 0.055f,
                    subtitleTextSize = radius * 0.035f
                )
            }
        }

        drawLine(
            color = ZoomAccent,
            start = Offset(centerX, wheelTop + radius * 0.025f),
            end = Offset(centerX, wheelTop + radius * 0.062f),
            strokeWidth = radius * 0.006f
        )
        drawCenteredText(
            text = state.displayedZoomText,
            center = Offset(centerX, wheelTop + radius * 0.20f),
            color = ZoomAccent,
            textSize = radius * 0.072f,
            bold = true
        )
    }
}

private fun DrawScope.drawWheelLabel(
    label: String,
    subtitle: String?,
    center: Offset,
    rotationDegrees: Float,
    labelTextSize: Float,
    subtitleTextSize: Float
) {
    val canvas = drawContext.canvas.nativeCanvas
    canvas.save()
    canvas.rotate(rotationDegrees, center.x, center.y)
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WheelText.toArgb()
        textSize = labelTextSize
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    canvas.drawText(label, center.x, center.y, labelPaint)
    subtitle?.let {
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WheelSecondaryText.toArgb()
            textSize = subtitleTextSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        canvas.drawText(it, center.x, center.y + subtitleTextSize * 1.45f, subtitlePaint)
    }
    canvas.restore()
}

private fun DrawScope.drawCenteredText(
    text: String,
    center: Offset,
    color: Color,
    textSize: Float,
    bold: Boolean
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        this.textSize = textSize
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    drawContext.canvas.nativeCanvas.drawText(text, center.x, center.y, paint)
}

private fun Float.toRadians(): Float = (this / 180f * PI).toFloat()

private data class ZoomQuickTarget(
    val label: String,
    val zoomRatio: Float,
    val focalLengthLabel: String? = null
) {
    fun isSelected(currentZoomRatio: Float): Boolean {
        val tolerance = maxOf(zoomRatio * 0.08f, 0.05f)
        return abs(currentZoomRatio - zoomRatio) <= tolerance
    }
}

private fun CameraZoomUiState.compactTargets(): List<ZoomQuickTarget> {
    return wheelTargets().filter { it.zoomRatio <= 3f }
}

private fun CameraZoomUiState.wheelTargets(): List<ZoomQuickTarget> {
    val focalLengths = mapOf(
        0.6f to "15 mm",
        1f to "23 mm",
        3f to "73 mm"
    )
    return listOf(0.6f, 1f, 2f, 3f, 6f, CameraZoomController.MAX_EFFECTIVE_ZOOM_RATIO)
        .distinct()
        .filter { it >= zoomRange.start - 0.01f && it <= zoomRange.endInclusive + 0.01f }
        .map {
            ZoomQuickTarget(
                label = formatQuickTarget(it, includeOneX = false),
                zoomRatio = it,
                focalLengthLabel = focalLengths[it]
            )
        }
}

private fun formatQuickTarget(zoomRatio: Float, includeOneX: Boolean): String {
    return when {
        abs(zoomRatio - 1f) < 0.01f && includeOneX -> "1x"
        abs(zoomRatio - zoomRatio.toInt()) < 0.01f -> zoomRatio.toInt().toString()
        else -> String.format("%.1f", zoomRatio)
    }
}

@Composable
fun CameraFacingSwitchButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color(0xFF171717))
            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Cameraswitch,
            contentDescription = "切换前后摄像头",
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(26.dp)
        )
    }
}
