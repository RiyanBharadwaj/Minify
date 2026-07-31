package com.shanks.minify.ui.editor.model

/**
 * Pure, Android-independent bounded-zoom model for the trim timeline (Req 9).
 *
 * The trim timeline is magnified horizontally in `px per ms`. Instead of a pinch gesture, the
 * `TrimPanel` drives zooming through explicit zoom-in/zoom-out buttons backed by this model:
 *
 * - [minPxPerMs] is the fit-to-viewport scale that shows the whole kept range in the visible
 *   timeline width (Req 9.5).
 * - [maxPxPerMs] is `16 ×` the minimum magnification (Req 9.5).
 * - [pxPerMs] is the current magnification, always clamped to `[minPxPerMs, maxPxPerMs]`.
 * - [zoomIn]/[zoomOut] step the magnification by a factor of `2`, clamped to the bounds
 *   (Req 9.2, 9.3).
 * - [canZoomIn]/[canZoomOut] report whether the corresponding button should be enabled
 *   (Req 9.7, 9.8).
 *
 * Every operation is **total**: degenerate durations and viewport widths are coerced to safe
 * defaults, and the constructor clamps [pxPerMs] into range, so no factory or transition ever
 * throws or produces a `NaN`/`Infinity` magnification.
 *
 * Validates Requirements 9.2, 9.3, 9.5, 9.7, 9.8.
 */
data class TimelineZoom(
    /** Fit-to-viewport scale for the current kept range; always finite and strictly positive. */
    val minPxPerMs: Float,
    /** Current magnification, clamped to `[minPxPerMs, maxPxPerMs]`. */
    val pxPerMs: Float,
) {
    /** The maximum magnification: `16 ×` the minimum (Req 9.5). */
    val maxPxPerMs: Float get() = minPxPerMs * MAX_FACTOR

    /** True when the magnification can still increase (Req 9.7). */
    val canZoomIn: Boolean get() = pxPerMs < maxPxPerMs

    /** True when the magnification can still decrease (Req 9.8). */
    val canZoomOut: Boolean get() = pxPerMs > minPxPerMs

    /**
     * Zoom in by multiplying [pxPerMs] by [ZOOM_STEP], clamped to [maxPxPerMs] (Req 9.2).
     */
    fun zoomIn(): TimelineZoom =
        copy(pxPerMs = (pxPerMs * ZOOM_STEP).coerceIn(minPxPerMs, maxPxPerMs))

    /**
     * Zoom out by dividing [pxPerMs] by [ZOOM_STEP], clamped to [minPxPerMs] (Req 9.3).
     */
    fun zoomOut(): TimelineZoom =
        copy(pxPerMs = (pxPerMs / ZOOM_STEP).coerceIn(minPxPerMs, maxPxPerMs))

    companion object {
        /** The multiplicative step applied by [zoomIn]/[zoomOut]. */
        const val ZOOM_STEP = 2f

        /** The maximum magnification factor relative to the fit-to-viewport minimum. */
        const val MAX_FACTOR = 16f

        /** Safe fallback magnification used when inputs are degenerate. */
        private const val FALLBACK_PX_PER_MS = 1f

        /**
         * Build a [TimelineZoom] fitted to a kept range of [keptRangeMs] milliseconds shown in a
         * viewport of [viewportPx] display pixels (Req 9.5).
         *
         * The minimum magnification is `viewportPx / keptRangeMs`, so the whole kept range exactly
         * fills the viewport at minimum zoom, and the initial magnification is the minimum (fully
         * zoomed out). Degenerate durations or viewport widths (zero, negative, `NaN`, or
         * `Infinity`) are coerced to a safe positive default so the result is always usable.
         */
        fun fit(keptRangeMs: Long, viewportPx: Float): TimelineZoom {
            val min = fitScale(keptRangeMs, viewportPx)
            return TimelineZoom(minPxPerMs = min, pxPerMs = min)
        }

        private fun fitScale(keptRangeMs: Long, viewportPx: Float): Float {
            if (keptRangeMs <= 0L) return FALLBACK_PX_PER_MS
            if (!viewportPx.isFinite() || viewportPx <= 0f) return FALLBACK_PX_PER_MS
            val scale = viewportPx / keptRangeMs.toFloat()
            if (!scale.isFinite() || scale <= 0f) return FALLBACK_PX_PER_MS
            return scale
        }
    }
}
