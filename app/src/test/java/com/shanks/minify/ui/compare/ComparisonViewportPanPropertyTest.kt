package com.shanks.minify.ui.compare

import androidx.compose.ui.geometry.Size
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [ComparisonViewport.clampPan], the pure pan-clamping step
 * the before/after image comparator applies after every zoom or pan gesture.
 *
 * A single [ComparisonViewport] drives both the "before" and "after" images, so the
 * transform they receive is - by construction - the very same object. This test pins
 * down the behavioral guarantee that makes sharing safe: [clampPan] is pure
 * (deterministic), so the same input always yields an equal viewport, and it keeps the
 * pan offset within the range that keeps zoomed content (`scale >= 1`) covering the view.
 * Because one clamped viewport feeds both images, their pixel coordinates stay aligned.
 *
 * Note: view bounds are supplied as separate `width`/`height` floats rather than a
 * `Size` parameter because Compose's `Size` is a `@JvmInline value class` and would be
 * unboxed in the JVM method signature, defeating jqwik's arbitrary resolution.
 */
class ComparisonViewportPanPropertyTest {

    // Feature: media-editing-suite, Property 12: Pan is shared and clamped, keeping both images aligned
    @Property(tries = 300)
    fun panIsSharedAndClampedForZoomedContent(
        @ForAll("zoomedViewports") viewport: ComparisonViewport,
        @ForAll("dimensions") width: Float,
        @ForAll("dimensions") height: Float,
    ) {
        val bounds = Size(width, height)
        val clamped = viewport.clampPan(bounds)

        // The single viewport drives both images: clampPan is pure, so applying it to the
        // same input (as happens for the "before" and "after" images) yields equal results.
        val clampedAgain = viewport.clampPan(bounds)
        assertEquals(clamped, clampedAgain, "clampPan must be deterministic (pure): equal input yields equal output")

        // Covering invariant for zoomed content (scale >= 1): the valid pan range is
        // [extent * (1 - scale), 0] on each axis, keeping no gap at any edge.
        val minPanX = width * (1f - viewport.scale)
        val minPanY = height * (1f - viewport.scale)

        assertTrue(
            clamped.panX in minPanX..0f,
            "panX must stay within [$minPanX, 0] to keep zoomed content covering the view, was ${clamped.panX}",
        )
        assertTrue(
            clamped.panY in minPanY..0f,
            "panY must stay within [$minPanY, 0] to keep zoomed content covering the view, was ${clamped.panY}",
        )

        // Scale is untouched by clampPan (only the pan is constrained).
        assertEquals(viewport.scale, clamped.scale, "clampPan must not change scale")
    }

    @Provide
    fun zoomedViewports(): Arbitrary<ComparisonViewport> {
        // scale >= 1 (zoomed in / at fit) is where the covering invariant applies.
        val scales = Arbitraries.floats().between(1f, 25f)
        // Pans span far past both edges so the clamp is stressed on the min and max sides.
        val pans = Arbitraries.floats().between(-30000f, 30000f)
        return Combinators.combine(scales, pans, pans).`as` { scale, panX, panY ->
            ComparisonViewport(scale = scale, panX = panX, panY = panY)
        }
    }

    @Provide
    fun dimensions(): Arbitrary<Float> {
        // Positive, finite view dimensions covering small and large viewports.
        return Arbitraries.floats().between(1f, 4000f)
    }
}
