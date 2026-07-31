package com.shanks.minify.logic

/**
 * Pure helper for converting a raw compression progress value into a displayable
 * integer percentage.
 *
 * The compressor reports progress as a fraction in the range [0, 1] (see
 * `VideoCompressor` polling: `progressHolder.progress / 100f`). This helper turns
 * that raw fraction into an integer percentage that is always within the inclusive
 * range [0, 100], regardless of how out-of-range, non-finite (NaN/Infinity), or
 * unexpected the input is.
 *
 * The function is total: it never throws for any Float input.
 *
 * Validates Requirement 5.5 (progress displayed as a percentage between 0 and 100
 * inclusive).
 */
object ProgressClamp {

    /**
     * Converts a raw progress fraction to an integer percentage clamped to [0, 100].
     *
     * @param rawProgress the raw progress fraction, nominally in [0, 1].
     * @return an integer percentage in the inclusive range [0, 100].
     */
    fun toPercent(rawProgress: Float): Int {
        if (rawProgress.isNaN()) return 0
        val percent = (rawProgress * 100f).toInt()
        return percent.coerceIn(0, 100)
    }
}
