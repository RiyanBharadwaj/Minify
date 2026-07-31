package com.shanks.minify.ui.editor.model

/**
 * The complete, pure color/tone grade recorded in the Media_Edit_State: the set
 * of continuous [adjustments], the selected named [filter], and the [vignette]
 * amount.
 *
 * Editing is non-destructive: [withVignette] returns a new [ColorGrade] rather
 * than mutating in place, mirroring how [VideoTimeline.withVolume] and
 * [Adjustments.with] record clamped values. The vignette is recorded within its
 * `[0, 1]` bounded range (Req 5.4); [withVignette] clamps any raw input into that
 * range, and the same neutral value (`0`) applies to both `Photo` and `Video`
 * media types (Req 5.5).
 *
 * The grade is [isNeutral] exactly when nothing is applied: every adjustment is
 * at its [AdjustmentKind.neutral], the [filter] is [Filter.NONE], and the
 * [vignette] is at its neutral `0`. A neutral grade composes to the identity
 * [EffectSpec] (Req 4.3, 5.3).
 *
 * @param adjustments the continuous color/tone controls; defaults to neutral.
 * @param filter      the selected named preset; defaults to [Filter.NONE].
 * @param vignette    the edge-darkening amount recorded in `[0, 1]`; neutral `0`.
 */
data class ColorGrade(
    val adjustments: Adjustments = Adjustments.NEUTRAL,
    val filter: Filter = Filter.NONE,
    val vignette: Float = NEUTRAL_VIGNETTE,
) {
    /**
     * Record [raw] as the new [vignette], clamped into the `[MIN_VIGNETTE,
     * MAX_VIGNETTE]` = `[0, 1]` range (Req 5.4). Non-destructive: returns a new
     * value and leaves this one unchanged.
     */
    fun withVignette(raw: Float): ColorGrade =
        copy(vignette = raw.coerceIn(MIN_VIGNETTE, MAX_VIGNETTE))

    /**
     * True when no color contribution is applied: all adjustments neutral, no
     * filter, and neutral vignette. A neutral grade yields the identity
     * [EffectSpec] (Req 4.3, 5.3).
     */
    val isNeutral: Boolean
        get() = adjustments.isNeutral && filter == Filter.NONE && vignette == NEUTRAL_VIGNETTE

    companion object {
        /** The neutral (no-op) vignette amount. */
        const val NEUTRAL_VIGNETTE = 0f

        /** Inclusive lower bound of the vignette range (Req 5.4). */
        const val MIN_VIGNETTE = 0f

        /** Inclusive upper bound of the vignette range (Req 5.4). */
        const val MAX_VIGNETTE = 1f

        /** A fully neutral grade that renders the media unchanged. */
        val NEUTRAL: ColorGrade = ColorGrade()
    }
}

/**
 * The pure, ordered description of the color/tone GPU pipeline derived from a
 * [ColorGrade]. This is the single source of truth consumed by both thin
 * renderers — the photo GL renderer and the Media3 effect adapter — so the two
 * always agree.
 *
 * The color math is **independent of media type**: the same [ColorGrade] yields
 * the same [EffectSpec] whether the media is a `Photo` or a `Video` (Req 4.7,
 * 5.5). It is expressed entirely in terms of the pure [ColorMatrix4x4] algebra
 * and plain scalar pass amounts, so every composition and the neutral ⇔ identity
 * guarantee can be unit- and property-tested on the JVM without any GPU or
 * Android dependency.
 *
 * The pipeline is split into two kinds of passes:
 * - [rgbMatrix]: the single pre-multiplied linear color transform, composing the
 *   selected [Filter] with the linear adjustments (saturation, vibrance,
 *   temperature, tint, contrast, brightness, exposure). A renderer uploads this
 *   one matrix and applies it in a single pass.
 * - the parametric passes [highlights], [shadows], [sharpness], [blur], and
 *   [vignette], each a scalar amount at its own neutral `0`. These map to small
 *   custom shader passes; a renderer omits a pass whose amount is neutral.
 *
 * The spec is [isIdentity] exactly when the source grade was neutral: the
 * [rgbMatrix] equals [ColorMatrix4x4.IDENTITY] and every parametric amount is `0`
 * (Req 4.3, 4.4, 5.3).
 *
 * @param rgbMatrix  the composed linear color transform (filter ∘ adjustments).
 * @param highlights the highlights pass amount in `[-1, 1]`; neutral `0`.
 * @param shadows    the shadows pass amount in `[-1, 1]`; neutral `0`.
 * @param sharpness  the sharpness pass amount in `[0, 1]`; neutral `0`.
 * @param blur       the blur pass amount in `[0, 1]`; neutral `0`.
 * @param vignette   the edge-darkening amount in `[0, 1]`; neutral `0`.
 */
