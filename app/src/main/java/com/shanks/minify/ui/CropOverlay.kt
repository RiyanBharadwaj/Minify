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
import com.shanks.minify.ui.editor.model.CropDrag
import kotlin.math.abs

private const val HANDLE_TOUCH_RADIUS_FRAC = 0.14f

enum class DragHandle {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    TOP, BOTTOM, LEFT, RIGHT, BODY, NONE
}

/**
 * The letterboxed content rectangle (in canvas pixels) that the actual media
 * occupies inside a [cw] x [ch] canvas for a media aspect ratio derived from
 * [iw] x [ih]. For small images (smaller than the canvas), the rule is
 * "Inside" (native size, centered, no upscale). For large images it scales
 * down to fit (FIT_CENTER).
 */
private fun contentRect(cw: Float, ch: Float, iw: Int, ih: Int): androidx.compose.ui.geometry.Rect {
    if (iw <= 0 || ih <= 0 || cw <= 0f || ch <= 0f) {
        return androidx.compose.ui.geometry.Rect(0f, 0f, cw, ch)
    }
    val scale = minOf(1f, cw / iw.toFloat(), ch / ih.toFloat())
    val fittedW = iw * scale
    val fittedH = ih * scale
    val ox = (cw - fittedW) / 2f
    val oy = (ch - fittedH) / 2f
    return androidx.compose.ui.geometry.Rect(ox, oy, ox + fittedW, oy + fittedH)
}

@Composable
fun CropOverlay(
    crop: CropRect,
    lockedAspect: Float?,
    videoAspect: Float,
    imageWidth: Int,
    imageHeight: Int,
    onCropChange: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
    mirrored: Boolean = false,
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
    val latestWidth        by rememberUpdatedState(imageWidth)
    val latestHeight       by rememberUpdatedState(imageHeight)
    val latestMirrored     by rememberUpdatedState(mirrored)

    Box(modifier = modifier.onSizeChanged { canvasSize = it }) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val cs = latestCanvasSize
                            if (cs == IntSize.Zero) return@detectDragGestures
                            // Crop coordinates are relative to the media content rect
                            // (letterbox-corrected), not the full canvas.
                            val content = contentRect(cs.width.toFloat(), cs.height.toFloat(), latestWidth, latestHeight)
                            val fw     = content.width
                            val fh     = content.height
                            val l      = content.left + latestCrop.left   * fw
                            val t      = content.top  + latestCrop.top    * fh
                            val r      = content.left + latestCrop.right  * fw
                            val b      = content.top  + latestCrop.bottom * fh
                            val touchR = HANDLE_TOUCH_RADIUS_FRAC * minOf(fw, fh)

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
                            val content = contentRect(cs.width.toFloat(), cs.height.toFloat(), latestWidth, latestHeight)
                            // Normalize the drag against the media content rect so a drag maps
                            // to the same fraction of the media regardless of letterboxing.
                            // These deltas are in display space, matching CropDrag's contract.
                            val cw = content.width.coerceAtLeast(1f)
                            val ch = content.height.coerceAtLeast(1f)
                            val dNormX = dragAmount.x / cw
                            val dNormY = dragAmount.y / ch

                            // The locked aspect is a media-space ratio; convert it to the
                            // normalized crop space CropDrag operates in (divide by the
                            // content rect's own aspect).
                            val locked = latestLockedAspect
                            val lockedNormAspect = if (locked != null) locked / (cw / ch) else null

                            onCropChange(
                                CropDrag.resolve(
                                    crop = latestCrop,
                                    handle = activeDrag,
                                    dNormX = dNormX,
                                    dNormY = dNormY,
                                    mirrored = latestMirrored,
                                    lockedNormAspect = lockedNormAspect,
                                )
                            )
                        }
                    )
                }
        ) {
            val cw   = size.width
            val ch   = size.height
            // Position the crop within the letterbox-corrected media content rect
            // so the drawn selection lines up with the visible media.
            val content = contentRect(cw, ch, imageWidth, imageHeight)
            val fw   = content.width
            val fh   = content.height
            val l    = content.left + crop.left   * fw
            val t    = content.top  + crop.top    * fh
            val r    = content.left + crop.right  * fw
            val b    = content.top  + crop.bottom * fh
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