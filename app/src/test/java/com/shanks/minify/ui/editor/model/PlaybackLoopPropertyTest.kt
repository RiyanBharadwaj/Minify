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
 * Property-based tests for [PlaybackLoop.step], the pure looping decision behind the unified video
 * editor's preview playback (Req 8.4).
 *
 * The preview player's polling loop delegates to [PlaybackLoop.step] on every tick to keep the
 * playhead inside the kept [TrimRange]: reaching the trim end while playing loops back to the
 * start, a playhead before the start is pulled up to it, and anything already inside the range is
 * left alone.
 */
class PlaybackLoopPropertyTest {

    // Feature: media-editor-fixes, Property 7: Playback looping stays within the trim range
    /**
     * Feature: media-editor-fixes, Property 7: Playback looping stays within the trim range.
     *
     * For any position, play state, and trim range, [PlaybackLoop.step] returns loop-to-start
     * (seek to `trim.startMs`, keep playing) when playing and the position is at or beyond
     * `trim.endMs`, returns seek-to-start when the position is before `trim.startMs`, and otherwise
     * returns no action — so the resolved next position always lies within
     * `[trim.startMs, trim.endMs]`.
     *
     * **Validates: Requirements 8.4**
     */
    @Property(tries = 300)
    fun playbackLoopingStaysWithinTheTrimRange(
        @ForAll("trims") trim: TrimRange,
        @ForAll("positions") positionMs: Long,
        @ForAll isPlaying: Boolean,
    ) {
        val command = PlaybackLoop.step(positionMs, isPlaying, trim)

        // The command classification matches the specified rule exactly.
        val expected = when {
            isPlaying && positionMs >= trim.endMs -> PlaybackCommand.LoopToStart
            positionMs < trim.startMs -> PlaybackCommand.SeekToStart
            else -> PlaybackCommand.None
        }
        assertEquals(
            expected,
            command,
            "step($positionMs, playing=$isPlaying, $trim) classification",
        )

        // Resolve the next playhead position implied by the command: both corrective commands seek
        // to trim.startMs; None leaves the playhead where it is.
        val resolvedNext = when (command) {
            PlaybackCommand.LoopToStart, PlaybackCommand.SeekToStart -> trim.startMs
            PlaybackCommand.None -> positionMs
        }

        // While the player is playing, the resolved next position always lies within the kept
        // range: a corrective command snaps it to the start, and when no action is taken the
        // position is already inside [trim.startMs, trim.endMs).
        if (isPlaying) {
            assertTrue(
                resolvedNext in trim.startMs..trim.endMs,
                "resolved next position $resolvedNext must be within [${trim.startMs}, ${trim.endMs}]",
            )
        }

        // A corrective command (loop or seek) always resolves to the trim start, regardless of the
        // play state.
        if (command != PlaybackCommand.None) {
            assertEquals(
                trim.startMs,
                resolvedNext,
                "a corrective command must seek to trim.startMs",
            )
        }
    }

    /**
     * A valid [TrimRange]: whole ms, `startMs < endMs`, at least the 500ms minimum duration.
     */
    @Provide
    fun trims(): Arbitrary<TrimRange> {
        val starts = Arbitraries.longs().between(0L, 100_000L)
        val extraDurations = Arbitraries.longs().between(TrimRange.MIN_DURATION_MS, 50_000L)
        return Combinators.combine(starts, extraDurations)
            .`as` { start, extra -> TrimRange(startMs = start, endMs = start + extra) }
    }

    /**
     * Candidate playhead positions spanning well before the trim start through well beyond a
     * plausible trim end, so the generator exercises the before-start, inside-range, and
     * at/after-end branches of [PlaybackLoop.step].
     */
    @Provide
    fun positions(): Arbitrary<Long> = Arbitraries.longs().between(-10_000L, 200_000L)
}
