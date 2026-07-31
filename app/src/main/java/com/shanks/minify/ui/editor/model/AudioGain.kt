package com.shanks.minify.ui.editor.model

/**
 * Pure, Android-independent mapping from a [VideoTimeline] to the single audio gain value that both
 * the preview and the export apply (Req 6.1, 6.2, 6.3).
 *
 * Keeping this in one place guarantees that the loudness the user hears in the preview equals the
 * loudness export writes, even for volumes above 100% where the preview must route the gain through
 * an equivalent processor rather than the clamped player volume.
 */
object AudioGain {

    /**
     * The single gain both preview and export apply for the given [timeline].
     *
     * A muted timeline yields a gain of `0`; otherwise the timeline's [VideoTimeline.volume] is
     * clamped to `[0, MAX_VOLUME]`. The result is always finite and within `[0, MAX_VOLUME]`.
     *
     * @return the gain multiplier in `[0, VideoTimeline.MAX_VOLUME]`.
     */
    fun gain(timeline: VideoTimeline): Float {
        if (timeline.muted) return 0f
        return timeline.volume.coerceIn(0f, VideoTimeline.MAX_VOLUME)
    }
}
