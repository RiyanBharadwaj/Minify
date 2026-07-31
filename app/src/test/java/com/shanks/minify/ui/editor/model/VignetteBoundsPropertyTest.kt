package com.shanks.minify.ui.editor.model

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [ColorGrade.withVignette] and [EffectSpec.from], the pure clamping step
 * the unified editor applies whenever the Vignette tool records an amount (Req 5.4).
 *
 * The Vignette control can request any raw value (including values well outside the bounded range,
 * negative amounts, or huge magnitudes); the stored [ColorGrade.vignette] and the derived
 * [EffectSpec.vignette] must always be coerced into the inclusive `[MIN_VIGNETTE, MAX_VIGNETTE]` =
 * `[0, 1]` range. Editing is non-destructive: [ColorGrade.withVignette] returns a new value and
 * leaves the source unchanged.
 */
class VignetteBoundsPropertyTest {

    // Feature: unified-media-editor, Property 12: Vignette is recorded within its bounded range
    /**
     * Feature: unified-media-editor, Property 12: Vignette is recorded within its bounded range.
     *
     * For any raw vignette float, [ColorGrade.withVignette] stores a value within
     * `[MIN_VIGNETTE, MAX_VIGNETTE]` = `[0, 1]` equal to `raw.coerceIn(0, 1)`, and the
     * [EffectSpec] derived from that grade carries the same bounded vignette (Req 5.4).
     *
     * **Validates: Requirements 5.4**
     */
    @Property(tries = 300)
    fun vignetteIsRecordedWithinItsBoundedRange(
        @ForAll("rawVignettes") raw: Float,
    ) {
        val graded = ColorGrade.NEUTRAL.withVignette(raw)

        // The stored vignette lies within the bounded [0, 1] range (Req 5.4).
        assertTrue(
            graded.vignette in ColorGrade.MIN_VIGNETTE..ColorGrade.MAX_VIGNETTE,
            "stored vignette ${graded.vignette} must lie within " +
                "[${ColorGrade.MIN_VIGNETTE}, ${ColorGrade.MAX_VIGNETTE}] (raw=$raw)",
        )

        // And equals the raw value coerced into that range (Req 5.4).
        assertEquals(
            raw.coerceIn(ColorGrade.MIN_VIGNETTE, ColorGrade.MAX_VIGNETTE),
            graded.vignette,
            "stored vignette must equal raw coerced into " +
                "[${ColorGrade.MIN_VIGNETTE}, ${ColorGrade.MAX_VIGNETTE}] (raw=$raw)",
        )

        // Editing is non-destructive: the source neutral grade is unchanged (Req 5.4).
        assertEquals(
            ColorGrade.NEUTRAL_VIGNETTE,
            ColorGrade.NEUTRAL.vignette,
            "withVignette must not mutate the source grade",
        )

        // The derived EffectSpec carries the same bounded vignette (Req 5.4).
        val spec = EffectSpec.from(graded)
        assertTrue(
            spec.vignette in ColorGrade.MIN_VIGNETTE..ColorGrade.MAX_VIGNETTE,
            "EffectSpec vignette ${spec.vignette} must lie within " +
                "[${ColorGrade.MIN_VIGNETTE}, ${ColorGrade.MAX_VIGNETTE}] (raw=$raw)",
        )
        assertEquals(
            graded.vignette,
            spec.vignette,
            "EffectSpec vignette must equal the grade's clamped vignette (raw=$raw)",
        )
    }

    /**
     * Raw vignette amounts spanning far beyond `[0, 1]` on both sides (including negative and
     * greater-than-max values) so the clamp is stressed from both directions, plus values inside
     * the range.
     */
    @Provide
    fun rawVignettes(): Arbitrary<Float> =
        Arbitraries.floats().between(-1e6f, 1e6f)
}
