package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.CropRect

/**
 * Maps a crop rectangle expressed in **displayed** space (after the image's
 * clockwise rotation and horizontal mirror have been applied) back into
 * **source** space.
 *
 * This is exactly the inverse that [com.shanks.minify.ui.editor.photo.PhotoEffectRenderer]
 * bakes into its texture coordinates: each displayed corner is mapped to source
 * space by first inverting the rotation ({0, 90, 180, 270} clockwise) and then
 * inverting the mirror. Because these transforms map an axis-aligned rectangle's
 * corners to another axis-aligned rectangle's corners, the source-space region
 * is the bounding rectangle of the four mapped corners.
 *
 * Sharing this single definition across the video adapter, the exporter, and the
 * photo renderer guarantees preview and photo select the identical region
 * (Req 4.1, 4.2).
 */
object CropSpaceMapping {

    /**
     * Map [displayed] (post-rotate, post-mirror) into source space given the
     * clockwise [rotationDegrees] (normalized to {0, 90, 180, 270}) and whether
     * the image is horizontally [mirrored]. Coordinates are normalized in [0, 1].
     */
    fun toSourceSpace(displayed: CropRect, rotationDegrees: Int, mirrored: Boolean): CropRect {
        val rotation = ((rotationDegrees % 360) + 360) % 360

        // The four displayed corners of the rectangle.
        val xs = floatArrayOf(displayed.left, displayed.right, displayed.left, displayed.right)
        val ys = floatArrayOf(displayed.top, displayed.top, displayed.bottom, displayed.bottom)

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (i in 0 until 4) {
            val x = xs[i]
            val y = ys[i]

            // Invert the clockwise image rotation (displayed -> pre-rotation).
            val xp: Float
            val yp: Float
            when (rotation) {
                90 -> { xp = y; yp = 1f - x }
                180 -> { xp = 1f - x; yp = 1f - y }
                270 -> { xp = 1f - y; yp = x }
                else -> { xp = x; yp = y }
            }

            // Invert the horizontal mirror applied before rotation.
            val sx = if (mirrored) 1f - xp else xp
            val sy = yp

            if (sx < minX) minX = sx
            if (sx > maxX) maxX = sx
            if (sy < minY) minY = sy
            if (sy > maxY) maxY = sy
        }

        return CropRect(left = minX, top = minY, right = maxX, bottom = maxY)
    }
}
