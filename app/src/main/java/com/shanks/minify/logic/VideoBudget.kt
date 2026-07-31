package com.shanks.minify.logic

/**
 * The output encoding budget computed for a single video compression.
 *
 * All values are expressed in the units the Media3 encoder expects (bits/sec for
 * bitrates, pixels for dimensions, frames/sec for fps). Dimensions are guaranteed
 * (by [BitrateBudget.compute]) to be positive even multiples of 16 no larger than
 * the corresponding source dimension.
 *
 * @param videoBitrateBps target video bitrate, clamped to 400_000..100_000_000 (Req 7.3).
 * @param audioBitrateBps reserved audio bitrate; 64..192 kbps, capped at 40% of the
 *   size budget so it may fall below 64 kbps when the cap binds (Req 7.2).
 * @param outputWidth encoder output width, a positive multiple of 16 no larger than source (Req 7.8).
 * @param outputHeight encoder output height, a positive multiple of 16 no larger than source (Req 7.8).
 * @param outputFps encoder output frame rate, possibly reduced from source.
 */
data class VideoBudget(
    val videoBitrateBps: Int,
    val audioBitrateBps: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val outputFps: Float,
)

/**
 * Result of a size-budget calculation. Replaces the old `require(...)`/throw path so
 * that invalid inputs (e.g. dimensions that cannot be rendered as a positive even
 * multiple of 16) are reported as a value rather than crashing the process (Req 2.9).
 */
sealed interface BudgetResult {
    /** A usable budget was computed. */
    data class Valid(val budget: VideoBudget) : BudgetResult

    /** The inputs cannot produce a valid budget; [reason] is a human-readable cause. */
    data class Invalid(val reason: String) : BudgetResult
}
