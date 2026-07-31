package com.shanks.minify.ui.editor.model

import kotlin.math.abs

/**
 * Pure, Android-independent selection logic for the editor's playback speed (Req 8.4, 8.5).
 *
 * The editor offers only a bounded set of playback-rate multipliers ([PlaybackSpeed]). A speed
 * request is expressed as a raw [Float] multiplier and is accepted **iff** it lies within the
 * inclusive range `[0.25, 4.0]` times normal playback rate; any request outside that range (or a
 * non-finite value) is rejected. An accepted multiplier is mapped to the nearest offered
 * [PlaybackSpeed] so the recorded value always belongs to the bounded set.
 */
object PlaybackSpeedOps {

    /** The smallest accepted playback multiplier (inclusive). */
    const val MIN_MULTIPLIER: Float = 0.25f

    /** The largest accepted playback multiplier (inclusive). */
    const val MAX_MULTIPLIER: Float = 4.0f

    /**
     * Resolve a raw playback-rate [multiplier] to a bounded [PlaybackSpeed].
     *
     * Returns the offered [PlaybackSpeed] whose multiplier is nearest to [multiplier] when
     * [multiplier] is finite and within the inclusive range `[0.25, 4.0]`; returns `null` when the
     * request is out of range or not a finite number.
     */
    fun fromMultiplier(multiplier: Float): PlaybackSpeed? {
        if (!multiplier.isFinite()) return null
        if (multiplier < MIN_MULTIPLIER || multiplier > MAX_MULTIPLIER) return null
        return PlaybackSpeed.entries.minByOrNull { abs(it.multiplier - multiplier) }
    }
}
