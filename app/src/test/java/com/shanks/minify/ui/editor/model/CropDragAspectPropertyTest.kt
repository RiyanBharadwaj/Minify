package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.CropRect
import com.shanks.minify.ui.DragHandle
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.math.abs

/**
 * Property-based test for [CropDrag.resolve] under a **locked aspect ratio**,
 * verifying that resizing is symmetric across left-side and right-side handles.
 *
 * Under a locked aspect, [CropDrag] rebuilds the crop centered on the original
 * crop's center (`applyLockedAspect`), so a left-side handle dragged by a delta
 * `d` and the mirror-equivalent right-side handle dragged by `-d` (the LEFT&#8596;RIGHT
 * swap with the display-space X delta negated) must expand/contract by the same
 * amount and therefore produce crops of equal resulting size.
 */
class CropDragAspectPropertyTest {

    // Feature: media-editor-fixes, Property 14
    // Aspect-locked resize is symmetric across left and right handles.
    // Validates: Requirements 14.2
    @Property(tries = 300)
    fun aspectLockedResizeIsSymmetricAcrossLeftAndRightHandles(
        @ForAll("crops") crop: CropRect,
        @ForAll("horizontalHandlePairs") pair: Pair<DragHandle, DragHandle>,
        @ForAll("aspects") aspect: Float,
        @ForAll("deltas") d: Float,
        @ForAll("deltas") dy: Float,
    ) {
        val (leftHandle, rightHandle) = pair

        // Left-side handle dragged by d, and the mirror-equivalent right-side
        // handle dragged by -d (LEFT<->RIGHT swap negates the display-space X
        // delta). Both should resize symmetrically about the crop's center.
        val fromLeft = CropDrag.resolve(
            crop = crop,
            handle = leftHandle,
            dNormX = d,
            dNormY = dy,
            mirrored = false,
            lockedNormAspect = aspect,
        )
        val fromRight = CropDrag.resolve(
            crop = crop,
            handle = rightHandle,
            dNormX = -d,
            dNormY = dy,
            mirrored = false,
            lockedNormAspect = aspect,
        )

        // Both results stay within bounds with a valid minimum extent.
        assertValidCrop(fromLeft, "fromLeft ($leftHandle, d=$d)")
        assertValidCrop(fromRight, "fromRight ($rightHandle, d=${-d})")

        // Equal resulting size: symmetric expansion/contraction means the width
        // and height match regardless of which side handle was grabbed.
        val tol = 1e-4f
        assertEquals(
            fromLeft.width.toDouble(),
            fromRight.width.toDouble(),
            tol.toDouble(),
            "widths must match across left/right handles " +
                "(crop=$crop, pair=$pair, aspect=$aspect, d=$d)",
        )
        assertEquals(
            fromLeft.height.toDouble(),
            fromRight.height.toDouble(),
            tol.toDouble(),
            "heights must match across left/right handles " +
                "(crop=$crop, pair=$pair, aspect=$aspect, d=$d)",
        )

        // The symmetric rule keeps the crop centered on the original center, so
        // both handles yield the same rectangle, not merely the same size.
        assertTrue(
            abs(fromLeft.left - fromRight.left) <= tol &&
                abs(fromLeft.right - fromRight.right) <= tol &&
                abs(fromLeft.top - fromRight.top) <= tol &&
                abs(fromLeft.bottom - fromRight.bottom) <= tol,
            "left/right handles must produce the same centered crop " +
                "(fromLeft=$fromLeft, fromRight=$fromRight, " +
                "crop=$crop, pair=$pair, aspect=$aspect, d=$d)",
        )
    }

    private fun assertValidCrop(crop: CropRect, label: String) {
        assertTrue(
            crop.left in 0f..1f && crop.right in 0f..1f &&
                crop.top in 0f..1f && crop.bottom in 0f..1f,
            "$label edges must be within [0, 1]: $crop",
        )
        assertTrue(
            crop.width >= CropDrag.MIN_CROP_FRAC - 1e-4f &&
                crop.height >= CropDrag.MIN_CROP_FRAC - 1e-4f,
            "$label must keep the minimum extent: $crop",
        )
    }

    @Provide
    fun crops(): Arbitrary<CropRect> {
        // Build a valid crop centered in the interior with a comfortable margin
        // (>= 0.2) to every image boundary. This keeps the symmetric resize away
        // from the [0, 1] edges, where hitting the hard image boundary would
        // legitimately (and asymmetrically) clamp one side sooner than the other.
        val centers = Arbitraries.floats().between(0.4f, 0.6f)
        val halves = Arbitraries.floats().between(0.12f, 0.2f)
        return Combinators.combine(centers, centers, halves, halves)
            .`as` { cx, cy, hw, hh ->
                CropRect(cx - hw, cy - hh, cx + hw, cy + hh)
            }
    }

    @Provide
    fun horizontalHandlePairs(): Arbitrary<Pair<DragHandle, DragHandle>> =
        // The left-side and mirror-equivalent right-side handles that resolve to a
        // horizontal-driven resize under a locked aspect.
        Arbitraries.of(
            DragHandle.LEFT to DragHandle.RIGHT,
            DragHandle.TOP_LEFT to DragHandle.TOP_RIGHT,
            DragHandle.BOTTOM_LEFT to DragHandle.BOTTOM_RIGHT,
        )

    @Provide
    fun aspects(): Arbitrary<Float> =
        Arbitraries.floats().between(0.25f, 4f)

    @Provide
    fun deltas(): Arbitrary<Float> =
        // Drag magnitudes bounded by the crop's >= 0.2 margin so neither the left
        // nor the right handle drags an edge past the [0, 1] image boundary; the
        // symmetric expansion/contraction rule is then observable in isolation.
        Arbitraries.floats().between(-0.18f, 0.18f)
}
