package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.CropRect
import com.shanks.minify.ui.DragHandle

/**
 * Pure, Android-independent resolution of a crop-handle drag into a new [CropRect].
 *
 * The `CropOverlay` UI keeps only hit-testing and drawing; the geometry of "what does
 * this drag do to the crop rectangle" lives here so it can be verified with property
 * tests and shared without a Compose/Android dependency.
 *
 * ## Coordinate model
 *
 * - [crop] is expressed in **model (source) space**, with all edges normalized to `[0, 1]`.
 * - The image may be displayed **horizontally mirrored**. When it is, model X maps to
 *   display X via `displayX = 1 - modelX`, so the visual-left handle actually corresponds
 *   to the crop's model-right edge (and vice-versa).
 * - [handle] is the **visual** handle the user grabbed and [dNormX]/[dNormY] is the drag
 *   delta in **display space** (normalized to the media content rectangle).
 *
 * Resolution therefore:
 * 1. Swaps LEFT&#8596;RIGHT (and the X-component of corner handles) when [mirrored], so the
 *    grabbed visual edge moves the correct model edge (Req 14.1).
 * 2. Inverts the horizontal drag delta when [mirrored] (display X is the negation of model X).
 * 3. Applies the delta to the affected model edge(s), then clamps to `[0, 1]` while
 *    keeping at least [MIN_CROP_FRAC] on each axis.
 * 4. When [lockedNormAspect] is set, resizes **symmetrically about the crop's center** so a
 *    left-side and a right-side handle behave identically (Req 14.2).
 *
 * The function is total: it never throws for any input and returns [crop] unchanged for
 * [DragHandle.NONE].
 *
 * Validates Requirements 14.1 and 14.2.
 */
object CropDrag {

    /** Minimum normalized crop extent on each axis, matching the `CropOverlay` UI constant. */
    const val MIN_CROP_FRAC = 0.05f

    /**
     * Resolve a crop-handle drag into a new [CropRect].
     *
     * @param crop the current crop rectangle in model space (edges in `[0, 1]`).
     * @param handle the visual handle the user grabbed.
     * @param dNormX the horizontal drag delta in display space, normalized to the content rect.
     * @param dNormY the vertical drag delta in display space, normalized to the content rect.
     * @param mirrored whether the image is displayed horizontally mirrored.
     * @param lockedNormAspect the locked crop aspect ratio in **normalized space**
     *   (`width / height`), or `null` for a free resize.
     * @return the resolved crop rectangle, clamped to `[0, 1]` with a minimum extent.
     */
    fun resolve(
        crop: CropRect,
        handle: DragHandle,
        dNormX: Float,
        dNormY: Float,
        mirrored: Boolean,
        lockedNormAspect: Float?,
    ): CropRect {
        if (handle == DragHandle.NONE) return crop

        // Map the grabbed *visual* handle + *display-space* delta into model space.
        val modelHandle = if (mirrored) handle.mirroredX() else handle
        val mdx = if (mirrored) -dNormX else dNormX
        val mdy = dNormY

        // BODY: pure translation, keeping size constant and staying in bounds.
        if (modelHandle == DragHandle.BODY) {
            val w = crop.width
            val h = crop.height
            val l = (crop.left + mdx).coerceIn(0f, 1f - w)
            val t = (crop.top + mdy).coerceIn(0f, 1f - h)
            return CropRect(l, t, l + w, t + h)
        }

        var l = crop.left
        var t = crop.top
        var r = crop.right
        var b = crop.bottom

        when (modelHandle) {
            DragHandle.TOP_LEFT     -> { l += mdx; t += mdy }
            DragHandle.TOP_RIGHT    -> { r += mdx; t += mdy }
            DragHandle.BOTTOM_LEFT  -> { l += mdx; b += mdy }
            DragHandle.BOTTOM_RIGHT -> { r += mdx; b += mdy }
            DragHandle.LEFT         -> { l += mdx }
            DragHandle.RIGHT        -> { r += mdx }
            DragHandle.TOP          -> { t += mdy }
            DragHandle.BOTTOM       -> { b += mdy }
            else -> {}
        }

        val clamped = clampCrop(l, t, r, b)

        return if (lockedNormAspect != null && lockedNormAspect > 0f) {
            applyLockedAspect(crop, modelHandle, clamped, lockedNormAspect)
        } else {
            clamped
        }
    }

    /**
     * Rebuild the crop centered on the *original* crop's center so that resizing from a
     * left-side handle and a right-side handle expands/contracts by the same amount
     * (Req 14.2). The extent along the handle's primary axis is derived from the dragged
     * edge relative to the center, and the other axis follows from [aspect].
     */
    private fun applyLockedAspect(
        original: CropRect,
        handle: DragHandle,
        dragged: CropRect,
        aspect: Float,
    ): CropRect {
        val cx = (original.left + original.right) / 2f
        val cy = (original.top + original.bottom) / 2f
        val minHalf = MIN_CROP_FRAC / 2f

        val halfW: Float
        val halfH: Float
        if (handle.isHorizontalDriven()) {
            halfW = when (handle) {
                DragHandle.RIGHT, DragHandle.TOP_RIGHT, DragHandle.BOTTOM_RIGHT -> dragged.right - cx
                else -> cx - dragged.left
            }.coerceAtLeast(minHalf)
            halfH = (halfW / aspect).coerceAtLeast(minHalf)
        } else {
            halfH = when (handle) {
                DragHandle.BOTTOM -> dragged.bottom - cy
                else -> cy - dragged.top
            }.coerceAtLeast(minHalf)
            halfW = (halfH * aspect).coerceAtLeast(minHalf)
        }

        return clampCrop(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    /** True for handles whose primary resize axis is horizontal (side + corner handles). */
    private fun DragHandle.isHorizontalDriven(): Boolean = when (this) {
        DragHandle.LEFT, DragHandle.RIGHT,
        DragHandle.TOP_LEFT, DragHandle.TOP_RIGHT,
        DragHandle.BOTTOM_LEFT, DragHandle.BOTTOM_RIGHT -> true
        else -> false
    }

    /** Swap the X-component of a handle (LEFT&#8596;RIGHT, and the X side of corners). */
    private fun DragHandle.mirroredX(): DragHandle = when (this) {
        DragHandle.LEFT         -> DragHandle.RIGHT
        DragHandle.RIGHT        -> DragHandle.LEFT
        DragHandle.TOP_LEFT     -> DragHandle.TOP_RIGHT
        DragHandle.TOP_RIGHT    -> DragHandle.TOP_LEFT
        DragHandle.BOTTOM_LEFT  -> DragHandle.BOTTOM_RIGHT
        DragHandle.BOTTOM_RIGHT -> DragHandle.BOTTOM_LEFT
        else -> this
    }

    /**
     * Clamp edges into `[0, 1]` and enforce a minimum extent of [MIN_CROP_FRAC] on each
     * axis, matching the `CropOverlay` clamping behavior.
     */
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
}
