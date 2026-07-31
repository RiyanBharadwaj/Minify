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
import kotlin.math.abs

/**
 * Property-based tests for [SplitOps.addSplit], the pure split-recording operation behind the
 * media editor's timeline splits (Req 10.1).
 *
 * A [VideoTimeline] built over a valid [TrimRange] carries an ascending, distinct set of existing
 * splits. Applying [SplitOps.addSplit] with a candidate position must record the split exactly when
 * it is validly placed - strictly inside the kept range, at least [SectionOps.MIN_SPLIT_GAP_MS]
 * milliseconds from both trim boundaries and from every existing split - and otherwise leave the
 * timeline unchanged.
 */
class SplitPlacementValidityPropertyTest {

    // Feature: media-editor-ux-fixes, Property 18: Split is recorded only when validly placed
    /**
     * Feature: media-editor-ux-fixes, Property 18: Split is recorded only when validly placed.
     *
     * For any timeline built from a valid [TrimRange] with arbitrary existing splits, and any
     * candidate position, [SplitOps.addSplit] records the split if and only if it is strictly inside
     * the kept range (`trim.startMs < positionMs < trim.endMs`) and at least
     * [SectionOps.MIN_SPLIT_GAP_MS] milliseconds from both boundaries and from every existing split
     * (which also excludes duplicates). When valid, the result adds exactly that position and keeps
     * the split list ascending and distinct, leaving every other field unchanged. When invalid, the
     * timeline is returned unchanged.
     *
     * **Validates: Requirements 10.1**
     */
    @Property(tries = 300)
    fun splitRecordedOnlyWhenValidlyPlaced(
        @ForAll("timelineWithCandidate") input: Pair<VideoTimeline, Long>,
    ) {
        val (timeline, position) = input
        val gap = SectionOps.MIN_SPLIT_GAP_MS
        val start = timeline.trim.startMs
        val end = timeline.trim.endMs

        // Independent definition of a validly placed split (does not call the model's canSplitAt):
        // strictly inside the kept range, and >= MIN_SPLIT_GAP_MS from both boundaries and from
        // every existing split. The min-gap-from-existing check also rules out duplicates.
        val insideRange = position > start && position < end
        val clearOfBoundaries = (position - start) >= gap && (end - position) >= gap
        val clearOfSplits = timeline.splits.none { abs(position - it) < gap }
        val expectedRecorded = insideRange && clearOfBoundaries && clearOfSplits

        val result = SplitOps.addSplit(timeline, position)

        if (expectedRecorded) {
            // The split is recorded: exactly this position is added, list stays ascending/distinct.
            val expectedSplits = (timeline.splits + position).sorted()
            assertEquals(
                expectedSplits,
                result.splits,
                "valid split $position should be recorded (ascending, distinct)",
            )
            // No other field of the timeline changes.
            assertEquals(
                timeline.copy(splits = expectedSplits),
                result,
                "recording a split must not change any field other than splits",
            )
        } else {
            // Invalid placement: the timeline is left completely unchanged.
            assertEquals(
                timeline,
                result,
                "invalid split $position should leave the timeline unchanged",
            )
        }

        // Whatever the outcome, splits remain strictly ascending and distinct.
        val splits = result.splits
        for (i in 1 until splits.size) {
            assertTrue(
                splits[i - 1] < splits[i],
                "splits must remain strictly ascending and distinct but were $splits",
            )
        }
    }

    /**
     * A valid [VideoTimeline] (whole-ms [TrimRange] of at least the 500ms minimum duration, with a
     * mix of inside/on-boundary/outside/duplicate candidate splits already applied via
     * [SplitOps.addSplit]) paired with a candidate split position. The candidate is drawn from a
     * window that overlaps the range and its boundaries, so it exercises inside, on-boundary,
     * just-outside, and near-existing-split (min-gap) cases.
     */
    @Provide
    fun timelineWithCandidate(): Arbitrary<Pair<VideoTimeline, Long>> {
        val starts = Arbitraries.longs().between(0L, 100_000L)
        val extraDurations = Arbitraries.longs().between(TrimRange.MIN_DURATION_MS, 50_000L)

        val ranges: Arbitrary<TrimRange> = Combinators.combine(starts, extraDurations)
            .`as` { start, extra -> TrimRange(startMs = start, endMs = start + extra) }

        return ranges.flatMap { range ->
            // Existing splits: a mix of inside/on-boundary/outside/duplicate positions filtered by
            // addSplit, yielding a valid ascending, distinct, min-gap-respecting split set.
            val existing = Arbitraries.longs()
                .between(range.startMs - 5L, range.endMs + 5L)
                .list().ofMaxSize(10)
                .map { positions ->
                    var timeline = VideoTimeline(trim = range)
                    for (position in positions) {
                        timeline = SplitOps.addSplit(timeline, position)
                    }
                    timeline
                }
            // Candidate spans slightly beyond the boundaries so on/outside placements are covered.
            val candidate = Arbitraries.longs()
                .between(range.startMs - 5L, range.endMs + 5L)
            Combinators.combine(existing, candidate).`as` { timeline, position -> timeline to position }
        }
    }
}
