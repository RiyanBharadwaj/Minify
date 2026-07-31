package com.shanks.minify.ui.editor.model

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [TrimBarGeometry.fraction], the pure, NaN-safe
 * position→fraction helper behind the video trim bar.
 *
 * The trim bar divides a position (ms) by the total media duration (ms) to place
 * its handles and playhead. This helper must be total: for any [Long] position
 * and duration it returns a finite fraction in `[0, 1]`, and returns `0f` when the
 * duration is non-positive (zero/unknown duration).
 */
class TrimBarGeometryPropertyTest {

    // Feature: media-editor-fixes, Property 12: Trim-bar fractions are finite and bounded for any duration
    // Validates: Requirements 13.1
    @Property(tries = 200)
    fun fractionIsFiniteBoundedAndZeroForNonPositiveDuration(
        @ForAll positionMs: Long,
        @ForAll durationMs: Long,
    ) {
        val result = TrimBarGeometry.fraction(positionMs, durationMs)

        // Always finite: never NaN or infinity.
        assertFalse(result.isNaN(), "fraction should never be NaN (pos=$positionMs, dur=$durationMs)")
        assertFalse(
            result.isInfinite(),
            "fraction should never be infinite (pos=$positionMs, dur=$durationMs)",
        )

        // Always bounded within the inclusive range [0, 1].
        assertTrue(result >= 0f, "fraction should be >= 0 (pos=$positionMs, dur=$durationMs): $result")
        assertTrue(result <= 1f, "fraction should be <= 1 (pos=$positionMs, dur=$durationMs): $result")

        // Non-positive duration collapses to 0.
        if (durationMs <= 0L) {
            assertEquals(
                0f,
                result,
                "fraction should be 0 for non-positive duration (pos=$positionMs, dur=$durationMs)",
            )
        }
    }
}
