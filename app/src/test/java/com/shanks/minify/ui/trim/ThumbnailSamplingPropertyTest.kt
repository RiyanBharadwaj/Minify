package com.shanks.minify.ui.trim

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [TimelineSampling.sampleTimes], the pure thumbnail sampling
 * math behind the Video_Trimmer timeline filmstrip.
 *
 * The sampled positions must keep thumbnail memory bounded on long videos (Req 16.4):
 * the count never exceeds `maxThumbs`, every position lands within `[0, durationMs]`, the
 * positions are strictly ascending (hence distinct), and the samples span the full
 * duration — the first is always `0` and, when more than one sample is produced, the last
 * is `durationMs`.
 *
 * Because the implementation calls `distinct()`, short durations (`durationMs < maxThumbs - 1`)
 * can collapse evenly-spaced positions into fewer than `maxThumbs` samples, so the count is
 * asserted as an upper bound rather than an equality.
 */
class ThumbnailSamplingPropertyTest {

    // Feature: unified-media-editor, Property 24: Thumbnail sampling bounds the frame count and spans the duration
    // Validates: Requirements 16.4
    @Property(tries = 500)
    fun sampleTimesBoundsCountAndSpansDuration(
        @ForAll("durations") durationMs: Long,
        @ForAll("maxThumbs") maxThumbs: Int,
    ) {
        val times = TimelineSampling.sampleTimes(durationMs, maxThumbs)

        assertTrue(
            times.isNotEmpty(),
            "sampleTimes($durationMs, $maxThumbs) must produce at least one sample for a positive duration",
        )
        assertTrue(
            times.size <= maxThumbs,
            "sampleTimes($durationMs, $maxThumbs) must return at most $maxThumbs samples, got ${times.size}",
        )

        // All within [0, durationMs].
        for (t in times) {
            assertTrue(
                t in 0L..durationMs,
                "sample $t from sampleTimes($durationMs, $maxThumbs) must be within [0, $durationMs]",
            )
        }

        // Strictly ascending (implies distinct).
        for (i in 1 until times.size) {
            assertTrue(
                times[i] > times[i - 1],
                "sampleTimes($durationMs, $maxThumbs) must be strictly ascending, but ${times[i - 1]} !< ${times[i]} at index $i",
            )
        }

        // Spans the duration: first is always 0.
        assertEquals(
            0L,
            times.first(),
            "sampleTimes($durationMs, $maxThumbs) must start at 0",
        )
        // When more than one sample, the last is durationMs.
        if (times.size > 1) {
            assertEquals(
                durationMs,
                times.last(),
                "sampleTimes($durationMs, $maxThumbs) with more than one sample must end at $durationMs",
            )
        }
    }

    /** Positive durations spanning sub-millisecond-collapse ranges up to an hour. */
    @Provide
    fun durations(): Arbitrary<Long> = Arbitraries.longs().between(1L, 3_600_000L)

    /** At least one thumbnail, up to a generous cap. */
    @Provide
    fun maxThumbs(): Arbitrary<Int> = Arbitraries.integers().between(1, 512)
}
