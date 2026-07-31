package com.shanks.minify.ui.compare

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Preservation property tests for [DividerOps] and the reveal-region math.
 *
 * Feature: editor-compare-slider-fixes, Property 5 (Preservation) — Divider defaults, clamping,
 * and single-source extremes.
 *
 * These tests follow the observation-first methodology: they encode behavior observed on the
 * UNFIXED code so that it is protected against regression when the comparator fix is applied. They
 * therefore MUST PASS on the unfixed code and must continue to pass after the fix.
 *
 * Observed baseline behavior captured here (for inputs outside the bug condition, `¬C`):
 *  - The divider opens centered: [DividerOps.DEFAULT_DIVIDER_FRACTION] == `0.5f`.
 *  - [DividerOps.clampDivider] coerces every float into `[0, 1]` and maps `NaN → 0f`.
 *  - [DividerOps.revealRegions] yields a single source across the whole region at the extremes:
 *    at fraction `0` the "after" region covers the whole width (`beforeWidth == 0`,
 *    `afterWidth == width`) and at fraction `1` the "before" region covers the whole width
 *    (`beforeWidth == width`, `afterWidth == 0`).
 *
 * **Validates: Requirements 3.1, 3.2**
 */
class DividerDefaultsClampAndExtremesPropertyTest {

    // ---------------------------------------------------------------------------------------------
    // Divider default (Req 3.1)
    // ---------------------------------------------------------------------------------------------

    /**
     * Feature: editor-compare-slider-fixes, Property 5 (Preservation).
     *
     * The Compare_Overlay opens centered: the default divider fraction is exactly `0.5`.
     *
     * **Validates: Requirements 3.1**
     */
    @Property(tries = 1)
    fun dividerOpensCentered() {
        assertEquals(
            0.5f,
            DividerOps.DEFAULT_DIVIDER_FRACTION,
            "DEFAULT_DIVIDER_FRACTION must remain the exact center 0.5",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Clamping into [0, 1] with NaN -> 0 (Req 3.1)
    // ---------------------------------------------------------------------------------------------

    /**
     * Feature: editor-compare-slider-fixes, Property 5 (Preservation).
     *
     * For any finite float (including values far outside `[0, 1]` on both sides),
     * [DividerOps.clampDivider] returns a value in `[0, 1]` equal to `raw.coerceIn(0, 1)`.
     *
     * **Validates: Requirements 3.1**
     */
    @Property(tries = 500)
    fun clampDividerCoercesEveryFiniteFloatIntoUnitRange(
        @ForAll("finiteFloats") raw: Float,
    ) {
        val clamped = DividerOps.clampDivider(raw)

        assertTrue(
            clamped in 0f..1f,
            "clampDivider($raw) = $clamped must lie within [0, 1]",
        )
        assertEquals(
            raw.coerceIn(0f, 1f),
            clamped,
            "clampDivider($raw) must equal raw coerced into [0, 1]",
        )
    }

    /**
     * Feature: editor-compare-slider-fixes, Property 5 (Preservation).
     *
     * The non-finite guard is preserved: `NaN` resolves to `0f`.
     *
     * **Validates: Requirements 3.1**
     */
    @Property(tries = 1)
    fun clampDividerMapsNaNToZero() {
        assertEquals(
            0f,
            DividerOps.clampDivider(Float.NaN),
            "clampDivider(NaN) must resolve to 0f",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Single-source reveal at the extremes (Req 3.2)
    // ---------------------------------------------------------------------------------------------

    /**
     * Feature: editor-compare-slider-fixes, Property 5 (Preservation).
     *
     * At fraction `0`, exactly one source covers the whole region: the "before" region collapses to
     * zero width and the "after" region spans the entire width.
     *
     * **Validates: Requirements 3.2**
     */
    @Property(tries = 300)
    fun revealRegionsAtZeroGivesSingleAfterSource(
        @ForAll("widths") width: Float,
    ) {
        val regions = DividerOps.revealRegions(0f, width)

        assertEquals(width, regions.width, "width must be preserved for non-negative widths")
        assertEquals(
            0f,
            regions.beforeWidth,
            "at fraction 0 the before region must collapse to zero width",
        )
        assertEquals(
            width,
            regions.afterWidth,
            "at fraction 0 the after region must span the whole width",
        )
        assertEquals(
            regions.width,
            regions.beforeWidth + regions.afterWidth,
            "before + after must sum to the whole width",
        )
    }

    /**
     * Feature: editor-compare-slider-fixes, Property 5 (Preservation).
     *
     * At fraction `1`, exactly one source covers the whole region: the "before" region spans the
     * entire width and the "after" region collapses to zero width (the reverse of fraction `0`).
     *
     * **Validates: Requirements 3.2**
     */
    @Property(tries = 300)
    fun revealRegionsAtOneGivesSingleBeforeSource(
        @ForAll("widths") width: Float,
    ) {
        val regions = DividerOps.revealRegions(1f, width)

        assertEquals(width, regions.width, "width must be preserved for non-negative widths")
        assertEquals(
            width,
            regions.beforeWidth,
            "at fraction 1 the before region must span the whole width",
        )
        assertEquals(
            0f,
            regions.afterWidth,
            "at fraction 1 the after region must collapse to zero width",
        )
        assertEquals(
            regions.width,
            regions.beforeWidth + regions.afterWidth,
            "before + after must sum to the whole width",
        )
    }

    /**
     * Feature: editor-compare-slider-fixes, Property 5 (Preservation).
     *
     * For any divider fraction and any width, the reveal regions remain well-defined: both widths
     * are non-negative and sum to the (coerced) total width, and the divider sits at the boundary
     * between them. This captures the total, single-overlay invariant the fix must preserve.
     *
     * **Validates: Requirements 3.2**
     */
    @Property(tries = 500)
    fun revealRegionsAlwaysPartitionTheWholeWidth(
        @ForAll("anyFractions") fraction: Float,
        @ForAll("widths") width: Float,
    ) {
        val regions = DividerOps.revealRegions(fraction, width)

        assertTrue(regions.beforeWidth >= 0f, "beforeWidth must be non-negative")
        assertTrue(regions.afterWidth >= 0f, "afterWidth must be non-negative")
        assertTrue(
            regions.beforeWidth <= regions.width,
            "beforeWidth must not exceed the total width",
        )
        assertTrue(
            regions.afterWidth <= regions.width,
            "afterWidth must not exceed the total width",
        )
        // The observed baseline defines afterWidth as the complement of beforeWidth over the
        // (coerced) width, so the two partition the whole region exactly as the code computes them.
        assertEquals(
            regions.width - regions.beforeWidth,
            regions.afterWidth,
            "afterWidth must be the complement of beforeWidth over the whole width",
        )
        assertEquals(
            regions.beforeWidth,
            regions.dividerX,
            "the divider sits at the boundary between the before and after regions",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Generators
    // ---------------------------------------------------------------------------------------------

    /**
     * Finite floats spanning far beyond `[0, 1]` on both sides so the clamp is stressed from both
     * directions, including values inside the range.
     */
    @Provide
    fun finiteFloats(): Arbitrary<Float> =
        Arbitraries.floats().between(-1e6f, 1e6f)

    /** Any divider fraction, including out-of-range values that [DividerOps.clampDivider] normalizes. */
    @Provide
    fun anyFractions(): Arbitrary<Float> =
        Arbitraries.floats().between(-2f, 2f)

    /** Non-negative display widths, from a degenerate zero width up to a large region. */
    @Provide
    fun widths(): Arbitrary<Float> =
        Arbitraries.floats().between(0f, 1e5f)
}
