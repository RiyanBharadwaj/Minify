package com.shanks.minify.ui.trim

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [TimelineMapping.zoomBy], the pure zoom math behind the
 * Video_Trimmer timeline scale.
 *
 * Zooming must change the time-to-pixel scale monotonically — zooming in (factor > 1)
 * strictly increases [TimelineMapping.pxPerMs], zooming out (factor in (0, 1)) strictly
 * decreases it — while always keeping the scale strictly positive.
 *
 * Inputs are chosen so the strict monotonicity is observable well above the
 * [TimelineMapping.MIN_PX_PER_MS] floor (1e-6): a moderate starting `pxPerMs` combined
 * with factors clearly above 1 or clearly within (0, 1) but never so extreme that the
 * scaled result reaches the floor.
 */
class TimelineZoomMonotonicityPropertyTest {

    // Feature: media-editing-suite, Property 18: Timeline zoom changes the scale monotonically and keeps it positive
    @Property(tries = 500)
    fun zoomInStrictlyIncreasesScaleAndStaysPositive(
        @ForAll("mappings") mapping: TimelineMapping,
        @ForAll("zoomInFactors") factor: Float,
    ) {
        val zoomed = mapping.zoomBy(factor)
        assertTrue(
            zoomed.pxPerMs > mapping.pxPerMs,
            "zoomBy($factor) on pxPerMs=${mapping.pxPerMs} must strictly increase the scale, got ${zoomed.pxPerMs}",
        )
        assertTrue(
            zoomed.pxPerMs > 0f,
            "zoomBy($factor) on pxPerMs=${mapping.pxPerMs} must stay positive, got ${zoomed.pxPerMs}",
        )
    }

    // Feature: media-editing-suite, Property 18: Timeline zoom changes the scale monotonically and keeps it positive
    @Property(tries = 500)
    fun zoomOutStrictlyDecreasesScaleAndStaysPositive(
        @ForAll("mappings") mapping: TimelineMapping,
        @ForAll("zoomOutFactors") factor: Float,
    ) {
        val zoomed = mapping.zoomBy(factor)
        assertTrue(
            zoomed.pxPerMs < mapping.pxPerMs,
            "zoomBy($factor) on pxPerMs=${mapping.pxPerMs} must strictly decrease the scale, got ${zoomed.pxPerMs}",
        )
        assertTrue(
            zoomed.pxPerMs > 0f,
            "zoomBy($factor) on pxPerMs=${mapping.pxPerMs} must stay positive, got ${zoomed.pxPerMs}",
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

    /** Zoom-in factors clearly greater than 1, keeping the scaled result finite and above the floor. */
    @Provide
    fun zoomInFactors(): Arbitrary<Float> =
        Arbitraries.doubles().between(1.01, 50.0).map { it.toFloat() }

    /** Zoom-out factors clearly within (0, 1), keeping the scaled result strictly above the floor. */
    @Provide
    fun zoomOutFactors(): Arbitrary<Float> =
        Arbitraries.doubles().between(0.02, 0.99).map { it.toFloat() }
}
