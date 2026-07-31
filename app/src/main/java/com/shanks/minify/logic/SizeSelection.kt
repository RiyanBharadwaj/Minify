package com.shanks.minify.logic

/**
 * Result of validating a custom target-size entry for the sub-1 MB regime.
 *
 * - [Ok] carries a valid target size in MB, already rounded to a 0.1 MB resolution.
 * - [NotPositive] indicates the input was non-numeric or parsed to a non-positive value (Req 4.5).
 * - [BelowMinimum] indicates the input parsed to a positive value below the 0.1 MB floor (Req 4.7).
 */
sealed interface SizeResult {
    data class Ok(val mb: Float) : SizeResult
    data object NotPositive : SizeResult
    data object BelowMinimum : SizeResult
}

/**
 * Pure size-picker logic for small (sub-1 MB) compression targets.
 *
 * All functions are total and never throw. Validates Requirements 4.1–4.7.
 */
object SizeSelection {
    /** Minimum selectable target size in MB (Req 4.6). */
    const val ABS_MIN_MB = 0.1f

    /**
     * Snap a raw MB value to the nearest 0.1 MB (Req 4.3).
     *
     * The result is always a multiple of 0.1 within 0.05 MB of [mb].
     */
    fun roundToTenth(mb: Float): Float {
        val snapped = Math.round(mb * 10f) / 10f
        return snapped
    }

    /**
     * The sub-1 MB preset ladder: 0.1, 0.2, ... 0.9 MB in exact 0.1 MB steps (Req 4.1/4.2/4.6).
     */
    fun smallTargetPresets(): List<Float> =
        (1..9).map { roundToTenth(it / 10f) }

    /**
     * Validate a custom target-size entry (Req 4.4/4.5/4.7). Never throws.
     *
     * - Non-numeric or non-positive input -> [SizeResult.NotPositive].
     * - Positive value below 0.1 MB -> [SizeResult.BelowMinimum].
     * - Value in [0.1, <1) MB -> [SizeResult.Ok] rounded to a 0.1 MB resolution.
     */
    fun validateCustom(raw: String): SizeResult {
        val value = raw.trim().toFloatOrNull()
            ?: return SizeResult.NotPositive
        if (value.isNaN() || value <= 0f) return SizeResult.NotPositive
        if (value < ABS_MIN_MB) return SizeResult.BelowMinimum
        return SizeResult.Ok(roundToTenth(value))
    }
}
