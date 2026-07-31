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
 * Property-based test for [CropDrag.resolve] under horizontal mirroring, asserting that a
 * mirrored drag moves the **X-swapped model edge** with the **horizontal delta inverted**
 * relative to the non-mirrored case.
 *
 * Feature: media-editor-ux-fixes, Property 2: Mirrored crop drag moves the corresponding
 * visual edge.
 *
 * Validates: Requirements 1.2
 *
 * ## What this pins down
 *
 * The crop is stored in **model (source) space**; a horizontally mirrored image maps model
 * X to display X via `displayX = 1 - modelX`. Because the user grabs a *visual* handle and
 * drags in *display* space, resolving a mirrored drag must:
 *
 * 1. move the model edge corresponding to the **X-swapped** visual handle
 *    (LEFT&#8596;RIGHT, and the X-component of the corner handles), and
 * 2. apply the **negated** horizontal delta (display X is the negation of model X).
 *
 * This is exactly the transform that makes the visual edge under the pointer move rather
 * than its mirror opposite. The property expresses it as an equivalence: a mirrored drag of
 * visual handle `H` by `(dNormX, dNormY)` produces the identical [CropRect] as a
 * non-mirrored drag of the X-swapped handle `H'` by `(-dNormX, dNormY)`.
 *
 * The equivalence holds for every input — including large or clamping deltas — because the
 * mirror mapping is applied *before* the shared edge/clamp math. Complementing the existing
 * `CropDragMirrorPropertyTest` (which checks the resulting visual-edge displacement for the
 * side handles), this test covers side **and** corner handles via the model-edge mapping.
 */
class CropDragMirrorEdgePropertyTest {

    @Property(tries = 200)
    fun mirroredDragMatchesXSwappedNonMirroredDrag(
        @ForAll("crops") crop: CropRect,
        @ForAll("xSensitiveHandles") handle: DragHandle,
        @ForAll("deltas") dNormX: Float,
        @ForAll("deltas") dNormY: Float,
    ) {
        // Mirrored drag of the grabbed *visual* handle in display space.
        val mirrored = CropDrag.resolve(
            crop = crop,
            handle = handle,
            dNormX = dNormX,
            dNormY = dNormY,
            mirrored = true,
            lockedNormAspect = null,
        )

        // The equivalent non-mirrored drag: the X-swapped model handle with the
        // horizontal delta inverted (display X is the negation of model X).
        val equivalent = CropDrag.resolve(
            crop = crop,
            handle = mirroredX(handle),
            dNormX = -dNormX,
            dNormY = dNormY,
            mirrored = false,
            lockedNormAspect = null,
        )

        assertEquals(
            equivalent.left, mirrored.left, EPS,
            "mirrored $handle must drive the X-swapped model edge (left) " +
                "(crop=$crop, dNormX=$dNormX, dNormY=$dNormY)",
        )
        assertEquals(
            equivalent.right, mirrored.right, EPS,
            "mirrored $handle must drive the X-swapped model edge (right) " +
                "(crop=$crop, dNormX=$dNormX, dNormY=$dNormY)",
        )
        assertEquals(
            equivalent.top, mirrored.top, EPS,
            "vertical component must be unaffected by mirroring (top) " +
                "(crop=$crop, dNormX=$dNormX, dNormY=$dNormY)",
        )
        assertEquals(
            equivalent.bottom, mirrored.bottom, EPS,
            "vertical component must be unaffected by mirroring (bottom) " +
                "(crop=$crop, dNormX=$dNormX, dNormY=$dNormY)",
        )
    }

    /** Mirror the X-component of a handle (LEFT&#8596;RIGHT, and the X side of corners). */
    private fun mirroredX(handle: DragHandle): DragHandle = when (handle) {
        DragHandle.LEFT -> DragHandle.RIGHT
        DragHandle.RIGHT -> DragHandle.LEFT
        DragHandle.TOP_LEFT -> DragHandle.TOP_RIGHT
        DragHandle.TOP_RIGHT -> DragHandle.TOP_LEFT
        DragHandle.BOTTOM_LEFT -> DragHandle.BOTTOM_RIGHT
        DragHandle.BOTTOM_RIGHT -> DragHandle.BOTTOM_LEFT
        else -> handle
    }

    /** The mirror-sensitive handles: horizontal side edges and all four corners. */
    @Provide
    fun xSensitiveHandles(): Arbitrary<DragHandle> = Arbitraries.of(
        DragHandle.LEFT,
        DragHandle.RIGHT,
        DragHandle.TOP_LEFT,
        DragHandle.TOP_RIGHT,
        DragHandle.BOTTOM_LEFT,
        DragHandle.BOTTOM_RIGHT,
    )

    /**
     * Display-space deltas spanning a wide range (including magnitudes that trigger boundary
     * and minimum-extent clamping), since the mirror equivalence must hold for every input.
     */
    @Provide
    fun deltas(): Arbitrary<Float> = Arbitraries.floats().between(-1.5f, 1.5f)

    /** Well-ordered crops with a comfortable interior margin and a valid minimum extent. */
    @Provide
    fun crops(): Arbitrary<CropRect> {
        val lows: Arbitrary<Float> = Arbitraries.floats().between(0.1f, 0.4f)
        val highs: Arbitrary<Float> = Arbitraries.floats().between(0.6f, 0.9f)
        return Combinators.combine(lows, lows, highs, highs).`as` { left, top, right, bottom ->
            CropRect(left = left, top = top, right = right, bottom = bottom)
        }
    }

    private companion object {
        const val EPS = 1e-5f
    }
}
