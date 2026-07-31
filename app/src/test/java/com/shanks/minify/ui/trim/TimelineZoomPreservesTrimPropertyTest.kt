package com.shanks.minify.ui.trim

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based test for [TimelineMapping.zoomBy] preserving an independent [TrimRange].
 *
 * Zooming is a pure operation on [TimelineMapping] (it rescales `pxPerMs`) and has no bearing
 * on the kept segment: given any mapping, trim range, and zoom factor, applying the zoom must
 * leave the [TrimRange] `startMs`/`endMs` untouched while the mapping's `durationMs` is preserved
 * and its `pxPerMs` changes per the factor.
 */
class TimelineZoomPreservesTrimPropertyTest {

    // Feature: media-editing-suite, Property 20: Timeline zoom preserves the trim range
    @Property(tries = 500)
    fun zoomLeavesTrimRangeUnchanged(
        @ForAll("mappings") mapping: TimelineMapping,
        @ForAll("ranges") range: TrimRange,
        @ForAll("zoomFactors") factor: Float,
    ) {
        val originalStart = range.startMs
        val originalEnd = range.endMs

        val zoomed = mapping.zoomBy(factor)

        // The TrimRange is entirely unaffected by zooming the mapping.
        assertEquals(
            originalStart,
            range.startMs,
            "zoomBy($factor) must not change TrimRange.startMs",
        )
        assertEquals(
            originalEnd,
            range.endMs,
            "zoomBy($factor) must not change TrimRange.endMs",
        )
        assertEquals(
            TrimRange(originalStart, originalEnd),
            range,
            "zoomBy($factor) must leave the TrimRange instance equal",
        )

        // The mapping keeps its duration; only the pixel scale changes.
        assertEquals(
            mapping.durationMs,
            zoomed.durationMs,
            "zoomBy($factor) must preserve the mapping's durationMs",
        )

        val expectedPxPerMs = (mapping.pxPerMs * factor)
            .coerceAtLeast(TimelineMapping.MIN_PX_PER_MS)
        assertEquals(
            expectedPxPerMs,
            zoomed.pxPerMs,
            "zoomBy($factor) must scale pxPerMs from ${mapping.pxPerMs} to $expectedPxPerMs",
        )
        assertTrue(
            zoomed.pxPerMs > 0f,
            "zoomBy($factor) must keep pxPerMs strictly positive, got ${zoomed.pxPerMs}",
        )
    }

    /**
     * A [TimelineMapping] with a positive duration, a moderate `pxPerMs` in `[0.01, 100]`
     * (well above the 1e-6 floor even after zooming out), and an arbitrary scroll offset.
     */
    @Provide
    fun mappings(): Arbitrary<TimelineMapping> {
        val durations: Arbitrary<Long> = Arbitraries.longs().between(1L, 3_600_000L)
        val scales: Arbitrary<Float> =
            Arbitraries.doubles().between(0.01, 100.0).map { it.toFloat() }
        val scrolls: Arbitrary<Float> =
            Arbitraries.doubles().between(-10_000.0, 10_000.0).map { it.toFloat() }

        return Combinators.combine(durations, scales, scrolls).`as` { durationMs, pxPerMs, scrollPx ->
            TimelineMapping(durationMs = durationMs, pxPerMs = pxPerMs, scrollPx = scrollPx)
        }
    }

    /** A valid [TrimRange] with `startMs < endMs` and at least the 500ms minimum duration. */
    @Provide
    fun ranges(): Arbitrary<TrimRange> {
        val starts: Arbitrary<Long> = Arbitraries.longs().between(0L, 3_600_000L)
        val spans: Arbitrary<Long> =
            Arbitraries.longs().between(TrimRange.MIN_DURATION_MS, 3_600_000L)
        return Combinators.combine(starts, spans).`as` { startMs, span ->
            TrimRange(startMs = startMs, endMs = startMs + span)
        }
    }

    /** Zoom factors spanning zoom-out (0, 1) and zoom-in (> 1), all finite and positive. */
    @Provide
    fun zoomFactors(): Arbitrary<Float> =
        Arbitraries.doubles().between(0.02, 50.0).map { it.toFloat() }
}
