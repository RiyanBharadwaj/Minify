package com.shanks.minify.ui.editor.model

import com.shanks.minify.photo.ImageEditModel
import com.shanks.minify.ui.CropRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Example-based unit tests for the pure full-frame fallback decision made by
 * [GeometryReconciler] when a retained / restored video geometry is reopened
 * (Req 2.5).
 *
 * [CropRect] is an unvalidated `data class`, so a corrupted persisted payload can
 * yield a *malformed* crop — non-finite edges, edges outside the normalized
 * `[0,1]` range, or a degenerate/inverted rectangle (`left >= right` or
 * `top >= bottom`). The reconciler's contract is:
 *
 * - [GeometryReconciler.isRestorable] is `false` for any malformed crop and
 *   `true` for a well-formed one.
 * - [GeometryReconciler.reconcile] substitutes the [GeometryReconciler.FULL_FRAME_IDENTITY]
 *   identity geometry (full-frame [CropRect.FULL], rotation `0`, not mirrored) for
 *   an unrestorable geometry, and returns a restorable geometry unchanged.
 *
 * The host-level, non-blocking snackbar surfacing of the fallback is exercised
 * separately by instrumentation; this suite pins the underlying pure decision.
 */
class GeometryReconcilerTest {

    private fun geometryWithCrop(crop: CropRect): ImageEditModel =
        ImageEditModel(rotationDegrees = 0, mirrored = false, crop = crop)

    // --- isRestorable: malformed crops are rejected --------------------------

    @Test
    fun `isRestorable is false for non-finite crop edges`() {
        val nan = geometryWithCrop(CropRect(Float.NaN, 0f, 1f, 1f))
        val posInf = geometryWithCrop(CropRect(0f, 0f, Float.POSITIVE_INFINITY, 1f))
        val negInf = geometryWithCrop(CropRect(0f, Float.NEGATIVE_INFINITY, 1f, 1f))

        assertFalse(GeometryReconciler.isRestorable(nan), "a NaN edge is malformed")
        assertFalse(GeometryReconciler.isRestorable(posInf), "a +Inf edge is malformed")
        assertFalse(GeometryReconciler.isRestorable(negInf), "a -Inf edge is malformed")
    }

    @Test
    fun `isRestorable is false for out-of-bounds crop edges`() {
        val negativeLeft = geometryWithCrop(CropRect(-0.1f, 0f, 1f, 1f))
        val rightOverOne = geometryWithCrop(CropRect(0f, 0f, 1.5f, 1f))
        val bottomOverOne = geometryWithCrop(CropRect(0f, 0f, 1f, 2f))

        assertFalse(GeometryReconciler.isRestorable(negativeLeft), "left < 0 is out of bounds")
        assertFalse(GeometryReconciler.isRestorable(rightOverOne), "right > 1 is out of bounds")
        assertFalse(GeometryReconciler.isRestorable(bottomOverOne), "bottom > 1 is out of bounds")
    }

    @Test
    fun `isRestorable is false for degenerate or inverted crops`() {
        val invertedHorizontal = geometryWithCrop(CropRect(0.8f, 0f, 0.2f, 1f))
        val invertedVertical = geometryWithCrop(CropRect(0f, 0.8f, 1f, 0.2f))
        val zeroWidth = geometryWithCrop(CropRect(0.5f, 0f, 0.5f, 1f))
        val zeroHeight = geometryWithCrop(CropRect(0f, 0.5f, 1f, 0.5f))

        assertFalse(GeometryReconciler.isRestorable(invertedHorizontal), "left >= right is inverted")
        assertFalse(GeometryReconciler.isRestorable(invertedVertical), "top >= bottom is inverted")
        assertFalse(GeometryReconciler.isRestorable(zeroWidth), "left == right is degenerate")
        assertFalse(GeometryReconciler.isRestorable(zeroHeight), "top == bottom is degenerate")
    }

    // --- isRestorable: well-formed crops are accepted ------------------------

    @Test
    fun `isRestorable is true for valid crops`() {
        val full = geometryWithCrop(CropRect.FULL)
        val inset = geometryWithCrop(CropRect(0.1f, 0.2f, 0.9f, 0.8f))
        val edgeTouching = geometryWithCrop(CropRect(0f, 0f, 1f, 0.5f))

        assertTrue(GeometryReconciler.isRestorable(full), "the full frame is restorable")
        assertTrue(GeometryReconciler.isRestorable(inset), "a well-formed inset crop is restorable")
        assertTrue(GeometryReconciler.isRestorable(edgeTouching), "a crop touching the bounds is restorable")
    }

    // --- reconcile: malformed geometry falls back to full-frame identity -----

    @Test
    fun `reconcile substitutes the full-frame identity for a malformed geometry`() {
        // A malformed crop paired with a non-identity rotation + mirror, to prove
        // the WHOLE geometry (not just the crop) collapses to the identity.
        val malformed = ImageEditModel(
            rotationDegrees = 90,
            mirrored = true,
            crop = CropRect(0.8f, 0.8f, 0.2f, 0.2f),
        )

        val result = GeometryReconciler.reconcile(malformed)

        assertEquals(
            GeometryReconciler.FULL_FRAME_IDENTITY,
            result,
            "an unrestorable geometry must yield the full-frame identity geometry",
        )
        assertEquals(CropRect.FULL, result.crop, "the fallback crop must be the full frame")
        assertEquals(0, result.rotationDegrees, "the fallback rotation must be 0")
        assertFalse(result.mirrored, "the fallback must not be mirrored")
    }

    @Test
    fun `reconcile leaves the source geometry unchanged when malformed`() {
        // The malformed input instance itself must not be mutated by the fallback.
        val malformedCrop = CropRect(0.9f, 0.1f, 0.1f, 0.9f)
        val malformed = geometryWithCrop(malformedCrop)

        GeometryReconciler.reconcile(malformed)

        assertEquals(malformedCrop, malformed.crop, "the source geometry must be left untouched")
    }

    // --- reconcile: valid geometry is returned unchanged ---------------------

    @Test
    fun `reconcile returns a valid geometry unchanged`() {
        val valid = ImageEditModel(
            rotationDegrees = 270,
            mirrored = true,
            crop = CropRect(0.15f, 0.25f, 0.85f, 0.75f),
        )

        val result = GeometryReconciler.reconcile(valid)

        assertSame(valid, result, "a restorable geometry must be returned unchanged (same instance)")
        assertEquals(valid, result, "a restorable geometry must be returned unchanged")
    }
}
