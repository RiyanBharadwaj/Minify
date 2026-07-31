// Feature: media-editor-ux-fixes, Property 4: Displayed-to-source crop mapping is deterministic
package com.shanks.minify.ui.editor.model

import androidx.compose.runtime.saveable.SaverScope
import com.shanks.minify.photo.ImageEditModel
import com.shanks.minify.ui.CropRect
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull

/**
 * Property-based test for [CropSpaceMapping.toSourceSpace] determinism.
 *
 * Feature: media-editor-ux-fixes, Property 4: Displayed-to-source crop mapping is deterministic.
 *
 * For any displayed-space crop, rotation in `{0, 90, 180, 270}`, and mirror state,
 * [CropSpaceMapping.toSourceSpace] returns the same source-space [CropRect] on repeated calls
 * and for a round-tripped copy of the geometry (rotation, mirror, crop serialized and restored
 * through [ImageEditModel.Saver]), within `0.001` per edge. This guarantees the cropped region
 * is identical before and after an editor reopen.
 *
 * Validates: Requirements 2.3
 */
class CropSpaceMappingDeterminismPropertyTest {

    /** A permissive [SaverScope]: everything is considered "can-be-saved". */
    private val saverScope = SaverScope { true }

    /** Save then restore an [ImageEditModel] geometry through its [ImageEditModel.Saver]. */
    private fun roundTrip(model: ImageEditModel): ImageEditModel {
        val saved = with(ImageEditModel.Saver) { saverScope.save(model) }
        assertNotNull(saved)
        val restored = ImageEditModel.Saver.restore(saved!!)
        assertNotNull(restored)
        return restored!!
    }

    /**
     * Feature: media-editor-ux-fixes, Property 4: Displayed-to-source crop mapping is deterministic.
     *
     * `toSourceSpace` is a pure function of its inputs: calling it twice with the same
     * displayed crop, rotation, and mirror yields identical results, and calling it with a
     * round-tripped copy of the geometry yields the same source-space crop within `0.001` per edge.
     *
     * **Validates: Requirements 2.3**
     */
    @Property(tries = 200)
    fun toSourceSpaceIsDeterministicAcrossRepeatsAndRoundTrips(
        @ForAll("geometries") geometry: ImageEditModel,
    ) {
        val displayed = geometry.crop
        val rotation = geometry.rotationDegrees
        val mirrored = geometry.mirrored

        // Repeated calls with identical inputs are exactly identical.
        val first = CropSpaceMapping.toSourceSpace(displayed, rotation, mirrored)
        val second = CropSpaceMapping.toSourceSpace(displayed, rotation, mirrored)
        assertEquals(first.left, second.left, 0f, "repeat left must be identical: $geometry")
        assertEquals(first.top, second.top, 0f, "repeat top must be identical: $geometry")
        assertEquals(first.right, second.right, 0f, "repeat right must be identical: $geometry")
        assertEquals(first.bottom, second.bottom, 0f, "repeat bottom must be identical: $geometry")

        // A round-tripped copy of the geometry produces the same source-space crop within 0.001.
        val restored = roundTrip(geometry)
        val afterRoundTrip = CropSpaceMapping.toSourceSpace(
            restored.crop,
            restored.rotationDegrees,
            restored.mirrored,
        )
        assertEquals(first.left, afterRoundTrip.left, EPS, "round-trip left within 0.001: $geometry")
        assertEquals(first.top, afterRoundTrip.top, EPS, "round-trip top within 0.001: $geometry")
        assertEquals(first.right, afterRoundTrip.right, EPS, "round-trip right within 0.001: $geometry")
        assertEquals(first.bottom, afterRoundTrip.bottom, EPS, "round-trip bottom within 0.001: $geometry")
    }

    // --- Generators ----------------------------------------------------------

    /** Geometry with a supported rotation, either mirror state, and an in-bounds crop. */
    @Provide
    fun geometries(): Arbitrary<ImageEditModel> {
        val rotations = Arbitraries.of(0, 90, 180, 270)
        val mirrors = Arbitraries.of(true, false)
        return Combinators.combine(rotations, mirrors, crops())
            .`as` { rotation, mirrored, crop ->
                ImageEditModel(rotationDegrees = rotation, mirrored = mirrored, crop = crop)
            }
    }

    /** Well-ordered, in-bounds normalized crop rectangles in [0,1]x[0,1]. */
    private fun crops(): Arbitrary<CropRect> {
        val coord: Arbitrary<Float> = Arbitraries.floats().between(0f, 1f)
        return Combinators.combine(coord, coord, coord, coord).`as` { a, b, c, d ->
            CropRect(
                left = minOf(a, c),
                top = minOf(b, d),
                right = maxOf(a, c),
                bottom = maxOf(b, d),
            )
        }
    }

    private companion object {
        const val EPS = 0.001f
    }
}
