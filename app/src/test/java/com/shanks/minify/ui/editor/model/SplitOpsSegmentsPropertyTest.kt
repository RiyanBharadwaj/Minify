package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.trim.TrimRange
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [SplitOps.segments], the pure split-segmentation math behind the
 * Unified_Media_Editor's timeline splits (Req 7.4).
 *
 * A [VideoTimeline] over a valid [TrimRange] can accept arbitrary split positions via
 * [SplitOps.addSplit] (some inside the kept range, some on/outside the boundaries, some
 * duplicated). Regardless of the positions supplied, [SplitOps.segments] must partition the kept
 * range `[trim.startMs, trim.endMs]` into ordered, gap-free, non-overlapping [Segment]s.
 */
class SplitOpsSegmentsPropertyTest {

    // Feature: unified-media-editor, Property 15: Split produces ordered, gap-free, non-overlapping segments covering the kept range
    /**
     * Feature: unified-media-editor, Property 15: Split produces ordered, gap-free, non-overlapping
     * segments covering the kept range.
     *
     * For any valid [TrimRange] and any list of candidate split positions, applying
     * [SplitOps.addSplit] for each and then calling [SplitOps.segments] yields segments that are:
     * ordered ascending, non-overlapping and gap-free (each `endMs` equals the next `startMs`),
     * span exactly `[trim.startMs, trim.endMs]`, and each has `startMs < endMs`.
     *
     * **Validates: Requirements 7.4**
     */
    @Property(tries = 300)
    fun splitProducesOrderedGapFreeSegmentsCoveringTheKeptRange(
        @ForAll("timelineWithSplits") input: Pair<TrimRange, List<Long>>,
    ) {
        val (trim, positions) = input

        var timeline = VideoTimeline(trim = trim)
        for (position in positions) {
            timeline = SplitOps.addSplit(timeline, position)
        }

        val segments = SplitOps.segments(timeline)

        // At least one segment always covers the (non-empty) kept range.
        assertTrue(segments.isNotEmpty(), "segments should be non-empty for range $trim")

        // First starts at trim.startMs and last ends at trim.endMs (full coverage).
        assertEquals(trim.startMs, segments.first().startMs, "first segment starts at trim.startMs")
        assertEquals(trim.endMs, segments.last().endMs, "last segment ends at trim.endMs")

        var previousEnd: Long? = null
        for (segment in segments) {
            // Every segment is well-formed.
            assertTrue(
                segment.startMs < segment.endMs,
                "segment must have startMs < endMs but was [$segment]",
            )
            // Ordered, non-overlapping and gap-free: each start equals the previous end.
            if (previousEnd != null) {
                assertEquals(
                    previousEnd,
                    segment.startMs,
                    "segment start must equal previous segment end (gap-free, non-overlapping)",
                )
            }
            previousEnd = segment.endMs
        }
    }

    /**
     * A valid [TrimRange] (whole ms, `startMs < endMs`, at least the 500ms minimum duration) paired
     * with a list of candidate split positions. Positions are drawn from a window that overlaps the
     * range and its boundaries, so the list contains a mix of inside, on-boundary, outside, and
     * duplicated values to exercise [SplitOps.addSplit]'s filtering.
     */
    @Provide
    fun timelineWithSplits(): Arbitrary<Pair<TrimRange, List<Long>>> {
        val starts = Arbitraries.longs().between(0L, 100_000L)
        val extraDurations = Arbitraries.longs().between(TrimRange.MIN_DURATION_MS, 50_000L)

        val ranges: Arbitrary<TrimRange> = Combinators.combine(starts, extraDurations)
            .`as` { start, extra -> TrimRange(startMs = start, endMs = start + extra) }

        return ranges.flatMap { range ->
            // Candidate positions span slightly beyond the boundaries to include on/outside values,
            // and allow duplicates so addSplit's dedup path is exercised.
            val candidates = Arbitraries.longs()
                .between(range.startMs - 5L, range.endMs + 5L)
            candidates.list().ofMaxSize(12)
                .map { positions -> range to positions }
        }
    }
}
