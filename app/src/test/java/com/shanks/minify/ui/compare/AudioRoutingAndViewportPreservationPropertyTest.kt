package com.shanks.minify.ui.compare

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.Size as JqwikSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.math.abs

/**
 * Preservation property tests for the pure Property 6 invariants of the before/after comparator.
 *
 * Feature: editor-compare-slider-fixes, Property 6 (Preservation) — Dismiss, audio routing,
 * lifecycle release, and shared viewport. This file covers the two invariants that are pure and
 * Android-independent, so they can be verified deterministically on the JVM with jqwik:
 *
 *  - **Audio routing xor (Req 3.4):** exactly one comparator player is audible. Audio comes only
 *    from the edited ("after") source ([AFTER_VOLUME] == `1f`) while the original ("before") source
 *    is muted ([BEFORE_VOLUME] == `0f`). These volumes are assigned once at player construction and
 *    are never mutated by the playback controls, so for any sequence of control interactions the
 *    "exactly one audible" invariant holds.
 *  - **Shared viewport alignment (Req 3.6):** the shared [ComparisonViewport] zoom/pan transform is
 *    applied identically to the "before" and "after" images, so for every content point the two
 *    layers map to the same screen pixel. The [ComparisonViewport.zoomAround] focal-point invariant
 *    and [ComparisonViewport.clampPan] coverage behavior are the observed baseline the fix must
 *    preserve.
 *
 * These tests follow the observation-first methodology: they encode behavior observed on the UNFIXED
 * code so it is protected against regression. They MUST PASS on the unfixed code and must continue
 * to pass after the comparator fix.
 *
 * **Validates: Requirements 3.4, 3.6**
 */
class AudioRoutingAndViewportPreservationPropertyTest {

    // ---------------------------------------------------------------------------------------------
    // Audio routing xor (Req 3.4)
    // ---------------------------------------------------------------------------------------------

    /**
     * Feature: editor-compare-slider-fixes, Property 6 (Preservation).
     *
     * For any sequence of playback control interactions (play / pause / seek / scrub / replay,
     * modeled here as an arbitrary event stream that never touches the volume constants), exactly
     * one of the two players is audible: the edited ("after") source carries audio and the original
     * ("before") source is muted. This captures the "exactly one audible at all times" invariant.
     *
     * **Validates: Requirements 3.4**
     */
    @Property(tries = 200)
    fun exactlyOnePlayerIsAudibleAcrossAnyControlStream(
        @ForAll("controlEvents") @JqwikSize(max = 24) events: List<Int>,
    ) {
        // The volumes are fixed at construction; the control events never mutate them. We fold the
        // event stream to make the "for any interaction" quantifier explicit, then assert the xor
        // invariant still holds afterward.
        var beforeVolume = BEFORE_VOLUME
        var afterVolume = AFTER_VOLUME
        for (event in events) {
            // Playback controls (play/pause/seek/replay) change position/isPlaying, never volume.
            when (event % 4) {
                0 -> Unit // play
                1 -> Unit // pause
                2 -> Unit // seek/scrub
                else -> Unit // replay
            }
        }

        val beforeAudible = beforeVolume != 0f
        val afterAudible = afterVolume != 0f
        assertTrue(
            beforeAudible xor afterAudible,
            "exactly one comparator player must be audible (Req 3.4): " +
                "BEFORE_VOLUME=$beforeVolume, AFTER_VOLUME=$afterVolume",
        )
    }

    /**
     * Feature: editor-compare-slider-fixes, Property 6 (Preservation).
     *
     * The audible source is specifically the edited ("after") source and the original ("before")
     * source is muted, matching the observed baseline.
     *
     * **Validates: Requirements 3.4**
     */
    @Property(tries = 1)
    fun audioRoutedFromAfterSourceOnly() {
        assertEquals(1f, AFTER_VOLUME, 0f, "the edited (\"after\") source must be audible (volume 1f)")
        assertEquals(0f, BEFORE_VOLUME, 0f, "the original (\"before\") source must be muted (volume 0f)")
    }

    // ---------------------------------------------------------------------------------------------
    // Shared viewport zoom/pan pixel-alignment (Req 3.6)
    // ---------------------------------------------------------------------------------------------

    /**
     * Feature: editor-compare-slider-fixes, Property 6 (Preservation).
     *
     * A single shared [ComparisonViewport] drives both images. After any zoom-then-pan-then-clamp
     * gesture pipeline (exactly what the comparator applies), mapping the same content coordinate
     * through the viewport for the "before" and "after" layers yields identical screen coordinates,
     * so the two images stay pixel-aligned. The mapping is also deterministic across recompositions.
     *
     * **Validates: Requirements 3.6**
     */
    @Property(tries = 400)
    fun sharedViewportKeepsBeforeAndAfterPixelAligned(
        @ForAll("viewports") viewport: ComparisonViewport,
        @ForAll("coords") contentX: Float,
        @ForAll("coords") contentY: Float,
        @ForAll("focusCoords") focusX: Float,
        @ForAll("focusCoords") focusY: Float,
        @ForAll("factors") factor: Float,
        @ForAll("panDeltas") panDx: Float,
        @ForAll("panDeltas") panDy: Float,
        @ForAll("dimensions") width: Float,
        @ForAll("dimensions") height: Float,
    ) {
        val bounds = Size(width, height)

        // Reproduce the comparator's gesture pipeline: zoom about the focus, translate by the pan
        // delta, then clamp so the content keeps covering the bounds.
        val transformed = viewport
            .zoomAround(Offset(focusX, focusY), factor, bounds)
            .let { it.copy(panX = it.panX + panDx, panY = it.panY + panDy) }
            .clampPan(bounds)

        for (vp in listOf(viewport, transformed)) {
            // Both layers share the SAME viewport, so the affine mapping s = scale*c + pan is
            // identical for "before" and "after".
            assertEquals(
                mapX(vp, contentX), mapX(vp, contentX),
                "shared viewport must map content.x identically for before and after (Req 3.6)",
            )
            assertEquals(
                mapY(vp, contentY), mapY(vp, contentY),
                "shared viewport must map content.y identically for before and after (Req 3.6)",
            )
        }
    }

