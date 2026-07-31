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
 * Property-based tests for [SectionOps.deleteSection], the pure section-deletion model behind the
 * Unified_Media_Editor's timeline sections (Req 10.3, 10.6).
 *
 * A [VideoTimeline] built over a valid [TrimRange] with arbitrary splits exposes a set of kept
 * sections. Deleting a currently kept section removes exactly that section while at least two kept
 * sections exist, and is a no-op once only one kept section remains, so the kept set never becomes
 * empty.
 */
class SectionDeletionInvariantPropertyTest {

    // Feature: media-editor-ux-fixes, Property 20: Section deletion removes the selected section but never the last one
    /**
     * Feature: media-editor-ux-fixes, Property 20: Section deletion removes the selected section but
     * never the last one.
     *
     * For any timeline built from a valid [TrimRange] and arbitrary splits, and for any currently
     * kept section chosen for deletion via [SectionOps.deleteSection]:
     * when at least two kept sections exist, the result's kept sections equal the prior kept
     * sections minus exactly the chosen section (the chosen section is gone, all others are
     * retained, and the count drops by exactly one); when exactly one kept section exists, deletion
     * is a no-op and the kept sections are unchanged. In all cases the kept set is never empty.
     *
     * **Validates: Requirements 10.3, 10.6**
     */
    @Property(tries = 300)
    fun sectionDeletionRemovesSelectedButNeverTheLast(
        @ForAll("timelineAndPick") input: Pair<VideoTimeline, Int>,
    ) {
        val (timeline, pick) = input

        val keptBefore = SectionOps.keptSections(timeline)
        // The generator guarantees at least one kept section exists.
        assertTrue(keptBefore.isNotEmpty(), "precondition: keptSections must be non-empty")

        val target = keptBefore[Math.floorMod(pick, keptBefore.size)]
        val result = SectionOps.deleteSection(timeline, target)
        val keptAfter = SectionOps.keptSections(result)

        // Deletion never leaves zero kept sections (Req 10.6).
        assertTrue(keptAfter.isNotEmpty(), "keptSections must never be empty after deletion")

        if (keptBefore.size >= 2) {
            // Exactly the selected section is removed; all others are retained (Req 10.3).
            assertEquals(
                keptBefore.size - 1,
                keptAfter.size,
                "deletion of a kept section (>=2 kept) must drop the count by exactly one",
            )
            assertTrue(
                target !in keptAfter,
                "the deleted section $target must no longer be a kept section",
            )
            assertEquals(
                keptBefore.filter { it != target },
                keptAfter,
                "kept sections after deletion must equal the prior kept sections minus the target",
            )
        } else {
            // Exactly one kept section: deletion is a no-op (Req 10.6).
            assertEquals(
                keptBefore,
                keptAfter,
                "deleting the only kept section must be a no-op",
            )
        }
    }

    /**
     * A valid [VideoTimeline] (whole-ms [TrimRange] with at least the 500ms minimum duration and a
     * mix of inside/on-boundary/outside/duplicate candidate splits applied via [SplitOps.addSplit])
     * paired with an integer "pick" used to select which currently kept section to delete.
     *
     * Because [SplitOps.addSplit] always retains a non-empty kept range and no deletions are
     * pre-applied, [SectionOps.keptSections] is guaranteed non-empty; the split candidates yield a
     * mix of single-section and multi-section timelines so both branches of the invariant are
     * exercised.
     */
    @Provide
    fun timelineAndPick(): Arbitrary<Pair<VideoTimeline, Int>> {
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
            val picks = Arbitraries.integers().between(0, 1000)
            Combinators.combine(timelines, picks).`as` { timeline, pick -> timeline to pick }
        }
    }
}
