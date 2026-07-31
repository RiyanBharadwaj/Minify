package com.shanks.minify.ui.compare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit test for the default Comparison_Slider position (Requirement 6.3).
 *
 * When the Compare_Overlay first opens it must place the divider at the exact
 * center (`0.5`) of the shared display region. Both [ImageComparator] and
 * [VideoComparator] seed their initial `dividerFraction` from
 * [DividerOps.DEFAULT_DIVIDER_FRACTION], so asserting on that pure constant
 * verifies the "opens centered" behavior without a running Composable.
 */
class DefaultDividerPositionTest {

    // Feature: media-editor-ux-fixes, Requirement 6.3: Compare_Overlay opens with the slider centered
    @Test
    fun defaultDividerFractionIsCenter() {
        assertEquals(
            0.5f,
            DividerOps.DEFAULT_DIVIDER_FRACTION,
            0f,
            "Compare_Overlay must open with the Comparison_Slider centered at 0.5",
        )
    }

    // Feature: media-editor-ux-fixes, Requirement 6.3: the center default is a valid in-range divider position
    @Test
    fun defaultDividerFractionIsAValidInRangePosition() {
        val default = DividerOps.DEFAULT_DIVIDER_FRACTION

        // Center is within the inclusive [0,1] divider range.
        assertTrue(default in 0f..1f, "default divider fraction must lie within [0,1], was $default")

        // Clamping the center leaves it unchanged: 0.5 is a valid default the
        // drag path (DividerOps.clampDivider) accepts as-is.
        assertEquals(
            default,
            DividerOps.clampDivider(default),
            0f,
            "clampDivider must leave the centered default unchanged",
        )
    }
}
