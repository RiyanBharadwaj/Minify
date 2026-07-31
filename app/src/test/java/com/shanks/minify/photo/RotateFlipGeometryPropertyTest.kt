package com.shanks.minify.photo

import com.shanks.minify.ui.CropRect
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based test for the video/photo shared Rotate and Flip geometry model.
 *
 * The Rotate and Flip tools mutate the pure, Android-independent [ImageEditModel]
 * geometry that both the photo renderer and the video adapter consume. This test
 * pins the two invariants those tools rely on: rotate advances the clockwise
 * rotation by exactly 90 degrees, normalized to one of `{0, 90, 180, 270}`; and
 * flip toggles the horizontal mirror so that applying it twice is an involution
 * that returns the original geometry unchanged.
 */
class RotateFlipGeometryPropertyTest {

    // Feature: media-editor-ux-fixes, Property 5: Rotate advances by 90° and flip toggles, normalized
    /**
     * For any starting geometry, `rotateClockwise` yields a rotation equal to
     * `(rotation + 90) mod 360` that is always a member of `{0,90,180,270}`, and
     * `toggleMirror` inverts the mirror flag such that two toggles return the
     * original geometry.
     *
     * **Validates: Requirements 3.1, 3.2**
     */
    @Property(tries = 200)
    fun rotateAdvancesByNinetyAndFlipToggles(
        @ForAll("geometries") geometry: ImageEditModel,
    ) {
        // Rotate advances the clockwise rotation by exactly 90 degrees, normalized.
        val rotated = geometry.rotateClockwise()
        val expectedRotation = (geometry.rotationDegrees + 90) % 360
        assertEquals(expectedRotation, rotated.rotationDegrees)
        assertTrue(
            rotated.rotationDegrees in NORMALIZED_ROTATIONS,
            "rotation ${rotated.rotationDegrees} must be one of $NORMALIZED_ROTATIONS",
        )
        // Rotating leaves the rest of the geometry (mirror, crop) untouched.
        assertEquals(geometry.mirrored, rotated.mirrored)
        assertEquals(geometry.crop, rotated.crop)

        // Flip toggles the mirror flag.
        val flippedOnce = geometry.toggleMirror()
        assertEquals(!geometry.mirrored, flippedOnce.mirrored)

        // Two flips are an involution: the geometry returns to the original.
        val flippedTwice = geometry.toggleMirror().toggleMirror()
        assertEquals(geometry, flippedTwice)
    }

    /** Full geometry: any normalized rotation, either mirror state, and an in-bounds crop. */
    @Provide
    fun geometries(): Arbitrary<ImageEditModel> {
        val rotations = Arbitraries.of(0, 90, 180, 270)
        val mirrors = Arbitraries.of(true, false)
        return Combinators.combine(rotations, mirrors, crops())
            .`as` { rotation, mirrored, crop ->
                ImageEditModel(rotationDegrees = rotation, mirrored = mirrored, crop = crop)
            }
    }

    /** A valid normalized crop with a minimum extent on each axis. */
    private fun crops(): Arbitrary<CropRect> {
        val edges = Arbitraries.doubles().between(0.0, 1.0).map { it.toFloat() }
        return Combinators.combine(edges, edges, edges, edges)
            .`as` { a, b, c, d ->
                val left = minOf(a, b)
                val right = maxOf(a, b)
                val top = minOf(c, d)
                val bottom = maxOf(c, d)
                CropRect(
                    left = left,
                    top = top,
                    right = (left + MIN_EXTENT).coerceAtLeast(right).coerceAtMost(1f),
                    bottom = (top + MIN_EXTENT).coerceAtLeast(bottom).coerceAtMost(1f),
                )
            }
    }

    private companion object {
        val NORMALIZED_ROTATIONS = setOf(0, 90, 180, 270)
        const val MIN_EXTENT = 0.05f
    }
}
