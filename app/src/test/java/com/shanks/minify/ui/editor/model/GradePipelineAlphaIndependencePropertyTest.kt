package com.shanks.minify.ui.editor.model

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Property-based test for [GradePipeline.applyReference]'s alpha-independence of
 * the brightness/contrast additive offsets (Req 9.3).
 *
 * The brightness and contrast controls contribute their additive offset in the
 * alpha column of [EffectSpec.rgbMatrix]. A naive `mat4 · rgba` multiply would
 * scale that offset by the pixel's alpha, so a semi-transparent pixel would be
 * graded differently from an opaque one. [GradePipeline.applyReference] instead
 * adds the offset column as if `a == 1`, so the additive grade is independent of
 * the pixel's alpha.
 *
 * The property fixes the rgb triple and the brightness/contrast offset while
 * varying only the alpha, and asserts the graded rgb channels are identical for
 * two different alphas. [GradePipeline.Neighborhood.self] makes the spatial
 * passes (sharpness, blur, vignette) exact no-ops so the test isolates the
 * purely local color transform.
 */
class GradePipelineAlphaIndependencePropertyTest {

    // Feature: media-editor-fixes, Property 9: Brightness and contrast offsets are independent of alpha
    /**
     * Property 9: Brightness and contrast offsets are independent of alpha.
     *
     * For any RGB triple, any two alpha values, and any brightness/contrast
     * additive offset, the graded RGB result of [GradePipeline.applyReference] is
     * identical for the two alphas, so non-opaque pixels receive the same
     * additive grade as opaque pixels.
     *
     * **Validates: Requirements 9.3**
     */
    @Property(tries = 300)
    fun brightnessAndContrastOffsetsAreIndependentOfAlpha(
        @ForAll("scenarios") scenario: Scenario,
    ) {
        val spec = EffectSpec.from(
            ColorGrade(
                adjustments = Adjustments.NEUTRAL
                    .with(AdjustmentKind.BRIGHTNESS, scenario.brightness)
                    .with(AdjustmentKind.CONTRAST, scenario.contrast),
            ),
        )

        val pixelLow = floatArrayOf(scenario.r, scenario.g, scenario.b, scenario.alphaA)
        val pixelHigh = floatArrayOf(scenario.r, scenario.g, scenario.b, scenario.alphaB)

        // Neighborhood.self makes sharpness/blur/vignette no-ops, isolating the
        // color transform whose alpha-independence is under test.
        val gradedLow = GradePipeline.applyReference(
            pixelLow,
            spec,
            GradePipeline.Neighborhood.self(pixelLow),
        )
        val gradedHigh = GradePipeline.applyReference(
            pixelHigh,
            spec,
            GradePipeline.Neighborhood.self(pixelHigh),
        )

        // The graded rgb channels must match regardless of the pixel's alpha.
        assertEquals(gradedLow[0], gradedHigh[0], 0f, "red channel must be alpha-independent")
        assertEquals(gradedLow[1], gradedHigh[1], 0f, "green channel must be alpha-independent")
        assertEquals(gradedLow[2], gradedHigh[2], 0f, "blue channel must be alpha-independent")
    }

    @Provide
    fun scenarios(): Arbitrary<Scenario> {
        val channel: Arbitrary<Float> = Arbitraries.floats().between(0f, 1f).ofScale(4)
        val alpha: Arbitrary<Float> = Arbitraries.floats().between(0f, 1f).ofScale(4)
        // Brightness/contrast additive offsets over their full [-1, 1] range.
        val offset: Arbitrary<Float> = Arbitraries.floats().between(-1f, 1f).ofScale(4)
        return Combinators.combine(
            channel, channel, channel,
            alpha, alpha,
            offset, offset,
        ).`as` { r, g, b, alphaA, alphaB, brightness, contrast ->
            Scenario(r, g, b, alphaA, alphaB, brightness, contrast)
        }
    }

    data class Scenario(
        val r: Float,
        val g: Float,
        val b: Float,
        val alphaA: Float,
        val alphaB: Float,
        val brightness: Float,
        val contrast: Float,
    )
}
