package com.shanks.minify.ui.trim

import com.shanks.minify.ui.CropRect
import com.shanks.minify.ui.EditState

/**
 * Pure trim-range value type for the Video_Trimmer.
 *
 * A [TrimRange] describes the kept segment of a video in whole milliseconds. It carries no
 * Android dependencies so its logic can be property-tested on the JVM.
 *
 * @param startMs start of the kept segment in milliseconds.
 * @param endMs   end of the kept segment in milliseconds.
 */
data class TrimRange(val startMs: Long, val endMs: Long) {
    /** Selected duration in milliseconds. */
    val durationMs: Long get() = endMs - startMs

    companion object {
        /** Minimum selected duration the trimmer will allow (Req 14.5, 18.7). */
        const val MIN_DURATION_MS = 500L
    }
}

/**
 * Pure operations over [TrimRange].
 *
 * All operations preserve the invariants `startMs < endMs` and `durationMs >= MIN_DURATION_MS`
 * given a video whose full duration is at least [TrimRange.MIN_DURATION_MS].
 */
object TrimRangeOps {

    /**
     * Moves the start handle toward [targetMs].
     *
     * The resulting start is clamped to `[0, durationMs]` and further constrained so that it
     * stays strictly below the end while preserving the 500ms minimum selected duration
     * (Req 14.3, 14.5).
     */
    fun moveStart(range: TrimRange, targetMs: Long, durationMs: Long): TrimRange {
        val maxStart = (range.endMs - TrimRange.MIN_DURATION_MS).coerceIn(0L, durationMs)
        val newStart = targetMs.coerceIn(0L, maxStart)
        return range.copy(startMs = newStart)
    }

    /**
     * Moves the end handle toward [targetMs].
     *
     * The resulting end is clamped to `[0, durationMs]` and further constrained so that it stays
     * strictly above the start while preserving the 500ms minimum selected duration
     * (Req 14.3, 14.5).
     */
    fun moveEnd(range: TrimRange, targetMs: Long, durationMs: Long): TrimRange {
        val minEnd = (range.startMs + TrimRange.MIN_DURATION_MS).coerceIn(0L, durationMs)
        val newEnd = targetMs.coerceIn(minEnd, durationMs)
        return range.copy(endMs = newEnd)
    }

    /**
     * Maps a [TrimRange] to an [EditState] for the Video_Compression_Pipeline.
     *
     * A range that spans the full duration from zero (`startMs == 0 && endMs == fullDurationMs`)
     * is represented as "no trim" (`trimStartMs = 0`, `trimEndMs = null`); any other range carries
     * its exact start/end. The optional [crop] is preserved in [EditState.cropRect]; the optional
     * [splits] are preserved in [EditState.splits]. When omitted (or null/empty) the resulting
     * state carries no crop/splits, keeping existing callers backward compatible
     * (Req 7.5, 14.2, 17.1, 17.3, 17.4).
     */
    fun toEditState(
        range: TrimRange,
        fullDurationMs: Long,
        crop: CropRect? = null,
        splits: List<Long> = emptyList()
    ): EditState {
        return if (range.startMs == 0L && range.endMs == fullDurationMs) {
            EditState(trimStartMs = 0L, trimEndMs = null, cropRect = crop, splits = splits)
        } else {
            EditState(trimStartMs = range.startMs, trimEndMs = range.endMs, cropRect = crop, splits = splits)
        }
    }

    /**
     * Whether the range is long enough to confirm (Req 18.7).
     */
    fun isConfirmable(range: TrimRange): Boolean = range.durationMs >= TrimRange.MIN_DURATION_MS
}
