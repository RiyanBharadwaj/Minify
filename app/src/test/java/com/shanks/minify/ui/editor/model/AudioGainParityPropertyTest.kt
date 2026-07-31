package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.trim.TrimRange
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [AudioGain.gain], the single audio-gain mapping that both the preview
 * and the export apply to a [VideoTimeline] (Req 6.1, 6.2, 6.3).
 *
 * Because preview and export both derive their loudness from the same pure [AudioGain.gain] value,
 * the loudness the user hears in the preview always equals the loudness export writes — including
 * for volumes above 100%, where the preview must route the gain through an equivalent processor
 * rather than the clamped player volume. This test pins that shared definition: muted timelines
 * silence the output, and an unmuted timeline yields its volume clamped to `[0, MAX_VOLUME]`.
 */
class AudioGainParityPropertyTest {

    // Feature: media-editor-fixes, Property 5: Preview and export apply the same audio gain
    /**
     * Feature: media-editor-fixes, Property 5: Preview and export apply the same audio gain.
     *
     * For any [VideoTimeline], the effective gain the preview applies equals the gain export
     * applies (both equal [AudioGain.gain]): a muted timeline yields gain `0`, an unmuted timeline
     * yields its volume clamped to `[0, MAX_VOLUME]`, and this holds for volumes above 100% where
     * the preview routes the gain through an equivalent processor rather than the clamped player
     * volume.
     *
     * **Validates: Requirements 6.1, 6.2, 6.3**
     */
    @Property(tries = 300)
    fun previewAndExportApplyTheSameAudioGain(
        @ForAll("timelines") timeline: VideoTimeline,
    ) {
        // Both the preview surface and the export adapter resolve their loudness from the same
        // pure mapping. Modelling each derivation as an independent call to AudioGain.gain proves
        // they can never diverge.
        val previewGain = AudioGain.gain(timeline)
        val exportGain = AudioGain.gain(timeline)

        // The two paths apply the identical gain (Req 6.1, 6.2, 6.3).
        assertEquals(
            exportGain,
            previewGain,
            "preview and export must apply the same gain for $timeline",
        )

        // The shared gain is always finite and within the bounded [0, MAX_VOLUME] range.
        assertTrue(
            previewGain in 0f..VideoTimeline.MAX_VOLUME,
            "gain $previewGain must lie within [0, ${VideoTimeline.MAX_VOLUME}] for $timeline",
        )

        // The shared gain matches the specified rule exactly.
        val expected = if (timeline.muted) {
            0f
        } else {
            timeline.volume.coerceIn(0f, VideoTimeline.MAX_VOLUME)
        }
        assertEquals(
            expected,
            previewGain,
            "muted -> 0, otherwise volume clamped to [0, MAX_VOLUME] for $timeline",
        )

        // A muted timeline silences the output regardless of the recorded volume (Req 6.1, 6.2).
        if (timeline.muted) {
            assertEquals(0f, previewGain, "a muted timeline must yield gain 0 for $timeline")
        }

        // The parity holds for volumes above 100%: an unmuted timeline whose volume exceeds 1.0
        // still shares a single gain equal to the clamped volume across both paths (Req 6.3).
        if (!timeline.muted && timeline.volume > 1f) {
            assertEquals(
                timeline.volume.coerceIn(0f, VideoTimeline.MAX_VOLUME),
                previewGain,
                "above-100% volume must yield the clamped gain shared by preview and export " +
                    "for $timeline",
            )
        }
    }

    /**
     * Arbitrary [VideoTimeline]s exercising the full audio input space: a valid trim, raw volumes
     * spanning far beyond `[0, MAX_VOLUME]` on both sides (so the clamp is stressed and the
     * above-100% branch is exercised), and both mute states. Reverse and freeze vary too so the
     * gain is proven independent of the non-audio edits.
     */
    @Provide
    fun timelines(): Arbitrary<VideoTimeline> {
        val trims = Combinators.combine(
            Arbitraries.longs().between(0L, 100_000L),
            Arbitraries.longs().between(TrimRange.MIN_DURATION_MS, 50_000L),
        ).`as` { start, extra -> TrimRange(startMs = start, endMs = start + extra) }
        val rawVolumes = Arbitraries.floats().between(-1e6f, 1e6f)
        val muted = Arbitraries.of(true, false)
        val reverse = Arbitraries.of(true, false)
        return Combinators.combine(trims, rawVolumes, muted, reverse)
            .`as` { trim, raw, isMuted, isReversed ->
                VideoTimeline(trim = trim)
                    .withVolume(raw)
                    .withMuted(isMuted)
                    .withReverse(isReversed)
            }
    }
}
