package com.shanks.minify.ui.trim

import kotlin.math.roundToLong

/**
 * Pure helpers for frame-accurate scrubbing in the video trimmer.
 *
 * The frame-step size is the reciprocal of the frame rate expressed in
 * milliseconds: `round(1000 / fps)`. Forward and backward stepping move the
 * current playhead position by exactly one frame while staying clamped to a
 * `[minMs, maxMs]` window.
 *
 * Because a single frame is at least ~8ms (120fps) and at most 1000ms (1fps),
 * the step size is always at least 1ms, so repeated stepping always makes
 * progress until it hits a bound.
 *
 * Validates Requirements 15.3, 15.4 (frame-step forward/back), and supports
 * Property 17 (frame-step size is the reciprocal of the frame rate and a
 * forward step followed by a backward step is a no-op within frame-step
 * granularity, clamped to bounds).
 */
object FrameStep {

    /**
     * The frame-step size in milliseconds for a given frame rate.
     *
     * Computed as `round(1000 / fps)` and never less than 1ms so that a step
     * always advances the position by a whole millisecond.
     *
     * @param fps frames per second, nominally in `[1, 120]`.
     * @return the frame duration in milliseconds, at least 1.
     */
    fun frameStepMs(fps: Float): Long {
        if (!fps.isFinite() || fps <= 0f) return 1L
        val step = (1000.0 / fps).roundToLong()
        return step.coerceAtLeast(1L)
    }

    /**
     * Advances [currentMs] by one frame, clamped to `[minMs, maxMs]`.
     *
     * @param currentMs the current position in milliseconds.
     * @param fps frames per second used to size the step.
     * @param minMs inclusive lower bound.
     * @param maxMs inclusive upper bound.
     * @return the new position clamped to `[minMs, maxMs]`.
     */
    fun stepForward(currentMs: Long, fps: Float, minMs: Long, maxMs: Long): Long {
        val step = frameStepMs(fps)
        return (currentMs + step).coerceIn(minMs, maxMs)
    }

    /**
     * Moves [currentMs] back by one frame, clamped to `[minMs, maxMs]`.
     *
     * @param currentMs the current position in milliseconds.
     * @param fps frames per second used to size the step.
     * @param minMs inclusive lower bound.
     * @param maxMs inclusive upper bound.
     * @return the new position clamped to `[minMs, maxMs]`.
     */
    fun stepBackward(currentMs: Long, fps: Float, minMs: Long, maxMs: Long): Long {
        val step = frameStepMs(fps)
        return (currentMs - step).coerceIn(minMs, maxMs)
    }
}
