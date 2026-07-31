package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.trim.TrimRange
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [VideoTimeline.withVolume] and [VideoTimeline.withMuted], the pure audio
 * controls behind the Unified_Media_Editor's Volume and Mute tools (Req 8.3, 8.4, 8.5).
 *
 * The Volume control can request any raw level (including values well outside the bounded range);
 * the stored [VideoTimeline.volume] must always be coerced into `[0, MAX_VOLUME]`. Changing the
 * volume must never implicitly mute or un-mute the output: the [VideoTimeline.muted] flag is
 * retained across volume changes until the user explicitly toggles it.
 */
class VolumeMutePropertyTest {

    // Feature: unified-media-editor, Property 18: Volume is bounded and mute is retained across volume changes
    /**
     * Feature: unified-media-editor, Property 18: Volume is bounded and mute is retained across
     * volume changes.
     *
     * For any raw volume float, [VideoTimeline.withVolume] stores a value within
     * `[0, MAX_VOLUME]` equal to `raw.coerceIn(0, MAX_VOLUME)` (Req 8.3), and when the timeline is
     * muted, applying [VideoTimeline.withVolume] with any raw leaves [VideoTimeline.muted] true
     * (Req 8.4, 8.5).
     *
     * **Validates: Requirements 8.3, 8.4, 8.5**
     */
    @Property(tries = 300)
    fun volumeIsBoundedAndMuteIsRetainedAcrossVolumeChanges(
        @ForAll("trims") trim: TrimRange,
        @ForAll("rawVolumes") raw: Float,
    ) {
        val base = VideoTimeline(trim = trim)

        val afterVolume = base.withVolume(raw)

        // The stored volume lies within the bounded [0, MAX_VOLUME] range (Req 8.3).
        assertTrue(
            afterVolume.volume in 0f..VideoTimeline.MAX_VOLUME,
            "stored volume ${afterVolume.volume} must lie within [0, ${VideoTimeline.MAX_VOLUME}] " +
                "(raw=$raw)",
        )

        // And equals the raw value coerced into that range (Req 8.3).
        assertEquals(
            raw.coerceIn(0f, VideoTimeline.MAX_VOLUME),
            afterVolume.volume,
            "stored volume must equal raw coerced into [0, ${VideoTimeline.MAX_VOLUME}] (raw=$raw)",
        )

        // When muted, changing the volume with any raw retains the mute state (Req 8.4, 8.5).
        val muted = base.withMuted(true)
        val mutedAfterVolume = muted.withVolume(raw)
        assertTrue(
            mutedAfterVolume.muted,
            "mute must be retained across volume changes (raw=$raw)",
        )
        // The clamped volume is still recorded while muted so un-muting restores the level.
        assertEquals(
            raw.coerceIn(0f, VideoTimeline.MAX_VOLUME),
            mutedAfterVolume.volume,
            "volume must be recorded even while muted (raw=$raw)",
        )
    }

    /**
     * A valid [TrimRange] (whole ms, `startMs < endMs`, at least the minimum duration). The trim
     * value is irrelevant to the audio controls but is required to construct a [VideoTimeline].
     */
    @Provide
    fun trims(): Arbitrary<TrimRange> {
        val starts = Arbitraries.longs().between(0L, 100_000L)
        val extraDurations = Arbitraries.longs().between(TrimRange.MIN_DURATION_MS, 50_000L)
        return net.jqwik.api.Combinators.combine(starts, extraDurations)
            .`as` { start, extra -> TrimRange(startMs = start, endMs = start + extra) }
    }

    /**
     * Raw volume levels spanning far beyond `[0, MAX_VOLUME]` on both sides (including negative and
     * greater-than-max values) so the clamp is stressed from both directions, plus values inside
     * the range.
     */
    @Provide
    fun rawVolumes(): Arbitrary<Float> =
        Arbitraries.floats().between(-1e6f, 1e6f)
}
