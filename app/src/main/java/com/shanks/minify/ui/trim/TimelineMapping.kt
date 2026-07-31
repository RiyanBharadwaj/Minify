package com.shanks.minify.ui.trim

import kotlin.math.roundToLong

/**
 * Pure time <-> pixel <-> fraction mapping for the Video_Trimmer timeline.
 *
 * All of the timeline's geometric decisions (thumbnail positioning, ruler ticks,
 * trim-handle hit testing, and zoom) are expressed here so they can be unit- and
 * property-tested on the JVM with no Android, Compose, or Media3 dependency.
 *
 * The mapping is parameterised by:
 *  - [durationMs] — the video's total duration in milliseconds (`> 0` for a usable timeline).
 *  - [pxPerMs] — the current time-to-pixel scale (larger == zoomed in). Always kept positive.
 *  - [scrollPx] — the horizontal scroll offset of the visible viewport, in content pixels.
 *
 * Content coordinates run from `0` (time 0) to [contentWidthPx] (time [durationMs]).
 * [timeToPx] returns a *screen* position (content position minus [scrollPx]) and
 * [pxToTime] is its inverse, so a thumbnail and a ruler tick for the same time land at
 * the same pixel at every zoom scale.
 *
 * Validates Requirements 13.2, 16.1, 16.2, 16.3, 16.5, 18.2.
 */
data class TimelineMapping(
    val durationMs: Long,
    val pxPerMs: Float,
    val scrollPx: Float,
) {

    /**
     * Normalized position of [timeMs] within the timeline: `timeMs / durationMs`, clamped
     * to `[0, 1]`. Used to position thumbnails independently of the current zoom (Req 13.2).
     * Returns `0` for a non-positive [durationMs].
     */
    fun fractionOf(timeMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (timeMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Screen (viewport-relative) x pixel for [timeMs]: the content position `timeMs * pxPerMs`
     * shifted by the current [scrollPx]. Both thumbnails and ruler ticks use this so they stay
     * aligned at any zoom scale.
     */
    fun timeToPx(timeMs: Long): Float = timeMs * pxPerMs - scrollPx

    /**
     * Inverse of [timeToPx]: the whole-millisecond time under screen pixel [px], clamped to
     * `[0, durationMs]`. Drag handling resolves pointer positions to whole milliseconds here
     * before applying trim-range invariants.
     */
    fun pxToTime(px: Float): Long {
        if (pxPerMs <= 0f) return 0L
        val timeMs = ((px + scrollPx) / pxPerMs).roundToLong()
        return timeMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
    }

    /** Total width of the timeline content in pixels: `durationMs * pxPerMs`. */
    fun contentWidthPx(): Float = durationMs * pxPerMs

    /**
     * Returns a copy of this mapping with [pxPerMs] scaled by [factor], keeping the scale
     * strictly positive. A [factor] `> 1` zooms in (larger scale), a [factor] in `(0, 1)`
     * zooms out (smaller scale). Non-finite or non-positive results fall back to
     * [MIN_PX_PER_MS] so the mapping is never degenerate. The trim range is unaffected.
     */
    fun zoomBy(factor: Float): TimelineMapping {
        val scaled = pxPerMs * factor
        val next = if (scaled.isFinite() && scaled > 0f) scaled else MIN_PX_PER_MS
        return copy(pxPerMs = next.coerceAtLeast(MIN_PX_PER_MS))
    }

    companion object {
        /** Smallest allowed time-to-pixel scale; keeps [pxPerMs] strictly positive. */
        const val MIN_PX_PER_MS = 1e-6f
    }
}

/**
 * Pure sampling of thumbnail time positions for the timeline filmstrip.
 *
 * The count is capped so thumbnail memory stays bounded on long videos (Req 18.2), and
 * the returned times are the ascending set of frame positions fed to the Android
 * `MediaMetadataRetriever` glue.
 */
object TimelineSampling {

    /**
     * Returns at most [maxThumbs] ascending, distinct millisecond positions within
     * `[0, durationMs]` at which to extract thumbnails.
     *
     * The samples are evenly spaced and span the full duration: the first is always `0`
     * and, when more than one sample is produced, the last is [durationMs]. Returns an
     * empty list when [durationMs] `<= 0` or [maxThumbs] `<= 0`.
     */
    fun sampleTimes(durationMs: Long, maxThumbs: Int): List<Long> {
        if (durationMs <= 0L || maxThumbs <= 0) return emptyList()
        if (maxThumbs == 1) return listOf(0L)

        val count = maxThumbs
        val times = ArrayList<Long>(count)
        for (i in 0 until count) {
            val t = (i.toDouble() * durationMs / (count - 1)).roundToLong()
            times.add(t.coerceIn(0L, durationMs))
        }
        // Even spacing can collide when durationMs < count-1; keep the set ascending and distinct.
        return times.distinct()
    }
}
