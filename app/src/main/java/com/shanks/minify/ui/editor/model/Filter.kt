package com.shanks.minify.ui.editor.model

/**
 * A named color/tone preset for the unified editor's Filters panel.
 *
 * Each entry maps to a single, predefined [ColorMatrix4x4] color transform via
 * [FilterCatalog.colorMatrix]. [NONE] is the identity (no Filter contribution),
 * satisfying the "None" option that clears any previously selected Filter.
 */
enum class Filter {
    /** No filter applied; maps to [ColorMatrix4x4.IDENTITY]. */
    NONE,

    /** Punchy, saturated look with a mild contrast lift. */
    VIVID,

    /** Neutral grayscale via luminance weights. */
    MONO,

    /** High-contrast black & white. */
    NOIR,

    /** Warmer temperature: boosts red, trims blue. */
    WARM,

    /** Cooler temperature: boosts blue, trims red. */
    COOL,

    /** Faded, sepia-leaning vintage tone. */
    VINTAGE,

    /** Low-contrast, lifted-blacks fade. */
    FADE,
}

/**
 * The catalog of available [Filter]s and their color transforms.
 *
 * Every named Filter maps to a distinct [ColorMatrix4x4] that is guaranteed not
 * to equal [ColorMatrix4x4.IDENTITY]; only [Filter.NONE] maps to identity. The
 * matrices are pure algebra ([ColorMatrix4x4]), so they compose with the
 * adjustment layer and can be unit- and property-tested on the JVM without any
 * Android or GPU dependency.
 */
object FilterCatalog {

    /** Rec. 601 luminance weights used for grayscale conversions. */
    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f

    /**
     * The [ColorMatrix4x4] color transform for [filter].
     *
     * [Filter.NONE] returns [ColorMatrix4x4.IDENTITY]; every other Filter returns
     * a distinct, non-identity transform.
     */
    fun colorMatrix(filter: Filter): ColorMatrix4x4 = when (filter) {
        Filter.NONE -> ColorMatrix4x4.IDENTITY
        Filter.VIVID -> VIVID
        Filter.MONO -> MONO
        Filter.NOIR -> NOIR
        Filter.WARM -> WARM
        Filter.COOL -> COOL
        Filter.VINTAGE -> VINTAGE
        Filter.FADE -> FADE
    }

    /** All Filters, including [Filter.NONE], in declaration order. */
    val all: List<Filter> = Filter.entries.toList()

    /**
     * A saturation matrix around the luminance point with the given [amount]
     * (1 = neutral, >1 boosts saturation, <1 desaturates). Preserves alpha.
     */
    private fun saturation(amount: Float): ColorMatrix4x4 {
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

    /** Full grayscale (saturation 0): each channel becomes the luminance. */
    private val GRAYSCALE: ColorMatrix4x4 = saturation(0f)

    /** A per-channel contrast matrix pivoting around mid-gray (0.5). */
    private fun contrast(amount: Float): ColorMatrix4x4 {
        val t = 0.5f * (1f - amount)
        return ColorMatrix4x4.ofRows(
            floatArrayOf(amount, 0f, 0f, t),
            floatArrayOf(0f, amount, 0f, t),
            floatArrayOf(0f, 0f, amount, t),
            floatArrayOf(0f, 0f, 0f, 1f),
        )
    }

    /** VIVID: saturation boost with a slight contrast lift. */
    private val VIVID: ColorMatrix4x4 = contrast(1.1f) * saturation(1.5f)

    /** MONO: neutral grayscale. */
    private val MONO: ColorMatrix4x4 = GRAYSCALE

    /** NOIR: grayscale pushed through a strong contrast curve. */
    private val NOIR: ColorMatrix4x4 = contrast(1.4f) * GRAYSCALE

    /** WARM: lift red, trim blue for a warmer temperature. */
    private val WARM: ColorMatrix4x4 = ColorMatrix4x4.ofRows(
        floatArrayOf(1.1f, 0f, 0f, 0.02f),
        floatArrayOf(0f, 1.0f, 0f, 0.01f),
        floatArrayOf(0f, 0f, 0.85f, 0f),
        floatArrayOf(0f, 0f, 0f, 1f),
    )

    /** COOL: lift blue, trim red for a cooler temperature. */
    private val COOL: ColorMatrix4x4 = ColorMatrix4x4.ofRows(
        floatArrayOf(0.85f, 0f, 0f, 0f),
        floatArrayOf(0f, 1.0f, 0f, 0.01f),
        floatArrayOf(0f, 0f, 1.1f, 0.02f),
        floatArrayOf(0f, 0f, 0f, 1f),
    )

    /** VINTAGE: partial desaturation with a warm, sepia-leaning tint. */
    private val VINTAGE: ColorMatrix4x4 = ColorMatrix4x4.ofRows(
        floatArrayOf(0.9f, 0.05f, 0.0f, 0.03f),
        floatArrayOf(0.05f, 0.85f, 0.05f, 0.02f),
        floatArrayOf(0.0f, 0.05f, 0.7f, 0.0f),
        floatArrayOf(0f, 0f, 0f, 1f),
    ) * saturation(0.75f)

    /** FADE: reduced contrast with lifted blacks for a washed-out look. */
    private val FADE: ColorMatrix4x4 = ColorMatrix4x4.ofRows(
        floatArrayOf(0.8f, 0f, 0f, 0.1f),
        floatArrayOf(0f, 0.8f, 0f, 0.1f),
        floatArrayOf(0f, 0f, 0.8f, 0.1f),
        floatArrayOf(0f, 0f, 0f, 1f),
    )
}
