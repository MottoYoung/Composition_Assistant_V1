package com.photoframer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.photoframer.ui.components.AspectRatioOption
import com.photoframer.ui.theme.BackgroundDark
import com.photoframer.ui.theme.ErrorRed
import com.photoframer.ui.theme.PurplePrimary
import com.photoframer.ui.theme.SurfaceDark
import com.photoframer.ui.theme.TextSecondary

@Composable
internal fun ErrorOverlay(
    title: String,
    message: String,
    actionText: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = ErrorRed
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = PurplePrimary)
            ) {
                Text(actionText)
            }
        }
    }
}
@Composable
internal fun StepSkipOverlay(
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .padding(horizontal = 24.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceDark.copy(alpha = 0.92f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "这一步有点卡住了",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "可以先跳过，后面继续拍摄",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(
                onClick = onSkip,
                colors = ButtonDefaults.buttonColors(containerColor = PurplePrimary),
                shape = RoundedCornerShape(999.dp)
            ) {
                Text("跳过此步")
            }
        }
    }
}

@Composable
internal fun PostCaptureChoiceOverlay(
    message: String,
    onViewCandidates: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceDark.copy(alpha = 0.94f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "当前方案已拍完",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = message,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.08f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("留在当前方案", color = Color.White)
                }
                Button(
                    onClick = onViewCandidates,
                    colors = ButtonDefaults.buttonColors(containerColor = PurplePrimary),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("查看其他方案", color = Color.White)
                }
            }
        }
    }
}

@Composable
internal fun NewCandidateOverlay(
    message: String,
    onViewCandidates: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceDark.copy(alpha = 0.90f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f, fill = false)
            )
            TextButton(onClick = onDismiss) {
                Text("稍后", color = TextSecondary)
            }
            Button(
                onClick = onViewCandidates,
                colors = ButtonDefaults.buttonColors(containerColor = PurplePrimary),
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("查看", color = Color.White)
            }
        }
    }
}

@Composable
internal fun AspectRatioMaskOverlay(
    selectedRatio: AspectRatioOption,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val targetRatio = when (selectedRatio) {
            AspectRatioOption.RATIO_4_3 -> 4f / 3f
            AspectRatioOption.RATIO_1_1 -> 1f
            AspectRatioOption.FULL -> maxWidth.value / maxHeight.value
        }
        val containerRatio = maxWidth.value / maxHeight.value
        val maskColor = Color.Black.copy(alpha = 0.36f)
        val borderColor = Color.White.copy(alpha = 0.45f)

        if (containerRatio > targetRatio) {
            val visibleWidth: Dp = maxHeight * targetRatio
            val sideMask = (maxWidth - visibleWidth) / 2f

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(sideMask)
                    .background(maskColor)
                    .align(Alignment.CenterStart)
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(sideMask)
                    .background(maskColor)
                    .align(Alignment.CenterEnd)
            )
            Box(
                modifier = Modifier
                    .width(visibleWidth)
                    .fillMaxHeight()
                    .border(1.dp, borderColor)
                    .align(Alignment.Center)
            )
        } else {
            val visibleHeight: Dp = maxWidth / targetRatio
            val topMask = (maxHeight - visibleHeight) / 2f

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topMask)
                    .background(maskColor)
                    .align(Alignment.TopCenter)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topMask)
                    .background(maskColor)
                    .align(Alignment.BottomCenter)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(visibleHeight)
                    .border(1.dp, borderColor)
                    .align(Alignment.Center)
            )
        }
    }
}
