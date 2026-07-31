package com.shanks.minify.photo

/**
 * Pure, Android-independent EXIF orientation normalization.
 *
 * An EXIF orientation tag describes how the stored pixels are laid out relative
 * to the way the photo is meant to be viewed. To render the image upright we
 * must apply a *correction* transform. This file expresses that correction as
 * plain data ([OrientationTransform]) so it can be unit- and property-tested on
 * the JVM without an Android [android.graphics.Matrix].
 *
 * The transform is a member of the dihedral group D4 (the 8 rigid symmetries of
 * a rectangle: 4 rotations x optional reflection), which is enough to describe
 * every EXIF orientation and its inverse. We model it internally as a 2x2
 * integer orthogonal matrix so that composition, inversion, and the
 * "is-upright" (identity) check are all pure and exact.
 *
 * The [ExifInterface.ORIENTATION_*] integer constants referenced here are the
 * standard EXIF values (0..8); they are reproduced as [Tag] so the mapping does
 * not depend on `androidx.exifinterface` being on the JVM test classpath.
 */

/**
 * A rigid image transform expressed as plain data: a clockwise [rotationDegrees]
 * (0, 90, 180, or 270) plus optional axis flips.
 *
 * The three fields are an over-parameterized but convenient description; the
 * canonical algebra is carried by [toMatrix]. Composition and identity checks
 * should always go through the matrix so that geometrically-equal transforms
 * compare equal regardless of how they were spelled.
 */
data class OrientationTransform(
    val rotationDegrees: Int,
    val flipHorizontal: Boolean,
    val flipVertical: Boolean,
) {
    /**
     * The 2x2 integer matrix `M` such that a source coordinate `(x, y)` maps to
     * `(m00*x + m01*y, m10*x + m11*y)`. Built as `Rotation(r) * Flip`, where
     * `Flip` is the diagonal scaling `diag(sx, sy)` with `sx = -1` iff
     * [flipHorizontal] and `sy = -1` iff [flipVertical].
     */
    fun toMatrix(): Mat2 {
        val sx = if (flipHorizontal) -1 else 1
        val sy = if (flipVertical) -1 else 1
        val flip = Mat2(sx, 0, 0, sy)
        return rotationMatrix(rotationDegrees) * flip
    }

    /** True when this transform leaves every pixel in place (already upright). */
    val isIdentity: Boolean get() = toMatrix() == Mat2.IDENTITY

    /**
     * Apply [this] first, then [next]. The resulting matrix is `next * this`,
     * so `a.then(b)` describes "do a, then do b".
     */
    fun then(next: OrientationTransform): OrientationTransform =
        fromMatrix(next.toMatrix() * this.toMatrix())

    /**
     * The transform that exactly undoes [this]. Because every D4 element is an
     * orthogonal integer matrix, the inverse is the transpose.
     */
    fun inverse(): OrientationTransform = fromMatrix(toMatrix().transpose())

    companion object {
        /** The "already upright" transform. */
        val IDENTITY = OrientationTransform(0, flipHorizontal = false, flipVertical = false)

        private fun rotationMatrix(deg: Int): Mat2 = when (normalizeDegrees(deg)) {
            0 -> Mat2(1, 0, 0, 1)      // identity
            90 -> Mat2(0, 1, -1, 0)    // 90 clockwise: (x,y) -> (y, -x)
            180 -> Mat2(-1, 0, 0, -1)  // 180
            270 -> Mat2(0, -1, 1, 0)   // 270 clockwise: (x,y) -> (-y, x)
            else -> Mat2.IDENTITY      // unreachable; normalizeDegrees snaps to 0/90/180/270
        }

        private fun normalizeDegrees(deg: Int): Int = ((deg % 360) + 360) % 360

        /**
         * Recover a canonical [OrientationTransform] from a D4 matrix.
         *
         * Pure rotations (determinant +1) are returned with no flips. Reflections
         * (determinant -1) are canonicalized as a horizontal flip followed by a
         * rotation, so every reflection has a single unique spelling.
         */
        fun fromMatrix(m: Mat2): OrientationTransform {
            return if (m.determinant() == 1) {
                OrientationTransform(rotationOf(m), flipHorizontal = false, flipVertical = false)
            } else {
                // m = R(r) * FlipH  =>  R(r) = m * FlipH (FlipH is its own inverse).
                val flipH = Mat2(-1, 0, 0, 1)
                OrientationTransform(rotationOf(m * flipH), flipHorizontal = true, flipVertical = false)
            }
        }

        private fun rotationOf(m: Mat2): Int = when (m) {
            Mat2(1, 0, 0, 1) -> 0
            Mat2(0, 1, -1, 0) -> 90
            Mat2(-1, 0, 0, -1) -> 180
            Mat2(0, -1, 1, 0) -> 270
            else -> throw IllegalArgumentException("Not a rotation matrix: $m")
        }
    }
}

