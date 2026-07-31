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
 * Property-based test for how a selected [Filter] is folded into the color
 * pipeline (Req 5.1) and for the media-type independence of that derivation
 * (Req 5.4).
 *
 * Selecting a [Filter] records it on the [ColorGrade]; [EffectSpec.from] must
 * fold that filter into the single composed [EffectSpec.rgbMatrix] such that the
 * result is the filter's catalog matrix ([FilterCatalog.colorMatrix]) composed
 * with the current adjustments. Because the derivation reads only the grade and
 * takes no media-type input, the resulting [EffectSpec] is identical whether the
 * grade is applied to a `Photo` or a `Video`.
 */
class EffectSpecFilterColorTransformPropertyTest {

    // Feature: media-editor-ux-fixes, Property 8: Filter selection yields a media-type-independent color transform
    @Property(tries = 200)
    fun filterFoldsIntoComposedRgbMatrixAndIsMediaTypeIndependent(
        @ForAll("filters") filter: Filter,
        @ForAll("adjustments") adjustments: Adjustments,
        @ForAll("durations") durationMs: Long,
    ) {
        val grade = ColorGrade(adjustments = adjustments, filter = filter)

        // The filter is folded into the composed rgbMatrix as the catalog matrix
        // left-multiplied onto the adjustments-only transform: filter ∘ adjustments.
        // The adjustments-only transform is the same grade with Filter.NONE
        // (whose catalog matrix is the identity), so this isolates the filter's
        // contribution using only public APIs.
        val adjustmentsOnly = EffectSpec.from(ColorGrade(adjustments = adjustments, filter = Filter.NONE))
        val expectedRgbMatrix = FilterCatalog.colorMatrix(filter) * adjustmentsOnly.rgbMatrix

        val spec = EffectSpec.from(grade)
        assertTrue(
            spec.rgbMatrix.approxEquals(expectedRgbMatrix),
            "rgbMatrix must equal the filter's catalog matrix composed with the adjustments " +
                "for filter $filter and adjustments $adjustments",
        )

        // The derivation takes no media-type input, so a Photo state and a Video
        // state carrying the same grade must produce identical EffectSpecs.
        val photoState = MediaEditState.initial(MediaType.PHOTO).copy(color = grade)
        val videoState = MediaEditState.initial(MediaType.VIDEO, durationMs).copy(color = grade)

        val photoSpec = EffectSpec.from(photoState)
        val videoSpec = EffectSpec.from(videoState)

        assertEquals(
            photoSpec,
            videoSpec,
            "the filter color transform must be identical for photo and video for grade $grade",
        )
        assertTrue(
            photoSpec.rgbMatrix.approxEquals(videoSpec.rgbMatrix),
            "the composed rgbMatrix must be media-type independent for grade $grade",
        )
        // Both media types must also match the filter-folded transform.
        assertTrue(photoSpec.rgbMatrix.approxEquals(expectedRgbMatrix))
        assertTrue(videoSpec.rgbMatrix.approxEquals(expectedRgbMatrix))
    }

    /** Any named filter, including [Filter.NONE]. */
    @Provide
    fun filters(): Arbitrary<Filter> =
        Arbitraries.of(*Filter.entries.toTypedArray())

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
