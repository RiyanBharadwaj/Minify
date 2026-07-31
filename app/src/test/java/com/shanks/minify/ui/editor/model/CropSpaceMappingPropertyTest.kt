package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.CropRect
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based test for [CropSpaceMapping.toSourceSpace].
 *
 * Feature: media-editor-fixes, Property 3: Video crop mapping equals the photo
 * renderer's displayed->source inversion.
 *
 * Validates: Requirements 4.1, 4.2
 *
 * For any crop rectangle, any rotation in {0, 90, 180, 270}, and any mirror flag,
 * [CropSpaceMapping.toSourceSpace] must produce the same source-space region that
 * `PhotoEffectRenderer.computeTexCoords` bakes into its texture coordinates for the
 * equivalent geometry. The reference below replicates that renderer's per-corner
 * inversion exactly (invert rotation, then invert mirror) so the two definitions
 * are proven equivalent across the whole input space.
 */
class CropSpaceMappingPropertyTest {

    @Property(tries = 300)
    fun mappingEqualsPhotoRendererTexCoordInversion(
        @ForAll("crops") displayed: CropRect,
        @ForAll("rotations") rotationDegrees: Int,
        @ForAll mirrored: Boolean,
    ) {
        val expected = referenceSourceRegion(displayed, rotationDegrees, mirrored)
        val actual = CropSpaceMapping.toSourceSpace(displayed, rotationDegrees, mirrored)

        assertEquals(expected.left, actual.left, EPS, "left mismatch for $displayed r=$rotationDegrees m=$mirrored")
        assertEquals(expected.top, actual.top, EPS, "top mismatch for $displayed r=$rotationDegrees m=$mirrored")
        assertEquals(expected.right, actual.right, EPS, "right mismatch for $displayed r=$rotationDegrees m=$mirrored")
        assertEquals(expected.bottom, actual.bottom, EPS, "bottom mismatch for $displayed r=$rotationDegrees m=$mirrored")

        // The mapped region stays a well-ordered rectangle inside the unit square,
        // since the source-space selection must remain a valid crop.
        assertTrue(actual.left <= actual.right + EPS, "left <= right: $actual")
        assertTrue(actual.top <= actual.bottom + EPS, "top <= bottom: $actual")
        assertTrue(actual.left >= -EPS && actual.right <= 1f + EPS, "x within [0,1]: $actual")
        assertTrue(actual.top >= -EPS && actual.bottom <= 1f + EPS, "y within [0,1]: $actual")
    }

    /**
     * The reference source-space region: the bounding rectangle of the four
     * displayed corners after inverting the rotation and then the mirror, mirroring
     * `PhotoEffectRenderer.computeTexCoords` (BL, BR, TL, TR order is irrelevant to
     * the bounding box, so all four corners are used).
     */
    private fun referenceSourceRegion(displayed: CropRect, rotationDegrees: Int, mirrored: Boolean): CropRect {
        val rotation = ((rotationDegrees % 360) + 360) % 360
        val corners = arrayOf(
            displayed.left to displayed.bottom,   // BL
            displayed.right to displayed.bottom,  // BR
            displayed.left to displayed.top,      // TL
            displayed.right to displayed.top,     // TR
        )

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for ((x, y) in corners) {
            // Invert the clockwise image rotation (displayed -> pre-rotation).
            val (xp, yp) = when (rotation) {
                90 -> y to (1f - x)
                180 -> (1f - x) to (1f - y)
                270 -> (1f - y) to x
                else -> x to y
            }
            // Invert the horizontal mirror applied before rotation.
            val sx = if (mirrored) 1f - xp else xp
            val sy = yp

            minX = minOf(minX, sx)
            maxX = maxOf(maxX, sx)
            minY = minOf(minY, sy)
            maxY = maxOf(maxY, sy)
        }
        return CropRect(left = minX, top = minY, right = maxX, bottom = maxY)
    }

    /** Rotations the editor supports. */
    @Provide
    fun rotations(): Arbitrary<Int> = Arbitraries.of(0, 90, 180, 270)

    /** Well-ordered, in-bounds normalized crop rectangles in [0,1]x[0,1]. */
    @Provide
    fun crops(): Arbitrary<CropRect> {
        val coord: Arbitrary<Float> = Arbitraries.floats().between(0f, 1f)
        return Combinators.combine(coord, coord, coord, coord).`as` { a, b, c, d ->
            CropRect(
                left = minOf(a, c),
                top = minOf(b, d),
                right = maxOf(a, c),
                bottom = maxOf(b, d),
            )
        }
    }

    private companion object {
        const val EPS = 1e-6f
    }
}
