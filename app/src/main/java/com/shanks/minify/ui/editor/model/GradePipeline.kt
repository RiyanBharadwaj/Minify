package com.shanks.minify.ui.editor.model

/**
 * The single canonical ordering of the color/tone/parametric passes, plus a
 * pure per-pixel CPU reference of that pipeline.
 *
 * Root cause it fixes (Req 3.1, 3.2): the photo GL renderer and the Media3 video
 * adapter historically applied the passes in different orders and with slightly
 * different math, so the same [EffectSpec] produced different grades on a photo
 * versus a video. Both renderers are edited to follow [ORDER] and to approximate
 * [applyReference]; this object is the one place that ordering and the reference
 * math live, so the two pipelines cannot drift apart.
 *
 * The canonical order is:
 * ```
 * 1. rgbMatrix   (filter ∘ linear adjustments) — offsets applied independent of alpha
 * 2. highlights
 * 3. shadows
 * 4. sharpness
 * 5. blur
 * 6. vignette
 * ```
 *
 * [applyReference] is pure algebra over a straight-alpha RGBA pixel and carries
 * no Android/GL/Media3 dependency, so the ordering, the neutral ⇔ identity
 * guarantee, and the alpha-independence of the brightness/contrast offsets
 * (Req 9.3) can all be unit- and property-tested on the JVM. It is also the model
 * in the model-based parity tests that pin what both renderers must approximate
 * (Req 3.3).
 */
object GradePipeline {

    /**
     * A single stage of the grade pipeline. The declaration order of the enum is
     * incidental; [ORDER] is the authoritative sequence both renderers follow.
     */
    enum class Pass {
        /** The composed linear color transform (`filter ∘ adjustments`), [EffectSpec.rgbMatrix]. */
        RGB_MATRIX,

        /** Tone lift/cut weighted toward bright regions, [EffectSpec.highlights]. */
        HIGHLIGHTS,

        /** Tone lift/cut weighted toward dark regions, [EffectSpec.shadows]. */
        SHADOWS,

        /** Unsharp-mask detail enhancement against the neighborhood, [EffectSpec.sharpness]. */
        SHARPNESS,

        /** Neighborhood averaging blur, [EffectSpec.blur]. */
        BLUR,

        /** Radial edge darkening, [EffectSpec.vignette]. */
        VIGNETTE,
    }

    /**
     * The canonical documented order both renderers MUST follow (Req 3.1). The
     * photo fragment shader and the Media3 adapter's pass split are both derived
     * from this list so their ordering is identical by construction.
     */
    val ORDER: List<Pass> = listOf(
        Pass.RGB_MATRIX,
        Pass.HIGHLIGHTS,
        Pass.SHADOWS,
        Pass.SHARPNESS,
        Pass.BLUR,
        Pass.VIGNETTE,
    )

    /** Rec. 601 luminance weights, matching [ColorAdjustmentMatrices]/[FilterCatalog]. */
    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f

    private const val R = 0
    private const val G = 1
    private const val B = 2
    private const val A = 3

    /**
     * The spatial sample context a per-pixel pass needs beyond the pixel itself.
     *
     * The [RGB_MATRIX][Pass.RGB_MATRIX], [HIGHLIGHTS][Pass.HIGHLIGHTS] and
     * [SHADOWS][Pass.SHADOWS] passes are purely local (they depend only on the
     * pixel), but [SHARPNESS][Pass.SHARPNESS] and [BLUR][Pass.BLUR] convolve the
     * pixel with its surroundings, and [VIGNETTE][Pass.VIGNETTE] depends on the
     * pixel's position within the frame. Those inputs are supplied here so
     * [applyReference] stays a pure function of its arguments.
     *
     * @param average    the mean RGBA of the sampled neighborhood (a 4-vector),
     *                   used by the sharpness and blur passes. When it equals the
     *                   pixel, both passes are no-ops regardless of their amounts.
     * @param edgeFactor the pixel's normalized radial position for the vignette
     *                   pass, in `[0, 1]`: `0` at the frame center (no darkening)
     *                   rising toward `1` at the far corner (maximum darkening).
     */
    data class Neighborhood(
        val average: FloatArray,
        val edgeFactor: Float = 0f,
    ) {
        init {
            require(average.size == ColorMatrix4x4.SIZE) {
                "Neighborhood.average expects an RGBA 4-vector, got ${average.size} components"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Neighborhood) return false
            return average.contentEquals(other.average) && edgeFactor == other.edgeFactor
        }

        override fun hashCode(): Int = 31 * average.contentHashCode() + edgeFactor.hashCode()

        companion object {
            /**
             * A neighborhood whose average is the pixel [rgba] itself and whose
             * [edgeFactor] is the frame center. Under this neighborhood the
             * sharpness, blur, and vignette passes are exact no-ops, isolating the
             * purely local passes — convenient for the alpha-independence property
             * (Req 9.3), which varies only the brightness/contrast offsets.
             */
            fun self(rgba: FloatArray): Neighborhood {
                require(rgba.size == ColorMatrix4x4.SIZE) {
                    "self expects an RGBA 4-vector, got ${rgba.size} components"
                }
                return Neighborhood(average = rgba.copyOf(), edgeFactor = 0f)
            }
        }
    }

