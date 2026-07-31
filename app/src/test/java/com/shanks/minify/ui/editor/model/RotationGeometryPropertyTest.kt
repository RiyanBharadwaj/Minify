package com.shanks.minify.ui.editor.model

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based test for [RotationGeometry].
 *
 * Feature: media-editor-ux-fixes, Property 7: 90/270 rotation presents the inverse
 * displayed aspect.
 *
 * Validates: Requirements 3.6
 *
 * A clockwise rotation of `90` or `270` degrees swaps a frame's width and height, so
 * the displayed aspect ratio becomes the inverse of the source aspect ratio, while a
 * rotation of `0` or `180` degrees preserves it. This test asserts both behaviors:
 *
 * - [RotationGeometry.displayedAspect] returns the source aspect for `0`/`180` and the
 *   inverse (`1 / sourceAspect`, within `1%`) for `90`/`270`.
 * - [RotationGeometry.displayedSize] preserves `(w, h)` for `0`/`180` and swaps it
 *   exactly to `(h, w)` for `90`/`270`.
 *
 * Generators keep source dimensions and aspects positive and finite so the mapping
 * under test is exercised rather than the degenerate-input coercion path.
 */
class RotationGeometryPropertyTest {

    @Property(tries = 200)
    fun rotationPresentsInverseAspectAndSwapsSize(
        @ForAll("dimensions") sourceW: Int,
        @ForAll("dimensions") sourceH: Int,
        @ForAll("rotations") rotationDegrees: Int,
    ) {
        val sourceAspect = sourceW.toFloat() / sourceH.toFloat()

        val displayedAspect = RotationGeometry.displayedAspect(sourceAspect, rotationDegrees)
        val (displayedW, displayedH) = RotationGeometry.displayedSize(sourceW, sourceH, rotationDegrees)

        val swaps = rotationDegrees == 90 || rotationDegrees == 270

        if (swaps) {
            // Aspect becomes the inverse of the source aspect within 1%.
            val expected = 1f / sourceAspect
            assertRelativeWithinOnePercent(
                expected, displayedAspect,
                "90/270 must present inverse aspect (source=$sourceAspect, rot=$rotationDegrees)",
            )
            // Size swaps exactly.
            assertEquals(
                sourceH, displayedW,
                "90/270 must swap width to source height (rot=$rotationDegrees)",
            )
            assertEquals(
                sourceW, displayedH,
                "90/270 must swap height to source width (rot=$rotationDegrees)",
            )
        } else {
            // Aspect is preserved.
            assertRelativeWithinOnePercent(
                sourceAspect, displayedAspect,
                "0/180 must preserve source aspect (source=$sourceAspect, rot=$rotationDegrees)",
            )
            // Size is preserved exactly.
            assertEquals(
                sourceW, displayedW,
                "0/180 must preserve source width (rot=$rotationDegrees)",
            )
            assertEquals(
                sourceH, displayedH,
                "0/180 must preserve source height (rot=$rotationDegrees)",
            )
        }
    }

    /** Asserts [actual] is within `1%` (relative) of [expected]. */
    private fun assertRelativeWithinOnePercent(expected: Float, actual: Float, message: String) {
        val tolerance = kotlin.math.abs(expected) * 0.01f
        assertTrue(
            kotlin.math.abs(actual - expected) <= tolerance,
            "$message: expected=$expected actual=$actual tolerance=$tolerance",
        )
    }

    /** Positive source dimensions that yield finite, positive aspect ratios. */
    @Provide
    fun dimensions(): Arbitrary<Int> = Arbitraries.integers().between(1, 8192)

    /** The canonical clockwise rotations. */
    @Provide
    fun rotations(): Arbitrary<Int> = Arbitraries.of(0, 90, 180, 270)
}
