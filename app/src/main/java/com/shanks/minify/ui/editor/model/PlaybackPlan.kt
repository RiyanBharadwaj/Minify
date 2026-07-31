package com.shanks.minify.ui.editor.model

/**
 * A single ordered step of preview/export playback over a [VideoTimeline] (Req 7.1, 7.2).
 *
 * A plan segment describes a span of the kept source range together with how it should be
 * played back:
 * - a normal span has `startMs < endMs` and [freezeHoldMs] == 0; when [reversed] is set the
 *   span is stepped backward from [endMs] toward [startMs] during playback;
 * - a freeze entry has `startMs == endMs` (a zero-length point in source time) and a positive
 *   or zero [freezeHoldMs], representing a held frame at that position for [freezeHoldMs]
 *   milliseconds.
 *
 * @param startMs      inclusive start of the span in source milliseconds (>= [endMs] never; `<=`).
 * @param endMs        exclusive end of the span in source milliseconds.
 * @param reversed     whether the span plays backward (mirrors [VideoTimeline.reverse]).
 * @param freezeHoldMs hold duration for a freeze entry; `0` for a normal span.
 */
data class PlanSegment(
    val startMs: Long,
    val endMs: Long,
    val reversed: Boolean,
    val freezeHoldMs: Long,
) {
    init {
        require(startMs <= endMs) { "PlanSegment requires startMs <= endMs but was [$startMs, $endMs)" }
        require(freezeHoldMs >= 0L) { "PlanSegment.freezeHoldMs must be >= 0 but was $freezeHoldMs" }
    }

    /**
     * True when this segment is a held-frame (freeze) entry rather than a played span.
     *
     * A freeze entry is a zero-length point in source time (`startMs == endMs`); the frame at
     * that position is held for [freezeHoldMs] milliseconds.
     */
    val isFreeze: Boolean get() = startMs == endMs
}

/**
 * Pure, Android-independent description of how a [VideoTimeline] should be played back for both
 * preview and export (Req 7.1, 7.2, 7.3).
 *
 * The plan is an ordered list of [PlanSegment]s that covers the kept range
 * `[trim.startMs, trim.endMs]`, divided at every split (via [SplitOps.segments]), with each span
 * marked [PlanSegment.reversed] to mirror the timeline's [VideoTimeline.reverse] flag and, when a
 * [FreezeFrame] is present, a single freeze entry inserted at its position holding the frame for
 * the requested duration.
 *
 * Carrying no Android dependencies, the plan is the testable core that both the preview player and
 * the exporter delegate to, keeping the two paths consistent.
 */
object PlaybackPlan {

    /**
     * Produce the ordered playback plan for [timeline].
     *
     * The result covers the *kept* range honoring splits and section deletions: without a freeze the
     * segments are exactly [SectionOps.keptSections] mapped through [PlanSegment] with
     * [PlanSegment.reversed] set to the timeline's [VideoTimeline.reverse] flag, so every section
     * recorded in [VideoTimeline.deletedSections] is omitted while the retained sections stay in
     * ascending start-time order (Req 10.5). When [VideoTimeline.freeze] is present, exactly one
     * freeze entry (a zero-length [PlanSegment] at `freeze.atMs` with `freezeHoldMs = freeze.holdMs`)
     * is inserted at the matching position; if the freeze position falls strictly inside a segment
     * that segment is split around it so coverage of the kept range is preserved.
     *
     * With no deletions [SectionOps.keptSections] equals [SplitOps.segments], so plans for timelines
     * that carry no deleted sections are unchanged.
     */
    fun of(timeline: VideoTimeline): List<PlanSegment> {
        val reversed = timeline.reverse
        val base = SectionOps.keptSections(timeline).map { segment ->
            PlanSegment(
                startMs = segment.startMs,
                endMs = segment.endMs,
                reversed = reversed,
                freezeHoldMs = 0L,
            )
        }
        val freeze = timeline.freeze ?: return base
        return insertFreeze(base, freeze, reversed, timeline.trim.startMs, timeline.trim.endMs)
    }

    /**
     * Insert exactly one freeze entry at [freeze]'s position into the ordered [segments], splitting
     * the containing segment when the freeze falls strictly inside it and clamping insertion to the
     * plan boundaries when it falls at or outside the kept range.
     */
    private fun insertFreeze(
        segments: List<PlanSegment>,
        freeze: FreezeFrame,
        reversed: Boolean,
        startMs: Long,
        endMs: Long,
    ): List<PlanSegment> {
        val atMs = freeze.atMs
        val freezeSegment = PlanSegment(
            startMs = atMs,
            endMs = atMs,
            reversed = reversed,
            freezeHoldMs = freeze.holdMs,
        )
        if (atMs <= startMs) return listOf(freezeSegment) + segments
        if (atMs >= endMs) return segments + freezeSegment

        val result = mutableListOf<PlanSegment>()
        var inserted = false
        for (segment in segments) {
            when {
                !inserted && atMs > segment.startMs && atMs < segment.endMs -> {
                    result += segment.copy(endMs = atMs)
                    result += freezeSegment
                    result += segment.copy(startMs = atMs)
                    inserted = true
                }

                !inserted && atMs == segment.startMs -> {
                    result += freezeSegment
                    result += segment
                    inserted = true
                }

                else -> result += segment
            }
        }
        if (!inserted) result += freezeSegment
        return result
    }
}
