package com.shanks.minify.photo

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based test for the Photo Editor's preview downsampling.
 *
 * Exercises the pure [previewSampleSize] helper that chooses the
 * `BitmapFactory.inSampleSize` for the editor preview so that the decoded
 * bitmap's longest edge stays bounded while editing large sources.
 *
 * **Validates: Requirements 11.3**
 */
class PreviewDownsamplingPropertyTest {

    // Feature: unified-media-editor, Property 22: Preview downsampling bounds the longest edge
    @Property(tries = 200)
    fun previewDownsamplingBoundsTheLongestEdge(
        @ForAll @IntRange(min = 1, max = 40000) w: Int,
        @ForAll @IntRange(min = 1, max = 40000) h: Int,
    ) {
        val sample = previewSampleSize(w, h)
        val longest = maxOf(w, h)

        // Sample size is always a positive power of two.
        assertTrue(sample >= 1, "sample size must be >= 1 but was $sample")
        assertTrue(
            sample and (sample - 1) == 0,
            "sample size must be a power of two but was $sample",
        )

        if (longest > PREVIEW_MAX_EDGE) {
            // Source exceeds the cap: the downsampled longest edge is bounded.
            assertTrue(
                longest / sample <= PREVIEW_MAX_EDGE,
                "downsampled longest edge ${longest / sample} exceeds cap $PREVIEW_MAX_EDGE " +
                    "(longest=$longest, sample=$sample)",
            )
        } else {
            // Source fits within the cap: it is shown unscaled.
            assertEquals(
                1,
                sample,
                "source within cap should be unscaled but sample was $sample (longest=$longest)",
            )
        }
    }
}
