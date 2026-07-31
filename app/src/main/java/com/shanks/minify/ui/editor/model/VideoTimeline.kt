package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.trim.TrimRange

/**
 * A single contiguous span of the kept video range in whole milliseconds.
 *
 * A [Segment] always satisfies `startMs < endMs`.
 */
data class Segment(val startMs: Long, val endMs: Long) {
    init {
        require(startMs < endMs) { "Segment requires startMs < endMs but was [$startMs, $endMs)" }
    }

    /** Duration of the segment in milliseconds. */
    val durationMs: Long get() = endMs - startMs
}

/**
 * The bounded set of output playback-rate multipliers offered by the editor (Req 8.1).
 */
enum class PlaybackSpeed(val multiplier: Float) {
    X0_25(0.25f),
    X0_5(0.5f),
    X1(1f),
    X2(2f),
    X4(4f),
}

/**
 * A held-frame effect: hold the frame at [atMs] for [holdMs] milliseconds (Req 9.4).
 *
 * @param atMs   position of the frame to freeze, in milliseconds (>= 0).
 * @param holdMs how long the frame is held, in milliseconds (>= 0).
 */
data class FreezeFrame(val atMs: Long, val holdMs: Long) {
    init {
        require(atMs >= 0L) { "FreezeFrame.atMs must be >= 0 but was $atMs" }
        require(holdMs >= 0L) { "FreezeFrame.holdMs must be >= 0 but was $holdMs" }
    }
}

/**
 * Pure, Android-independent model of the video timeline edits (Req 7, 8, 9).
 *
 * Composes the reused [TrimRange] (the kept range) with split positions, playback speed, audio
 * volume/mute, and the optional reverse and freeze-frame effects. Carries no Android dependencies
 * so its logic can be property-tested on the JVM.
 *
 * @param trim    the kept range of the video.
 * @param splits  ascending, distinct split positions strictly inside the kept range.
 * @param speed   the output playback-rate multiplier (Req 8.1, 8.2).
 * @param volume  the output audio level, clamped to `[0, MAX_VOLUME]` (Req 8.3).
 * @param muted   whether the output audio is silenced (Req 8.4, 8.5).
 * @param reverse whether the kept range plays backward (optional, Req 9.2).
 * @param freeze  an optional freeze-frame effect (optional, Req 9.4).
 * @param deletedSections sections marked for deletion, omitted from the exported output (Req 10).
 */
data class VideoTimeline(
    val trim: TrimRange,
    val splits: List<Long> = emptyList(),
    val speed: PlaybackSpeed = PlaybackSpeed.X1,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val reverse: Boolean = false,
    val freeze: FreezeFrame? = null,
    val deletedSections: List<Segment> = emptyList(),
) {
    /** Whether the timeline carries no edits beyond a full-range, default-audio trim. */
    val isNoEdit: Boolean
        get() = splits.isEmpty() &&
            speed == PlaybackSpeed.X1 &&
            volume == 1f &&
            !muted &&
            !reverse &&
            freeze == null &&
            deletedSections.isEmpty()

    /**
     * Records a new [volume], clamped to `[0, MAX_VOLUME]`.
     *
     * The [muted] state is retained across volume changes: changing the volume does not implicitly
     * mute or unmute the output (Req 8.5).
     */
    fun withVolume(raw: Float): VideoTimeline = copy(volume = raw.coerceIn(0f, MAX_VOLUME))

    /**
     * Records the [muted] state (Req 8.4). The [volume] value is retained unchanged so that
     * un-muting restores the previously set level.
     */
    fun withMuted(value: Boolean): VideoTimeline = copy(muted = value)

    /** Records the selected playback [speed] (Req 8.2). */
    fun withSpeed(value: PlaybackSpeed): VideoTimeline = copy(speed = value)

    /** Records whether the kept range plays backward (Req 9.2). */
    fun withReverse(value: Boolean): VideoTimeline = copy(reverse = value)

    /** Records an optional freeze-frame effect (Req 9.4). */
    fun withFreeze(value: FreezeFrame?): VideoTimeline = copy(freeze = value)

    companion object {
        /** Maximum audio-level multiplier the editor allows (Req 8.3). */
        const val MAX_VOLUME = 2f
    }
}

/**
 * Pure operations over a [VideoTimeline]'s split positions (Req 7.4).
 */
object SplitOps {

