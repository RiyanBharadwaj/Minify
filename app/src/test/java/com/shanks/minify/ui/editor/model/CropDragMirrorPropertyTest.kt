package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.CropRect
import com.shanks.minify.ui.DragHandle
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Property-based test for [CropDrag.resolve] under horizontal mirroring.
 *
 * Feature: media-editor-fixes, Property 13: Crop-edge drags move the grabbed visual
 * edge under mirroring.
 *
 * Validates: Requirements 14.1
 *
 * ## Coordinate model
 *
 * The crop is stored in **model (source) space** with edges normalized to `[0, 1]`.
 * When the image is displayed horizontally mirrored, model X maps to display X via
 * `displayX = 1 - modelX`. Therefore the *visual* (display-space) position of a
 * grabbed horizontal edge is:
 *
 * - visual LEFT edge  -> `1 - crop.right`   (driven by the model's right edge)
 * - visual RIGHT edge -> `1 - crop.left`    (driven by the model's left edge)
 *
 * Requirement 14.1 says that while mirrored, dragging a crop edge must move the
 * *visual* edge the user grabbed. This test asserts exactly that: after a mirrored
 * drag of the visual LEFT or RIGHT handle by `dNormX` (a display-space delta), the
 * visual position of that grabbed edge advances by precisely `dNormX`, while the
 * opposite visual edge is left untouched.
 *
 * Generators keep the crop well inside the unit square and the delta small so no
 * boundary/min-extent clamping is triggered, isolating the mirror mapping under test.
 */
class CropDragMirrorPropertyTest {

    @Property(tries = 200)
    fun mirroredDragMovesGrabbedVisualEdge(
        @ForAll("crops") crop: CropRect,
        @ForAll("horizontalEdges") handle: DragHandle,
        @ForAll("deltas") dNormX: Float,
    ) {
        val result = CropDrag.resolve(
            crop = crop,
            handle = handle,
            dNormX = dNormX,
            dNormY = 0f,
            mirrored = true,
            lockedNormAspect = null,
        )

        // The grabbed visual edge follows the drag by exactly dNormX (Req 14.1).
        val expectedGrabbed = visualEdge(crop, handle) + dNormX
        val actualGrabbed = visualEdge(result, handle)
        assertEquals(
            expectedGrabbed, actualGrabbed, EPS,
            "grabbed visual $handle edge must move by dNormX=$dNormX (crop=$crop, result=$result)",
        )

        // A single-edge drag must not disturb the opposite visual edge.
        val opposite = opposite(handle)
        assertEquals(
            visualEdge(crop, opposite), visualEdge(result, opposite), EPS,
            "opposite visual $opposite edge must stay fixed (crop=$crop, result=$result)",
        )

        // The vertical extent is untouched when only a horizontal edge is dragged.
        assertEquals(crop.top, result.top, EPS, "top must be unchanged (crop=$crop, result=$result)")
        assertEquals(crop.bottom, result.bottom, EPS, "bottom must be unchanged (crop=$crop, result=$result)")
    }

    /** Display-space position of the [handle]'s visual edge for a mirrored image. */
    private fun visualEdge(crop: CropRect, handle: DragHandle): Float = when (handle) {
        DragHandle.LEFT -> 1f - crop.right
        DragHandle.RIGHT -> 1f - crop.left
        else -> error("unsupported handle: $handle")
    }

    private fun opposite(handle: DragHandle): DragHandle = when (handle) {
        DragHandle.LEFT -> DragHandle.RIGHT
        DragHandle.RIGHT -> DragHandle.LEFT
        else -> error("unsupported handle: $handle")
    }

    /** The two mirror-sensitive horizontal crop edges. */
    @Provide
    fun horizontalEdges(): Arbitrary<DragHandle> = Arbitraries.of(DragHandle.LEFT, DragHandle.RIGHT)

    /**
     * Small display-space horizontal deltas that, combined with the margined [crops],
     * never push an edge outside `[0, 1]` nor below [CropDrag.MIN_CROP_FRAC], so the
     * pure mirror mapping is what is being verified rather than the clamp.
     */
    @Provide
    fun deltas(): Arbitrary<Float> = Arbitraries.floats().between(-0.1f, 0.1f)

    /**
     * Well-ordered crops kept clear of the unit-square boundary: left in `[0.2, 0.4]`,
     * right in `[0.6, 0.8]`, top in `[0.2, 0.4]`, bottom in `[0.6, 0.8]`. Every extent
     * is >= 0.2 (well above [CropDrag.MIN_CROP_FRAC] = 0.08) and stays in bounds after a
     * `+/-0.1` drag.
     */
    @Provide
    fun crops(): Arbitrary<CropRect> {
        val lows: Arbitrary<Float> = Arbitraries.floats().between(0.2f, 0.4f)
        val highs: Arbitrary<Float> = Arbitraries.floats().between(0.6f, 0.8f)
        return Combinators.combine(lows, lows, highs, highs).`as` { left, top, right, bottom ->
            CropRect(left = left, top = top, right = right, bottom = bottom)
        }
    }

    private companion object {
        const val EPS = 1e-5f
    }
}
