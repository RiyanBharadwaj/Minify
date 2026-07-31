package com.shanks.minify.ui.trim

import com.shanks.minify.ui.CropRect
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull

/**
 * Property-based test for [TrimRangeOps.toEditState], the pure mapping from a confirmed
 * [TrimRange] to an [com.shanks.minify.ui.EditState] for the Video_Compression_Pipeline.
 *
 * The mapping must always carry the input crop through unchanged, represent a full-from-zero
 * range as "no trim", and otherwise carry the exact start/end of a strict sub-range.
 */
class TrimConfirmationMappingPropertyTest {

    // Feature: unified-media-editor, Property 14: Trim confirmation maps the range to EditState, preserving crop and the no-trim case
    @Property(tries = 500)
    fun trimConfirmationMapsRangeToEditState(
        @ForAll("scenarios") scenario: MappingScenario,
    ) {
        val (fullDurationMs, range, crop) = scenario

        val state = TrimRangeOps.toEditState(range, fullDurationMs, crop)

        // Crop is always preserved verbatim (Req 14.2).
        assertEquals(crop, state.cropRect, "cropRect must equal input crop for range=$range")

        val isFullFromZero = range.startMs == 0L && range.endMs == fullDurationMs
        if (isFullFromZero) {
            // Full-from-zero maps to the no-trim representation (Req 7.5).
            assertEquals(0L, state.trimStartMs, "full-from-zero must set trimStartMs=0 (range=$range)")
            assertNull(state.trimEndMs, "full-from-zero must set trimEndMs=null (range=$range)")
            assertFalse(state.hasTrim, "full-from-zero must not report a trim (range=$range)")
        } else {
            // A strict sub-range carries its exact bounds (Req 7.5).
            assertEquals(range.startMs, state.trimStartMs, "sub-range must carry startMs (range=$range)")
            assertEquals(range.endMs, state.trimEndMs, "sub-range must carry endMs (range=$range)")
        }
    }

    /**
     * A full timeline duration (>= 500ms), a valid [TrimRange] contained in `[0, fullDurationMs]`
     * with at least the 500ms minimum selected duration, and an optional [CropRect]. Scenarios
     * intentionally include both full-from-zero ranges and strict sub-ranges, plus both null and
     * non-null crops.
     */
    @Provide
    fun scenarios(): Arbitrary<MappingScenario> {
        val durations: Arbitrary<Long> =
            Arbitraries.longs().between(TrimRange.MIN_DURATION_MS, 3_600_000L)

        return durations.flatMap { fullDurationMs ->
            val maxStart = fullDurationMs - TrimRange.MIN_DURATION_MS
            // Bias toward 0 so the full-from-zero case is exercised frequently.
            val starts: Arbitrary<Long> =
                Arbitraries.oneOf(
                    Arbitraries.just(0L),
                    Arbitraries.longs().between(0L, maxStart),
                )

            starts.flatMap { startMs ->
                // Bias toward the full duration so full-from-zero is hit alongside sub-ranges.
                val ends: Arbitrary<Long> =
                    Arbitraries.oneOf(
                        Arbitraries.just(fullDurationMs),
                        Arbitraries.longs().between(startMs + TrimRange.MIN_DURATION_MS, fullDurationMs),
                    )

                ends.flatMap { endMs ->
                    crops().map { crop ->
                        MappingScenario(fullDurationMs, TrimRange(startMs, endMs), crop)
                    }
                }
            }
        }
    }

    /** Optional normalised crop rectangles: null (no crop) and arbitrary in-bounds rectangles. */
    private fun crops(): Arbitrary<CropRect?> {
        val coords: Arbitrary<Float> = Arbitraries.floats().between(0f, 1f)
        val rects: Arbitrary<CropRect?> =
            Combinators.combine(coords, coords, coords, coords).`as` { a, b, c, d ->
                CropRect(minOf(a, c), minOf(b, d), maxOf(a, c), maxOf(b, d))
            }
        return Arbitraries.oneOf(Arbitraries.just(null), rects)
    }

    data class MappingScenario(
        val fullDurationMs: Long,
        val range: TrimRange,
        val crop: CropRect?,
    )
}