    /**
     * The full per-pixel transform for a straight-alpha RGBA pixel [rgba] under
     * [spec], applying every [Pass] in [ORDER] against [neighborhood].
     *
     * The [rgba] input is a straight- (non-premultiplied) alpha 4-vector and is
     * left unchanged; the graded RGBA is returned as a fresh array. Each pass
     * whose amount is neutral (`0`) is an exact no-op, so a [EffectSpec.IDENTITY]
     * spec returns the input unchanged.
     *
     * The [RGB_MATRIX][Pass.RGB_MATRIX] pass applies the additive
     * brightness/contrast offsets (which live in the matrix's alpha column)
     * **independent of the pixel's alpha** — the offset column is added as if
     * `a == 1` rather than being scaled by the actual alpha — so a non-opaque
     * pixel receives the same additive grade as an opaque one (Req 9.3). Alpha
     * itself is carried through the matrix's last row and by the later passes,
     * which only touch rgb.
     */
    fun applyReference(rgba: FloatArray, spec: EffectSpec, neighborhood: Neighborhood): FloatArray {
        require(rgba.size == ColorMatrix4x4.SIZE) {
            "applyReference expects an RGBA 4-vector, got ${rgba.size} components"
        }
        var pixel = rgba.copyOf()
        for (pass in ORDER) {
            pixel = when (pass) {
                Pass.RGB_MATRIX -> applyRgbMatrix(pixel, spec.rgbMatrix)
                Pass.HIGHLIGHTS -> applyToneMask(pixel, spec.highlights, highlight = true)
                Pass.SHADOWS -> applyToneMask(pixel, spec.shadows, highlight = false)
                Pass.SHARPNESS -> applySharpness(pixel, spec.sharpness, neighborhood.average)
                Pass.BLUR -> applyBlur(pixel, spec.blur, neighborhood.average)
                Pass.VIGNETTE -> applyVignette(pixel, spec.vignette, neighborhood.edgeFactor)
            }
        }
        return pixel
    }

    /**
     * Apply the composed color [matrix] with the alpha column added independent of
     * alpha. For the rgb rows the alpha-column coefficient is added as a constant
     * (as if `a == 1`) instead of being multiplied by the pixel's alpha, which is
     * exactly the brightness/contrast alpha-independence fix (Req 9.3). The alpha
     * output follows the matrix's last row unchanged.
     */
    private fun applyRgbMatrix(pixel: FloatArray, matrix: ColorMatrix4x4): FloatArray {
        val r = pixel[R]
        val g = pixel[G]
        val b = pixel[B]
        val a = pixel[A]
        val out = FloatArray(ColorMatrix4x4.SIZE)
        // rgb rows: the alpha-column term uses a constant 1 so additive offsets
        // are independent of the pixel's alpha.
        for (row in R..B) {
            out[row] = matrix[row, R] * r +
                matrix[row, G] * g +
                matrix[row, B] * b +
                matrix[row, A] * 1f
        }
        // alpha row: standard multiply so the transform still controls alpha.
        out[A] = matrix[A, R] * r +
            matrix[A, G] * g +
            matrix[A, B] * b +
            matrix[A, A] * a
        return out
    }

    /** Rec. 601 luminance of an RGBA pixel's rgb channels. */
    private fun luma(pixel: FloatArray): Float =
        LUMA_R * pixel[R] + LUMA_G * pixel[G] + LUMA_B * pixel[B]

    /**
     * Tone adjustment weighted by luminance. With [highlight] true the mask is the
     * luminance (bright pixels affected most); otherwise it is `1 - luminance`
     * (dark pixels affected most). A neutral [amount] of `0` is a no-op and alpha
     * is preserved.
     */
    private fun applyToneMask(pixel: FloatArray, amount: Float, highlight: Boolean): FloatArray {
        if (amount == 0f) return pixel
        val l = luma(pixel)
        val mask = if (highlight) l else 1f - l
        val delta = amount * mask
        return floatArrayOf(pixel[R] + delta, pixel[G] + delta, pixel[B] + delta, pixel[A])
    }

    /**
     * Unsharp-mask sharpening: push the pixel away from the neighborhood
     * [average] by [amount] (`0` neutral). `out = pixel + amount * (pixel - avg)`.
     * Alpha is preserved.
     */
    private fun applySharpness(pixel: FloatArray, amount: Float, average: FloatArray): FloatArray {
        if (amount == 0f) return pixel
        return floatArrayOf(
            pixel[R] + amount * (pixel[R] - average[R]),
            pixel[G] + amount * (pixel[G] - average[G]),
            pixel[B] + amount * (pixel[B] - average[B]),
            pixel[A],
        )
    }

    /**
     * Blur: linearly interpolate the pixel toward the neighborhood [average] by
     * [amount] (`0` neutral, `1` fully the average). Alpha is preserved.
     */
    private fun applyBlur(pixel: FloatArray, amount: Float, average: FloatArray): FloatArray {
        if (amount == 0f) return pixel
        val t = amount
        return floatArrayOf(
            pixel[R] + t * (average[R] - pixel[R]),
            pixel[G] + t * (average[G] - pixel[G]),
            pixel[B] + t * (average[B] - pixel[B]),
            pixel[A],
        )
    }

    /**
     * Vignette: darken rgb by `amount * edgeFactor` (`0` neutral, no darkening at
     * the frame center where `edgeFactor == 0`). Alpha is preserved.
     */
    private fun applyVignette(pixel: FloatArray, amount: Float, edgeFactor: Float): FloatArray {
        if (amount == 0f) return pixel
        val scale = 1f - amount * edgeFactor
        return floatArrayOf(pixel[R] * scale, pixel[G] * scale, pixel[B] * scale, pixel[A])
    }
}
