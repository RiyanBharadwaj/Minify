package com.shanks.minify.ui.compare

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

/**
 * Pure, Android-independent transform state shared by the before/after image
 * comparator. A single [ComparisonViewport] is applied identically to both the
 * "before" and "after" images so that any zoom or pan keeps them aligned to the
 * same pixel coordinates.
 *
 * The transform maps a content coordinate `c` to a screen coordinate `s` as:
 *
 *     s = scale * c + pan
 *
 * where `pan` is [panX] on the horizontal axis and [panY] on the vertical axis.
 * At `scale == 1f`, `panX == 0f`, `panY == 0f` the content fills the view exactly.
 *
 * All operations are total: they never throw for any finite input.
 *
 * Validates Requirements 10.4, 10.5, 10.6.
 */
data class ComparisonViewport(
    val scale: Float,
    val panX: Float,
    val panY: Float,
) {

    /**
     * Zooms the viewport by [factor] about the screen [focus] point, keeping the
     * content coordinate currently under [focus] fixed on screen.
     *
     * The scale is multiplied by [factor]; the pan is adjusted so the focal screen
     * coordinate continues to reveal the same content point after the zoom.
     *
     * @param focus the screen-space focal point of the gesture.
     * @param factor the multiplicative zoom factor (> 1 zooms in, in (0,1) zooms out).
     * @param bounds the size of the view (unused by the transform math, accepted for
     *   symmetry with [clampPan] and to allow callers to pass it uniformly).
     */
    fun zoomAround(focus: Offset, factor: Float, bounds: Size): ComparisonViewport {
        val newScale = scale * factor
        // Keep the content point under `focus` unchanged:
        //   newScale * c + newPan = focus, where c = (focus - pan) / scale
        //   => newPan = focus * (1 - factor) + factor * pan
        val newPanX = focus.x * (1f - factor) + factor * panX
        val newPanY = focus.y * (1f - factor) + factor * panY
        return copy(scale = newScale, panX = newPanX, panY = newPanY)
    }

    /**
     * Clamps the pan offset so the zoomed content continues to cover the view
     * [bounds]. When the content is larger than the view (`scale >= 1`), the pan is
     * constrained to the range that keeps no gap at any edge. When the content is
     * smaller than the view, the pan is clamped into the (degenerate) valid range so
     * the result stays well-defined.
     */
    fun clampPan(bounds: Size): ComparisonViewport {
        return copy(
            panX = clampAxis(panX, bounds.width, scale),
            panY = clampAxis(panY, bounds.height, scale),
        )
    }

    private fun clampAxis(pan: Float, extent: Float, scale: Float): Float {
        // Content screen extent is [pan, scale * extent + pan]. To cover [0, extent]:
        //   pan <= 0  and  scale * extent + pan >= extent  =>  pan >= extent * (1 - scale)
        val minPan = extent * (1f - scale)
        val maxPan = 0f
        return pan.coerceIn(min(minPan, maxPan), max(minPan, maxPan))
    }
}

/**
 * Pure, Android-independent result of the wipe reveal-region computation.
 *
 * The shared display region spans `[0, width]` horizontally. The divider sits at
 * [dividerX], splitting the region into a leading region `[0, dividerX]` of width
 * [beforeWidth] and a trailing region `[dividerX, width]` of width [afterWidth]. The
 * two widths always sum to [width] and are each in `[0, width]`.
 *
 * ## Layer interpretation (shared overlay)
 *
 * In the shared [CompareWipeOverlay], the two layers are stacked — the original
 * ("before") fills the whole region as the bottom layer and the edited ("after") is
 * drawn on top and **cropped by the divider**. Under that overlay, [beforeWidth] is
 * the width of the **cropped top (edited) layer** (the leading region where the
 * edited version is drawn); the remaining [afterWidth] region reveals the original
 * beneath. Only which layer the region applies to changed — the numeric partition is
 * unchanged, so [DividerOps.DEFAULT_DIVIDER_FRACTION], [DividerOps.clampDivider], and
 * the single-source-at-`0`/`1` behavior are all preserved.
 */
data class RevealRegions(
    val width: Float,
    val dividerX: Float,
    val beforeWidth: Float,
    val afterWidth: Float,
)

/**
 * Pure helpers for the comparator's draggable reveal divider.
 *
 * Validates Requirements 6.4, 6.6, 10.3.
 */
object DividerOps {

    /**
     * The divider position the Compare_Overlay opens with: the exact center of the
     * shared display region, expressed as a fraction in `[0, 1]` (Req 6.3). Both the
     * image and video comparators seed their initial divider fraction from this
     * constant so the "opens centered" behavior lives in one pure, testable place.
     */
    const val DEFAULT_DIVIDER_FRACTION = 0.5f

    /**
     * Clamps a divider position [fraction] into the inclusive range [0, 1]. Non-finite
     * inputs (NaN) resolve to 0 so the result is always a valid fraction.
     */
    fun clampDivider(fraction: Float): Float {
        if (fraction.isNaN()) return 0f
        return fraction.coerceIn(0f, 1f)
    }

    /**
     * Computes the wipe reveal regions for a divider [fraction] over a display of the
     * given [width].
     *
     * In the shared overlay the cropped top (edited) layer is revealed across the
     * leading region of width `fraction × width` (from `0` to the divider), reported
     * as [RevealRegions.beforeWidth]; the remaining trailing region (from the divider
     * to `width`), reported as [RevealRegions.afterWidth], reveals the original bottom
     * layer beneath. Consequently, at fraction `0` the original covers the whole
     * region (the edited crop collapses to zero width) and at fraction `1` the edited
     * layer covers the whole region — exactly one source at each extreme.
     *
     * The computation is total: the [fraction] is normalized via [clampDivider] (so NaN
     * and out-of-range inputs resolve into `[0, 1]`) and a negative or NaN [width] is
     * coerced to `0`. The returned [RevealRegions.beforeWidth] and
     * [RevealRegions.afterWidth] are therefore always non-negative and sum to the
     * coerced width.
     */
    fun revealRegions(fraction: Float, width: Float): RevealRegions {
        val clampedFraction = clampDivider(fraction)
        val safeWidth = if (width.isNaN() || width < 0f) 0f else width
        val beforeWidth = clampedFraction * safeWidth
        val afterWidth = safeWidth - beforeWidth
        return RevealRegions(
            width = safeWidth,
            dividerX = beforeWidth,
            beforeWidth = beforeWidth,
            afterWidth = afterWidth,
        )
    }
}
