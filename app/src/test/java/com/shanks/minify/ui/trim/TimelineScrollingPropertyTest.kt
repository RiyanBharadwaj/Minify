package com.shanks.minify.ui.trim

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
 * Property-based test for [TimelineMapping]'s scroll behaviour on a zoomed-in timeline.
 *
 * When the user zooms in far enough that the timeline content is wider than the visible
 * viewport, the timeline must still let them reach every moment of the video by scrolling.
 * Concretely, with `maxScrollPx = contentWidthPx() - viewportWidth`:
 *  - at `scrollPx = 0` the first visible pixel (0) maps back to time `0`, and
 *  - at `scrollPx = maxScrollPx` the last visible pixel (`viewportWidth`) maps back to
 *    `durationMs` (within whole-millisecond rounding),
 * so [TimelineMapping.pxToTime] covers the whole `[0, durationMs]` range across the scroll span.
 */
class TimelineScrollingPropertyTest {

    // Feature: media-editing-suite, Property 19: A zoomed timeline scrolls across the full duration
    @Property(tries = 500)
    fun zoomedTimelineScrollsAcrossTheFullDuration(
        @ForAll("scenarios") scenario: ScrollScenario,
    ) {
        val durationMs = scenario.durationMs
        val pxPerMs = scenario.pxPerMs
        val viewportWidth = scenario.viewportWidth

        // Base (unscrolled) mapping. contentWidth > viewport is guaranteed by the generator.
        val base = TimelineMapping(durationMs = durationMs, pxPerMs = pxPerMs, scrollPx = 0f)
        val contentWidth = base.contentWidthPx()
        assertTrue(
            contentWidth > viewportWidth,
            "generator must produce a zoomed-in timeline: contentWidth=$contentWidth > viewport=$viewportWidth",
        )

        val maxScrollPx = contentWidth - viewportWidth

        // Whole-ms tolerance: absorb float rounding at the current magnitude/scale plus the
        // roundToLong step inside pxToTime.
        val tolerance = ((contentWidth * 1e-6f) / pxPerMs).toLong() + 2L

        // Time 0 is reachable: at scrollPx = 0 the first visible pixel maps back to 0.
        assertEquals(
            0L,
            base.pxToTime(0f),
            "at scrollPx=0 the first visible pixel must map back to time 0",
        )

        // durationMs is reachable: at the maximum scroll offset the last visible pixel
        // (viewportWidth) maps back to durationMs within rounding tolerance.
        val scrolledToEnd = base.copy(scrollPx = maxScrollPx)
        val lastVisibleTime = scrolledToEnd.pxToTime(viewportWidth)
        assertTrue(
            abs(lastVisibleTime - durationMs) <= tolerance,
            "at maxScrollPx=$maxScrollPx the last visible pixel ($viewportWidth) should map to " +
                "durationMs=$durationMs but was $lastVisibleTime (tolerance=$tolerance)",
        )

        // Every intermediate scroll offset yields visible times inside [0, durationMs], and the
        // reachable time is non-decreasing as we scroll right: pxToTime covers the full range.
        var previousStart = -1L
        for (step in 0..STEPS) {
            val scrollPx = maxScrollPx * step / STEPS
            val mapping = base.copy(scrollPx = scrollPx)

            val firstVisible = mapping.pxToTime(0f)
            val lastVisible = mapping.pxToTime(viewportWidth)

            assertTrue(
                firstVisible in 0L..durationMs,
                "first visible time $firstVisible at scrollPx=$scrollPx must lie in [0, $durationMs]",
            )
            assertTrue(
                lastVisible in 0L..durationMs,
                "last visible time $lastVisible at scrollPx=$scrollPx must lie in [0, $durationMs]",
            )
            assertTrue(
                lastVisible >= firstVisible,
                "last visible time $lastVisible must be >= first visible $firstVisible at scrollPx=$scrollPx",
            )
            assertTrue(
                firstVisible >= previousStart,
                "scrolling right must not move the first visible time backwards " +
                    "($firstVisible < $previousStart at scrollPx=$scrollPx)",
            )
            previousStart = firstVisible
        }
    }

    /**
     * A positive [durationMs], a viewport width, and a [pxPerMs] scale chosen so the timeline is
     * zoomed in — content width strictly exceeds the viewport. The scale is derived from a zoom
     * multiplier `> 1` applied to the just-fits scale `viewportWidth / durationMs`.
     */
    @Provide
    fun scenarios(): Arbitrary<ScrollScenario> {
        val durations: Arbitrary<Long> = Arbitraries.longs().between(1_000L, 600_000L)
        val viewports: Arbitrary<Float> =
            Arbitraries.floats().between(320f, 4_000f).ofScale(2).filter { it.isFinite() }
        // Zoom multiplier strictly greater than 1 so contentWidth > viewportWidth.
        val zoomFactors: Arbitrary<Float> =
            Arbitraries.floats().between(1.01f, 50f).ofScale(3).filter { it.isFinite() }

        return Combinators.combine(durations, viewports, zoomFactors).`as` { durationMs, viewport, zoom ->
            // "Just fits" scale packs the whole duration into the viewport; multiplying by a
            // factor > 1 zooms in so the content is wider than the viewport.
            val justFits = viewport / durationMs.toFloat()
            val pxPerMs = (justFits * zoom).coerceAtLeast(TimelineMapping.MIN_PX_PER_MS)
            ScrollScenario(durationMs, pxPerMs, viewport)
        }.filter { it.durationMs * it.pxPerMs > it.viewportWidth }
    }

    data class ScrollScenario(
        val durationMs: Long,
        val pxPerMs: Float,
        val viewportWidth: Float,
    )

    companion object {
        /** Number of scroll samples taken across `[0, maxScrollPx]`. */
        private const val STEPS = 20
    }
}
