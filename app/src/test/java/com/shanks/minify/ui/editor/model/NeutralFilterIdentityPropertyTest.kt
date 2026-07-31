package com.shanks.minify.ui.editor.model

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Property-based test for the neutral-filter identity guarantee of [EffectSpec.from]
 * (Req 5.5): selecting the neutral (no filter) option leaves the graded frame
 * indistinguishable from the ungraded source.
 *
 * A [ColorGrade] whose [ColorGrade.filter] is [Filter.NONE] and whose adjustments
 * and vignette are all neutral contributes no color transform, so
 * [EffectSpec.from] derives an [EffectSpec.rgbMatrix] equal to
 * [ColorMatrix4x4.IDENTITY]. Applying that matrix to any RGBA pixel must return
 * each channel unchanged within `1` on an 8-bit `[0,255]` scale.
 *
 * A pixel is modeled as an RGBA 4-vector in `[0, 1]` (the same convention as
 * [ColorMatrix4x4.apply]); the test samples each RGB channel over the full 8-bit
 * `0..255` range, applies the neutral [EffectSpec.rgbMatrix], and asserts the
 * round-tripped 8-bit channel differs from the input by at most `1`.
 */
class NeutralFilterIdentityPropertyTest {

    /** Full 8-bit scale used to project the normalized channel back to `[0,255]`. */
    private val eightBitMax = 255f

    /** Allowed per-channel difference on the 8-bit scale (Req 5.5). */
    private val toleranceEightBit = 1

    // Feature: media-editor-ux-fixes, Property 9: Neutral filter leaves channels unchanged
    /**
     * Property 9: Neutral filter leaves channels unchanged.
     *
     * For any RGB input over the 8-bit `0..255` range (and any alpha), applying
     * the [EffectSpec.rgbMatrix] derived from a neutral grade (Filter.NONE, no
     * other color contribution) leaves each channel within `1` on an 8-bit
     * `[0,255]` scale, i.e. the neutral filter is an identity transform.
     *
     * **Validates: Requirements 5.5**
     */
    @Property(tries = 200)
    fun neutralFilterLeavesChannelsUnchanged(
        @ForAll("neutralGrades") grade: ColorGrade,
        @ForAll("pixels8Bit") pixel: IntArray,
    ) {
        // Precondition: the grade is genuinely neutral (Filter.NONE, no contribution).
        assertTrue(grade.isNeutral, "generator must produce a neutral grade: $grade")

        val spec = EffectSpec.from(grade)

        // Normalize the 8-bit RGBA pixel into the [0,1] vector the matrix expects.
        val rgba = floatArrayOf(
            pixel[0] / eightBitMax,
            pixel[1] / eightBitMax,
            pixel[2] / eightBitMax,
            pixel[3] / eightBitMax,
        )

        val graded = spec.rgbMatrix.apply(rgba)

        // Each RGB channel, projected back to 8 bits, must match the input within 1.
        for (channel in 0 until 3) {
            val original = pixel[channel]
            val transformed = (graded[channel] * eightBitMax).roundToInt()
            assertTrue(
                abs(transformed - original) <= toleranceEightBit,
                "neutral filter must leave channel $channel unchanged within " +
                    "$toleranceEightBit on an 8-bit scale: original=$original " +
                    "transformed=$transformed for pixel=${pixel.toList()} grade=$grade",
            )
        }
    }

    /**
     * Neutral grades built via several equivalent constructions, each with
     * [Filter.NONE] and no other color contribution so the derived
     * [EffectSpec.rgbMatrix] is exactly the identity.
     */
    @Provide
    fun neutralGrades(): Arbitrary<ColorGrade> {
        val canonical = Arbitraries.just(ColorGrade.NEUTRAL)
        // Explicitly spelling out Filter.NONE, neutral adjustments, and neutral
        // vignette must be equivalent to the canonical neutral grade.
        val explicit = Arbitraries.just(
            ColorGrade(Adjustments.NEUTRAL, Filter.NONE, ColorGrade.NEUTRAL_VIGNETTE),
        )
        return Arbitraries.oneOf(canonical, explicit)
    }

    /** RGBA pixels with each channel drawn from the full 8-bit `0..255` range. */
    @Provide
    fun pixels8Bit(): Arbitrary<IntArray> {
        val channel: Arbitrary<Int> = Arbitraries.integers().between(0, 255)
        return Combinators.combine(channel, channel, channel, channel)
            .`as` { r, g, b, a -> intArrayOf(r, g, b, a) }
    }
}
