package com.shanks.minify.logic

import com.shanks.minify.ui.CodecChoice
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure size/quality budgeting for video compression. Extracted from the old
 * `VideoCompressor.computeParams` so it can be unit- and property-tested on the JVM
 * with no Android or Media3 dependency, and so it returns a [BudgetResult] instead of
 * throwing on bad input (Req 2.9).
 *
 * Size math uses 1 MB = 1,048,576 bytes, i.e. [BITS_PER_MB] bits per MB, consistent
 * with the rest of the app.
 *
 * ### Monotonicity (Req 7.4)
 * The video bitrate is a non-decreasing function of the target size *by construction*.
 * The audio reservation is a fixed nominal bitrate capped at [MAX_AUDIO_SHARE] of the
 * (headroom-adjusted) target budget, so the video budget is either `0.6 * targetBits`
 * (when the cap binds) or `targetBits - audioConst` (when it does not). Both branches
 * are non-decreasing in `targetBits` and they meet continuously at the crossover, so
 * dividing by the fixed duration and clamping to a fixed band preserves monotonicity.
 * A *tiered* audio bitrate (used by the original code) would break this, because an
 * upward audio jump at a tier boundary can shrink the video budget as the target grows.
 */
object BitrateBudget {

    /** Reserve 3% for container/muxing overhead so the muxed file lands within target (Req 7.7). */
    const val HEADROOM = 0.97f

    /** 1 MB = 1,048,576 bytes = 8,388,608 bits. */
    const val BITS_PER_MB = 8_388_608L

    private const val AUDIO_MIN_KBPS = 64
    private const val AUDIO_MAX_KBPS = 192

    /**
     * Fixed nominal audio bitrate. Held constant (not tiered by size) so the video
     * budget stays monotone in the target size — see the class-level note.
     */
    private const val AUDIO_NOMINAL_KBPS = 128

    /** Audio may claim at most 40% of the size budget (Req 7.2). */
    private const val MAX_AUDIO_SHARE = 0.4f

    /** Video bitrate clamp band (Req 7.3). */
    const val MIN_VIDEO_BPS = 400_000
    const val MAX_VIDEO_BPS = 100_000_000

    private const val MIN_HIGH_FPS_TARGET_BPS = 2_500_000
    private const val REDUCED_FPS = 30f

