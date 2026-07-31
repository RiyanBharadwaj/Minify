package com.shanks.minify.photo

import androidx.compose.runtime.saveable.Saver
import com.shanks.minify.ui.CropRect

/**
 * The Photo Editor's pure, Android-independent edit model.
 *
 * Structured like [OrientationTransform]: it carries the user's pending Crop,
 * Rotate (90 clockwise steps), and Flip-Horizontal (Mirror) edits as plain data
 * so every geometric decision can be property-tested on the JVM without an
 * Android [android.graphics.Matrix]. The rotation + mirror portion is expressed
 * through the same D4 [Mat2] algebra used for EXIF orientation, keeping the
 * transform math exact and reusable.
 *
 * @param rotationDegrees clockwise rotation, always normalized to one of
 *        `{0, 90, 180, 270}`.
 * @param mirrored        whether the image is flipped horizontally about its
 *        vertical center axis.
 * @param crop            the normalized crop rectangle in `[0,1]` space; defaults
 *        to [CropRect.FULL] (no crop).
 */
data class ImageEditModel(
    val rotationDegrees: Int = 0,
    val mirrored: Boolean = false,
    val crop: CropRect = CropRect.FULL,
) {
    init {
        require(rotationDegrees in NORMALIZED_ROTATIONS) {
            "rotationDegrees must be normalized to one of $NORMALIZED_ROTATIONS but was $rotationDegrees"
        }
    }

    /** Advance the rotation by 90 clockwise, keeping it normalized to `{0,90,180,270}`. */
    fun rotateClockwise(): ImageEditModel =
        copy(rotationDegrees = normalizeDegrees(rotationDegrees + 90))

    /** Toggle the horizontal flip (mirror). Two toggles return to the original state. */
    fun toggleMirror(): ImageEditModel = copy(mirrored = !mirrored)

    /** Replace the crop rectangle. */
    fun withCrop(c: CropRect): ImageEditModel = copy(crop = c)

    /**
     * The D4 transform (a [Mat2]) describing the rotation + mirror, excluding the
     * crop. Reuses the [OrientationTransform] algebra: the matrix is
     * `Rotation(rotationDegrees) * FlipH`, so a source coordinate is first
     * mirrored (when [mirrored]) and then rotated clockwise.
     */
    fun orientationMatrix(): Mat2 =
        OrientationTransform(
            rotationDegrees = rotationDegrees,
            flipHorizontal = mirrored,
            flipVertical = false,
        ).toMatrix()

    companion object {
        private val NORMALIZED_ROTATIONS = setOf(0, 90, 180, 270)

        private fun normalizeDegrees(deg: Int): Int = ((deg % 360) + 360) % 360

        /**
         * Custom Saver so the edit model survives configuration changes via
         * rememberSaveable. Encodes to a FloatArray following [EditState.Saver]:
         * `[rotation, mirrored?1:0, left, top, right, bottom]`.
         */
        val Saver: Saver<ImageEditModel, Any> = Saver(
            save = { model ->
                floatArrayOf(
                    model.rotationDegrees.toFloat(),
                    if (model.mirrored) 1f else 0f,
                    model.crop.left,
                    model.crop.top,
                    model.crop.right,
                    model.crop.bottom,
                )
            },
            restore = { saved ->
                val arr = saved as FloatArray
                ImageEditModel(
                    rotationDegrees = normalizeDegrees(arr[0].toInt()),
                    mirrored = arr[1] != 0f,
                    crop = CropRect(arr[2], arr[3], arr[4], arr[5]),
                )
            },
        )
    }
}
