package com.shanks.minify.ui.editor.model

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for the media-type independence of the color pipeline:
 * the guarantee both thin renderers rely on so that a `Photo` and a `Video`
 * carrying the same [ColorGrade] resolve to the exact same GPU color pipeline
 * (Req 4.7, 5.5).
 *
 * For any generated [ColorGrade], deriving an [EffectSpec] from a
 * [MediaEditState] for a `Photo` must equal deriving it from a `Video` state
 * carrying the same grade. `EffectSpec.from` reads only [MediaEditState.color],
 * so the media type, geometry, timeline, and photo settings must have no effect
 * on the resulting color pipeline.
 */
class EffectSpecMediaIndependencePropertyTest {

    // Feature: unified-media-editor, Property 10: The color pipeline is independent of media type
    @Property(tries = 200)
    fun colorPipelineIsIndependentOfMediaType(
        @ForAll("grades") grade: ColorGrade,
        @ForAll("durations") durationMs: Long,
    ) {
        val photoState = MediaEditState.initial(MediaType.PHOTO).copy(color = grade)
        val videoState = MediaEditState.initial(MediaType.VIDEO, durationMs).copy(color = grade)

        val photoSpec = EffectSpec.from(photoState)
        val videoSpec = EffectSpec.from(videoState)

        // EffectSpec is a data class and ColorMatrix4x4 has structural (exact)
        // equality, so identical grades must derive fully equal specs regardless
        // of media type.
        assertEquals(
            photoSpec,
            videoSpec,
            "the color pipeline must not depend on media type for grade $grade",
        )

        // Guard against any float fragility in the composed matrix: the scalar
        // passes match exactly and the matrices agree within tolerance.
        assertTrue(
            photoSpec.rgbMatrix.approxEquals(videoSpec.rgbMatrix),
            "the composed rgbMatrix must be media-type independent for grade $grade",
        )
        assertEquals(photoSpec.highlights, videoSpec.highlights)
        assertEquals(photoSpec.shadows, videoSpec.shadows)
        assertEquals(photoSpec.sharpness, videoSpec.sharpness)
        assertEquals(photoSpec.blur, videoSpec.blur)
        assertEquals(photoSpec.vignette, videoSpec.vignette)
    }

    // Feature: unified-media-editor, Property 10: The color pipeline is independent of media type
    @Property(tries = 200)
    fun stateOverloadMatchesGradeOverloadForBothMediaTypes(
        @ForAll("grades") grade: ColorGrade,
        @ForAll("durations") durationMs: Long,
    ) {
        val photoState = MediaEditState.initial(MediaType.PHOTO).copy(color = grade)
        val videoState = MediaEditState.initial(MediaType.VIDEO, durationMs).copy(color = grade)

        // The state overload delegates to the grade overload; both media types
        // must agree with deriving directly from the shared grade.
        val fromGrade = EffectSpec.from(grade)
        assertEquals(fromGrade, EffectSpec.from(photoState))
        assertEquals(fromGrade, EffectSpec.from(videoState))
    }

    /**
     * Arbitrary color grades spanning the full input space: every adjustment set
     * to a random in-range value, any named filter (including [Filter.NONE]), and
     * any vignette including out-of-range raw values (clamped via [ColorGrade]).
     */
    @Provide
    fun grades(): Arbitrary<ColorGrade> {
        val adjustments = adjustments()
        val filters = Arbitraries.of(*Filter.entries.toTypedArray())
        // Include out-of-range vignette values so clamping is exercised identically.
        val vignettes = Arbitraries.floats().between(-0.5f, 1.5f)
        return Combinators.combine(adjustments, filters, vignettes)
            .`as` { adj, filter, vignette -> ColorGrade(adj, filter, vignette) }
    }

    /** A full [Adjustments] map with each kind set to a random in-range value. */
    @Provide
    fun adjustments(): Arbitrary<Adjustments> {
        val kinds = AdjustmentKind.entries
        // One float per kind, drawn slightly outside range so per-kind clamping is
        // exercised the same way for both media types.
        val perKind: List<Arbitrary<Float>> = kinds.map { kind ->
            Arbitraries.floats().between(kind.min - 0.25f, kind.max + 0.25f)
        }
        return Combinators.combine(perKind).`as` { values ->
            var adjustments = Adjustments.NEUTRAL
            kinds.forEachIndexed { index, kind ->
                adjustments = adjustments.with(kind, values[index])
            }
            adjustments
        }
    }

    /** Non-negative video durations, including zero, in whole milliseconds. */
    @Provide
    fun durations(): Arbitrary<Long> =
        Arbitraries.longs().between(0L, 3_600_000L)
}
