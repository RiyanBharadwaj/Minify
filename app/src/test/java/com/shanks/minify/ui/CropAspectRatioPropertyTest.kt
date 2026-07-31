package com.shanks.minify.ui

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.FloatRange
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [CropRect.forAspectRatio], the pure crop math behind
 * the Photo Editor's aspect-ratio preset chips.
 *
 * A [CropRect] is normalized in [0,1] relative to the image's display frame, so
 * its width-to-height ratio in *pixel* space is
 * `(crop.width / crop.height) * imageAspectRatio`. Locking to a preset should
 * make that pixel ratio equal the preset ratio, while keeping the crop inside
 * the unit square.
 */
class CropAspectRatioPropertyTest {

    // Feature: media-editing-suite, Property 3: Aspect-ratio preset constrains the crop
    @Property(tries = 300)
    fun aspectRatioPresetConstrainsTheCrop(
        @ForAll("presetRatios") targetAr: Float,
        @ForAll @FloatRange(min = 0.05f, max = 20f) imageAr: Float,
    ) {
        val crop = CropRect.forAspectRatio(targetAr, imageAr)

        // Lies within the unit bounds [0,1]x[0,1] with a well-ordered rectangle.
        assertTrue(crop.left >= -EPS, "left within bounds: ${crop.left}")
        assertTrue(crop.top >= -EPS, "top within bounds: ${crop.top}")
        assertTrue(crop.right <= 1f + EPS, "right within bounds: ${crop.right}")
        assertTrue(crop.bottom <= 1f + EPS, "bottom within bounds: ${crop.bottom}")
        assertTrue(crop.left < crop.right, "left < right: ${crop.left} !< ${crop.right}")
        assertTrue(crop.top < crop.bottom, "top < bottom: ${crop.top} !< ${crop.bottom}")

        // Pixel-space width-to-height ratio equals the preset ratio within tolerance.
        val pixelRatio = (crop.width / crop.height) * imageAr
        val tolerance = 1e-3f * targetAr + 1e-4f
        assertTrue(
            kotlin.math.abs(pixelRatio - targetAr) <= tolerance,
            "pixel ratio $pixelRatio should equal preset $targetAr (imageAr=$imageAr)",
        )
    }

    /** Every preset that locks a fixed ratio (i.e. excluding FREE). */
    @Provide
    fun presetRatios(): Arbitrary<Float> =
        Arbitraries.of(*AspectRatioPreset.values().mapNotNull { it.ratio }.toTypedArray())

    private companion object {
        const val EPS = 1e-4f
    }
}
