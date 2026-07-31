package com.shanks.minify.ui.editor.model

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.math.abs

/**
 * Property-based tests for [TimelineZoom], the pure bounded-zoom model that backs the
 * Trim_Panel's explicit zoom-in/zoom-out buttons.
 *
 * The trim timeline is magnified in `px per ms`. Zooming steps the magnification by a factor
 * of two, clamped to a `[minPxPerMs, maxPxPerMs]` range where `maxPxPerMs == 16 × minPxPerMs`
 * and `minPxPerMs` fits the whole kept range within the viewport. The zoom-in/zoom-out controls
 * are enabled exactly when there is still headroom in the corresponding direction.
 */
class TimelineZoomPropertyTest {

    // Feature: media-editor-ux-fixes, Property 16: Timeline zoom is bounded and steps by a factor of 2
    @Property(tries = 300)
    fun timelineZoomIsBoundedAndStepsByAFactorOfTwo(
        @ForAll("zooms") zoom: TimelineZoom,
    ) {
        val min = zoom.minPxPerMs
        val max = zoom.maxPxPerMs

        // maxPxPerMs equals 16 × minPxPerMs (Req 9.5).
        assertEquals(
            min * TimelineZoom.MAX_FACTOR,
            max,
            "maxPxPerMs must equal 16 × minPxPerMs (min=$min)",
        )
        assertTrue(min > 0f && min.isFinite(), "minPxPerMs must be finite and positive (min=$min)")

        // Current magnification is always clamped into [minPxPerMs, maxPxPerMs].
        assertTrue(
            zoom.pxPerMs in min..max,
            "pxPerMs ${zoom.pxPerMs} must lie within [$min, $max]",
        )

        // zoomIn multiplies pxPerMs by 2, clamped to maxPxPerMs (Req 9.2).
        val zoomedIn = zoom.zoomIn()
        assertEquals(
            (zoom.pxPerMs * TimelineZoom.ZOOM_STEP).coerceIn(min, max),
            zoomedIn.pxPerMs,
            "zoomIn must multiply pxPerMs by 2 clamped to [$min, $max]",
        )
        assertTrue(zoomedIn.pxPerMs in min..max, "zoomIn result must stay within bounds")
        assertTrue(zoomedIn.pxPerMs >= zoom.pxPerMs, "zoomIn must not decrease magnification")

        // zoomOut divides pxPerMs by 2, clamped to minPxPerMs (Req 9.3).
        val zoomedOut = zoom.zoomOut()
        assertEquals(
            (zoom.pxPerMs / TimelineZoom.ZOOM_STEP).coerceIn(min, max),
            zoomedOut.pxPerMs,
            "zoomOut must divide pxPerMs by 2 clamped to [$min, $max]",
        )
        assertTrue(zoomedOut.pxPerMs in min..max, "zoomOut result must stay within bounds")
        assertTrue(zoomedOut.pxPerMs <= zoom.pxPerMs, "zoomOut must not increase magnification")

        // canZoomIn is false exactly when pxPerMs >= maxPxPerMs (Req 9.7).
        assertEquals(
            zoom.pxPerMs < max,
            zoom.canZoomIn,
            "canZoomIn must be true exactly when pxPerMs (${zoom.pxPerMs}) < maxPxPerMs ($max)",
        )

        // canZoomOut is false exactly when pxPerMs <= minPxPerMs (Req 9.8).
        assertEquals(
            zoom.pxPerMs > min,
            zoom.canZoomOut,
            "canZoomOut must be true exactly when pxPerMs (${zoom.pxPerMs}) > minPxPerMs ($min)",
        )
    }

    // Feature: media-editor-ux-fixes, Property 16: Timeline zoom is bounded and steps by a factor of 2
    @Property(tries = 300)
    fun fitMakesMinimumMagnificationFillTheViewportWithTheWholeKeptRange(
        @ForAll("keptRanges") keptRangeMs: Long,
        @ForAll("viewports") viewportPx: Float,
    ) {
        val zoom = TimelineZoom.fit(keptRangeMs, viewportPx)

        // fit is total: it always yields a usable, finite, strictly positive minimum, and starts
        // fully zoomed out (pxPerMs == minPxPerMs).
        assertTrue(
            zoom.minPxPerMs > 0f && zoom.minPxPerMs.isFinite(),
            "fit must yield a finite positive minPxPerMs (kept=$keptRangeMs, viewport=$viewportPx)",
        )
        assertEquals(
            zoom.minPxPerMs,
            zoom.pxPerMs,
            "fit must start fully zoomed out (pxPerMs == minPxPerMs)",
        )
        // At the minimum, zoom-out is disabled and zoom-in is available (headroom to max).
        assertFalse(zoom.canZoomOut, "fit result is at minimum, so canZoomOut must be false")
        assertTrue(zoom.canZoomIn, "fit result has room to zoom in, so canZoomIn must be true")

        // For non-degenerate inputs, the whole kept range exactly fills the viewport at minimum
        // magnification: minPxPerMs × keptRangeMs ≈ viewportPx (Req 9.5).
        if (keptRangeMs > 0L && viewportPx.isFinite() && viewportPx > 0f) {
            val filledPx = zoom.minPxPerMs * keptRangeMs.toFloat()
            val tolerance = maxOf(1e-3f, abs(viewportPx) * 1e-4f)
            assertTrue(
                abs(filledPx - viewportPx) <= tolerance,
                "minPxPerMs × keptRangeMs ($filledPx) must fill viewport ($viewportPx) " +
                    "within $tolerance (kept=$keptRangeMs)",
            )
        }
    }

    @Provide
    fun zooms(): Arbitrary<TimelineZoom> {
        // Positive, finite fit-to-viewport minimums spanning tiny (long clip in a wide viewport)
        // to large (very short clip) magnifications; the higher decimal scale keeps sub-unit
        // px/ms values representable.
        val mins: Arbitrary<Float> = Arbitraries.floats().between(0.001f, 1000f).ofScale(6)
        // pxPerMs occupies the valid [min, max = 16×min] range maintained by fit/zoomIn/zoomOut.
        // jqwik samples the endpoints (factor 1 and 16) so the min/max boundary conditions for
        // canZoomIn/canZoomOut and the zoom-step clamping are both stressed.
        return Combinators.combine(mins, Arbitraries.floats().between(1f, TimelineZoom.MAX_FACTOR))
            .`as` { min, factor ->
                TimelineZoom(
                    minPxPerMs = min,
                    pxPerMs = (min * factor).coerceIn(min, min * TimelineZoom.MAX_FACTOR),
                )
            }
    }

    @Provide
    fun keptRanges(): Arbitrary<Long> =
        // Include degenerate durations (zero, negative) plus realistic clip lengths in ms.
        Arbitraries.longs().between(-1_000L, 3_600_000L)

    @Provide
    fun viewports(): Arbitrary<Float> =
        // Include degenerate widths (zero, negative) plus realistic timeline pixel widths.
        Arbitraries.floats().between(-100f, 4000f)
}
