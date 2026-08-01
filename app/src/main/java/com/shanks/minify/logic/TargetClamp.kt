package com.shanks.minify.logic

/**
 * Pure auto-adjustment logic for compression targets that are too small (or too
 * large) to produce a valid encode. Instead of rejecting the request, the caller
 * clamps the target into a viable range and proceeds with a best-effort encode.
 *
 * The minimum viable target is derived from the effective (post-trim/speed)
 * duration and the absolute minimum bitrates the encoder pipeline can use
 * (250 kbps video + 48 kbps audio). Anything below that floor would produce an
 * output the encoder cannot actually honour, so we bump the target up silently.
 */
object TargetClamp {

    const val BYTES_PER_MB = 1_048_576L

    /** Hard UI-level floor; matches SizeSelection.ABS_MIN_MB. */
    const val ABSOLUTE_MIN_MB = 0.1f

    /** Must match VideoCompressor.MIN_VIDEO_BITRATE_BPS. */
    private const val MIN_VIDEO_BPS = 250_000

    /** Must match VideoCompressor.MIN_AUDIO_BITRATE_BPS. */
    private const val MIN_AUDIO_BPS = 48_000

    /**
     * The smallest target (in MB) that can physically hold [durationSecs] of
     * video at the minimum encoder bitrates. For a 60 s clip with audio this
     * is ≈ 2.1 MB; for a 5-minute clip ≈ 10.6 MB.
     */
    fun minimumViableMb(durationSecs: Long, removeAudio: Boolean): Float {
        val minBps = MIN_VIDEO_BPS + if (removeAudio) 0 else MIN_AUDIO_BPS
        val minBytes = (minBps.toLong() * durationSecs.coerceAtLeast(1L)) / 8L
        return (minBytes / BYTES_PER_MB.toFloat()).coerceAtLeast(ABSOLUTE_MIN_MB)
    }

    /**
     * Clamps [targetSizeMb] into the range
     * `[minimumViableMb, sourceBytes / BYTES_PER_MB]`.
     *
     * - Non-finite or non-positive input is raised to [ABSOLUTE_MIN_MB] first.
     * - If the result is below the duration-derived floor it is raised to that
     *   floor (the encode would be impossible below it).
     * - If [sourceBytes] is known and positive the result is capped at the
     *   source size (compressing to *more* than the source is pointless).
     *
     * The function is total and never throws.
     */
    fun clamp(
        targetSizeMb: Float,
        sourceBytes: Long,
        durationSecs: Long,
        removeAudio: Boolean,
    ): Float {
        var result = if (!targetSizeMb.isFinite() || targetSizeMb <= 0f) ABSOLUTE_MIN_MB else targetSizeMb

        val floor = minimumViableMb(durationSecs, removeAudio)
        result = result.coerceAtLeast(floor)

        if (sourceBytes > 0L) {
            val sourceMb = sourceBytes / BYTES_PER_MB.toFloat()
            result = result.coerceAtMost(sourceMb)
        }

        return result.coerceAtLeast(ABSOLUTE_MIN_MB)
    }
}
