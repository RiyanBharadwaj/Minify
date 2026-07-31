package com.shanks.minify.ui.editor.model

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [Adjustments.with], the pure clamping step the
 * unified editor's Adjustment_Panel applies to every slider value.
 *
 * A slider can request any raw value (including values well outside the
 * control's bounded range); the stored value must always be coerced into the
 * kind's inclusive `[min, max]` range (Req 4.2).
 */
class AdjustmentsClampPropertyTest {

    // Feature: unified-media-editor, Property 8: Adjustment values are clamped to their bounded range
    @Property(tries = 300)
    fun adjustmentValuesAreClampedToTheirBoundedRange(
        @ForAll("kinds") kind: AdjustmentKind,
        @ForAll("raws") raw: Float,
    ) {
        val stored = Adjustments.NEUTRAL.with(kind, raw)[kind]

        // The stored value lies within the kind's inclusive [min, max] range.
        assertTrue(
            stored in kind.min..kind.max,
            "stored value $stored must lie within [${kind.min}, ${kind.max}] " +
                "for $kind (raw=$raw)",
        )

        // And it equals the raw value coerced into that range.
        assertEquals(
            raw.coerceIn(kind.min, kind.max),
            stored,
            "stored value must equal raw coerced into [${kind.min}, ${kind.max}] " +
                "for $kind (raw=$raw)",
        )
    }

    @Provide
    fun kinds(): Arbitrary<AdjustmentKind> =
        Arbitraries.of(*AdjustmentKind.entries.toTypedArray())

    @Provide
    fun raws(): Arbitrary<Float> =
        // Raw slider values spanning far beyond any control's [min, max] so the
        // clamp is stressed from both sides, including values inside the range.
        Arbitraries.floats().between(-1e9f, 1e9f)
}