/**
 * A 2x2 integer matrix, sufficient to represent the dihedral group D4 used for
 * EXIF orientation math. Immutable value type with exact integer arithmetic.
 */
data class Mat2(val m00: Int, val m01: Int, val m10: Int, val m11: Int) {

    /** Matrix product `this * other` (this applied after other). */
    operator fun times(other: Mat2): Mat2 = Mat2(
        m00 = m00 * other.m00 + m01 * other.m10,
        m01 = m00 * other.m01 + m01 * other.m11,
        m10 = m10 * other.m00 + m11 * other.m10,
        m11 = m10 * other.m01 + m11 * other.m11,
    )

    fun transpose(): Mat2 = Mat2(m00, m10, m01, m11)

    fun determinant(): Int = m00 * m11 - m01 * m10

    companion object {
        val IDENTITY = Mat2(1, 0, 0, 1)
    }
}

/**
 * The EXIF orientation normalization mapping, as a pure function over the raw
 * integer tag. Never throws: unknown or [Tag.UNDEFINED] values are treated as
 * [Tag.NORMAL] (already upright).
 */
object ExifOrientation {

    /**
     * Standard EXIF orientation tag values, mirroring the
     * `androidx.exifinterface.media.ExifInterface.ORIENTATION_*` integer
     * constants so this logic stays independent of the Android library.
     */
    object Tag {
        const val UNDEFINED = 0
        const val NORMAL = 1
        const val FLIP_HORIZONTAL = 2
        const val ROTATE_180 = 3
        const val FLIP_VERTICAL = 4
        const val TRANSPOSE = 5
        const val ROTATE_90 = 6
        const val TRANSVERSE = 7
        const val ROTATE_270 = 8
    }

    /** Every defined EXIF orientation tag value. Useful for exhaustive testing. */
    val ALL_TAGS: List<Int> = listOf(
        Tag.UNDEFINED,
        Tag.NORMAL,
        Tag.FLIP_HORIZONTAL,
        Tag.ROTATE_180,
        Tag.FLIP_VERTICAL,
        Tag.TRANSPOSE,
        Tag.ROTATE_90,
        Tag.TRANSVERSE,
        Tag.ROTATE_270,
    )

    /**
     * Map an EXIF orientation tag to the correction [OrientationTransform] that
     * renders the stored image upright. Total: any unrecognized value (including
     * [Tag.UNDEFINED]) maps to the identity, matching typical decoder behavior
     * where an absent/undefined orientation is assumed normal.
     */
    fun normalize(orientation: Int): OrientationTransform = when (orientation) {
        Tag.FLIP_HORIZONTAL -> OrientationTransform(0, flipHorizontal = true, flipVertical = false)
        Tag.ROTATE_180 -> OrientationTransform(180, flipHorizontal = false, flipVertical = false)
        Tag.FLIP_VERTICAL -> OrientationTransform(0, flipHorizontal = false, flipVertical = true)
        Tag.TRANSPOSE -> OrientationTransform(90, flipHorizontal = true, flipVertical = false)
        Tag.ROTATE_90 -> OrientationTransform(90, flipHorizontal = false, flipVertical = false)
        Tag.TRANSVERSE -> OrientationTransform(270, flipHorizontal = true, flipVertical = false)
        Tag.ROTATE_270 -> OrientationTransform(270, flipHorizontal = false, flipVertical = false)
        // Tag.NORMAL, Tag.UNDEFINED, and any unknown value -> already upright.
        else -> OrientationTransform.IDENTITY
    }

    /**
     * The transform that the tag *represents*: how an upright image was
     * transformed to produce the stored pixels. This is exactly the inverse of
     * the correction returned by [normalize]. Composing the correction with the
     * represented orientation therefore yields the identity (an upright image),
     * which is the invariant exercised by Property 18.
     */
    fun represented(orientation: Int): OrientationTransform = normalize(orientation).inverse()
}
