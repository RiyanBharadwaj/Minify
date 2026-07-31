package com.shanks.minify.ui.trim

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [TrimRangeOps.moveStart] and [TrimRangeOps.moveEnd], the pure
 * trim-handle math behind the Video_Trimmer.
 *
 * Moving either handle must always leave a well-ordered range whose selected duration is at
 * least [TrimRange.MIN_DURATION_MS], regardless of where the drag target lands (including
 * targets far outside the timeline).
 */
class TrimRangeMovePropertyTest {

    // Feature: media-editing-suite, Property 15: Trim moves preserve ordering and the minimum duration
    // Feature: unified-media-editor, Property 13: Trim moves preserve ordering and the minimum duration
    @Property(tries = 500)
    fun trimMovesPreserveOrderingAndMinimumDuration(
        @ForAll("scenarios") scenario: MoveScenario,
    ) {
        val (durationMs, range, targetMs) = scenario

        val afterMoveStart = TrimRangeOps.moveStart(range, targetMs, durationMs)
        assertInvariants(afterMoveStart, "moveStart(target=$targetMs, duration=$durationMs, range=$range)")

        val afterMoveEnd = TrimRangeOps.moveEnd(range, targetMs, durationMs)
        assertInvariants(afterMoveEnd, "moveEnd(target=$targetMs, duration=$durationMs, range=$range)")
    }

    private fun assertInvariants(result: TrimRange, label: String) {
        // Whole-millisecond precision holds by construction: TrimRange stores startMs/endMs as
        // Long and TrimRangeOps derives moved handles via Long coerceIn, so no fractional
        // milliseconds can arise. Ordering and the 500ms minimum are asserted below.
        assertTrue(
            result.startMs < result.endMs,
            "$label -> startMs (${result.startMs}) must be < endMs (${result.endMs})",
        )
        assertTrue(
            result.durationMs >= TrimRange.MIN_DURATION_MS,
            "$label -> durationMs (${result.durationMs}) must be >= ${TrimRange.MIN_DURATION_MS}",
        )
    }

    /**
     * A full duration (>= 500ms), a valid initial [TrimRange] contained in `[0, durationMs]`
     * with a selected duration of at least 500ms, and an arbitrary drag target (which may fall
     * well outside the timeline bounds).
     */
    @Provide
    fun scenarios(): Arbitrary<MoveScenario> {
        // Full timeline duration: at least the 500ms minimum, up to ~1 hour.
        val durations: Arbitrary<Long> =
            Arbitraries.longs().between(TrimRange.MIN_DURATION_MS, 3_600_000L)

        return durations.flatMap { durationMs ->
            // Start can range from 0 up to the latest point that still leaves 500ms of room.
            val maxStart = durationMs - TrimRange.MIN_DURATION_MS
            val starts: Arbitrary<Long> = Arbitraries.longs().between(0L, maxStart)

            starts.flatMap { startMs ->
                // End must keep at least 500ms of selected duration and stay within the timeline.
                val ends: Arbitrary<Long> =
                    Arbitraries.longs().between(startMs + TrimRange.MIN_DURATION_MS, durationMs)

                // Targets include in-bounds values and deliberate out-of-bounds values.
                val targets: Arbitrary<Long> =
                    Arbitraries.longs().between(-durationMs, durationMs * 2 + 1_000L)

                Combinators.combine(ends, targets).`as` { endMs, targetMs ->
                    MoveScenario(durationMs, TrimRange(startMs, endMs), targetMs)
                }
            }
        }
    }

    data class MoveScenario(
        val durationMs: Long,
        val range: TrimRange,
        val targetMs: Long,
    )
}
