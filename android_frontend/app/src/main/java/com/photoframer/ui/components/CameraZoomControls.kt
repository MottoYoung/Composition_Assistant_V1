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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import com.photoframer.ui.theme.AccentYellow
import com.photoframer.utils.CameraZoomController
import com.photoframer.utils.ZoomStop
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

private const val WHEEL_HEIGHT_DP = 244

private val WheelSurface = Color(0xD9161616)
private val WheelRim = Color.White.copy(alpha = 0.20f)
private val WheelFineTick = Color.White.copy(alpha = 0.28f)
private val WheelMajorTick = Color.White.copy(alpha = 0.78f)

data class CameraZoomUiState(
    val opticalStops: List<ZoomStop>,
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
    Box(
        modifier = modifier
            .zoomWheelDragInput(state, onWheelReveal, onWheelScrub, onWheelDragEnd)
            .fillMaxWidth()
            .height(if (state.isWheelVisible) WHEEL_HEIGHT_DP.dp else 62.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        if (state.isWheelVisible) {
            ApertureZoomWheel(state)
        } else {
            ZoomQuickButtons(
                stops = state.opticalStops,
                displayedZoomRatio = state.displayedZoomRatio,
                displayedZoomText = state.displayedZoomText,
                onZoomTargetClick = onZoomTargetClick,
                onWheelReveal = onWheelReveal
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
private fun ZoomQuickButtons(
    stops: List<ZoomStop>,
    displayedZoomRatio: Float,
    displayedZoomText: String,
    onZoomTargetClick: (Float) -> Unit,
    onWheelReveal: () -> Unit
) {
    if (stops.isEmpty()) {
        ZoomQuickButton(
            label = displayedZoomText,
            selected = true,
            onClick = onWheelReveal
        )
        return
    }

    Row(
        modifier = Modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        stops.forEach { stop ->
            val selected = stop.isSelected(displayedZoomRatio)
            ZoomQuickButton(
                label = CameraZoomController.formatZoomRatio(stop.zoomRatio),
                selected = selected,
                onClick = {
                    if (selected) onWheelReveal() else onZoomTargetClick(stop.zoomRatio)
                }
            )
        }
    }
}

@Composable
private fun ZoomQuickButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (selected) Color(0xD92D2923) else Color(0x8A121212))
            .border(1.dp, if (selected) AccentYellow.copy(alpha = 0.35f) else WheelRim, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) AccentYellow else Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        )
    }
}

@Composable
private fun ApertureZoomWheel(state: CameraZoomUiState) {
    Canvas(modifier = Modifier.fillMaxWidth().height(WHEEL_HEIGHT_DP.dp)) {
        val centerX = size.width / 2f
        val radius = size.width * 0.54f
        val centerY = size.height + radius * 0.43f
        val wheelTop = centerY - radius
        val outerTickRadius = radius * 0.95f

        drawCircle(WheelSurface, radius, Offset(centerX, centerY))
        drawArc(
            color = WheelRim,
            startAngle = 198f,
            sweepAngle = 144f,
            useCenter = false,
            topLeft = Offset(centerX - radius, centerY - radius),
            size = Size(radius * 2f, radius * 2f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = radius * 0.004f)
        )

        repeat(31) { index ->
            val angleDegrees = -150f + index * 4f
            val major = index % 4 == 0
            val angle = angleDegrees.toRadians()
            val length = if (major) radius * 0.055f else radius * 0.022f
            drawLine(
                color = if (major) WheelMajorTick else WheelFineTick,
                start = Offset(
                    centerX + cos(angle) * (outerTickRadius - length),
                    centerY + sin(angle) * (outerTickRadius - length)
                ),
                end = Offset(
                    centerX + cos(angle) * outerTickRadius,
                    centerY + sin(angle) * outerTickRadius
                ),
                strokeWidth = if (major) radius * 0.004f else radius * 0.002f
            )
        }

        state.opticalStops.forEach { stop ->
            val angleDegrees = angleForStop(stop.zoomRatio, state.displayedZoomRatio)
            if (angleDegrees !in -143f..-37f || abs(angleDegrees + 90f) < 5f) return@forEach
            val angle = angleDegrees.toRadians()
            drawLine(
                color = Color.White,
                start = Offset(
                    centerX + cos(angle) * (outerTickRadius - radius * 0.08f),
                    centerY + sin(angle) * (outerTickRadius - radius * 0.08f)
                ),
                end = Offset(
                    centerX + cos(angle) * outerTickRadius,
                    centerY + sin(angle) * outerTickRadius
                ),
                strokeWidth = radius * 0.005f
            )
            drawWheelLabel(
                label = CameraZoomController.formatZoomRatio(stop.zoomRatio),
                center = Offset(
                    centerX + cos(angle) * radius * 0.70f,
                    centerY + sin(angle) * radius * 0.70f
                ),
                rotationDegrees = angleDegrees + 90f,
                textSize = radius * 0.055f
            )
        }

        drawLine(
            color = AccentYellow,
            start = Offset(centerX, wheelTop + radius * 0.025f),
            end = Offset(centerX, wheelTop + radius * 0.07f),
            strokeWidth = radius * 0.007f
        )
        drawWheelLabel(
            label = state.displayedZoomText,
            center = Offset(centerX, wheelTop + radius * 0.20f),
            rotationDegrees = 0f,
            textSize = radius * 0.078f,
            color = AccentYellow,
            bold = true
        )
    }
}

private fun angleForStop(stopZoomRatio: Float, currentZoomRatio: Float): Float {
    val ratio = stopZoomRatio / currentZoomRatio.coerceAtLeast(0.01f)
    return -90f + (ln(ratio) / ln(2f)).toFloat() * 36f
}

private fun ZoomStop.isSelected(currentZoomRatio: Float): Boolean {
    val tolerance = maxOf(zoomRatio * 0.08f, 0.05f)
    return abs(currentZoomRatio - zoomRatio) <= tolerance
}

private fun DrawScope.drawWheelLabel(
    label: String,
    center: Offset,
    rotationDegrees: Float,
    textSize: Float,
    color: Color = Color.White,
    bold: Boolean = false
) {
    val canvas = drawContext.canvas.nativeCanvas
    canvas.save()
    canvas.rotate(rotationDegrees, center.x, center.y)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        this.textSize = textSize
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    canvas.drawText(label, center.x, center.y, paint)
    canvas.restore()
}

private fun Float.toRadians(): Float = (this / 180f * PI).toFloat()

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
            imageVector = Icons.Default.Refresh,
            contentDescription = "切换前后摄像头",
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(26.dp)
        )
    }
}
