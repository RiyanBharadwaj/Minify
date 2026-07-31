package com.shanks.minify.ui.editor.model

/**
 * Pure, Android-independent 4x4 RGBA color matrix.
 *
 * This is the algebra backbone for the unified editor's color pipeline: filters
 * and the linear adjustments (saturation, vibrance, temperature, tint, contrast,
 * brightness, exposure) are each expressed as a [ColorMatrix4x4], and the full
 * color transform for a frame is their composition via [times]. It mirrors how
 * [com.shanks.minify.photo.Mat2] backs the geometry (D4) algebra: a small,
 * immutable value type with exact, well-defined arithmetic that can be unit- and
 * property-tested on the JVM without any GPU or Android dependency.
 *
 * A pixel is modeled as an RGBA 4-vector `(r, g, b, a)`. The matrix maps a source
 * vector `v` to `M · v`, i.e. `out[i] = Σ_j m[i][j] * v[j]` ([apply]). Composition
 * follows the same convention used by [com.shanks.minify.photo.Mat2]: `a * b`
 * describes "apply b first, then a", so `a.times(b).apply(v) == a.apply(b.apply(v))`.
 *
 * The 16 coefficients are stored row-major in [values]:
 * ```
 * | m00 m01 m02 m03 |   | r |
 * | m10 m11 m12 m13 | · | g |
 * | m20 m21 m22 m23 |   | b |
 * | m30 m31 m32 m33 |   | a |
 * ```
 */
class ColorMatrix4x4 private constructor(
    /** The 16 row-major coefficients. Treated as immutable; never mutate in place. */
    val values: FloatArray,
) {
    init {
        require(values.size == SIZE * SIZE) {
            "A ColorMatrix4x4 requires ${SIZE * SIZE} coefficients, got ${values.size}"
        }
    }

    /** The coefficient at [row] and [col] (both in `0..3`), row-major. */
    operator fun get(row: Int, col: Int): Float = values[row * SIZE + col]

    /**
     * Matrix product `this * other`, i.e. [other] applied first, then [this].
     *
     * Composition is associative with [IDENTITY] as the neutral element, so a
     * chain of color passes can be pre-multiplied into a single matrix that the
     * renderer uploads once.
     */
    operator fun times(other: ColorMatrix4x4): ColorMatrix4x4 {
        val out = FloatArray(SIZE * SIZE)
        for (row in 0 until SIZE) {
            for (col in 0 until SIZE) {
                var sum = 0f
                for (k in 0 until SIZE) {
                    sum += this[row, k] * other[k, col]
                }
                out[row * SIZE + col] = sum
            }
        }
        return ColorMatrix4x4(out)
    }

    /**
     * Apply this matrix to an RGBA vector, returning the transformed
     * `(r, g, b, a)`. The input must contain exactly 4 components; the result is
     * a fresh array and the input is left unchanged.
     */
    fun apply(rgba: FloatArray): FloatArray {
        require(rgba.size == SIZE) { "apply expects an RGBA 4-vector, got ${rgba.size} components" }
        val out = FloatArray(SIZE)
        for (row in 0 until SIZE) {
            var sum = 0f
            for (col in 0 until SIZE) {
                sum += this[row, col] * rgba[col]
            }
            out[row] = sum
        }
        return out
    }

    /**
     * True when every coefficient equals [other]'s within [tolerance]. Preferred
     * over [equals] for comparing results of floating-point composition, where
     * exact bit-equality is not expected.
     */
    fun approxEquals(other: ColorMatrix4x4, tolerance: Float = 1e-5f): Boolean {
        for (i in values.indices) {
            if (kotlin.math.abs(values[i] - other.values[i]) > tolerance) return false
        }
        return true
    }

    /** Structural (exact) equality over all 16 coefficients. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ColorMatrix4x4) return false
        return values.contentEquals(other.values)
    }

    override fun hashCode(): Int = values.contentHashCode()

    override fun toString(): String = buildString {
        append("ColorMatrix4x4(")
        for (row in 0 until SIZE) {
            append("[")
            for (col in 0 until SIZE) {
                append(this@ColorMatrix4x4[row, col])
                if (col < SIZE - 1) append(", ")
            }
            append("]")
            if (row < SIZE - 1) append(", ")
        }
        append(")")
    }

    companion object {
        /** The side length of the (square) matrix and the RGBA vector dimension. */
        const val SIZE = 4

        /** The identity transform: leaves every RGBA vector unchanged. */
        val IDENTITY: ColorMatrix4x4 = diagonal(1f, 1f, 1f, 1f)

        /**
         * Build a matrix from 16 row-major coefficients.
         *
         * @throws IllegalArgumentException unless exactly 16 values are supplied.
         */
        fun of(vararg values: Float): ColorMatrix4x4 = ColorMatrix4x4(values.copyOf())

        /**
         * Build a matrix from its four rows, each an RGBA 4-vector.
         *
         * @throws IllegalArgumentException unless four rows of 4 values each are supplied.
         */
        fun ofRows(
            row0: FloatArray,
            row1: FloatArray,
            row2: FloatArray,
            row3: FloatArray,
        ): ColorMatrix4x4 {
            val rows = listOf(row0, row1, row2, row3)
            require(rows.all { it.size == SIZE }) { "Each row must have $SIZE components" }
            val out = FloatArray(SIZE * SIZE)
            rows.forEachIndexed { r, row -> row.copyInto(out, r * SIZE) }
            return ColorMatrix4x4(out)
        }

        /** A diagonal matrix with the given RGBA scale factors on the diagonal. */
        fun diagonal(r: Float, g: Float, b: Float, a: Float): ColorMatrix4x4 {
            val out = FloatArray(SIZE * SIZE)
            out[0] = r
            out[SIZE + 1] = g
            out[2 * SIZE + 2] = b
            out[3 * SIZE + 3] = a
            return ColorMatrix4x4(out)
        }
    }
}
