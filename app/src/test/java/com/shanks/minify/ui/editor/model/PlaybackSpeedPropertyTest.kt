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
 * Property-based tests for [VideoTimeline.withSpeed], the pure step that records the
 * unified editor's Playback_Speed selection into the Media_Edit_State (Req 8.1, 8.2).
 *
 * The editor offers only a bounded set of speed multipliers ([PlaybackSpeed.entries]); selecting
 * any of them must record exactly that member, and every member's multiplier must be positive and
 * drawn from the defined set.
 */
class PlaybackSpeedPropertyTest {

    // Feature: unified-media-editor, Property 17: Playback speed is recorded from the bounded multiplier set
    /**
     * Feature: unified-media-editor, Property 17: Playback speed is recorded from the bounded
     * multiplier set.
     *
     * For any [PlaybackSpeed] from the bounded set, recording it via [VideoTimeline.withSpeed]
     * stores exactly that speed; the stored speed is a member of the bounded [PlaybackSpeed.entries]
     * set; and its multiplier is positive and belongs to the defined set of multipliers.
     *
     * **Validates: Requirements 8.1, 8.2**
     */
    @Property(tries = 200)
    fun playbackSpeedIsRecordedFromTheBoundedMultiplierSet(
        @ForAll("speeds") speed: PlaybackSpeed,
    ) {
        val timeline = VideoTimeline(trim = TrimRange(startMs = 0L, endMs = 1_000L))

        val updated = timeline.withSpeed(speed)

        // The selected multiplier is recorded exactly (Req 8.2).
        assertEquals(speed, updated.speed, "withSpeed must record the selected speed")

        // The recorded speed is a member of the bounded offered set (Req 8.1).
        assertTrue(
            PlaybackSpeed.entries.contains(updated.speed),
            "recorded speed ${updated.speed} must be a member of the bounded PlaybackSpeed set",
        )

        // Its multiplier is positive and drawn from the defined set of multipliers.
        assertTrue(
            updated.speed.multiplier > 0f,
            "multiplier ${updated.speed.multiplier} must be positive",
        )
        assertTrue(
            PlaybackSpeed.entries.map { it.multiplier }.contains(updated.speed.multiplier),
            "multiplier ${updated.speed.multiplier} must belong to the defined multiplier set",
        )
    }

    /** Any member of the bounded [PlaybackSpeed] set. */
    @Provide
    fun speeds(): Arbitrary<PlaybackSpeed> =
        Arbitraries.of(*PlaybackSpeed.entries.toTypedArray())
}
