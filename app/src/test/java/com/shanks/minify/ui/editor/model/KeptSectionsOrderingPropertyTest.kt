package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.trim.TrimRange
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [SectionOps.keptSections] after arbitrary section-deletion sequences
 * (Req 10.2, 10.4).
 *
 * A [VideoTimeline] built over a valid [TrimRange] with arbitrary splits is subjected to an
 * arbitrary sequence of [SectionOps.deleteSection] calls. Regardless of what is deleted,
 * [SectionOps.keptSections] must return sections that are ascending by `startMs`, non-overlapping,
 * and drawn only from the retained ranges produced by [SectionOps.allSections].
 */
class KeptSectionsOrderingPropertyTest {

    // Feature: media-editor-ux-fixes, Property 19: Kept sections stay ordered and non-overlapping
    /**
     * Feature: media-editor-ux-fixes, Property 19: Kept sections stay ordered and non-overlapping.
     *
     * For any timeline built from a valid [TrimRange] and arbitrary splits, and for any sequence of
     * section deletions applied via [SectionOps.deleteSection], [SectionOps.keptSections]:
     * is ordered ascending by `startMs`, is non-overlapping (each section's `startMs` is >= the
     * previous section's `endMs`), and contains only sections that appear in
     * [SectionOps.allSections] (the retained ranges bounded by the trim ends and recorded splits).
     *
     * **Validates: Requirements 10.2, 10.4**
     */
    @Property(tries = 300)
    fun keptSectionsStayOrderedNonOverlappingAndRetained(
        @ForAll("timelineAndDeletions") input: Pair<VideoTimeline, List<Int>>,
    ) {
        val (initialTimeline, deletionPicks) = input

        // Apply an arbitrary sequence of section deletions. At each step, pick a section out of the
        // current kept sections (by index) and delete it via SectionOps.deleteSection.
        var timeline = initialTimeline
        for (pick in deletionPicks) {
            val kept = SectionOps.keptSections(timeline)
            if (kept.isEmpty()) continue
            val section = kept[Math.floorMod(pick, kept.size)]
            timeline = SectionOps.deleteSection(timeline, section)
        }

        val kept = SectionOps.keptSections(timeline)
        val all = SectionOps.allSections(timeline).toSet()

        // Deletion never removes the last section: at least one always remains.
        assertTrue(kept.isNotEmpty(), "keptSections must never be empty")

        var previousEnd: Long? = null
        for (section in kept) {
            // Every kept section is a well-formed segment.
            assertTrue(
                section.startMs < section.endMs,
                "section must have startMs < endMs but was [$section]",
            )
            // Kept sections are drawn only from the retained ranges (allSections).
            assertTrue(
                section in all,
                "kept section $section must be one of the retained ranges $all",
            )
            // Ordered ascending and non-overlapping: each start is at or after the previous end.
            val prev = previousEnd
            if (prev != null) {
                assertTrue(
                    section.startMs >= prev,
                    "kept sections must be ascending and non-overlapping: " +
                        "section $section starts before previous end $prev",
                )
            }
            previousEnd = section.endMs
        }
    }

    /**
     * A valid [VideoTimeline] (whole-ms [TrimRange] with at least the 500ms minimum duration and a
     * mix of inside/on-boundary/outside/duplicate candidate splits applied via
     * [SplitOps.addSplit]), paired with a list of integer "picks" used to select which kept section
     * to delete at each step of the deletion sequence.
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
