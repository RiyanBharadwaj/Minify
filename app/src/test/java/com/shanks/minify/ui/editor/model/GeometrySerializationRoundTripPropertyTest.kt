// Feature: media-editor-ux-fixes, Property 3: Geometry serialization round-trips exactly
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
 * Property-based test for exact geometry serialization round-trips.
 *
 * Feature: media-editor-ux-fixes, Property 3: Geometry serialization round-trips exactly.
 *
 * For any [ImageEditModel] geometry (rotation in `{0,90,180,270}`, mirror, in-bounds crop) — and
 * the enclosing [MediaEditState] — saving via the `Saver` and restoring reproduces a geometry
 * whose every crop edge equals the original within `0.001` (in fact exactly) and whose rotation
 * and mirror are identical.
 *
 * Validates: Requirements 2.1
 */
class GeometrySerializationRoundTripPropertyTest {

    /** A permissive [SaverScope]: everything is considered "can-be-saved". */
    private val saverScope = SaverScope { true }

    /** Save then restore an [ImageEditModel] geometry through its [ImageEditModel.Saver]. */
    private fun roundTrip(model: ImageEditModel): ImageEditModel {
        val saved = with(ImageEditModel.Saver) { saverScope.save(model) }
        assertNotNull(saved, "ImageEditModel.Saver must produce a saveable payload: $model")
        val restored = ImageEditModel.Saver.restore(saved!!)
        assertNotNull(restored, "ImageEditModel.Saver must restore a non-null model: $model")
        return restored!!
    }

    /** Save then restore a [MediaEditState] through its [MediaEditState.Saver]. */
    private fun roundTrip(state: MediaEditState): MediaEditState {
        val saved = with(MediaEditState.Saver) { saverScope.save(state) }
        assertNotNull(saved, "MediaEditState.Saver must produce a saveable payload: $state")
        val restored = MediaEditState.Saver.restore(saved!!)
        assertNotNull(restored, "MediaEditState.Saver must restore a non-null state: $state")
        return restored!!
    }

    /** Assert two geometries have identical rotation/mirror and every crop edge within 0.001. */
    private fun assertGeometryRoundTrips(original: ImageEditModel, restored: ImageEditModel) {
        assertEquals(
            original.rotationDegrees,
            restored.rotationDegrees,
            "rotation must be identical: $original",
        )
        assertEquals(original.mirrored, restored.mirrored, "mirror must be identical: $original")
        assertEquals(original.crop.left, restored.crop.left, EPS, "left within 0.001: $original")
        assertEquals(original.crop.top, restored.crop.top, EPS, "top within 0.001: $original")
        assertEquals(original.crop.right, restored.crop.right, EPS, "right within 0.001: $original")
        assertEquals(
            original.crop.bottom,
            restored.crop.bottom,
            EPS,
            "bottom within 0.001: $original",
        )
    }

    /**
     * Feature: media-editor-ux-fixes, Property 3: Geometry serialization round-trips exactly.
     *
     * Saving and restoring an [ImageEditModel] via [ImageEditModel.Saver] reproduces every crop
     * edge within `0.001` and identical rotation and mirror.
     *
     * **Validates: Requirements 2.1**
     */
    @Property(tries = 200)
    fun imageEditModelRoundTripsExactly(
        @ForAll("geometries") geometry: ImageEditModel,
    ) {
        assertGeometryRoundTrips(geometry, roundTrip(geometry))
    }

    /**
     * Feature: media-editor-ux-fixes, Property 3: Geometry serialization round-trips exactly.
     *
     * Saving and restoring the enclosing [MediaEditState] via [MediaEditState.Saver] reproduces
     * the geometry's crop edges within `0.001` and identical rotation and mirror.
     *
     * **Validates: Requirements 2.1**
     */
    @Property(tries = 200)
    fun mediaEditStateGeometryRoundTripsExactly(
        @ForAll("states") state: MediaEditState,
    ) {
        assertGeometryRoundTrips(state.geometry, roundTrip(state).geometry)
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

    /** A [MediaEditState] (photo or video) carrying an arbitrary geometry. */
    @Provide
    fun states(): Arbitrary<MediaEditState> {
        val mediaTypes = Arbitraries.of(MediaType.PHOTO, MediaType.VIDEO)
        return Combinators.combine(mediaTypes, geometries()).`as` { type, geometry ->
            MediaEditState.initial(type, fullDurationMs = 10_000L).copy(geometry = geometry)
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
