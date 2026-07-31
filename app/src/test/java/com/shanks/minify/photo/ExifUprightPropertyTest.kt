package com.shanks.minify.photo

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Property-based tests for EXIF orientation correction.
 *
 * These exercise the pure D4 [Mat2] algebra that backs the Photo Editor's
 * "open upright" behavior, independent of any Android [android.graphics.Matrix].
 */
class ExifUprightPropertyTest {

    // Feature: media-editing-suite, Property 1: EXIF orientation correction yields an upright image
    @Property(tries = 200)
    fun exifCorrectionYieldsUprightImage(
        @ForAll("exifTags") tag: Int,
    ) {
        // The correction transform the Photo Editor applies to render the stored
        // pixels upright.
        val correction = ExifOrientation.normalize(tag)

        // The orientation the tag represents: how an upright image was
        // transformed to produce the stored pixels.
        val representedOrientation = ExifOrientation.represented(tag)

        // Composing the correction with the represented orientation must land on
        // an upright image: correctionMatrix * tagOrientationMatrix == identity.
        val composed = correction.toMatrix() * representedOrientation.toMatrix()
        assertEquals(Mat2.IDENTITY, composed)

        // Equivalently, applying the represented orientation and then the
        // correction is the identity transform (already upright).
        assertEquals(
            OrientationTransform.IDENTITY,
            representedOrientation.then(correction),
        )
    }

    @Provide
    fun exifTags(): Arbitrary<Int> = Arbitraries.of(ExifOrientation.ALL_TAGS)
}
