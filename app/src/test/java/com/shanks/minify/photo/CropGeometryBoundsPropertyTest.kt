package com.shanks.minify.photo

import com.shanks.minify.ui.CropRect
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [CropGeometry.clampToBounds], the pure crop-clamping
 * step the Photo Editor's crop tool applies after every drag adjustment.
 *
 * These mirror the drag/clamp the editor performs: an arbitrary starting crop is
 * pushed by an arbitrary drag delta (possibly past an edge), and the clamped
 * result must always be a valid, in-bounds crop.
 */
class CropGeometryBoundsPropertyTest {

    // Feature: media-editing-suite, Property 2: Crop stays within image bounds under any adjustment
    // Feature: unified-media-editor, Property 4: Crop stays within image bounds under any adjustment
    @Property(tries = 300)
    fun cropStaysWithinImageBoundsUnderAnyAdjustment(
        @ForAll("crops") start: CropRect,
        @ForAll("deltas") delta: FloatArray,
    ) {
        // Mirror the editor's crop-tool drag: an edge (or the whole body) is
        // moved by the drag delta, which may push it well past [0,1]. dx is
        // applied to the horizontal edges, dy to the vertical edges.
        val (dLeft, dTop, dRight, dBottom) = delta
        val adjusted = CropRect(
            left = start.left + dLeft,
            top = start.top + dTop,
            right = start.right + dRight,
            bottom = start.bottom + dBottom,
        )

        val result = CropGeometry.clampToBounds(adjusted)

        // Entirely within the unit bounds [0,1] x [0,1].
        assertTrue(
            result.left in 0f..1f && result.top in 0f..1f &&
                result.right in 0f..1f && result.bottom in 0f..1f,
            "clamped crop must lie within [0,1]x[0,1], was $result (from $adjusted)",
        )

        // Non-degenerate: left < right and top < bottom.
        assertTrue(
            result.left < result.right,
            "clamped crop must satisfy left < right, was $result (from $adjusted)",
        )
        assertTrue(
            result.top < result.bottom,
            "clamped crop must satisfy top < bottom, was $result (from $adjusted)",
        )
    }

    @Provide
    fun crops(): Arbitrary<CropRect> {
        // Arbitrary crop coordinates, deliberately spanning beyond [0,1] and
        // including inverted/degenerate rectangles so the clamp is stressed.
        val coord = Arbitraries.floats().between(-2f, 3f)
        return Combinators.combine(coord, coord, coord, coord)
            .`as` { l, t, r, b -> CropRect(l, t, r, b) }
    }

    @Provide
    fun deltas(): Arbitrary<FloatArray> {
        // Drag deltas per edge, including large values that push past an edge.
        val d = Arbitraries.floats().between(-3f, 3f)
        return Combinators.combine(d, d, d, d)
            .`as` { a, b, c, e -> floatArrayOf(a, b, c, e) }
    }
}
