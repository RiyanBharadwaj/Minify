package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.trim.TrimRange

/**
 * The playback correction decided by [PlaybackLoop.step] for the unified video editor (Req 8.4).
 *
 * The command tells the preview player what to do next given the current playhead relative to the
 * kept [TrimRange]. Every command that moves the playhead moves it to `trim.startMs`, so the
 * resolved next position always lies within `[trim.startMs, trim.endMs]`.
 */
sealed interface PlaybackCommand {

    /** No correction is needed: the playhead is already inside the kept range. */
    data object None : PlaybackCommand

    /**
     * The playhead reached the trim end while playing: seek back to `trim.startMs` and keep
     * playing (loop). This replaces the old pause-and-stall behavior (Req 8.4).
     */
    data object LoopToStart : PlaybackCommand

    /**
     * The playhead is before the trim start: seek to `trim.startMs` without otherwise changing the
     * play state.
     */
    data object SeekToStart : PlaybackCommand
}

/**
 * Pure, Android-independent decision for keeping video preview playback within the kept range
 * (Req 8.4).
 *
 * The preview player's polling loop delegates to [step] on every tick: while playing, reaching or
 * passing `trim.endMs` loops back to `trim.startMs`; a playhead before `trim.startMs` is pulled
 * up to the start; otherwise nothing happens. Because it carries no Android dependencies, the loop
 * decision is verified with property-based tests on the JVM.
 */
object PlaybackLoop {

    /**
     * Decide the playback correction for a [positionMs] within [trim] given the current
     * [isPlaying] state.
     *
     * - Returns [PlaybackCommand.LoopToStart] when [isPlaying] and `positionMs >= trim.endMs`
     *   (loop at the trim end, keep playing).
     * - Returns [PlaybackCommand.SeekToStart] when `positionMs < trim.startMs`.
     * - Returns [PlaybackCommand.None] otherwise.
     *
     * The trim-end loop is only triggered while playing; a paused playhead resting at or past the
     * end is left untouched.
     */
    fun step(positionMs: Long, isPlaying: Boolean, trim: TrimRange): PlaybackCommand = when {
        isPlaying && positionMs >= trim.endMs -> PlaybackCommand.LoopToStart
        positionMs < trim.startMs -> PlaybackCommand.SeekToStart
        else -> PlaybackCommand.None
    }
}
