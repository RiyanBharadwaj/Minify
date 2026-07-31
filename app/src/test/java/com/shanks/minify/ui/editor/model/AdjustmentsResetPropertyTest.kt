package com.shanks.minify.ui.editor.model

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Property-based tests for [Adjustments.reset].
 *
 * Feature: unified-media-editor, Property 11: Resetting an adjustment restores its fixed neutral value
 *
 * Resetting a single [AdjustmentKind] must restore that kind to its fixed
 * [AdjustmentKind.neutral] value (which is media-type independent, Req 4.6/4.7)
 * while leaving every other kind's value untouched.
 */
class AdjustmentsResetPropertyTest {

    // Feature: unified-media-editor, Property 11: Resetting an adjustment restores its fixed neutral value
    /**
     * For any [Adjustments] value and any target [AdjustmentKind], calling
     * [Adjustments.reset] on that kind sets it to the kind's fixed neutral value and
     * leaves all other kinds unchanged from their pre-reset values.
     *
     * **Validates: Requirements 4.6**
     */
    @Property(tries = 300)
    fun resetRestoresFixedNeutralAndLeavesOthersUnchanged(
        @ForAll("adjustments") before: Adjustments,
        @ForAll("kinds") target: AdjustmentKind,
    ) {
        val after = before.reset(target)

        // The reset kind returns to its fixed, media-type-independent neutral value.
        assertEquals(
            target.neutral,
            after[target],
            "reset($target) must restore its fixed neutral ${target.neutral}, was ${after[target]}",
        )

        // Every other kind keeps exactly the value it had before the reset.
        for (kind in AdjustmentKind.entries) {
            if (kind == target) continue
            assertEquals(
                before[kind],
                after[kind],
                "reset($target) must leave $kind unchanged: was ${before[kind]}, became ${after[kind]}",
            )
        }
    }

    @Provide
    fun kinds(): Arbitrary<AdjustmentKind> =
        Arbitraries.of(*AdjustmentKind.entries.toTypedArray())

    @Provide
    fun adjustments(): Arbitrary<Adjustments> =
        // Fold one finite raw value per kind (spanning below, within, and above each
        // range so `with` clamps into `[min, max]`) onto a neutral base, yielding an
        // arbitrary-but-valid value for every kind.
        combineRaws(Adjustments.NEUTRAL, AdjustmentKind.entries.toList())

    /** Recursively draw a clamped raw value for each remaining kind. */
    private fun combineRaws(
        acc: Adjustments,
        kinds: List<AdjustmentKind>,
    ): Arbitrary<Adjustments> {
        if (kinds.isEmpty()) return Arbitraries.just(acc)
        val kind = kinds.first()
        return Arbitraries.floats().between(-2f, 2f).flatMap { raw ->
            combineRaws(acc.with(kind, raw), kinds.drop(1))
        }
    }
}
