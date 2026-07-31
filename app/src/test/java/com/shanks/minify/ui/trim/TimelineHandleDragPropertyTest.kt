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
 * Property-based test for the pure trim-handle drag math behind the Video_Trimmer.
 *
 * A drag resolves a pointer's screen pixel to a whole-millisecond time via
 * [TimelineMapping.pxToTime], then applies that time to a single endpoint via
 * [TrimRangeOps.moveStart] or [TrimRangeOps.moveEnd]. Moving one handle must never disturb the
 * other endpoint, and the resolved target must be a whole millisecond inside `[0, durationMs]`.
 */
class TimelineHandleDragPropertyTest {

    // Feature: media-editing-suite, Property 14: Trim handle drag resolves to whole-millisecond positions on the correct endpoint
    @Property(tries = 500)
    fun trimHandleDragResolvesToWholeMsOnCorrectEndpoint(
        @ForAll("dragScenarios") scenario: DragScenario,
    ) {
        val (mapping, range, dragPx) = scenario

        // pxToTime resolves the drag to a whole millisecond within the timeline bounds.
        val targetMs = mapping.pxToTime(dragPx)
        assertTrue(
            targetMs in 0L..mapping.durationMs,
            "pxToTime($dragPx) = $targetMs must be within [0, ${mapping.durationMs}]",
        )

        // Moving the start handle applies the target to startMs and leaves endMs untouched.
        val afterMoveStart = TrimRangeOps.moveStart(range, targetMs, mapping.durationMs)
        assertEquals(
            range.endMs,
            afterMoveStart.endMs,
            "moveStart(target=$targetMs) must leave endMs unchanged (range=$range)",
        )

        // Moving the end handle applies the target to endMs and leaves startMs untouched.
        val afterMoveEnd = TrimRangeOps.moveEnd(range, targetMs, mapping.durationMs)
        assertEquals(
            range.startMs,
            afterMoveEnd.startMs,
            "moveEnd(target=$targetMs) must leave startMs unchanged (range=$range)",
        )
    }

    /**
     * A usable [TimelineMapping] (positive duration of at least 500ms and positive scale), a valid
     * initial [TrimRange] contained in `[0, durationMs]` with at least 500ms selected, and an
     * arbitrary drag pixel position (which may fall well outside the visible content).
     */
    @Provide
    fun dragScenarios(): Arbitrary<DragScenario> {
        // Full timeline duration: at least the 500ms minimum, up to ~1 hour.
        val durations: Arbitrary<Long> =
            Arbitraries.longs().between(TrimRange.MIN_DURATION_MS, 3_600_000L)

        return durations.flatMap { durationMs ->
            // Strictly positive time-to-pixel scale across zoomed-out and zoomed-in ranges.
            // A high scale keeps the tiny MIN_PX_PER_MS lower bound representable.
            val scales: Arbitrary<Float> =
                Arbitraries.floats().between(TimelineMapping.MIN_PX_PER_MS, 10f).ofScale(6)
            // Scroll offset spanning before, within, and past the content width.
            val scrolls: Arbitrary<Float> =
                Arbitraries.floats().between(-5_000f, 5_000f)

            // A valid initial trim range that keeps at least 500ms selected.
            val maxStart = durationMs - TrimRange.MIN_DURATION_MS
            val starts: Arbitrary<Long> = Arbitraries.longs().between(0L, maxStart)

            starts.flatMap { startMs ->
                val ends: Arbitrary<Long> =
                    Arbitraries.longs().between(startMs + TrimRange.MIN_DURATION_MS, durationMs)
                // Drag pixels include in-bounds, negative, and past-the-end values.
                val dragPxs: Arbitrary<Float> =
                    Arbitraries.floats().between(-5_000f, 15_000f)

                Combinators.combine(scales, scrolls, ends, dragPxs).`as` { pxPerMs, scrollPx, endMs, dragPx ->
                    DragScenario(
                        mapping = TimelineMapping(durationMs, pxPerMs, scrollPx),
                        range = TrimRange(startMs, endMs),
                        dragPx = dragPx,
                    )
                }
            }
        }
    }

    data class DragScenario(
        val mapping: TimelineMapping,
        val range: TrimRange,
        val dragPx: Float,
    )
}
