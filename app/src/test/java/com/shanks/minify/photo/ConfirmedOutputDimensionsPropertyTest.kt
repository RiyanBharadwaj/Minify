package com.shanks.minify.photo

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Property-based test for the Photo Editor's confirmed output dimensions.
 *
 * Exercises the pure [outputDimensions] helper that derives the edited image's
 * pixel dimensions from the full-resolution source `(w, h)` and the applied
 * rotation, independent of any downsampled preview or Android bitmap.
 */
class ConfirmedOutputDimensionsPropertyTest {

    // Feature: unified-media-editor, Property 23: Confirmed output dimensions follow the rotation of the full-resolution source
    // Validates: Requirements 11.5
    @Property(tries = 200)
    fun confirmedOutputDimensionsFollowRotationOfFullResolutionSource(
        @ForAll @IntRange(min = 1, max = 20000) w: Int,
        @ForAll @IntRange(min = 1, max = 20000) h: Int,
        @ForAll("rotations") rotation: Int,
    ) {
        val (outW, outH) = outputDimensions(rotation, w, h)

        if (rotation == 90 || rotation == 270) {
            // A quarter turn swaps the axes.
            assertEquals(h, outW)
            assertEquals(w, outH)
        } else {
            // 0° / 180° preserve the source axes.
            assertEquals(w, outW)
            assertEquals(h, outH)
        }
    }

    @Provide
    fun rotations(): Arbitrary<Int> = Arbitraries.of(0, 90, 180, 270)
}