data class EffectSpec(
    val rgbMatrix: ColorMatrix4x4,
    val highlights: Float,
    val shadows: Float,
    val sharpness: Float,
    val blur: Float,
    val vignette: Float,
) {
    /**
     * True when this spec applies no visible change: the linear transform is the
     * identity and every parametric pass is at its neutral `0`. Equivalent to the
     * source [ColorGrade] being neutral (Req 4.3, 4.4, 5.3).
     */
    val isIdentity: Boolean
        get() = rgbMatrix.approxEquals(ColorMatrix4x4.IDENTITY) &&
            highlights == 0f &&
            shadows == 0f &&
            sharpness == 0f &&
            blur == 0f &&
            vignette == 0f

    companion object {
        /** The identity spec: renders the media unchanged. */
        val IDENTITY: EffectSpec = EffectSpec(
            rgbMatrix = ColorMatrix4x4.IDENTITY,
            highlights = 0f,
            shadows = 0f,
            sharpness = 0f,
            blur = 0f,
            vignette = 0f,
        )

        /**
         * Derive the [EffectSpec] for a [color] grade.
         *
         * The result is **media-type independent** — the same [ColorGrade]
         * produces the same [EffectSpec] regardless of whether it is applied to a
         * `Photo` or a `Video` — because the color math depends only on the grade
         * (Req 4.7, 5.5). It is [EffectSpec.isIdentity] iff [color] is
         * [ColorGrade.isNeutral] (Req 4.3, 4.4, 5.3).
         *
         * The [rgbMatrix] pre-multiplies the selected [Filter]'s color matrix with
         * each linear adjustment's matrix; the [highlights]/[shadows]/[sharpness]/
         * [blur] parametric amounts are read straight from the grade's
         * adjustments, and the vignette is the grade's clamped [ColorGrade.vignette].
         */
        fun from(color: ColorGrade): EffectSpec {
            val adjustments = color.adjustments
            val rgbMatrix = ColorAdjustmentMatrices.compose(color.filter, adjustments)
            return EffectSpec(
                rgbMatrix = rgbMatrix,
                highlights = adjustments[AdjustmentKind.HIGHLIGHTS],
                shadows = adjustments[AdjustmentKind.SHADOWS],
                sharpness = adjustments[AdjustmentKind.SHARPNESS],
                blur = adjustments[AdjustmentKind.BLUR],
                vignette = color.vignette.coerceIn(ColorGrade.MIN_VIGNETTE, ColorGrade.MAX_VIGNETTE),
            )
        }

        /**
         * Derive the [EffectSpec] for a whole [state], reading its [MediaEditState.color]
         * grade. Convenience overload of [from] so preview renderers can map a
         * `MediaEditState` straight to its color pipeline; the result is
         * media-type independent (Req 4.7, 5.5) and [isIdentity] iff the grade is
         * neutral (Req 4.3, 4.4, 5.3).
         */
        fun from(state: MediaEditState): EffectSpec = from(state.color)
    }
}

/**
 * Builds the [ColorMatrix4x4] for each linear color adjustment and composes them
 * with the selected [Filter] into a single pre-multiplied transform.
 *
 * Every builder maps its adjustment's [AdjustmentKind.neutral] value (`0`) to
 * [ColorMatrix4x4.IDENTITY], so a fully neutral grade composes to the identity
 * matrix exactly. The math is pure algebra and carries no Android/GPU dependency.
 */
internal object ColorAdjustmentMatrices {

    /** Rec. 601 luminance weights, matching [FilterCatalog]'s grayscale basis. */
    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f

    /** Per-unit channel gain applied by temperature/tint at full strength. */
    private const val TEMP_TINT_GAIN = 0.2f

    /** Fraction of full saturation strength applied by the milder vibrance control. */
    private const val VIBRANCE_STRENGTH = 0.5f

