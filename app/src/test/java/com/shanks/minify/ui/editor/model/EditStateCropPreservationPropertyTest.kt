package com.shanks.minify.ui.editor.model

import com.shanks.minify.photo.ImageEditModel
import com.shanks.minify.ui.CropRect
import com.shanks.minify.ui.trim.TrimRange
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Property-based test for crop preservation under timeline edits.
 *
 * A [MediaEditState] records the reused [ImageEditModel] geometry (including its
 * crop) independently of the [VideoTimeline]. Applying a trim or adding a split
 * must update only the timeline, leaving the recorded geometry — and in
 * particular its crop — untouched (Req 7.6).
 */
class EditStateCropPreservationPropertyTest {

    // Feature: unified-media-editor, Property 16: Trim and split edits preserve the existing crop
    @Property(tries = 200)
    fun trimAndSplitPreserveTheExistingCrop(
        @ForAll("scenarios") scenario: Scenario,
    ) {
        val (crop, fullDurationMs, trim, splitPositionMs) = scenario

        // A VIDEO state carrying a non-trivial crop on its geometry.
        val original = MediaEditState.initial(MediaType.VIDEO, fullDurationMs)
            .let { it.copy(geometry = it.geometry.withCrop(crop)) }

        // Applying a trim leaves the recorded crop unchanged.
        val afterTrim = original.applyTrim(trim)
        assertEquals(
            crop,
            afterTrim.geometry.crop,
            "applyTrim(trim=$trim) must preserve crop $crop",
        )

        // Adding a split leaves the recorded crop unchanged.
        val afterSplit = afterTrim.addSplit(splitPositionMs)
        assertEquals(
            crop,
            afterSplit.geometry.crop,
            "addSplit(position=$splitPositionMs) must preserve crop $crop",
        )
    }

    /**
     * A non-trivial normalized [CropRect], a full video duration (>= 500ms), a
     * valid trim range within `[0, fullDurationMs]` (start < end, at least the
     * 500ms minimum duration), and an arbitrary split position (in- or
     * out-of-bounds).
     */
    @Provide
    fun scenarios(): Arbitrary<Scenario> {
        val crops: Arbitrary<CropRect> = nonTrivialCrops()

        val durations: Arbitrary<Long> =
            Arbitraries.longs().between(TrimRange.MIN_DURATION_MS, 3_600_000L)

        val cropAndDuration: Arbitrary<Pair<CropRect, Long>> =
            Combinators.combine(crops, durations).`as` { crop, durationMs -> crop to durationMs }

        return cropAndDuration.flatMap { (crop, durationMs) ->
            val maxStart = durationMs - TrimRange.MIN_DURATION_MS
            val starts: Arbitrary<Long> = Arbitraries.longs().between(0L, maxStart)

            starts.flatMap { startMs ->
                val ends: Arbitrary<Long> =
                    Arbitraries.longs().between(startMs + TrimRange.MIN_DURATION_MS, durationMs)
                val splits: Arbitrary<Long> =
                    Arbitraries.longs().between(-durationMs, durationMs * 2 + 1_000L)

                Combinators.combine(ends, splits).`as` { endMs, splitMs ->
                    Scenario(crop, durationMs, TrimRange(startMs, endMs), splitMs)
                }
            }
        }
    }

    /**
     * Generates a non-trivial crop rectangle in `[0,1]` space with strictly
     * positive width and height (so it differs from [CropRect.FULL] in general).
     */
    private fun nonTrivialCrops(): Arbitrary<CropRect> {
        val coords: Arbitrary<Float> = Arbitraries.floats().between(0f, 1f)
        return Combinators.combine(coords, coords, coords, coords)
            .`as` { a, b, c, d ->
                val left = minOf(a, b)
                val right = maxOf(a, b)
                val top = minOf(c, d)
                val bottom = maxOf(c, d)
                // Ensure strictly positive extent to keep the crop non-degenerate.
                CropRect(
                    left = left,
                    top = top,
                    right = if (right > left) right else (left + 0.1f).coerceAtMost(1f),
                    bottom = if (bottom > top) bottom else (top + 0.1f).coerceAtMost(1f),
                )
            }
    }

    data class Scenario(
        val crop: CropRect,
        val fullDurationMs: Long,
        val trim: TrimRange,
        val splitPositionMs: Long,
    )
}
