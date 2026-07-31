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
 * Property-based tests for [PlaybackPlan.of], the pure ordered description of preview/export
 * playback behind the unified video editor's reverse and freeze-frame edits (Req 7.1, 7.2).
 *
 * The plan divides the kept range `[trim.startMs, trim.endMs]` at every split, marks each span
 * reversed to mirror the timeline's [VideoTimeline.reverse] flag, and inserts a single held-frame
 * entry when (and only when) a [FreezeFrame] is present, all while preserving coverage of the kept
 * range.
 */
class PlaybackPlanPropertyTest {

    // Feature: media-editor-fixes, Property 6: Playback plan honors reverse and freeze
    /**
     * Feature: media-editor-fixes, Property 6: Playback plan honors reverse and freeze.
     *
     * For any [VideoTimeline], [PlaybackPlan.of] produces ordered segments that cover the kept
     * range such that: the played (non-freeze) spans are ordered, gap-free, and span exactly
     * `[trim.startMs, trim.endMs]`; every segment is marked reversed exactly when the timeline's
     * [VideoTimeline.reverse] flag is set; and the plan contains a single freeze entry at
     * `freeze.atMs` with hold equal to `freeze.holdMs` exactly when a [FreezeFrame] is present (and
     * no freeze entry otherwise).
     *
     * **Validates: Requirements 7.1, 7.2**
     */
    @Property(tries = 300)
    fun playbackPlanHonorsReverseAndFreeze(
        @ForAll("timelines") timeline: VideoTimeline,
    ) {
        val plan = PlaybackPlan.of(timeline)

        assertTrue(plan.isNotEmpty(), "plan should be non-empty for timeline $timeline")

        // Every segment's reverse flag mirrors the timeline's reverse flag exactly.
        for (segment in plan) {
            assertEquals(
                timeline.reverse,
                segment.reversed,
                "segment $segment must be reversed exactly when timeline.reverse=${timeline.reverse}",
            )
        }

        // The played (non-freeze) spans cover the kept range: ordered, gap-free, and spanning
        // exactly [trim.startMs, trim.endMs].
        val played = plan.filter { !it.isFreeze }
        assertTrue(played.isNotEmpty(), "plan must contain at least one played span for $timeline")
        assertEquals(
            timeline.trim.startMs,
            played.first().startMs,
            "first played span starts at trim.startMs",
        )
        assertEquals(
            timeline.trim.endMs,
            played.last().endMs,
            "last played span ends at trim.endMs",
        )
        var previousEnd: Long? = null
        for (span in played) {
            assertTrue(
                span.startMs < span.endMs,
                "a played span must have startMs < endMs but was $span",
            )
            if (previousEnd != null) {
                assertEquals(
                    previousEnd,
                    span.startMs,
                    "played spans must be gap-free and non-overlapping (start equals previous end)",
                )
            }
            previousEnd = span.endMs
        }

        // A freeze entry exists exactly when a FreezeFrame is present, at freeze.atMs with the
        // requested hold; and none exists otherwise.
        val freezeEntries = plan.filter { it.isFreeze }
        val freeze = timeline.freeze
        if (freeze == null) {
            assertTrue(
                freezeEntries.isEmpty(),
                "plan must contain no freeze entry when the timeline has no freeze",
            )
        } else {
            assertEquals(
                1,
                freezeEntries.size,
                "plan must contain exactly one freeze entry when a freeze is present",
            )
            val entry = freezeEntries.single()
            assertEquals(freeze.atMs, entry.startMs, "freeze entry sits at freeze.atMs")
            assertEquals(freeze.atMs, entry.endMs, "freeze entry is a zero-length point at freeze.atMs")
            assertEquals(
                freeze.holdMs,
                entry.freezeHoldMs,
                "freeze entry holds for freeze.holdMs",
            )
        }
    }

    /**
     * Arbitrary [VideoTimeline]s over a valid [TrimRange] with a mix of split positions (inside,
     * on-boundary, outside, and duplicated), either reverse flag, and an optional [FreezeFrame]
     * whose position spans before, inside, on-boundary, and after the kept range.
     */
    @Provide
    fun timelines(): Arbitrary<VideoTimeline> {
        val starts = Arbitraries.longs().between(0L, 100_000L)
        val extraDurations = Arbitraries.longs().between(TrimRange.MIN_DURATION_MS, 50_000L)
        val ranges: Arbitrary<TrimRange> = Combinators.combine(starts, extraDurations)
            .`as` { start, extra -> TrimRange(startMs = start, endMs = start + extra) }

        return ranges.flatMap { range ->
            val splitCandidates = Arbitraries.longs()
                .between(range.startMs - 5L, range.endMs + 5L)
                .list().ofMaxSize(8)

            val reverses = Arbitraries.of(true, false)

            // Freeze positions include the boundaries and just outside them to exercise the
            // prepend/append/split-inside branches; null models "no freeze".
            val freezePositions = Arbitraries.longs().between(range.startMs - 5L, range.endMs + 5L)
            val holds = Arbitraries.longs().between(0L, 100_000L)
            val freezes: Arbitrary<FreezeFrame?> = Combinators.combine(freezePositions, holds)
                .`as` { at, hold -> FreezeFrame(atMs = at.coerceAtLeast(0L), holdMs = hold) }
                .injectNull(0.3)

            Combinators.combine(splitCandidates, reverses, freezes)
                .`as` { positions, reverse, freeze ->
                    var timeline = VideoTimeline(trim = range)
                    for (position in positions) {
                        timeline = SplitOps.addSplit(timeline, position)
                    }
                    timeline.copy(reverse = reverse, freeze = freeze)
                }
        }
    }
}