    /**
     * Compose the [filter]'s color matrix with the linear adjustment matrices, in
     * the order `filter ∘ saturation ∘ vibrance ∘ temperature ∘ tint ∘ contrast ∘
     * brightness ∘ exposure`. Returns exactly [ColorMatrix4x4.IDENTITY] when the
     * filter is [Filter.NONE] and every linear adjustment is neutral.
     */
    fun compose(filter: Filter, adjustments: Adjustments): ColorMatrix4x4 {
        var m = FilterCatalog.colorMatrix(filter)
        m *= saturation(adjustments[AdjustmentKind.SATURATION])
        m *= vibrance(adjustments[AdjustmentKind.VIBRANCE])
        m *= temperature(adjustments[AdjustmentKind.TEMPERATURE])
        m *= tint(adjustments[AdjustmentKind.TINT])
        m *= contrast(adjustments[AdjustmentKind.CONTRAST])
        m *= brightness(adjustments[AdjustmentKind.BRIGHTNESS])
        m *= exposure(adjustments[AdjustmentKind.EXPOSURE])
        return m
    }

    /** Additive brightness offset on RGB; neutral `0` → identity. */
    private fun brightness(value: Float): ColorMatrix4x4 =
        if (value == 0f) {
            ColorMatrix4x4.IDENTITY
        } else {
            ColorMatrix4x4.ofRows(
                floatArrayOf(1f, 0f, 0f, value),
                floatArrayOf(0f, 1f, 0f, value),
                floatArrayOf(0f, 0f, 1f, value),
                floatArrayOf(0f, 0f, 0f, 1f),
            )
        }

    /** Multiplicative exposure gain `1 + value`; neutral `0` → identity. */
    private fun exposure(value: Float): ColorMatrix4x4 {
        if (value == 0f) return ColorMatrix4x4.IDENTITY
        val gain = 1f + value
        return ColorMatrix4x4.diagonal(gain, gain, gain, 1f)
    }

    /** Contrast pivoting around mid-gray (0.5) with amount `1 + value`; neutral `0` → identity. */
    private fun contrast(value: Float): ColorMatrix4x4 {
        if (value == 0f) return ColorMatrix4x4.IDENTITY
        val amount = 1f + value
        val t = 0.5f * (1f - amount)
        return ColorMatrix4x4.ofRows(
            floatArrayOf(amount, 0f, 0f, t),
            floatArrayOf(0f, amount, 0f, t),
            floatArrayOf(0f, 0f, amount, t),
            floatArrayOf(0f, 0f, 0f, 1f),
        )
    }

    /** Saturation around the luminance point with amount `1 + value`; neutral `0` → identity. */
    private fun saturation(value: Float): ColorMatrix4x4 =
        if (value == 0f) ColorMatrix4x4.IDENTITY else saturationMatrix(1f + value)

    /** Milder saturation (vibrance) with amount `1 + VIBRANCE_STRENGTH * value`; neutral `0` → identity. */
    private fun vibrance(value: Float): ColorMatrix4x4 =
        if (value == 0f) ColorMatrix4x4.IDENTITY else saturationMatrix(1f + VIBRANCE_STRENGTH * value)

    /** The saturation matrix for a given [amount] (1 = neutral); preserves alpha. */
    private fun saturationMatrix(amount: Float): ColorMatrix4x4 {
        val inv = 1f - amount
        val r = LUMA_R * inv
        val g = LUMA_G * inv
        val b = LUMA_B * inv
        return ColorMatrix4x4.ofRows(
            floatArrayOf(r + amount, g, b, 0f),
            floatArrayOf(r, g + amount, b, 0f),
            floatArrayOf(r, g, b + amount, 0f),
            floatArrayOf(0f, 0f, 0f, 1f),
        )
    }

    /** Temperature: warms (positive) by lifting red and trimming blue; neutral `0` → identity. */
    private fun temperature(value: Float): ColorMatrix4x4 {
        if (value == 0f) return ColorMatrix4x4.IDENTITY
        val shift = TEMP_TINT_GAIN * value
        return ColorMatrix4x4.diagonal(1f + shift, 1f, 1f - shift, 1f)
    }

    /** Tint: shifts green (positive) vs. magenta (negative); neutral `0` → identity. */
    private fun tint(value: Float): ColorMatrix4x4 {
        if (value == 0f) return ColorMatrix4x4.IDENTITY
        val shift = TEMP_TINT_GAIN * value
        return ColorMatrix4x4.diagonal(1f, 1f + shift, 1f, 1f)
    }
}
