package com.shanks.minify.photo

import com.shanks.minify.ui.CropRect
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Property-based tests for the Photo Editor's Mirror (Flip-Horizontal) algebra.
 *
 * Feature: unified-media-editor, Property 7: Mirror is an involution that flips horizontally
 *
 * Validates: Requirements 3.5, 3.7
 *
 * These tests exercise the pure [ImageEditModel] D4 algebra (via [Mat2]) so no
 * Android `Matrix` or emulator is required.
 */
class ImageEditModelMirrorPropertyTest {

    /**
     * A horizontal flip about the vertical center axis expressed as a D4 matrix.
     * In source-coordinate form it negates the x-axis, i.e. `(x, y) -> (-x, y)`.
     * Applied to the pre-existing orientation via right-multiplication, so a
     * source pixel is first mirrored and then rotated.
     */
    private val flipH = Mat2(-1, 0, 0, 1)

    @Provide
    fun editModels(): Arbitrary<ImageEditModel> {
        val rotation = Arbitraries.of(0, 90, 180, 270)
        val mirrored = Arbitraries.of(true, false)
        val coord = Arbitraries.floats().between(0f, 1f)
        val crop = Combinators.combine(coord, coord, coord, coord)
            .`as` { a, b, c, d ->
                // Normalize to a valid crop (left < right, top < bottom) without
                // constraining the orientation math the property actually targets.
                CropRect(
                    left = minOf(a, c),
                    top = minOf(b, d),
                    right = maxOf(a, c).coerceAtLeast(minOf(a, c) + 1e-4f).coerceAtMost(1f),
                    bottom = maxOf(b, d).coerceAtLeast(minOf(b, d) + 1e-4f).coerceAtMost(1f),
                )
            }
        return Combinators.combine(rotation, mirrored, crop)
            .`as` { r, m, c -> ImageEditModel(rotationDegrees = r, mirrored = m, crop = c) }
    }

    /**
     * Req 3.5: one `toggleMirror` composes a horizontal flip into the
     * orientation matrix (right-multiply by [flipH]) and flips the mirror flag.
     */
    @Property(tries = 200)
    fun mirrorComposesHorizontalFlipIntoOrientationMatrix(
        @ForAll("editModels") model: ImageEditModel,
    ) {
        val toggled = model.toggleMirror()

        // The mirror flag is flipped; rotation and crop are untouched.
        assertEquals(!model.mirrored, toggled.mirrored)
        assertEquals(model.rotationDegrees, toggled.rotationDegrees)
        assertEquals(model.crop, toggled.crop)

        // Toggling the mirror composes a horizontal flip into the orientation
        // matrix: M' = M * FlipH (a source pixel is mirrored, then rotated).
        assertEquals(model.orientationMatrix() * flipH, toggled.orientationMatrix())
    }

    /**
     * Req 3.5: the horizontal flip maps a source column `x` in an image of width
     * `w` to `w - 1 - x`. Working in center-origin coordinates, the flip negates
     * the x-axis, which is exactly the `x -> w - 1 - x` pixel mapping.
     */
    @Property(tries = 200)
    fun horizontalFlipMapsColumnXtoWMinus1MinusX(
        @ForAll("widths") w: Int,
        @ForAll seed: Int,
    ) {
        val x = Math.floorMod(seed, w) // any valid column in [0, w)

        // Center-origin coordinate of column x (scaled by 2 to stay integral).
        val centered = 2 * x - (w - 1)
        // Apply FlipH: (cx, cy) -> (-cx, cy); take the x component.
        val flippedCentered = flipH.m00 * centered + flipH.m01 * 0
        // Convert back to a pixel column.
        val flippedColumn = (flippedCentered + (w - 1)) / 2

        assertEquals(w - 1 - x, flippedColumn)
    }

    @Provide
    fun widths(): Arbitrary<Int> = Arbitraries.integers().between(1, 10_000)

    /**
     * Req 3.7: two consecutive `toggleMirror` operations return the model
     * to its pre-flip state (an involution), both as a value and as an
     * orientation matrix.
     */
    @Property(tries = 200)
    fun mirrorIsAnInvolution(
        @ForAll("editModels") model: ImageEditModel,
    ) {
        val doubled = model.toggleMirror().toggleMirror()

        assertEquals(model, doubled)
        assertEquals(model.orientationMatrix(), doubled.orientationMatrix())
    }
}
