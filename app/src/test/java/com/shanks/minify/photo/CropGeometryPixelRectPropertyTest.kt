package com.shanks.minify.photo

import com.shanks.minify.ui.CropRect
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [CropGeometry.toPixelRect].
 *
 * Exercises the pure crop-to-pixel mapping that the Photo Editor uses when it
 * confirms a crop against the full-resolution source bitmap, independent of any
 * Android bitmap dependency.
 */
class CropGeometryPixelRectPropertyTest {

    // Feature: media-editing-suite, Property 4: Confirmed crop maps to a pixel rectangle within the full-resolution bounds
    @Property(tries = 200)
    fun confirmedCropMapsToPixelRectWithinFullResolutionBounds(
        @ForAll("crops") crop: CropRect,
        @ForAll @IntRange(min = 1, max = 10000) width: Int,
        @ForAll @IntRange(min = 1, max = 10000) height: Int,
    ) {
        val rect = CropGeometry.toPixelRect(crop, width, height)
        val clamped = CropGeometry.clampToBounds(crop)

        // Width and height follow directly from the clamped normalized extents.
        assertEquals(Math.round(clamped.width * width), rect.width, "width")
        assertEquals(Math.round(clamped.height * height), rect.height, "height")

        // The rectangle is fully contained in [0,width] x [0,height].
        assertTrue(rect.left >= 0, "left >= 0 but was ${rect.left}")
        assertTrue(rect.top >= 0, "top >= 0 but was ${rect.top}")
        assertTrue(
            rect.left + rect.width <= width,
            "left+width <= width but was ${rect.left + rect.width} > $width",
        )
        assertTrue(
            rect.top + rect.height <= height,
            "top+height <= height but was ${rect.top + rect.height} > $height",
        )
    }

    @Provide
    fun crops(): Arbitrary<CropRect> {
        // Include coordinates outside [0,1] so clampToBounds is exercised across
        // in-bounds, partially-out, and fully-out crop rectangles.
        val coord: Arbitrary<Float> = Arbitraries.floats().between(-0.5f, 1.5f)
        return Combinators.combine(coord, coord, coord, coord)
            .`as` { a, b, c, d -> CropRect(a, b, c, d) }
    }
}
