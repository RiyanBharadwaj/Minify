package com.shanks.minify.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToLong

private fun formatMs(ms: Long): String {
    val totalSecs = ms / 1000L
    val m    = totalSecs / 60
    val s    = totalSecs % 60
    val frac = (ms % 1000L) / 100L
    return "%d:%02d.%d".format(m, s, frac)
}

@Composable
fun TrimBar(
    durationMs: Long,
    startMs: Long,
    endMs: Long?,
    playheadMs: Long,
    onRangeChange: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
    handleWidth: Dp = 18.dp,
    barHeight: Dp = 56.dp,
    accentColor: Color = Color(0xFFBF5AF2),
    trackColor: Color = Color(0xFF1C1C1E),
    dimColor: Color = Color(0x88000000),
) {
    val density = LocalDensity.current
    val handleWidthPx = with(density) { handleWidth.toPx() }

    var draggingStart by remember { mutableStateOf(false) }
    var draggingEnd   by remember { mutableStateOf(false) }

    // rememberUpdatedState ensures drag lambdas always read the latest prop
    // values without restarting the gesture detector (which would cancel the
    // in-progress drag and snap the handle back to where it started).
    val latestStart    by rememberUpdatedState(startMs)
    val latestEnd      by rememberUpdatedState(endMs ?: durationMs)
    val latestDuration by rememberUpdatedState(durationMs)

    Column(modifier = modifier) {
        // Timestamp labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = handleWidth / 2)
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatMs(startMs),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = accentColor
            )
            Text(
                text = formatMs(endMs ?: durationMs),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = accentColor
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    // Key is Unit — never restarts. Latest values come via
                    // rememberUpdatedState so the lambda is always up-to-date.
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                val totalW  = size.width.toFloat()
                                val usableW = totalW - handleWidthPx * 2
                                val startX  = handleWidthPx + (latestStart.toFloat() / latestDuration) * usableW
                                val endX    = handleWidthPx + (latestEnd.toFloat()   / latestDuration) * usableW
                                draggingStart = kotlin.math.abs(offset.x - startX) < handleWidthPx * 2.5f
                                draggingEnd   = !draggingStart && kotlin.math.abs(offset.x - endX) < handleWidthPx * 2.5f
                            },
                            onDragEnd    = { draggingStart = false; draggingEnd = false },
                            onDragCancel = { draggingStart = false; draggingEnd = false },
                            onHorizontalDrag = { _, dragAmount ->
                                val usableW = size.width.toFloat() - handleWidthPx * 2
                                val msPx    = latestDuration.toFloat() / usableW
                                val delta   = (dragAmount * msPx).roundToLong()
                                when {
                                    draggingStart -> {
                                        val newStart = (latestStart + delta).coerceIn(0L, latestEnd - 500L)
                                        onRangeChange(newStart, latestEnd)
                                    }
                                    draggingEnd -> {
                                        val newEnd = (latestEnd + delta).coerceIn(latestStart + 500L, latestDuration)
                                        onRangeChange(latestStart, newEnd)
                                    }
                                }
                            }
                        )
                    }
            ) {
                val w        = size.width
                val h        = size.height
                val usableW  = w - handleWidthPx * 2
                val curEnd   = endMs ?: durationMs
                val startFrac = startMs.toFloat() / durationMs
                val endFrac   = curEnd.toFloat()  / durationMs
                val startX    = handleWidthPx + startFrac * usableW
                val endX      = handleWidthPx + endFrac   * usableW
                val borderPx  = 3f

                // Track
                drawRoundRect(color = trackColor, topLeft = Offset(0f,0f), size = Size(w,h), cornerRadius = CornerRadius(8f))

                // Dim outside selection
                if (startX > handleWidthPx)    drawRect(color = dimColor, topLeft = Offset(0f,0f),    size = Size(startX, h))
                if (endX < w - handleWidthPx)  drawRect(color = dimColor, topLeft = Offset(endX, 0f), size = Size(w - endX, h))

                // Selection border top + bottom
                drawRect(color = accentColor, topLeft = Offset(startX, 0f),            size = Size(endX - startX, borderPx))
                drawRect(color = accentColor, topLeft = Offset(startX, h - borderPx),  size = Size(endX - startX, borderPx))

                // Left handle
                drawRoundRect(color = accentColor, topLeft = Offset(startX - handleWidthPx, 0f), size = Size(handleWidthPx, h), cornerRadius = CornerRadius(6f))
                val grip = Color(0xFF0D0B14)
                for (i in -1..1) {
                    drawLine(grip, Offset(startX - handleWidthPx/2 + i*3.5f, h*0.35f), Offset(startX - handleWidthPx/2 + i*3.5f, h*0.65f), 1.5f, StrokeCap.Round)
                }

                // Right handle
                drawRoundRect(color = accentColor, topLeft = Offset(endX, 0f), size = Size(handleWidthPx, h), cornerRadius = CornerRadius(6f))
                for (i in -1..1) {
                    drawLine(grip, Offset(endX + handleWidthPx/2 + i*3.5f, h*0.35f), Offset(endX + handleWidthPx/2 + i*3.5f, h*0.65f), 1.5f, StrokeCap.Round)
                }

                // Playhead
                val playFrac = playheadMs.toFloat() / durationMs.coerceAtLeast(1L)
                val playX    = handleWidthPx + playFrac * usableW
                drawLine(Color.White, Offset(playX, 4f), Offset(playX, h - 4f), 2f, StrokeCap.Round)
                drawCircle(Color.White, radius = 5f, center = Offset(playX, h / 2f))
            }
        }

        // Duration label
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                text  = "Selected: ${formatMs((endMs ?: durationMs) - startMs)}  /  Total: ${formatMs(durationMs)}",
                fontSize = 10.sp,
                color = Color(0xFF8E8E93)
            )
        }
    }
}