package com.shanks.minify.ui.editor.model

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.math.max
import kotlin.math.min

/**
 * Property-based tests for [PhotoSettings.resizedPreservingAspect], the pure
 * step that scales an edited photo so its longest edge becomes a chosen target
 * while preserving the edited aspect ratio (Req 10.4).
 *
 * The result must always have strictly positive integer dimensions, the longest
 * edge must equal the coerced target, and the aspect ratio must approximately
 * match the edited one (within integer-rounding tolerance, allowing for the
 * floor-at-1 behavior on extreme aspect ratios).
 */
class PhotoResizePropertyTest {

    // Feature: unified-media-editor, Property 20: Photo resize yields positive integer dimensions preserving the edited aspect ratio
    @Property(tries = 300)
    fun photoResizeYieldsPositiveIntegerDimensionsPreservingAspect(
        @ForAll("targets") target: Int,
        @ForAll("dimensions") edited: Pair<Int, Int>,
    ) {
        val (editedW, editedH) = edited
        val result = PhotoSettings().resizedPreservingAspect(target, editedW, editedH)

        val coercedTarget = max(target, 1)

        // Dimensions are strictly positive integers.
        assertTrue(
            result.width > 0 && result.height > 0,
            "result dimensions must be positive, was ${result.width}x${result.height} " +
                "(target=$target, edited=${editedW}x$editedH)",
        )

        // The longest edge of the result equals the coerced target.
        assertEquals(
            coercedTarget,
            max(result.width, result.height),
            "longest edge must equal coerced target ($coercedTarget) " +
                "(target=$target, edited=${editedW}x$editedH, " +
                "result=${result.width}x${result.height})",
        )

        // The result preserves the edited aspect ratio within a tolerance that
        // accounts for integer rounding of the shorter edge and the floor-at-1
        // guard used for extreme aspect ratios.
        val editedRatio = max(editedW, editedH).toDouble() / min(editedW, editedH)
        val resultRatio = max(result.width, result.height).toDouble() /
            min(result.width, result.height)

        // Rounding the shorter edge can move it by up to half a pixel; the
        // floor-at-1 guard can move it further for extreme ratios. Bound the
        // relative error by what a +/-1 pixel shift on the shorter edge allows.
        val shorterEdge = min(result.width, result.height)
        val absoluteRatioTolerance =
            coercedTarget.toDouble() / (shorterEdge - 0.5).coerceAtLeast(0.5) -
                coercedTarget.toDouble() / (shorterEdge + 0.5)

        // When the ideal shorter edge would round below 1 it is floored to 1,
        // which legitimately compresses the ratio; only require ratio fidelity
        // when the result did not hit that floor with a still-smaller ideal.
        val idealShorter = coercedTarget.toDouble() * min(editedW, editedH) / max(editedW, editedH)
        val hitFloor = shorterEdge == 1 && idealShorter < 1.0

        if (!hitFloor) {
            assertTrue(
                kotlin.math.abs(resultRatio - editedRatio) <=
                    absoluteRatioTolerance + 1e-9,
                "result aspect ratio ($resultRatio) must approximate edited " +
                    "ratio ($editedRatio) within $absoluteRatioTolerance " +
                    "(target=$target, edited=${editedW}x$editedH, " +
                    "result=${result.width}x${result.height})",
            )
        } else {
            // Floored case: the result ratio must be at least the edited ratio
            // (the shorter edge was rounded up to 1, never below).
            assertTrue(
                resultRatio <= editedRatio + 1e-9,
                "floored result ratio ($resultRatio) must not exceed edited " +
                    "ratio ($editedRatio) (target=$target, edited=${editedW}x$editedH, " +
                    "result=${result.width}x${result.height})",
            )
        }
    }

    // Feature: media-editor-fixes, Property 11
    // Photo resize always resolves to positive, aspect-preserving dimensions.
    // Validates: Requirements 12.3
    @Property(tries = 300)
    fun photoResizeAlwaysResolvesToPositiveAspectPreservingDimensions(
        @ForAll("targetsIncludingNonPositive") target: Int,
        @ForAll("dimensions") edited: Pair<Int, Int>,
    ) {
        val (editedW, editedH) = edited
        val result = PhotoSettings().resizedPreservingAspect(target, editedW, editedH)

        // A zero or negative requested edge is coerced to a valid minimum of 1.
        val coercedTarget = max(target, 1)

        // The resolved OutputSize has strictly positive width and height for any
        // requested target edge (including zero or negative) and any edited dims.
        assertTrue(
            result.width > 0 && result.height > 0,
            "resolved dimensions must be strictly positive, was " +
                "${result.width}x${result.height} " +
                "(target=$target, edited=${editedW}x$editedH)",
        )

        // The longest edge equals the coerced target: zero/negative targets
        // resolve to a longest edge of 1, positive targets to themselves.
        assertEquals(
            coercedTarget,
            max(result.width, result.height),
            "longest edge must equal coerced target ($coercedTarget) " +
                "(target=$target, edited=${editedW}x$editedH, " +
                "result=${result.width}x${result.height})",
        )

        // Aspect ratio is preserved: the result ratio approximates the edited
        // ratio within integer-rounding tolerance, except where the shorter edge
        // is floored at 1 (extreme aspect ratios), which can only compress it.
        val editedRatio = max(editedW, editedH).toDouble() / min(editedW, editedH)
        val shorterEdge = min(result.width, result.height)
        val resultRatio = max(result.width, result.height).toDouble() / shorterEdge

        val absoluteRatioTolerance =
            coercedTarget.toDouble() / (shorterEdge - 0.5).coerceAtLeast(0.5) -
                coercedTarget.toDouble() / (shorterEdge + 0.5)

        val idealShorter = coercedTarget.toDouble() * min(editedW, editedH) / max(editedW, editedH)
        val hitFloor = shorterEdge == 1 && idealShorter < 1.0

        if (!hitFloor) {
            assertTrue(
                kotlin.math.abs(resultRatio - editedRatio) <= absoluteRatioTolerance + 1e-9,
                "result aspect ratio ($resultRatio) must approximate edited " +
                    "ratio ($editedRatio) within $absoluteRatioTolerance " +
                    "(target=$target, edited=${editedW}x$editedH, " +
                    "result=${result.width}x${result.height})",
            )
        } else {
            assertTrue(
                resultRatio <= editedRatio + 1e-9,
                "floored result ratio ($resultRatio) must not exceed edited " +
                    "ratio ($editedRatio) (target=$target, edited=${editedW}x$editedH, " +
                    "result=${result.width}x${result.height})",
            )
        }
    }

    @Provide
    fun targets(): Arbitrary<Int> =
        // Target longest-edge lengths, including 1 to exercise the coercion.
        Arbitraries.integers().between(1, 10_000)

    @Provide
    fun targetsIncludingNonPositive(): Arbitrary<Int> =
        // Requested target edges spanning negative, zero, and positive values so
        // the coerce-to-1 guard is exercised alongside normal resizing.
        Arbitraries.integers().between(-5_000, 10_000)

    @Provide
    fun dimensions(): Arbitrary<Pair<Int, Int>> {
        val edges = Arbitraries.integers().between(1, 20_000)
        return Combinators.combine(edges, edges).`as`<Pair<Int, Int>> { w, h -> w to h }
    }
}
