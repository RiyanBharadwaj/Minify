package com.shanks.minify.ui.editor.model

import com.shanks.minify.photo.ImageEditModel
import com.shanks.minify.ui.CropRect

/**
 * Pure, Android-independent reconciliation of a retained / restored
 * [ImageEditModel] geometry when the editor is reopened for a video (Req 2.5).
 *
 * The crop stored on [ImageEditModel] is plain data with no construction-time
 * validation ([CropRect] is an unchecked `data class`), so a corrupted or
 * incompatible persisted payload can produce a *malformed* crop — non-finite
 * edges, edges outside the normalized `[0,1]` range, or a degenerate/inverted
 * rectangle with a non-positive area. Displaying such a crop yields the reported
 * "glitched after reopen" behavior.
 *
 * When the retained geometry cannot be reconciled, the caller substitutes the
 * [FULL_FRAME_IDENTITY] geometry (full-frame [CropRect.FULL], rotation `0`, not
 * mirrored) so the reopened video shows an uncropped full frame rather than a
 * malformed crop, while the source media itself is left untouched.
 *
 * Kept pure so the fallback decision is fully JVM-testable without Compose or a
 * live player, matching the design's "push every decision into a testable pure
 * function" principle.
 */
object GeometryReconciler {

    /** The uncropped, un-rotated, un-mirrored identity geometry (Req 2.5). */
    val FULL_FRAME_IDENTITY: ImageEditModel = ImageEditModel(
        rotationDegrees = 0,
        mirrored = false,
        crop = CropRect.FULL,
    )

    /**
     * True when [geometry] can be restored as-is: its crop edges are finite,
     * within the normalized `[0,1]` range, and form a non-degenerate rectangle
     * (`left < right` and `top < bottom`). A geometry that fails any of these is
     * considered malformed and must fall back to [FULL_FRAME_IDENTITY].
     */
    fun isRestorable(geometry: ImageEditModel): Boolean {
        val c = geometry.crop
        val finite = c.left.isFinite() && c.top.isFinite() &&
            c.right.isFinite() && c.bottom.isFinite()
        if (!finite) return false
        val inBounds = c.left in 0f..1f && c.top in 0f..1f &&
            c.right in 0f..1f && c.bottom in 0f..1f
        if (!inBounds) return false
        return c.left < c.right && c.top < c.bottom
    }

    /**
     * Returns [geometry] unchanged when [isRestorable]; otherwise the
     * [FULL_FRAME_IDENTITY] fallback (Req 2.5).
     */
    fun reconcile(geometry: ImageEditModel): ImageEditModel =
        if (isRestorable(geometry)) geometry else FULL_FRAME_IDENTITY
}
