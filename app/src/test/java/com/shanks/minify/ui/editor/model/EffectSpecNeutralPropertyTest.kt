package com.shanks.minify.ui.editor.model

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for the neutral ⇔ identity guarantee of [EffectSpec.from]:
 * the single fact both thin renderers rely on to skip a no-op grade.
 *
 * A [ColorGrade] is neutral exactly when every adjustment is at its neutral, the
 * filter is [Filter.NONE], and the vignette is `0`. Deriving an [EffectSpec] from
 * a neutral grade must yield the identity spec (composed matrix equal to
 * [ColorMatrix4x4.IDENTITY] and every parametric pass at `0`), and any grade with
 * a real, visible contribution must not (Req 4.3, 4.4, 5.3).
 */
class EffectSpecNeutralPropertyTest {

    // Feature: unified-media-editor, Property 9: A neutral color grade yields the identity EffectSpec, and any non-neutral value does not
    @Property(tries = 200)
    fun neutralGradeYieldsIdentityEffectSpec(
        @ForAll("neutralGrades") grade: ColorGrade,
    ) {
        // Precondition: the generator only produces neutral grades.
        assertTrue(grade.isNeutral, "generator must produce a neutral grade: $grade")

        val spec = EffectSpec.from(grade)

        assertTrue(
            spec.isIdentity,
            "a neutral grade must derive the identity EffectSpec, got $spec for $grade",
        )
    }

    // Feature: unified-media-editor, Property 9: A neutral color grade yields the identity EffectSpec, and any non-neutral value does not
    @Property(tries = 300)
    fun nonNeutralGradeDoesNotYieldIdentityEffectSpec(
        @ForAll("nonNeutralGrades") grade: ColorGrade,
    ) {
        // Precondition: the generator only produces non-neutral grades.
        assertFalse(grade.isNeutral, "generator must produce a non-neutral grade: $grade")

        val spec = EffectSpec.from(grade)

        assertFalse(
            spec.isIdentity,
            "a non-neutral grade must not derive the identity EffectSpec, got $spec for $grade",
        )
    }

    // Feature: unified-media-editor, Property 9: A neutral color grade yields the identity EffectSpec, and any non-neutral value does not
    @Property(tries = 300)
    fun identityHoldsExactlyForNeutralGrades(
        @ForAll("anyGrades") grade: ColorGrade,
    ) {
        // isIdentity of the derived spec agrees with isNeutral of the source grade,
        // in both directions, across the mixed neutral/non-neutral generator.
        assertEquals(
            grade.isNeutral,
            EffectSpec.from(grade).isIdentity,
            "EffectSpec.isIdentity must equal ColorGrade.isNeutral for $grade",
        )
    }

    /** Grades built to be fully neutral, via several equivalent constructions. */
    @Provide
    fun neutralGrades(): Arbitrary<ColorGrade> {
        val canonical = Arbitraries.just(ColorGrade.NEUTRAL)
        // Explicitly writing each adjustment's own neutral value must stay neutral.
        val rewrittenNeutral = kinds().map { kind ->
            ColorGrade(adjustments = Adjustments.NEUTRAL.with(kind, kind.neutral))
        }
        // Explicitly setting the neutral filter and neutral vignette must stay neutral.
        val explicitDefaults = Arbitraries.just(
            ColorGrade(Adjustments.NEUTRAL, Filter.NONE, ColorGrade.NEUTRAL_VIGNETTE),
        )
        return Arbitraries.oneOf(canonical, rewrittenNeutral, explicitDefaults)
    }

    /**
     * Grades with exactly one real, visible contribution: an off-neutral
     * adjustment, a named filter, or a non-zero vignette. Each is guaranteed both
     * [ColorGrade.isNeutral] `== false` and to derive a non-identity [EffectSpec].
     */
    @Provide
    fun nonNeutralGrades(): Arbitrary<ColorGrade> =
        Arbitraries.oneOf(
            offNeutralAdjustmentGrades(),
            namedFilterGrades(),
            vignetteGrades(),
        )

    /** A mix of neutral and non-neutral grades to exercise both directions at once. */
    @Provide
    fun anyGrades(): Arbitrary<ColorGrade> =
        Arbitraries.oneOf(neutralGrades(), nonNeutralGrades())

    private fun offNeutralAdjustmentGrades(): Arbitrary<ColorGrade> =
        Combinators.combine(kinds(), offNeutralMagnitudes(), signs())
            .`as` { kind, magnitude, sign ->
                // Keep the value inside the kind's range while staying clear of the
                // neutral 0 by more than the matrix comparison tolerance, so the
                // contribution is always visible in the derived EffectSpec.
                val raw = if (kind.min < kind.neutral) sign * magnitude else magnitude
                ColorGrade(adjustments = Adjustments.NEUTRAL.with(kind, raw))
            }

    private fun namedFilterGrades(): Arbitrary<ColorGrade> =
        Arbitraries.of(*Filter.entries.toTypedArray())
            .filter { it != Filter.NONE }
            .map { ColorGrade(filter = it) }

    private fun vignetteGrades(): Arbitrary<ColorGrade> =
        Arbitraries.floats()
            .between(0.05f, ColorGrade.MAX_VIGNETTE)
            .map { ColorGrade(vignette = it) }

    @Provide
    fun kinds(): Arbitrary<AdjustmentKind> =
        Arbitraries.of(*AdjustmentKind.entries.toTypedArray())

    /**
     * Off-neutral magnitudes kept well above the `1e-5` matrix tolerance (and the
     * temperature/tint `0.2` per-unit gain) so a single perturbed adjustment
     * always shifts the composed matrix or a parametric pass off identity.
     */
    private fun offNeutralMagnitudes(): Arbitrary<Float> =
        Arbitraries.floats().between(0.05f, 1f)

    private fun signs(): Arbitrary<Float> =
        Arbitraries.of(-1f, 1f)
}
