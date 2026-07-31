package com.shanks.minify.ui.compare

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.math.abs
import kotlin.math.max

/**
 * Property-based test for [ComparisonViewport.zoomAround], the pure focal-point zoom
 * step shared by the before/after image comparator.
 *
 * A single viewport drives both images, so verifying that a zoom multiplies the scale
 * by the given factor and pins the content point under the focal point on screen is
 * what keeps the two images aligned pixel-for-pixel under any zoom gesture.
 */
class ComparisonViewportZoomPropertyTest {

    // Feature: media-editing-suite, Property 11: Zoom preserves the focal point with a single shared factor
    @Property(tries = 300)
    fun zoomPreservesFocalPointWithSharedFactor(
        @ForAll("scales") scale: Float,
        @ForAll("pans") panX: Float,
        @ForAll("pans") panY: Float,
        @ForAll("coords") focusX: Float,
        @ForAll("coords") focusY: Float,
        @ForAll("factors") factor: Float,
    ) {
        val viewport = ComparisonViewport(scale = scale, panX = panX, panY = panY)
        val focus = Offset(focusX, focusY)
        val bounds = Size(1080f, 1920f)

        val result = viewport.zoomAround(focus, factor, bounds)

        // The scale is multiplied by the single shared factor.
        val expectedScale = scale * factor
        val scaleTolerance = max(1e-3f, abs(expectedScale) * 1e-4f)
        assertEquals(
            expectedScale, result.scale, scaleTolerance,
            "zoomAround must multiply scale by the factor: expected $expectedScale, was ${result.scale}",
        )

        // The content point currently under `focus` must map back to the same screen
        // position after the zoom: newScale * contentUnderFocus + newPan == focus.
        val contentUnderFocusX = (focus.x - panX) / scale
        val contentUnderFocusY = (focus.y - panY) / scale

        val mappedX = result.scale * contentUnderFocusX + result.panX
        val mappedY = result.scale * contentUnderFocusY + result.panY

        // Tolerance scales with the magnitude of the coordinates to stay robust against
        // ordinary float rounding in the multiply/add chain.
        val tolX = max(1e-2f, abs(focus.x) * 1e-3f)
        val tolY = max(1e-2f, abs(focus.y) * 1e-3f)
        assertEquals(
            focus.x, mappedX, tolX,
            "content under focus.x must stay fixed on screen: expected ${focus.x}, was $mappedX",
        )
        assertEquals(
            focus.y, mappedY, tolY,
            "content under focus.y must stay fixed on screen: expected ${focus.y}, was $mappedY",
        )
    }

    @Provide
    fun scales(): Arbitrary<Float> {
        // Finite, strictly positive scales so contentUnderFocus = (focus - pan)/scale is
        // well-conditioned and the transform stays invertible.
        return Arbitraries.floats().between(0.25f, 8f)
    }

    @Provide
    fun factors(): Arbitrary<Float> {
        // Finite, positive zoom factors spanning zoom-out (<1) and zoom-in (>1).
        return Arbitraries.floats().between(0.25f, 4f)
    }

    @Provide
    fun pans(): Arbitrary<Float> {
        return Arbitraries.floats().between(-2000f, 2000f)
    }

    @Provide
    fun coords(): Arbitrary<Float> {
        // Screen-space focal coordinates within a generous device-sized range.
        return Arbitraries.floats().between(0f, 3000f)
    }
}