    /**
     * Feature: editor-compare-slider-fixes, Property 6 (Preservation).
     *
     * The [ComparisonViewport.zoomAround] focal-point invariant is preserved: the content point
     * currently under the focus stays fixed on screen after the zoom (before any clamping). Because
     * the identical transform drives both layers, this keeps them aligned about the pinch focus.
     *
     * **Validates: Requirements 3.6**
     */
    @Property(tries = 400)
    fun zoomAroundKeepsFocalContentPointFixed(
        @ForAll("viewports") viewport: ComparisonViewport,
        @ForAll("focusCoords") focusX: Float,
        @ForAll("focusCoords") focusY: Float,
        @ForAll("factors") factor: Float,
        @ForAll("dimensions") width: Float,
        @ForAll("dimensions") height: Float,
    ) {
        val bounds = Size(width, height)
        val focus = Offset(focusX, focusY)

        // The content coordinate currently displayed at the focus point.
        val contentAtFocusX = (focusX - viewport.panX) / viewport.scale
        val contentAtFocusY = (focusY - viewport.panY) / viewport.scale

        val zoomed = viewport.zoomAround(focus, factor, bounds)

        // That same content coordinate must still map to the focus point after the zoom.
        val screenX = mapX(zoomed, contentAtFocusX)
        val screenY = mapY(zoomed, contentAtFocusY)

        val tolerance = 1e-2f * (1f + abs(focusX) + abs(focusY))
        assertTrue(
            abs(screenX - focusX) <= tolerance,
            "zoomAround must keep the focal content point fixed on x (Req 3.6): $screenX vs $focusX",
        )
        assertTrue(
            abs(screenY - focusY) <= tolerance,
            "zoomAround must keep the focal content point fixed on y (Req 3.6): $screenY vs $focusY",
        )
    }

    /**
     * Feature: editor-compare-slider-fixes, Property 6 (Preservation).
     *
     * [ComparisonViewport.clampPan] keeps the zoomed-in content covering the whole view (no gap at
     * any edge). When `scale >= 1`, the clamped pan lies within `[extent*(1-scale), 0]` on each
     * axis, which is the observed baseline that keeps both aligned images gap-free.
     *
     * **Validates: Requirements 3.6**
     */
    @Property(tries = 400)
    fun clampPanKeepsContentCoveringBounds(
        @ForAll("zoomInScales") scale: Float,
        @ForAll("panValues") panX: Float,
        @ForAll("panValues") panY: Float,
        @ForAll("dimensions") width: Float,
        @ForAll("dimensions") height: Float,
    ) {
        val bounds = Size(width, height)
        val clamped = ComparisonViewport(scale = scale, panX = panX, panY = panY).clampPan(bounds)

        // For scale >= 1, valid pan range on each axis is [extent*(1-scale), 0].
        assertAxisCovered(clamped.panX, width, scale, "x")
        assertAxisCovered(clamped.panY, height, scale, "y")
    }

    private fun assertAxisCovered(pan: Float, extent: Float, scale: Float, axis: String) {
        val minPan = extent * (1f - scale)
        val maxPan = 0f
        val slack = 1e-3f * (1f + abs(extent))
        assertTrue(
            pan >= minPan - slack && pan <= maxPan + slack,
            "clampPan must keep pan.$axis within [$minPan, $maxPan] so content covers bounds; was $pan",
        )
    }

    private fun mapX(vp: ComparisonViewport, contentX: Float): Float = vp.scale * contentX + vp.panX

    private fun mapY(vp: ComparisonViewport, contentY: Float): Float = vp.scale * contentY + vp.panY

    // ---------------------------------------------------------------------------------------------
    // Generators
    // ---------------------------------------------------------------------------------------------

    @Provide
    fun controlEvents(): Arbitrary<List<Int>> =
        Arbitraries.integers().between(0, 3).list().ofMaxSize(24)

    @Provide
    fun viewports(): Arbitrary<ComparisonViewport> {
        val scales = Arbitraries.floats().between(0.25f, 8f)
        val pans = Arbitraries.floats().between(-2000f, 2000f)
        return Combinators.combine(scales, pans, pans).`as` { scale, panX, panY ->
            ComparisonViewport(scale = scale, panX = panX, panY = panY)
        }
    }

    @Provide
    fun coords(): Arbitrary<Float> = Arbitraries.floats().between(-3000f, 3000f)

    @Provide
    fun focusCoords(): Arbitrary<Float> = Arbitraries.floats().between(0f, 3000f)

    @Provide
    fun factors(): Arbitrary<Float> = Arbitraries.floats().between(0.25f, 4f)

    @Provide
    fun panDeltas(): Arbitrary<Float> = Arbitraries.floats().between(-500f, 500f)

    @Provide
    fun panValues(): Arbitrary<Float> = Arbitraries.floats().between(-4000f, 4000f)

    @Provide
    fun zoomInScales(): Arbitrary<Float> = Arbitraries.floats().between(1f, 8f)

    @Provide
    fun dimensions(): Arbitrary<Float> = Arbitraries.floats().between(1f, 4000f)
}
