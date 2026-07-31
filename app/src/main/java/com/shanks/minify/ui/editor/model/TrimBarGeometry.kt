package com.shanks.minify.ui.editor.model

/**
 * Pure, NaN-safe geometry helper for the video trim bar.
 *
 * The `TrimBar` positions its start/end handles and the playhead by dividing a
 * position (in milliseconds) by the total media duration. When a video reports a
 * zero or unknown duration, a naive `position / duration` division produces a
 * not-a-number (or infinite) fraction that corrupts layout (Req 13.1).
 *
 * This helper centralizes that division so every consumer gets a fraction that is:
 * - `0f` when `durationMs <= 0` (zero/unknown duration),
 * - always finite (never `NaN` or `Infinity`), and
 * - clamped to the inclusive range `[0, 1]`.
 *
 * The function is **total**: it never throws for any `Long` input.
 *
 * Validates Requirement 13.1 (trim bar renders valid, bounded positions rather
 * than a not-a-number value when the media duration is zero).
 */
object TrimBarGeometry {

    /**
     * Converts a [positionMs] within a total [durationMs] into a bounded fraction.
     *
     * @param positionMs the position along the timeline, in milliseconds.
     * @param durationMs the total media duration, in milliseconds.
     * @return a fraction in the inclusive range `[0, 1]`; `0f` when
     *   [durationMs] is non-positive. Always finite, never `NaN`/`Infinity`.
     */
    fun fraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        val raw = positionMs.toDouble() / durationMs.toDouble()
        if (raw.isNaN()) return 0f
        return raw.toFloat().coerceIn(0f, 1f)
    }
}