    /**
     * Computes the output budget for [targetSizeMb], mirroring the argument shape of the
     * old `computeParams`. Returns [BudgetResult.Invalid] instead of throwing when the
     * source parameters or computed dimensions cannot yield a valid output.
     *
     * @param targetSizeMb requested maximum output size in MB (1 MB = 1,048,576 bytes).
     * @param durationSecs effective output duration in seconds (post-trim); may be <= 0
     *   when metadata is unavailable, in which case a source-bitrate-derived fallback is used.
     * @param srcBitrateKbps source video bitrate in kbps, used only for the no-duration fallback.
     * @param srcWidth source (or cropped) width in pixels.
     * @param srcHeight source (or cropped) height in pixels.
     * @param frameRate source frame rate.
     * @param codecChoice selected output codec (drives the bits-per-pixel target).
     * @param headroom fraction of the target to actually budget for (defaults to [HEADROOM]).
     */
    fun compute(
        targetSizeMb: Float,
        durationSecs: Long,
        srcBitrateKbps: Int,
        srcWidth: Int,
        srcHeight: Int,
        frameRate: Float,
        codecChoice: CodecChoice,
        headroom: Float = HEADROOM,
        removeAudio: Boolean = false,
    ): BudgetResult {
        if (targetSizeMb <= 0f) {
            return BudgetResult.Invalid("Target size must be positive, got $targetSizeMb MB")
        }
        if (srcWidth <= 0 || srcHeight <= 0) {
            return BudgetResult.Invalid("Source dimensions must be positive, got ${srcWidth}x$srcHeight")
        }

        val safeHeadroom = headroom.coerceIn(0f, 1f)
        // Bits available after reserving muxing headroom (Req 7.7).
        val targetBits = (targetSizeMb * BITS_PER_MB * safeHeadroom).toLong().coerceAtLeast(1L)

        // --- Audio reservation (Req 7.2): fixed nominal bitrate, capped at 40% of budget. ---
        val nominalAudioBudget =
            if (durationSecs > 0 && !removeAudio) AUDIO_NOMINAL_KBPS * 1000L * durationSecs else 0L
        val maxAudioBudget = (targetBits * MAX_AUDIO_SHARE).toLong().coerceAtLeast(0L)
        val effectiveAudioBudget = nominalAudioBudget.coerceAtMost(maxAudioBudget)
        val audioBitrateBps = if (durationSecs > 0 && !removeAudio) {
            // May fall below AUDIO_MIN_KBPS when the 40% cap binds — allowed by Req 7.2.
            (effectiveAudioBudget / durationSecs).toInt().coerceAtMost(AUDIO_MAX_KBPS * 1000)
        } else if (removeAudio) {
            0
        } else {
            (AUDIO_NOMINAL_KBPS * 1000).coerceIn(AUDIO_MIN_KBPS * 1000, AUDIO_MAX_KBPS * 1000)
        }

        // --- Video budget: everything the audio didn't take. Non-decreasing in target. ---
        val videoBudgetBits = (targetBits - effectiveAudioBudget).coerceAtLeast(0L)
        val videoBitrateRaw = if (durationSecs > 0) {
            (videoBudgetBits / durationSecs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            // No duration: scale off the source bitrate so the result at least tracks the
            // source's own demand. Independent of target size, hence trivially monotone.
            (srcBitrateKbps.toLong() * 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
        // Highest bitrate that fits the budget, clamped to the supported band (Req 7.3).
        val videoBitrateBps = videoBitrateRaw.coerceIn(MIN_VIDEO_BPS, MAX_VIDEO_BPS)

        val outFps = pickOutputFrameRate(frameRate, videoBitrateBps)

        // --- Output dimensions: positive even multiples of 16, no larger than source (Req 7.8). ---
        val targetBpp = when (codecChoice) {
            CodecChoice.AV1 -> 0.08f
            CodecChoice.H265 -> 0.12f
            CodecChoice.H264 -> 0.18f
        }
        val aspect = srcWidth.toFloat() / srcHeight.toFloat()
        val rawHeight = sqrt(videoBitrateBps.toFloat() / (targetBpp * outFps) / aspect)
        if (!rawHeight.isFinite() || rawHeight <= 0f) {
            return BudgetResult.Invalid("Computed a non-finite output height from the budget")
        }

        // Floor to a multiple of 16 (never rounding up past the source) so the result is
        // guaranteed <= source and a valid encoder dimension.
        val outHeight = alignDown16(rawHeight.roundToInt().coerceAtMost(srcHeight))
        val scaledWidth = (outHeight * aspect).roundToInt().coerceAtMost(srcWidth)
        val outWidth = alignDown16(scaledWidth)

        if (!isValidDimension(outWidth, srcWidth) || !isValidDimension(outHeight, srcHeight)) {
            return BudgetResult.Invalid(
                "Output dimensions ${outWidth}x$outHeight are not positive multiples of 16 within source ${srcWidth}x$srcHeight"
            )
        }

        return BudgetResult.Valid(
            VideoBudget(
                videoBitrateBps = videoBitrateBps,
                audioBitrateBps = audioBitrateBps,
                outputWidth = outWidth,
                outputHeight = outHeight,
                outputFps = outFps,
            )
        )
    }

    private fun pickOutputFrameRate(sourceFps: Float, videoTargetBps: Int): Float {
        val fps = sourceFps.coerceIn(1f, 120f)
        return if (fps > REDUCED_FPS && videoTargetBps < MIN_HIGH_FPS_TARGET_BPS) REDUCED_FPS else fps
    }

    /** Largest multiple of 16 that is <= [v]. Returns 0 for non-positive input. */
    private fun alignDown16(v: Int): Int = if (v <= 0) 0 else (v / 16) * 16

    /** A valid output dimension is a positive multiple of 16 no larger than [source]. */
    private fun isValidDimension(value: Int, source: Int): Boolean =
        value > 0 && value % 16 == 0 && value <= source
}
