package com.shanks.minify.ui.editor.model

import com.shanks.minify.photo.PhotoCompressor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The output quality budget selected for a photo export.
 *
 * Each value maps to a concrete target byte budget (expressed in megabytes)
 * handed to [PhotoCompressor.compress]: a higher quality permits a larger
 * output file, a lower quality forces a smaller one (Req 10.1). The mapping is
 * pure data so the selection can be unit-tested on the JVM without touching the
 * Android encoder.
 *
 * @property targetSizeMb the target size budget in megabytes (1 MB = 1,048,576
 *   bytes) passed to the photo compression pipeline for this quality.
 */
enum class ExportQuality(val targetSizeMb: Float) {
    /** Largest budget — preserves the most fidelity. */
    HIGH(8f),

    /** Balanced budget. */
    MEDIUM(4f),

    /** Smallest budget — favors a compact file over fidelity. */
    LOW(2f),
}

/**
 * A pair of positive output pixel dimensions for a resized photo export.
 *
 * Both [width] and [height] are guaranteed to be strictly positive; the
 * constructor rejects non-positive values so an invalid size can never be
 * recorded in the edit state (Req 10.4).
 *
 * @property width  the output width in pixels; must be `> 0`.
 * @property height the output height in pixels; must be `> 0`.
 */
data class OutputSize(val width: Int, val height: Int) {
    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
    }
}

/**
 * The photo-only export settings recorded in the Media_Edit_State: the chosen
 * [ExportQuality] budget and an optional [resize] target.
 *
 * Editing is non-destructive: this is an immutable value type. A `null` [resize]
 * means the export keeps the edited dimensions unchanged (Req 10.3, 10.4).
 *
 * @property quality the selected export quality budget (Req 10.1, 10.2).
 * @property resize   the explicit output dimensions, or `null` to keep the
 *   edited dimensions.
 */
data class PhotoSettings(
    val quality: ExportQuality = ExportQuality.HIGH,
    val resize: OutputSize? = null,
) {
    /**
     * Compute an [OutputSize] that scales the edited image so its longest edge
     * becomes [target] pixels, preserving the edited aspect ratio (Req 12.3).
     *
     * The result always has strictly positive integer dimensions regardless of
     * the requested [target]: a zero or negative target is coerced to `1`, and
     * the shorter edge is rounded to the nearest pixel and floored at `1` so a
     * very wide or tall aspect ratio can never collapse to zero. Because both
     * edges are floored at `1`, the returned [OutputSize] never trips its own
     * non-positive rejection.
     *
     * @param target  the desired longest-edge length in pixels; coerced to at
     *   least `1` (so zero/negative requests resolve to a valid `1`).
     * @param editedW the current edited width in pixels; must be `> 0`.
     * @param editedH the current edited height in pixels; must be `> 0`.
     * @return the resized dimensions preserving the edited aspect ratio.
     */
    fun resizedPreservingAspect(target: Int, editedW: Int, editedH: Int): OutputSize {
        require(editedW > 0) { "editedW must be positive, was $editedW" }
        require(editedH > 0) { "editedH must be positive, was $editedH" }

        val longestEdge = target.coerceAtLeast(1)
        return if (editedW >= editedH) {
            // Width is the longest edge.
            val height = (longestEdge.toDouble() * editedH / editedW).roundToInt()
            OutputSize(longestEdge, height.coerceAtLeast(1))
        } else {
            // Height is the longest edge.
            val width = (longestEdge.toDouble() * editedW / editedH).roundToInt()
            OutputSize(width.coerceAtLeast(1), longestEdge)
        }
    }

    /**
     * Resolve the final export [OutputSize] for an image whose edited pixel
     * dimensions are [editedW] x [editedH] (Req 12.3).
     *
     * When [resize] is absent this falls back to the edited dimensions
     * unchanged; when [resize] is present the edited image is scaled so its
     * longest edge matches the resize target's longest edge while preserving the
     * edited aspect ratio. The result is always strictly positive because
     * [OutputSize] rejects non-positive values and [resizedPreservingAspect]
     * floors every edge at `1`, so an invalid selection can never reach export.
     *
     * @param editedW the current edited width in pixels; must be `> 0`.
     * @param editedH the current edited height in pixels; must be `> 0`.
     * @return the dimensions export should render at.
     */
    fun resolveOutputSize(editedW: Int, editedH: Int): OutputSize {
        require(editedW > 0) { "editedW must be positive, was $editedW" }
        require(editedH > 0) { "editedH must be positive, was $editedH" }

        val target = resize ?: return OutputSize(editedW, editedH)
        val longestEdge = max(target.width, target.height)
        return resizedPreservingAspect(longestEdge, editedW, editedH)
    }
}
