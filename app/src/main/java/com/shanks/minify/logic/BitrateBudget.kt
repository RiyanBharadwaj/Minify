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

    /** Smallest encoder-valid dimension (one macroblock). */
    private const val MIN_DIMENSION = 16

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
        // ── Only truly impossible inputs are rejected ────────────────────
        if (srcWidth <= 0 || srcHeight <= 0) {
            return BudgetResult.Invalid(
                "Source dimensions must be positive, got ${srcWidth}x$srcHeight"
            )
        }
        // Non-positive / non-finite target is clamped to the absolute floor
        // instead of returning Invalid (auto-adjust, don't crash).
        val safeTargetMb = if (!targetSizeMb.isFinite() || targetSizeMb <= 0f) 0.1f else targetSizeMb
        // ─────────────────────────────────────────────────────────────────

        val safeHeadroom = headroom.coerceIn(0f, 1f)
        val targetBits = (safeTargetMb * BITS_PER_MB * safeHeadroom).toLong().coerceAtLeast(1L)

        val nominalAudioBudget =
            if (durationSecs > 0 && !removeAudio) AUDIO_NOMINAL_KBPS * 1000L * durationSecs else 0L
        val maxAudioBudget = (targetBits * MAX_AUDIO_SHARE).toLong().coerceAtLeast(0L)
        val effectiveAudioBudget = nominalAudioBudget.coerceAtMost(maxAudioBudget)

        val audioBitrateBps = if (durationSecs > 0 && !removeAudio) {
            (effectiveAudioBudget / durationSecs).toInt().coerceAtMost(AUDIO_MAX_KBPS * 1000)
        } else if (removeAudio) {
            0
        } else {
            (AUDIO_NOMINAL_KBPS * 1000).coerceIn(AUDIO_MIN_KBPS * 1000, AUDIO_MAX_KBPS * 1000)
        }

        val videoBudgetBits = (targetBits - effectiveAudioBudget).coerceAtLeast(0L)
        val videoBitrateRaw = if (durationSecs > 0) {
            (videoBudgetBits / durationSecs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            (srcBitrateKbps.toLong() * 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
        val videoBitrateBps = videoBitrateRaw.coerceIn(MIN_VIDEO_BPS, MAX_VIDEO_BPS)

        val outFps = pickOutputFrameRate(frameRate, videoBitrateBps)

        val targetBpp = when (codecChoice) {
            CodecChoice.AV1  -> 0.08f
            CodecChoice.H265 -> 0.12f
            CodecChoice.H264 -> 0.18f
        }
        val aspect = srcWidth.toFloat() / srcHeight.toFloat()
        val rawHeight = sqrt(videoBitrateBps.toFloat() / (targetBpp * outFps) / aspect)

        // ── Clamp dimensions instead of returning Invalid ────────────────
        val clampedRawHeight = if (!rawHeight.isFinite() || rawHeight <= 0f) {
            MIN_DIMENSION.toFloat()
        } else {
            rawHeight
        }

        var outHeight = alignDown16(clampedRawHeight.roundToInt().coerceAtMost(srcHeight))
        var scaledWidth = (outHeight * aspect).roundToInt().coerceAtMost(srcWidth)
        var outWidth = alignDown16(scaledWidth)

        // If alignDown16 produced 0 (source < 16 px on an axis), fall back to
        // the smallest valid encoder dimension that still fits the source.
        if (outHeight < MIN_DIMENSION) outHeight = MIN_DIMENSION.coerceAtMost(srcHeight)
        if (outWidth  < MIN_DIMENSION) outWidth  = MIN_DIMENSION.coerceAtMost(srcWidth)

        // Last-resort: if even MIN_DIMENSION exceeds the source (e.g. 10×10),
        // use the source dimension rounded down to the nearest even number.
        // Media3 can handle non-multiple-of-16 via Presentation scaling.
        if (outHeight <= 0) outHeight = srcHeight.coerceAtLeast(2)
        if (outWidth  <= 0) outWidth  = srcWidth.coerceAtLeast(2)
        // ─────────────────────────────────────────────────────────────────

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

    private fun alignDown16(v: Int): Int = if (v <= 0) 0 else (v / 16) * 16
}
