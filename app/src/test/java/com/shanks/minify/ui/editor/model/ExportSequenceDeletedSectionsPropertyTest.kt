package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.trim.TrimRange
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based test for the export sequence produced by [PlaybackPlan.of] after arbitrary
 * section deletions (Req 10.5).
 *
 * The export path builds its segment sequence from [PlaybackPlan.of]. With no reverse or freeze,
 * that sequence must equal [SectionOps.keptSections] mapped through [PlanSegment], so every
 * deleted section is omitted while the retained sections stay in ascending start-time order.
 */
class ExportSequenceDeletedSectionsPropertyTest {

    // Feature: media-editor-ux-fixes, Property 21: Export sequence omits deleted sections in ascending order
    /**
     * Feature: media-editor-ux-fixes, Property 21: Export sequence omits deleted sections in
     * ascending order.
     *
     * For any timeline built from a valid [TrimRange] and arbitrary splits, subjected to an
     * arbitrary sequence of [SectionOps.deleteSection] calls (with no reverse and no freeze), the
     * non-freeze [PlanSegment]s of [PlaybackPlan.of] equal [SectionOps.keptSections] as
     * `(startMs, endMs)` pairs in ascending start-time order, and none of them equals any deleted
     * section or falls within a deleted range.
     *
     * **Validates: Requirements 10.5**
     */
    @Property(tries = 300)
    fun exportSequenceOmitsDeletedSectionsInAscendingOrder(
        @ForAll("timelineAndDeletions") input: Pair<VideoTimeline, List<Int>>,
    ) {
        val (initialTimeline, deletionPicks) = input

        // Apply an arbitrary sequence of section deletions: at each step pick a current kept
        // section (by index) and delete it via SectionOps.deleteSection.
        var timeline = initialTimeline
        for (pick in deletionPicks) {
            val kept = SectionOps.keptSections(timeline)
            if (kept.isEmpty()) continue
            val section = kept[Math.floorMod(pick, kept.size)]
            timeline = SectionOps.deleteSection(timeline, section)
        }

        // With no reverse and no freeze, the export sequence is the plan's played spans.
        val plan = PlaybackPlan.of(timeline)
        val exportSequence = plan.filter { !it.isFreeze }
        val keptSections = SectionOps.keptSections(timeline)

        // The export sequence equals keptSections as (startMs, endMs) pairs, in the same order.
        val exportPairs = exportSequence.map { it.startMs to it.endMs }
        val keptPairs = keptSections.map { it.startMs to it.endMs }
        assertEquals(
            keptPairs,
            exportPairs,
            "export sequence must equal keptSections as (startMs, endMs) pairs in order",
        )

        // The export sequence is in ascending start-time order (and non-overlapping).
        var previousEnd: Long? = null
        for (segment in exportSequence) {
            val prev = previousEnd
            if (prev != null) {
                assertTrue(
                    segment.startMs >= prev,
                    "export segments must be ascending and non-overlapping: " +
                        "segment $segment starts before previous end $prev",
                )
            }
            previousEnd = segment.endMs
        }

        // None of the export segments equals a deleted section, and none falls within any deleted
        // range.
        for (deleted in timeline.deletedSections) {
            for (segment in exportSequence) {
                assertFalse(
                    segment.startMs == deleted.startMs && segment.endMs == deleted.endMs,
                    "export segment $segment must not equal deleted section $deleted",
                )
                val overlaps = segment.startMs < deleted.endMs && deleted.startMs < segment.endMs
                assertFalse(
                    overlaps,
                    "export segment $segment must not fall within deleted range $deleted",
                )
            }
        }
    }

    /**
     * A valid [VideoTimeline] (whole-ms [TrimRange] at least the minimum duration with a mix of
     * inside/on-boundary/outside/duplicate candidate splits applied via [SplitOps.addSplit], no
     * reverse and no freeze), paired with a list of integer "picks" used to select which kept
     * section to delete at each step of the deletion sequence.
     */
    @Provide
    fun timelineAndDeletions(): Arbitrary<Pair<VideoTimeline, List<Int>>> {
        val starts = Arbitraries.longs().between(0L, 100_000L)
        val extraDurations = Arbitraries.longs().between(TrimRange.MIN_DURATION_MS, 50_000L)

        val ranges: Arbitrary<TrimRange> = Combinators.combine(starts, extraDurations)
            .`as` { start, extra -> TrimRange(startMs = start, endMs = start + extra) }

        return ranges.flatMap { range ->
            val candidates = Arbitraries.longs()
                .between(range.startMs - 5L, range.endMs + 5L)
            val timelines: Arbitrary<VideoTimeline> = candidates.list().ofMaxSize(12)
                .map { positions ->
                    var timeline = VideoTimeline(trim = range)
                    for (position in positions) {
                        timeline = SplitOps.addSplit(timeline, position)
                    }
                    timeline
                }
            val deletions = Arbitraries.integers().between(0, 1000).list().ofMaxSize(15)
            Combinators.combine(timelines, deletions).`as` { timeline, picks -> timeline to picks }
        }
    }
}
