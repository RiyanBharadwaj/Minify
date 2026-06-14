package com.shanks.minify.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

private const val HANDLE_TOUCH_RADIUS_FRAC = 0.14f
private const val MIN_CROP_FRAC = 0.08f

enum class DragHandle {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    TOP, BOTTOM, LEFT, RIGHT, BODY, NONE
}

private fun clampCrop(l: Float, t: Float, r: Float, b: Float): CropRect {
    var cl = l.coerceIn(0f, 1f)
    var ct = t.coerceIn(0f, 1f)
    var cr = r.coerceIn(0f, 1f)
    var cb = b.coerceIn(0f, 1f)

    if (cr - cl < MIN_CROP_FRAC) {
        cr = (cl + MIN_CROP_FRAC).coerceAtMost(1f)
        cl = (cr - MIN_CROP_FRAC).coerceAtLeast(0f)
    }

    if (cb - ct < MIN_CROP_FRAC) {
        cb = (ct + MIN_CROP_FRAC).coerceAtMost(1f)
        ct = (cb - MIN_CROP_FRAC).coerceAtLeast(0f)
    }

    return CropRect(cl, ct, cr, cb)
}

@Composable
fun CropOverlay(
    crop: CropRect,
    lockedAspect: Float?,
    videoAspect: Float,
    onCropChange: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
    overlayColor: Color = Color(0x88000000),
    borderColor: Color = Color.White,
    handleColor: Color = Color.White,
    gridColor: Color = Color(0x55FFFFFF),
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var activeDrag by remember { mutableStateOf(DragHandle.NONE) }

    val latestCrop         by rememberUpdatedState(crop)
    val latestLockedAspect by rememberUpdatedState(lockedAspect)
    val latestCanvasSize   by rememberUpdatedState(canvasSize)

    Box(modifier = modifier.onSizeChanged { canvasSize = it }) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val cs = latestCanvasSize
                            if (cs == IntSize.Zero) return@detectDragGestures
                            val cw     = cs.width.toFloat()
                            val ch     = cs.height.toFloat()
                            val l      = latestCrop.left   * cw
                            val t      = latestCrop.top    * ch
                            val r      = latestCrop.right  * cw
                            val b      = latestCrop.bottom * ch
                            val touchR = HANDLE_TOUCH_RADIUS_FRAC * minOf(cw, ch)

                            activeDrag = when {
                                offset.distanceTo(Offset(l, t)) < touchR                    -> DragHandle.TOP_LEFT
                                offset.distanceTo(Offset(r, t)) < touchR                    -> DragHandle.TOP_RIGHT
                                offset.distanceTo(Offset(l, b)) < touchR                    -> DragHandle.BOTTOM_LEFT
                                offset.distanceTo(Offset(r, b)) < touchR                    -> DragHandle.BOTTOM_RIGHT
                                abs(offset.x - l) < touchR && offset.y in t..b             -> DragHandle.LEFT
                                abs(offset.x - r) < touchR && offset.y in t..b             -> DragHandle.RIGHT
                                abs(offset.y - t) < touchR && offset.x in l..r             -> DragHandle.TOP
                                abs(offset.y - b) < touchR && offset.x in l..r             -> DragHandle.BOTTOM
                                offset.x in l..r && offset.y in t..b                       -> DragHandle.BODY
                                else                                                         -> DragHandle.NONE
                            }
                        },
                        onDragEnd    = { activeDrag = DragHandle.NONE },
                        onDragCancel = { activeDrag = DragHandle.NONE },
                        onDrag = { _, dragAmount ->
                            val cs = latestCanvasSize
                            if (activeDrag == DragHandle.NONE || cs == IntSize.Zero) return@detectDragGestures
                            val cw = cs.width.toFloat()
                            val ch = cs.height.toFloat()
                            val dx = dragAmount.x / cw
                            val dy = dragAmount.y / ch

                            var l = latestCrop.left
                            var t = latestCrop.top
                            var r = latestCrop.right
                            var b = latestCrop.bottom

                            when (activeDrag) {
                                DragHandle.TOP_LEFT     -> { l += dx; t += dy }
                                DragHandle.TOP_RIGHT    -> { r += dx; t += dy }
                                DragHandle.BOTTOM_LEFT  -> { l += dx; b += dy }
                                DragHandle.BOTTOM_RIGHT -> { r += dx; b += dy }
                                DragHandle.LEFT         -> { l += dx }
                                DragHandle.RIGHT        -> { r += dx }
                                DragHandle.TOP          -> { t += dy }
                                DragHandle.BOTTOM       -> { b += dy }
                                DragHandle.BODY -> {
                                    val w = r - l; val h = b - t
                                    l = (l + dx).coerceIn(0f, 1f - w)
                                    t = (t + dy).coerceIn(0f, 1f - h)
                                    r = l + w; b = t + h
                                }
                                DragHandle.NONE -> {}
                            }

                            val clamped = clampCrop(l, t, r, b)
                            var cl = clamped.left
                            var ct = clamped.top
                            var cr = clamped.right
                            var cb = clamped.bottom

                            val locked = latestLockedAspect
                            if (locked != null && activeDrag != DragHandle.BODY) {
                                val newW = cr - cl
                                val newH = cb - ct
                                val canvasAr = cw / ch
                                val targetNormAr = locked / canvasAr
                                when (activeDrag) {
                                    DragHandle.TOP_LEFT, DragHandle.BOTTOM_LEFT, DragHandle.LEFT -> {
                                        val h2 = (newW / targetNormAr).coerceAtLeast(MIN_CROP_FRAC)
                                        when (activeDrag) {
                                            DragHandle.TOP_LEFT -> ct = (cb - h2).coerceAtLeast(0f)
                                            else                -> cb = (ct + h2).coerceAtMost(1f)
                                        }
                                    }
                                    else -> {
                                        val w2 = (newH * targetNormAr).coerceAtLeast(MIN_CROP_FRAC)
                                        when (activeDrag) {
                                            DragHandle.TOP_RIGHT, DragHandle.RIGHT -> cr = (cl + w2).coerceAtMost(1f)
                                            else                                   -> cl = (cr - w2).coerceAtLeast(0f)
                                        }
                                    }
                                }
                                val final = clampCrop(cl, ct, cr, cb)
                                cl = final.left; ct = final.top; cr = final.right; cb = final.bottom
                            }

                            onCropChange(CropRect(cl, ct, cr, cb))
                        }
                    )
                }
        ) {
            val cw   = size.width
            val ch   = size.height
            val l    = crop.left   * cw
            val t    = crop.top    * ch
            val r    = crop.right  * cw
            val b    = crop.bottom * ch
            val selW = r - l
            val selH = b - t

            drawRect(color = overlayColor, topLeft = Offset(0f, 0f), size = Size(cw, t))
            drawRect(color = overlayColor, topLeft = Offset(0f, b),  size = Size(cw, ch - b))
            drawRect(color = overlayColor, topLeft = Offset(0f, t),  size = Size(l, selH))
            drawRect(color = overlayColor, topLeft = Offset(r, t),   size = Size(cw - r, selH))

            for (i in 1..2) {
                drawLine(gridColor, Offset(l + selW * i / 3f, t), Offset(l + selW * i / 3f, b), 0.5f)
                drawLine(gridColor, Offset(l, t + selH * i / 3f), Offset(r, t + selH * i / 3f), 0.5f)
            }

            drawRect(color = borderColor, topLeft = Offset(l, t), size = Size(selW, selH), style = Stroke(width = 2f))

            val cHalf = 24f
            val cFull = cHalf * 2f
            drawRect(handleColor, topLeft = Offset(l - cHalf, t - cHalf), size = Size(cFull, cFull))
            drawRect(handleColor, topLeft = Offset(r - cHalf, t - cHalf), size = Size(cFull, cFull))
            drawRect(handleColor, topLeft = Offset(l - cHalf, b - cHalf), size = Size(cFull, cFull))
            drawRect(handleColor, topLeft = Offset(r - cHalf, b - cHalf), size = Size(cFull, cFull))
            val cutBg = Color(0x99000000)
            val cut   = cHalf * 0.55f
            drawRect(cutBg, topLeft = Offset(l - cHalf + cut, t - cHalf + cut), size = Size(cFull - cut, cFull - cut))
            drawRect(cutBg, topLeft = Offset(r - cFull + cut, t - cHalf + cut), size = Size(cFull - cut, cFull - cut))
            drawRect(cutBg, topLeft = Offset(l - cHalf + cut, b - cFull + cut), size = Size(cFull - cut, cFull - cut))
            drawRect(cutBg, topLeft = Offset(r - cFull + cut, b - cFull + cut), size = Size(cFull - cut, cFull - cut))

            val mLong  = 72f
            val mShort = 18f
            drawRect(handleColor, topLeft = Offset(l + selW/2f - mLong/2f, t - mShort/2f), size = Size(mLong, mShort))
            drawRect(handleColor, topLeft = Offset(l + selW/2f - mLong/2f, b - mShort/2f), size = Size(mLong, mShort))
            drawRect(handleColor, topLeft = Offset(l - mShort/2f, t + selH/2f - mLong/2f), size = Size(mShort, mLong))
            drawRect(handleColor, topLeft = Offset(r - mShort/2f, t + selH/2f - mLong/2f), size = Size(mShort, mLong))
        }
    }
}

private fun Offset.distanceTo(other: Offset): Float {
    val dx = x - other.x; val dy = y - other.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

@Composable
fun AspectRatioChips(
    selected: AspectRatioPreset,
    onSelect: (AspectRatioPreset) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AspectRatioPreset.entries.forEach { preset ->
            val isSelected = preset == selected
            FilterChip(
                selected = isSelected,
                onClick  = { onSelect(preset) },
                label    = { Text(preset.label, fontSize = 12.sp) },
                shape    = RoundedCornerShape(8.dp),
                colors   = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accentColor,
                    selectedLabelColor     = Color(0xFFFFFFFF),
                    containerColor         = Color(0xFF251E35),
                    labelColor             = Color(0xFFAEAEB2),
                )
            )
        }
    }
}