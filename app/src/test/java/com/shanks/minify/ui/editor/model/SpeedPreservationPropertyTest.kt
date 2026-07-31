package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.trim.TrimRange
import com.shanks.minify.ui.trim.TrimRangeOps
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Property-based tests asserting that timeline structural edits never alter the recorded playback
 * speed (Req 8.6).
 *
 * A [VideoTimeline] built over a valid [TrimRange] with an arbitrary recorded [PlaybackSpeed] is
 * subjected to an arbitrary sequence of trim, split, and section-delete edits. Regardless of the
 * edits applied, [VideoTimeline.speed] must equal the originally recorded speed.
 */
class SpeedPreservationPropertyTest {

    /**
     * The kinds of structural timeline edits exercised by the property. Each descriptor carries the
     * parameters for one edit; how it is applied is decided in [applyEdit].
     */
    sealed interface Edit {
        /** Move the start handle toward [targetMs] (a trim edit). */
        data class TrimStart(val targetMs: Long) : Edit

        /** Move the end handle toward [targetMs] (a trim edit). */
        data class TrimEnd(val targetMs: Long) : Edit

        /** Insert a split at [positionMs]. */
        data class Split(val positionMs: Long) : Edit

        /** Delete the kept section selected by [pick] (modulo the kept-section count). */
        data class DeleteSection(val pick: Int) : Edit
    }

    // Feature: media-editor-ux-fixes, Property 15: Timeline edits preserve the recorded playback speed
    /**
     * Feature: media-editor-ux-fixes, Property 15: Timeline edits preserve the recorded playback
     * speed.
     *
     * For any timeline built from a valid [TrimRange] with an arbitrary recorded [PlaybackSpeed],
     * and for any sequence of trim, split, and section-delete edits, the recorded
     * [VideoTimeline.speed] is unchanged after the edits.
     *
     * **Validates: Requirements 8.6**
     */
    @Property(tries = 300)
    fun timelineEditsPreserveRecordedPlaybackSpeed(
        @ForAll("timelineWithEdits") input: TimelineWithEdits,
    ) {
        val originalSpeed = input.timeline.speed

        var timeline = input.timeline
        for (edit in input.edits) {
            timeline = applyEdit(timeline, edit, input.fullDurationMs)
        }

        assertEquals(
            originalSpeed,
            timeline.speed,
            "trim/split/section-delete edits must leave the recorded PlaybackSpeed unchanged",
        )
    }

    private fun applyEdit(timeline: VideoTimeline, edit: Edit, fullDurationMs: Long): VideoTimeline =
        when (edit) {
            is Edit.TrimStart ->
                timeline.copy(trim = TrimRangeOps.moveStart(timeline.trim, edit.targetMs, fullDurationMs))
            is Edit.TrimEnd ->
                timeline.copy(trim = TrimRangeOps.moveEnd(timeline.trim, edit.targetMs, fullDurationMs))
            is Edit.Split ->
                SplitOps.addSplit(timeline, edit.positionMs)
            is Edit.DeleteSection -> {
                val kept = SectionOps.keptSections(timeline)
                if (kept.isEmpty()) {
                    timeline
                } else {
                    SectionOps.deleteSection(timeline, kept[Math.floorMod(edit.pick, kept.size)])
                }
            }
        }

    /** A valid timeline with an arbitrary recorded speed, its full duration, and a list of edits. */
    data class TimelineWithEdits(
        val timeline: VideoTimeline,
        val fullDurationMs: Long,
        val edits: List<Edit>,
    )

    /**
     * A [VideoTimeline] over a valid whole-ms [TrimRange] (at least the 500ms minimum duration and
     * within the generated full duration) carrying an arbitrary recorded [PlaybackSpeed], paired
     * with an arbitrary sequence of trim, split, and section-delete edits. Split/trim targets are
     * drawn from a window that overlaps the boundaries so both accepted and rejected edits are
     * exercised.
     */
    @Provide
    fun timelineWithEdits(): Arbitrary<TimelineWithEdits> {
        val fullDurations = Arbitraries.longs().between(TrimRange.MIN_DURATION_MS * 2, 200_000L)

        return fullDurations.flatMap { fullDuration ->
            val starts = Arbitraries.longs().between(0L, fullDuration - TrimRange.MIN_DURATION_MS)
            starts.flatMap { start ->
                val ends = Arbitraries.longs().between(start + TrimRange.MIN_DURATION_MS, fullDuration)
                ends.flatMap { end ->
                    val range = TrimRange(startMs = start, endMs = end)
                    val speeds = Arbitraries.of(*PlaybackSpeed.values())
                    val editArb = editArbitrary(range, fullDuration)
                    Combinators.combine(speeds, editArb.list().ofMaxSize(20))
                        .`as` { speed, edits ->
                            TimelineWithEdits(
                                timeline = VideoTimeline(trim = range, speed = speed),
                                fullDurationMs = fullDuration,
                                edits = edits,
                            )
                        }
                }
            }
        }
    }

    private fun editArbitrary(range: TrimRange, fullDurationMs: Long): Arbitrary<Edit> {
        val positions = Arbitraries.longs().between(range.startMs - 5L, range.endMs + 5L)
        val trimTargets = Arbitraries.longs().between(-5L, fullDurationMs + 5L)
        val picks = Arbitraries.integers().between(0, 1000)

        val trimStarts: Arbitrary<Edit> = trimTargets.map { Edit.TrimStart(it) }
        val trimEnds: Arbitrary<Edit> = trimTargets.map { Edit.TrimEnd(it) }
        val splits: Arbitrary<Edit> = positions.map { Edit.Split(it) }
        val deletes: Arbitrary<Edit> = picks.map { Edit.DeleteSection(it) }

        return Arbitraries.oneOf<Edit>(trimStarts, trimEnds, splits, deletes)
    }
}
