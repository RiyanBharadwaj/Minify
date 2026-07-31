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
 * Property-based test for [TimelineMapping.fractionOf] and [TimelineMapping.timeToPx], the pure
 * time <-> fraction <-> pixel math behind the Video_Trimmer timeline.
 *
 * The timeline draws thumbnails, a ruler, and trim handles. Because they all resolve a time to a
 * pixel through the same [TimelineMapping.timeToPx], a thumbnail and a ruler tick for the same
 * time must land on the same pixel at every zoom scale and scroll offset. [fractionOf] must match
 * `timeMs / durationMs` and stay within `[0, 1]`.
 */
class TimelineMappingConsistencyPropertyTest {

    // Feature: media-editing-suite, Property 13: Timeline mapping is consistent across thumbnails, ruler, and zoom
    @Property(tries = 500)
    fun timelineMappingIsConsistentAcrossThumbnailsRulerAndZoom(
        @ForAll("scenarios") scenario: MappingScenario,
    ) {
        val mapping = TimelineMapping(
            durationMs = scenario.durationMs,
            pxPerMs = scenario.pxPerMs,
            scrollPx = scenario.scrollPx,
        )
        val timeMs = scenario.timeMs

        // fractionOf(timeMs) == timeMs / durationMs (within float tolerance) and in [0, 1].
        val expectedFraction = timeMs.toFloat() / scenario.durationMs.toFloat()
        val actualFraction = mapping.fractionOf(timeMs)
        assertEquals(
            expectedFraction.toDouble(),
            actualFraction.toDouble(),
            FRACTION_TOLERANCE,
            "fractionOf($timeMs) with duration=${scenario.durationMs} should equal timeMs/durationMs",
        )
        assertTrue(
            actualFraction in 0f..1f,
            "fractionOf($timeMs) = $actualFraction must lie in [0, 1]",
        )

        // A thumbnail position and a ruler tick position for the same time both come from the
        // single deterministic timeToPx, so they are equal at this zoom scale and scroll offset.
        val thumbnailPx = mapping.timeToPx(timeMs)
        val rulerTickPx = mapping.timeToPx(timeMs)
        assertEquals(
            thumbnailPx,
            rulerTickPx,
            "thumbnail and ruler positions for time=$timeMs must be identical (same mapping)",
        )

        // timeToPx is a single deterministic function: repeated calls with the same inputs agree,
        // and it matches the closed-form content-minus-scroll expression at any zoom scale.
        val expectedPx = timeMs * scenario.pxPerMs - scenario.scrollPx
        assertEquals(
            expectedPx,
            thumbnailPx,
            "timeToPx($timeMs) must equal timeMs * pxPerMs - scrollPx at scale=${scenario.pxPerMs}",
        )
    }

    /**
     * A positive [durationMs], a positive finite [pxPerMs] (spanning zoomed-out to zoomed-in
     * scales), an arbitrary [scrollPx] offset, and a [timeMs] in `[0, durationMs]`.
     */
    @Provide
    fun scenarios(): Arbitrary<MappingScenario> {
        val durations: Arbitrary<Long> = Arbitraries.longs().between(1L, 3_600_000L)

        // Positive, finite pixel-per-ms scales covering multiple zoom levels. A scale of 7
        // decimal places lets the generator represent values as small as MIN_PX_PER_MS (1e-6).
        val scales: Arbitrary<Float> =
            Arbitraries.floats().between(TimelineMapping.MIN_PX_PER_MS, 100f).ofScale(7)
                .filter { it > 0f && it.isFinite() }

        val scrolls: Arbitrary<Float> =
            Arbitraries.floats().between(-100_000f, 100_000f).ofScale(3).filter { it.isFinite() }

        return durations.flatMap { durationMs ->
            val times: Arbitrary<Long> = Arbitraries.longs().between(0L, durationMs)
            Combinators.combine(scales, scrolls, times).`as` { pxPerMs, scrollPx, timeMs ->
                MappingScenario(durationMs, pxPerMs, scrollPx, timeMs)
            }
        }
    }

    data class MappingScenario(
        val durationMs: Long,
        val pxPerMs: Float,
        val scrollPx: Float,
        val timeMs: Long,
    )

    companion object {
        /** Tolerance for comparing fractionOf against timeMs/durationMs in float arithmetic. */
        private const val FRACTION_TOLERANCE = 1e-6
    }
}
