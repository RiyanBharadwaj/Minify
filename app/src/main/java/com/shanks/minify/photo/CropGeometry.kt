package com.shanks.minify.photo

import com.shanks.minify.ui.CropRect

/**
 * The concrete integer pixel rectangle produced by mapping a normalized
 * [CropRect] onto a full-resolution bitmap. Always contained within the source
 * bounds `[0,width] x [0,height]`.
 */
data class PixelRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/**
 * Pure crop math shared by the Photo Editor. Holds no Android/bitmap
 * dependencies so it is fully JVM-testable.
 */
object CropGeometry {

    /**
     * Intersect a normalized [crop] with the unit image bounds `[0,1] x [0,1]`.
     *
     * The returned rectangle never exceeds the unit square and always satisfies
     * `left < right` and `top < bottom`. When the input is degenerate or falls
     * entirely outside the bounds, it collapses to [CropRect.FULL] so a valid
     * (non-empty) crop is always produced (Req 4.5).
     */
    fun clampToBounds(crop: CropRect): CropRect {
        val left = crop.left.coerceIn(0f, 1f)
        val top = crop.top.coerceIn(0f, 1f)
        val right = crop.right.coerceIn(0f, 1f)
        val bottom = crop.bottom.coerceIn(0f, 1f)

        // Guard against inverted or zero-area rectangles: fall back to full.
        if (right <= left || bottom <= top) return CropRect.FULL

        return CropRect(left, top, right, bottom)
    }

    /**
     * Map a normalized [crop] to an integer [PixelRect] contained in
     * `[0,width] x [0,height]`.
     *
     * The crop is first clamped to the unit bounds. The rectangle's `width`
     * equals `round(clampedCrop.width * width)` and its `height` equals
     * `round(clampedCrop.height * height)`; the origin is rounded and then
     * shifted so the rectangle stays fully within the source bounds (Req 4.4,
     * 4.5, 9.2).
     */
    fun toPixelRect(crop: CropRect, width: Int, height: Int): PixelRect {
        val clamped = clampToBounds(crop)

        // Dimensions follow directly from the clamped normalized extents.
        val pxWidth = Math.round(clamped.width * width).coerceIn(0, width)
        val pxHeight = Math.round(clamped.height * height).coerceIn(0, height)

        // Origin rounded, then nudged so left+width <= width and top+height <= height.
        val pxLeft = Math.round(clamped.left * width).coerceIn(0, width - pxWidth)
        val pxTop = Math.round(clamped.top * height).coerceIn(0, height - pxHeight)

        return PixelRect(
            left = pxLeft,
            top = pxTop,
            width = pxWidth,
            height = pxHeight,
        )
    }
}
