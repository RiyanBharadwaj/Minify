package com.shanks.minify.ui.compare

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.math.abs

/**
 * Property-based tests for [DividerOps.clampDivider], the pure clamping step the
 * before/after image comparator applies to the draggable reveal divider after
 * every horizontal drag.
 *
 * The comparator feeds an arbitrary drag-derived fraction into [DividerOps.clampDivider];
 * the result must always be a valid fraction in [0,1], and the mapping must be
 * monotonically non-decreasing so dragging right never moves the reveal boundary left.
 */
class DividerOpsPropertyTest {

    // Feature: media-editing-suite, Property 10: Divider fraction is clamped and monotonic
    @Property(tries = 300)
    fun dividerFractionIsClampedAndMonotonic(
        @ForAll("finiteFractions") a: Float,
        @ForAll("finiteFractions") b: Float,
    ) {
        val ca = DividerOps.clampDivider(a)
        val cb = DividerOps.clampDivider(b)

        // Both results are always valid fractions within [0,1].
        assertTrue(ca in 0f..1f, "clampDivider must lie within [0,1], was $ca (from $a)")
        assertTrue(cb in 0f..1f, "clampDivider must lie within [0,1], was $cb (from $b)")

        // Monotonic non-decreasing: a <= b implies clampDivider(a) <= clampDivider(b).
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        assertTrue(
            DividerOps.clampDivider(lo) <= DividerOps.clampDivider(hi),
            "clampDivider must be monotonic: clampDivider($lo)=${DividerOps.clampDivider(lo)} " +
                "must be <= clampDivider($hi)=${DividerOps.clampDivider(hi)}",
        )
    }

    // Feature: media-editor-ux-fixes, Property 10: Wipe reveal shows before on the leading side and after on the trailing side
    @Property(tries = 300)
    fun wipeRevealShowsBeforeLeadingAndAfterTrailing(
        @ForAll("inRangeFractions") fraction: Float,
        @ForAll("nonNegativeWidths") width: Float,
    ) {
        val regions = DividerOps.revealRegions(fraction, width)

        // Tolerance scales with the magnitude of the width so large widths do not
        // trip on float rounding of the fraction × width product.
        val tolerance = 1e-3f * maxOf(1f, abs(width))

        // "Before" covers the leading region of width fraction × width.
        assertTrue(
            abs(regions.beforeWidth - fraction * width) <= tolerance,
            "beforeWidth (${regions.beforeWidth}) must equal fraction × width " +
                "(${fraction * width}) for fraction=$fraction, width=$width",
        )

        // "After" covers the remainder of the region.
        assertTrue(
            abs(regions.afterWidth - (width - regions.beforeWidth)) <= tolerance,
            "afterWidth (${regions.afterWidth}) must equal width − beforeWidth " +
                "(${width - regions.beforeWidth}) for fraction=$fraction, width=$width",
        )

        // The two regions partition the whole display: they sum to the width.
        assertTrue(
            abs((regions.beforeWidth + regions.afterWidth) - width) <= tolerance,
            "beforeWidth + afterWidth (${regions.beforeWidth + regions.afterWidth}) " +
                "must equal width ($width) for fraction=$fraction, width=$width",
        )

        // Neither region is ever negative.
        assertTrue(regions.beforeWidth >= 0f, "beforeWidth must be non-negative, was ${regions.beforeWidth}")
        assertTrue(regions.afterWidth >= 0f, "afterWidth must be non-negative, was ${regions.afterWidth}")

        // The reported width is the (coerced) input width for non-negative inputs.
        assertEquals(width, regions.width, tolerance, "reported width must match the input width")
    }

    // Feature: media-editor-ux-fixes, Property 10: Wipe reveal shows before on the leading side and after on the trailing side
    @Property(tries = 300)
    fun wipeRevealEndpointsShowSingleSide(
        @ForAll("nonNegativeWidths") width: Float,
    ) {
        // Fraction 0: "after" covers the whole region, "before" shows nothing.
        val atZero = DividerOps.revealRegions(0f, width)
        assertEquals(0f, atZero.beforeWidth, 0f, "at fraction 0 beforeWidth must be 0")
        assertEquals(width, atZero.afterWidth, 1e-3f * maxOf(1f, abs(width)), "at fraction 0 afterWidth must cover the whole width")

        // Fraction 1: "before" covers the whole region, "after" shows nothing.
        val atOne = DividerOps.revealRegions(1f, width)
        assertEquals(width, atOne.beforeWidth, 1e-3f * maxOf(1f, abs(width)), "at fraction 1 beforeWidth must cover the whole width")
        assertEquals(0f, atOne.afterWidth, 1e-3f * maxOf(1f, abs(width)), "at fraction 1 afterWidth must be 0")
    }