    /**
     * Inserts a split at [positionMs].
     *
     * The split is accepted only when it satisfies [SectionOps.canSplitAt]: it lies strictly inside
     * the kept range (`trim.startMs < positionMs < trim.endMs`), is at least
     * [SectionOps.MIN_SPLIT_GAP_MS] milliseconds from both boundaries and from every existing split,
     * and is not already present. The resulting [VideoTimeline.splits] list is ascending and
     * distinct. Any position that violates the min-gap guard, lands on or outside the boundaries, or
     * duplicates an existing split leaves the timeline unchanged (Req 10.1, 10.8).
     */
    fun addSplit(timeline: VideoTimeline, positionMs: Long): VideoTimeline {
        if (!SectionOps.canSplitAt(timeline, positionMs)) return timeline
        val updated = (timeline.splits + positionMs).sorted()
        return timeline.copy(splits = updated)
    }

    /**
     * Produces the ordered, gap-free, non-overlapping [Segment]s that cover the kept range
     * `[trim.startMs, trim.endMs]`, divided at every split strictly inside the range (Req 7.4).
     *
     * The segments are returned in ascending order; each segment's `endMs` equals the next
     * segment's `startMs` (gap-free and non-overlapping); the first segment starts at
     * `trim.startMs` and the last ends at `trim.endMs`.
     */
    fun segments(timeline: VideoTimeline): List<Segment> {
        val start = timeline.trim.startMs
        val end = timeline.trim.endMs
        val boundaries = buildList {
            add(start)
            timeline.splits
                .asSequence()
                .filter { it > start && it < end }
                .distinct()
                .sorted()
                .forEach { add(it) }
            add(end)
        }
        return boundaries.zipWithNext { a, b -> Segment(a, b) }
    }
}

/**
 * Pure operations over a [VideoTimeline]'s sections: split-gap validation, section derivation, and
 * section deletion (Req 10). Extends the responsibilities of [SplitOps] with the deletion model.
 *
 * All functions are total: degenerate inputs are treated as disallowed and yield a no-op rather
 * than throwing, matching the totality convention of the other pure models.
 */
object SectionOps {

    /** Minimum gap, in milliseconds, a split must keep from every boundary and existing split. */
    const val MIN_SPLIT_GAP_MS = 100L

    /**
     * True when a split at [positionMs] is allowed: strictly inside the kept range
     * (`trim.startMs < positionMs < trim.endMs`), at least [MIN_SPLIT_GAP_MS] milliseconds from both
     * boundaries and from every existing split, and not already present (Req 10.1, 10.8).
     */
    fun canSplitAt(timeline: VideoTimeline, positionMs: Long): Boolean {
        val start = timeline.trim.startMs
        val end = timeline.trim.endMs
        if (positionMs <= start || positionMs >= end) return false
        if (positionMs - start < MIN_SPLIT_GAP_MS) return false
        if (end - positionMs < MIN_SPLIT_GAP_MS) return false
        if (timeline.splits.contains(positionMs)) return false
        return timeline.splits.none { kotlin.math.abs(positionMs - it) < MIN_SPLIT_GAP_MS }
    }

    /**
     * All sections derived from the trim range and its splits, ascending and non-overlapping.
     *
     * Delegates to [SplitOps.segments], so the first section starts at `trim.startMs`, the last ends
     * at `trim.endMs`, and each section's `endMs` equals the next section's `startMs` (Req 10.2).
     */
    fun allSections(timeline: VideoTimeline): List<Segment> = SplitOps.segments(timeline)

    /**
     * The sections not marked deleted, in ascending start order and non-overlapping (Req 10.2,
     * 10.4). Returns [allSections] minus the entries recorded in [VideoTimeline.deletedSections].
     */
    fun keptSections(timeline: VideoTimeline): List<Segment> {
        val deleted = timeline.deletedSections.toSet()
        return allSections(timeline).filter { it !in deleted }
    }

    /**
     * Marks [section] deleted so it is omitted from the exported output (Req 10.3, 10.5).
     *
     * A no-op when [section] is not a current kept section or when deleting it would leave zero kept
     * sections; deletion is only recorded while at least two kept sections exist (Req 10.6).
     */
    fun deleteSection(timeline: VideoTimeline, section: Segment): VideoTimeline {
        val kept = keptSections(timeline)
        if (section !in kept) return timeline
        if (kept.size <= 1) return timeline
        return timeline.copy(deletedSections = timeline.deletedSections + section)
    }
}
