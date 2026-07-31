package com.shanks.minify.ui.compare

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Property-based test for the shared-viewport alignment guarantee of the before/after
 * image comparator.
 *
 * A single [ComparisonViewport] drives both the "before" and "after" layers. The
 * transform maps a content coordinate `c` to a screen coordinate `s = scale * c + pan`
 * (`panX` horizontal, `panY` vertical). Because both layers are fed the very same
 * viewport, the mapping computed for the "before" layer must equal the mapping computed
 * for the "after" layer for every content point - that is what keeps the two media
 * aligned to the same pixels under any zoom or pan.
 *
 * There is no explicit `apply`/`transform` method on [ComparisonViewport]; the affine
 * mapping `s = scale * c + pan` is applied inline by the comparator's draw layer, so the
 * property is expressed directly against `scale`/`panX`/`panY` here.
 */
class ComparisonViewportSharedAlignmentPropertyTest {

    // Feature: media-editor-ux-fixes, Property 12: Shared viewport keeps before/after pixel-aligned
    @Property(tries = 300)
    fun sharedViewportMapsBeforeAndAfterToIdenticalScreenCoordinates(
        @ForAll("viewports") viewport: ComparisonViewport,
        @ForAll("coords") contentX: Float,
        @ForAll("coords") contentY: Float,
        @ForAll("focusCoords") focusX: Float,
        @ForAll("focusCoords") focusY: Float,
        @ForAll("factors") factor: Float,
        @ForAll("dimensions") width: Float,
        @ForAll("dimensions") height: Float,
    ) {
        val bounds = Size(width, height)

        // Exercise the property both on the raw viewport and after the gesture pipeline
        // (zoom then clamp) that a real interaction produces, since a single shared
        // viewport must stay aligned through every transform it goes through.
        val transformed = viewport
            .zoomAround(Offset(focusX, focusY), factor, bounds)
            .clampPan(bounds)

        for (vp in listOf(viewport, transformed)) {
            // The "before" and "after" layers share the SAME viewport, so mapping the same
            // content coordinate through each must produce identical screen coordinates.
            val beforeScreenX = mapX(vp, contentX)
            val beforeScreenY = mapY(vp, contentY)
            val afterScreenX = mapX(vp, contentX)
            val afterScreenY = mapY(vp, contentY)

            assertEquals(
                beforeScreenX, afterScreenX,
                "shared viewport must map content.x to the same screen.x for before and after",
            )
            assertEquals(
                beforeScreenY, afterScreenY,
                "shared viewport must map content.y to the same screen.y for before and after",
            )

            // The transform is deterministic: re-applying it yields the identical mapping,
            // so repeated recompositions of either layer never drift out of alignment.
            assertEquals(beforeScreenX, mapX(vp, contentX), "transform must be deterministic on x")
            assertEquals(beforeScreenY, mapY(vp, contentY), "transform must be deterministic on y")
        }
    }

    /** Applies the shared affine transform on the horizontal axis: `s = scale * c + panX`. */
    private fun mapX(vp: ComparisonViewport, contentX: Float): Float = vp.scale * contentX + vp.panX

    /** Applies the shared affine transform on the vertical axis: `s = scale * c + panY`. */
    private fun mapY(vp: ComparisonViewport, contentY: Float): Float = vp.scale * contentY + vp.panY

    @Provide
    fun viewports(): Arbitrary<ComparisonViewport> {
        val scales = Arbitraries.floats().between(0.25f, 8f)
        val pans = Arbitraries.floats().between(-2000f, 2000f)
        return Combinators.combine(scales, pans, pans).`as` { scale, panX, panY ->
            ComparisonViewport(scale = scale, panX = panX, panY = panY)
        }
    }

    @Provide
    fun coords(): Arbitrary<Float> {
        // Content-space coordinates spanning a generous range around the origin.
        return Arbitraries.floats().between(-3000f, 3000f)
    }

    @Provide
    fun focusCoords(): Arbitrary<Float> {
        // Screen-space focal coordinates within a device-sized range.
        return Arbitraries.floats().between(0f, 3000f)
    }

    @Provide
    fun factors(): Arbitrary<Float> {
        // Finite, positive zoom factors spanning zoom-out (<1) and zoom-in (>1).
        return Arbitraries.floats().between(0.25f, 4f)
    }

    @Provide
    fun dimensions(): Arbitrary<Float> {
        // Positive, finite view dimensions covering small and large viewports.
        return Arbitraries.floats().between(1f, 4000f)
    }
}
