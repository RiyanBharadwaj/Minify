package com.shanks.minify.photo

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [ImageEditModel]'s rotation algebra.
 *
 * These exercise the pure D4 [Mat2] transform that backs the Photo Editor's
 * Rotate tool, independent of any Android [android.graphics.Matrix].
 */
class ImageEditModelRotationPropertyTest {

    // Feature: media-editing-suite, Property 5: Rotation is order-4 with 90° clockwise steps
    @Property(tries = 200)
    fun rotationIsOrder4WithNinetyDegreeClockwiseSteps(
        @ForAll("editModels") model: ImageEditModel,
    ) {
        // A single 90° clockwise rotation, expressed in the same D4 algebra the
        // model uses: (x, y) -> (y, -x).
        val rotate90Clockwise = Mat2(0, 1, -1, 0)

        // One rotateClockwise advances the orientation matrix by exactly a 90°
        // clockwise rotation: newMatrix == R90 * oldMatrix.
        val rotatedOnce = model.rotateClockwise()
        val before = model.orientationMatrix()
        val afterOne = rotatedOnce.orientationMatrix()
        assertEquals(rotate90Clockwise * before, afterOne)

        // The rotation stays normalized to one of {0,90,180,270} degrees.
        assertTrue(rotatedOnce.rotationDegrees in NORMALIZED_ROTATIONS)

        // Four consecutive rotateClockwise operations return an orientation
        // equal to the original (the rotation is order 4).
        val afterFour = model
            .rotateClockwise()
            .rotateClockwise()
            .rotateClockwise()
            .rotateClockwise()
        assertEquals(before, afterFour.orientationMatrix())
        assertEquals(model.rotationDegrees, afterFour.rotationDegrees)
    }

    @Provide
    fun editModels(): Arbitrary<ImageEditModel> {
        // orientationMatrix() ignores the crop, so vary only the fields that
        // influence the transform: the normalized rotation and the mirror flag.
        val rotations = Arbitraries.of(0, 90, 180, 270)
        val mirrored = Arbitraries.of(true, false)
        return rotations.flatMap { rot ->
            mirrored.map { mir ->
                ImageEditModel(rotationDegrees = rot, mirrored = mir)
            }
        }
    }

    private companion object {
        val NORMALIZED_ROTATIONS = setOf(0, 90, 180, 270)
    }
}