    // Feature: media-editor-ux-fixes, Property 10: Wipe reveal shows before on the leading side and after on the trailing side
    @Property(tries = 300)
    fun wipeRevealCoercesDegenerateInputs(
        @ForAll("unitFractions") fraction: Float,
        @ForAll("degenerateWidths") width: Float,
    ) {
        // Negative or NaN widths are coerced to 0, yielding empty regions that still partition.
        val regions = DividerOps.revealRegions(fraction, width)
        assertEquals(0f, regions.width, 0f, "degenerate width must coerce to 0")
        assertEquals(0f, regions.beforeWidth, 0f, "beforeWidth must be 0 for a coerced width")
        assertEquals(0f, regions.afterWidth, 0f, "afterWidth must be 0 for a coerced width")
    }

    @Provide
    fun inRangeFractions(): Arbitrary<Float> {
        // Valid divider fractions within [0,1]; the reveal invariant beforeWidth ==
        // fraction × width is stated for fractions already in range.
        return Arbitraries.floats().between(0f, 1f)
    }

    @Provide
    fun unitFractions(): Arbitrary<Float> {
        // Divider fractions across and slightly beyond [0,1] (revealRegions normalizes
        // via clampDivider). NaN is exercised separately below.
        return Arbitraries.floats().between(-0.5f, 1.5f)
    }

    @Provide
    fun nonNegativeWidths(): Arbitrary<Float> {
        // Display widths spanning zero-width up to large pixel extents.
        return Arbitraries.floats().between(0f, 10_000f)
    }

    @Provide
    fun degenerateWidths(): Arbitrary<Float> {
        // Negative and NaN widths that revealRegions must coerce to 0.
        return Arbitraries.of(Float.NaN, -0.001f, -1f, -100f, -10_000f, Float.NEGATIVE_INFINITY)
    }

    @Provide
    fun finiteFractions(): Arbitrary<Float> {
        // Finite drag-derived fractions spanning below, within, and above [0,1] so the
        // clamp is stressed at both ends. NaN is excluded because clampDivider maps NaN
        // to 0, which is intentionally outside the monotonic ordering check.
        return Arbitraries.floats().between(-2f, 3f)
    }

    // Feature: media-editor-ux-fixes, Property 11: Divider position clamps to [0,1]
    @Property(tries = 300)
    fun dividerPositionClampsToUnitInterval(@ForAll("anyFloats") input: Float) {
        val result = DividerOps.clampDivider(input)

        // The result is always a valid fraction within the inclusive range [0,1].
        assertTrue(result in 0f..1f, "clampDivider must lie within [0,1], was $result (from $input)")

        // The clamp resolves to the nearest boundary when out of range, maps NaN to 0,
        // and otherwise leaves in-range inputs unchanged.
        val expected = when {
            input.isNaN() -> 0f
            input < 0f -> 0f
            input > 1f -> 1f
            else -> input
        }
        assertEquals(expected, result, "clampDivider($input) must resolve to $expected, was $result")
    }

    @Provide
    fun anyFloats(): Arbitrary<Float> {
        // Any float input the divider might receive: a wide finite range spanning below,
        // within, and above [0,1], plus the non-finite / boundary special values (NaN and
        // both infinities) that stress the out-of-range and NaN handling of Req 6.5.
        val ranged: Arbitrary<Float> = Arbitraries.floats().between(-1000f, 1000f)
        val specials: Arbitrary<Float> = Arbitraries.of(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            -0f,
            0f,
            1f,
            Float.MAX_VALUE,
            -Float.MAX_VALUE,
            Float.MIN_VALUE,
        )
        return Arbitraries.oneOf(ranged, specials)
    }
}
