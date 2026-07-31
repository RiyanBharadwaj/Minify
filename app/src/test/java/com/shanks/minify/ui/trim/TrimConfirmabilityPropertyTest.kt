package com.shanks.minify.ui.trim

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Property-based test for [TrimRangeOps.isConfirmable], the pure guard behind the
 * Video_Trimmer's confirm action.
 *
 * A range may be confirmed exactly when its selected duration meets the 500ms minimum, so the
 * predicate must agree with the `durationMs >= MIN_DURATION_MS` boundary for every range,
 * including the values immediately around 500ms (499, 500, 501).
 */
class TrimConfirmabilityPropertyTest {

    // Feature: media-editing-suite, Property 23: Trim confirmability equals the minimum-duration boundary
    @Property(tries = 500)
    fun confirmabilityEqualsMinimumDurationBoundary(
        @ForAll("ranges") range: TrimRange,
    ) {
        val expected = range.durationMs >= TrimRange.MIN_DURATION_MS
        assertEquals(
            expected,
            TrimRangeOps.isConfirmable(range),
            "isConfirmable(range=$range) with durationMs=${range.durationMs} " +
                "must equal (durationMs >= ${TrimRange.MIN_DURATION_MS})",
        )
    }

    /**
     * Arbitrary [TrimRange]s built from a start and a duration. Durations are drawn from the
     * full range but biased toward the boundary (499, 500, 501, and nearby values) so the
     * biconditional is exercised right where it flips.
     */
    @Provide
    fun ranges(): Arbitrary<TrimRange> {
        val starts: Arbitrary<Long> = Arbitraries.longs().between(0L, 3_600_000L)

        // Durations spanning below, at, and above the 500ms boundary, plus explicit edge values.
        val wideDurations: Arbitrary<Long> = Arbitraries.longs().between(0L, 3_600_000L)
        val boundaryDurations: Arbitrary<Long> = Arbitraries.longs().between(490L, 510L)
        val edgeDurations: Arbitrary<Long> =
            Arbitraries.of(0L, 1L, 499L, 500L, 501L, 999L, 1_000L)
        val durations: Arbitrary<Long> =
            Arbitraries.oneOf(wideDurations, boundaryDurations, edgeDurations)

        return Combinators.combine(starts, durations).`as` { startMs, durationMs ->
            TrimRange(startMs, startMs + durationMs)
        }
    }
}
