package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.trim.TrimRange
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.math.abs

/**
 * Property-based test for [PlaybackSpeedOps.fromMultiplier] and [VideoTimeline.withSpeed].
 *
 * A requested playback-rate multiplier is accepted (mapped to a non-null [PlaybackSpeed]) if and
 * only if it is finite and lies in the inclusive range `[0.25, 4.0]`; any out-of-range or
 * non-finite request is rejected (`null`, previous speed retained). An accepted multiplier maps to
 * the nearest offered [PlaybackSpeed], and recording it via [VideoTimeline.withSpeed] yields a
 * timeline carrying exactly that value.
 */
class PlaybackSpeedSelectionPropertyTest {

    // Feature: media-editor-ux-fixes, Property 14: Speed selection is accepted exactly within range and recorded
    @Property(tries = 500)
    fun speedSelectionAcceptedExactlyWithinRangeAndRecorded(
        @ForAll("multipliers") multiplier: Float,
    ) {
        val result = PlaybackSpeedOps.fromMultiplier(multiplier)

        val inRange = multiplier.isFinite() &&
            multiplier >= PlaybackSpeedOps.MIN_MULTIPLIER &&
            multiplier <= PlaybackSpeedOps.MAX_MULTIPLIER

        if (inRange) {
            // Acceptance iff within the inclusive range and finite.
            assertNotNull(
                result,
                "multiplier=$multiplier is within [0.25, 4.0] and must be accepted (non-null)",
            )
            val accepted = result!!

            // The accepted speed is the nearest offered PlaybackSpeed to the request.
            val nearest = PlaybackSpeed.entries.minByOrNull { abs(it.multiplier - multiplier) }!!
            val bestDistance = abs(nearest.multiplier - multiplier)
            assertTrue(
                abs(accepted.multiplier - multiplier) <= bestDistance + 1e-6f,
                "accepted speed $accepted (x${accepted.multiplier}) must be nearest to $multiplier",
            )

            // Recording the accepted speed carries exactly that PlaybackSpeed.
            val base = VideoTimeline(trim = TrimRange(0L, 1_000L))
            val recorded = base.withSpeed(accepted)
            assertEquals(
                accepted,
                recorded.speed,
                "withSpeed must record exactly the accepted PlaybackSpeed",
            )
        } else {
            // Rejection for out-of-range or non-finite requests.
            assertNull(
                result,
                "multiplier=$multiplier is out of [0.25, 4.0] or non-finite and must be rejected (null)",
            )
        }
    }

    /**
     * A mix of finite in-range multipliers (including the discrete enum values and values strictly
     * between them), finite out-of-range multipliers on both sides, boundary values, and the
     * non-finite specials (`NaN`, `+Inf`, `-Inf`).
     */
    @Provide
    fun multipliers(): Arbitrary<Float> {
        val inRange: Arbitrary<Float> = Arbitraries.floats().between(0.25f, 4.0f).ofScale(4)
        val belowRange: Arbitrary<Float> =
            Arbitraries.floats().between(-100f, 0.2499f).ofScale(4)
        val aboveRange: Arbitrary<Float> =
            Arbitraries.floats().between(4.0001f, 100f).ofScale(4)
        val boundaries: Arbitrary<Float> =
            Arbitraries.of(0.25f, 4.0f, 0.24f, 4.01f, 0.0f)
        val nonFinite: Arbitrary<Float> =
            Arbitraries.of(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)

        return Arbitraries.oneOf(inRange, belowRange, aboveRange, boundaries, nonFinite)
    }
}
