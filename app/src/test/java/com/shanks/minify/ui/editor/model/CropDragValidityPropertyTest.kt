package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.CropRect
import com.shanks.minify.ui.DragHandle
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based test for [CropDrag.resolve] output validity.
 *
 * // Feature: media-editor-ux-fixes, Property 1: Crop drag always yields a valid crop
 *
 * Validates: Requirements 1.1, 1.3, 1.6, 1.7
 *
 * For *any* starting crop, grabbed handle, drag delta (including large and negative
 * deltas that push edges well outside the unit square), mirror flag, and locked aspect
 * (which encodes the rotation-aware displayed aspect), [CropDrag.resolve] must always
 * return a well-formed [CropRect]:
 *
 * - every edge stays within `[0, 1]`,
 * - `left < right` and `top < bottom` (non-degenerate, correctly ordered),
 * - both the width and the height are at least [CropDrag.MIN_CROP_FRAC] (0.05).
 *
 * This exercises the totality and clamping guarantees of the resolver rather than any
 * specific edge movement, so the generators deliberately cover the full input space.
 */
class CropDragValidityPropertyTest {

    @Property(tries = 300)
    fun resolveAlwaysYieldsValidCrop(
        @ForAll("crops") crop: CropRect,
        @ForAll("handles") handle: DragHandle,
        @ForAll("deltas") dNormX: Float,
        @ForAll("deltas") dNormY: Float,
        @ForAll mirrored: Boolean,
        @ForAll("aspects") lockedNormAspect: Float?,
    ) {
        val result = CropDrag.resolve(
            crop = crop,
            handle = handle,
            dNormX = dNormX,
            dNormY = dNormY,
            mirrored = mirrored,
            lockedNormAspect = lockedNormAspect,
        )

        val ctx = "crop=$crop handle=$handle dx=$dNormX dy=$dNormY " +
            "mirrored=$mirrored aspect=$lockedNormAspect result=$result"

        // Edges within [0, 1].
        assertTrue(result.left in 0f..1f, "left out of [0,1]: $ctx")
        assertTrue(result.top in 0f..1f, "top out of [0,1]: $ctx")
        assertTrue(result.right in 0f..1f, "right out of [0,1]: $ctx")
        assertTrue(result.bottom in 0f..1f, "bottom out of [0,1]: $ctx")

        // Correctly ordered / non-degenerate.
        assertTrue(result.left < result.right, "left must be < right: $ctx")
        assertTrue(result.top < result.bottom, "top must be < bottom: $ctx")

        // Minimum extent on each axis (with a tiny float tolerance).
        assertTrue(
            result.width >= CropDrag.MIN_CROP_FRAC - EPS,
            "width must be >= ${CropDrag.MIN_CROP_FRAC}: $ctx",
        )
        assertTrue(
            result.height >= CropDrag.MIN_CROP_FRAC - EPS,
            "height must be >= ${CropDrag.MIN_CROP_FRAC}: $ctx",
        )
    }

    /** Every drag handle, including corners, sides, BODY, and NONE. */
    @Provide
    fun handles(): Arbitrary<DragHandle> = Arbitraries.of(*DragHandle.values())

    /**
     * Drag deltas spanning small, large, and negative magnitudes so edges are routinely
     * pushed outside `[0, 1]`, forcing the clamp/min-extent path under test.
     */
    @Provide
    fun deltas(): Arbitrary<Float> = Arbitraries.floats().between(-5f, 5f)

    /**
     * Locked normalized aspects (`width / height`) encoding the rotation-aware displayed
     * aspect, plus `null` for a free resize. Covers portrait and landscape ratios.
     */
    @Provide
    fun aspects(): Arbitrary<Float?> =
        Arbitraries.oneOf(
            Arbitraries.just<Float?>(null),
            Arbitraries.floats().between(0.1f, 10f).map { it as Float? },
        )

    /**
     * Arbitrary well-formed crops covering the whole unit square: any ordered rectangle
     * with edges in `[0, 1]` and at least [CropDrag.MIN_CROP_FRAC] extent on each axis.
     */
    @Provide
    fun crops(): Arbitrary<CropRect> {
        val coord: Arbitrary<Float> = Arbitraries.floats().between(0f, 1f)
        return Combinators.combine(coord, coord, coord, coord).`as` { a, b, c, d ->
            val left = minOf(a, b)
            val right = maxOf(a, b)
            val top = minOf(c, d)
            val bottom = maxOf(c, d)
            val min = CropDrag.MIN_CROP_FRAC
            // Ensure a valid, non-degenerate starting crop with the required min extent.
            val l = left.coerceAtMost(1f - min)
            val r = right.coerceAtLeast(l + min).coerceAtMost(1f)
            val t = top.coerceAtMost(1f - min)
            val bo = bottom.coerceAtLeast(t + min).coerceAtMost(1f)
            CropRect(left = l, top = t, right = r, bottom = bo)
        }
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
