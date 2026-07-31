package com.shanks.minify.ui.editor.model

/**
 * Pure, total geometry helper for applying a clockwise rotation to a media
 * frame's presentation.
 *
 * A clockwise rotation of `90` or `270` degrees swaps a frame's width and
 * height, so the displayed aspect ratio becomes the inverse of the source
 * aspect ratio. The preview surface drives its `AspectRatioFrameLayout` ratio
 * from [displayedAspect] so a rotated frame keeps the correct inverse aspect
 * without non-uniform scaling, and the exporter derives its `Presentation`
 * output dimensions from [displayedSize] so the exported frame matches the
 * previewed one (Requirement 3.6).
 *
 * Both functions are **total**: the rotation is normalized to `{0, 90, 180,
 * 270}` and degenerate inputs (zero/negative dimensions or aspect, non-finite
 * aspect) are coerced to safe defaults. Neither function ever throws.
 */
object RotationGeometry {

    /**
     * Displayed `(width, height)` after applying a clockwise [rotationDegrees]
     * rotation to a source `(`[sourceW]`, `[sourceH]`)`.
     *
     * Width and height are swapped exactly on `90` and `270`; other rotations
     * preserve the source dimensions. The rotation is normalized to
     * `{0, 90, 180, 270}`; any other value falls back to `0` (no swap).
     *
     * Zero or negative dimensions are coerced to `0` so the result is always
     * non-negative. Never throws.
     */
    fun displayedSize(sourceW: Int, sourceH: Int, rotationDegrees: Int): Pair<Int, Int> {
        val w = sourceW.coerceAtLeast(0)
        val h = sourceH.coerceAtLeast(0)
        return if (swapsAxes(rotationDegrees)) h to w else w to h
    }

    /**
     * Displayed aspect (`width / height`) after applying a clockwise
     * [rotationDegrees] rotation to a frame whose source aspect is
     * [sourceAspect].
     *
     * Returns [sourceAspect] for `0` and `180`, and the inverse
     * (`1 / sourceAspect`) for `90` and `270`. The rotation is normalized to
     * `{0, 90, 180, 270}`; any other value falls back to `0` (source aspect).
     *
     * A non-finite or non-positive [sourceAspect] is coerced to the safe
     * default [DEFAULT_ASPECT] (a square, `1.0`). Never throws.
     */
    fun displayedAspect(sourceAspect: Float, rotationDegrees: Int): Float {
        val aspect = if (sourceAspect.isFinite() && sourceAspect > 0f) sourceAspect else DEFAULT_ASPECT
        return if (swapsAxes(rotationDegrees)) 1f / aspect else aspect
    }

    /** Safe fallback aspect (square) for degenerate source aspect inputs. */
    const val DEFAULT_ASPECT: Float = 1f

    /**
     * Whether a clockwise [rotationDegrees] rotation swaps the frame's axes.
     * Normalizes to `{0, 90, 180, 270}`; unrecognized values are treated as `0`.
     */
    private fun swapsAxes(rotationDegrees: Int): Boolean {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        return normalized == 90 || normalized == 270
    }
}
