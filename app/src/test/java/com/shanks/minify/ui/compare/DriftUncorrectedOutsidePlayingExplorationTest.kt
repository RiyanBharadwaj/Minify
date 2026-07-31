package com.shanks.minify.ui.compare

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * BUG CONDITION EXPLORATION TEST (Property 3 / C_sync).
 *
 * This test encodes the EXPECTED (fixed) behavior described in the design's Property 3:
 *
 *   "For any video comparator playback event — play, pause, seek, scrub, or loop — the fixed
 *    drift-correction decision SHALL bring the two players' positions within the drift tolerance
 *    regardless of whether both are actively playing, so there is no perceptible drift."
 *
 * It is therefore EXPECTED TO FAIL on the UNFIXED code. The current
 * [SyncController.decideDrift] short-circuits to [SyncAction.None] whenever `!bothPlaying`
 * (drift is only corrected while both play) and has no loop-boundary awareness (a near-`duration`
 * position delta is treated as real drift rather than a wrap). Those two defects are exactly what
 * this test surfaces:
 *
 *  - [driftIsCorrectedWhilePausedOrSeeked] (scoped PBT): for generated `(beforePosMs, afterPosMs)`
 *    pairs diverged beyond tolerance with `bothPlaying = false`, applying the decision must bring
 *    the two within [SyncController.DRIFT_TOLERANCE_MS]. On unfixed code the decision is
 *    [SyncAction.None], so nothing is applied and the two stay diverged — the assertion fails.
 *
 *  - [loopBoundaryWrapIsNotTreatedAsDrift] (example): at the loop boundary one player has wrapped
 *    to ~0 while the other is near the end. Modulo the clip duration the two are actually a few ms
 *    apart, so the correct decision is a wrap-aware no-op ([SyncAction.None]). On unfixed code the
 *    near-`duration` absolute delta is treated as real drift and the freshly-wrapped player is
 *    re-seeked back to the end — the assertion fails.
 *
 * DO NOT fix the test or the production code here — the failure IS the counterexample that
 * confirms C_sync. This same test validates the fix once it passes after implementation.
 *
 * **Validates: Requirements 1.4**
 */
class DriftUncorrectedOutsidePlayingExplorationTest {

    /**
     * Apply a [SyncAction] to a `(before, after)` position pair, returning the resulting positions.
     * A re-seek moves the lagging player to the leader; [SyncAction.None] leaves both unchanged.
     */
    private fun apply(action: SyncAction, beforePosMs: Long, afterPosMs: Long): Pair<Long, Long> =
        when (action) {
            is SyncAction.SeekBefore -> action.toMs to afterPosMs
            is SyncAction.SeekAfter -> beforePosMs to action.toMs
            SyncAction.None -> beforePosMs to afterPosMs
        }

    // Feature: editor-compare-slider-fixes, Property 3 (Bug Condition): Videos stay synchronized
    // across play, pause, seek, scrub, and loop.
    /**
     * Scoped PBT over `(beforePosMs, afterPosMs)` pairs with `bothPlaying = false`, diverged beyond
     * tolerance. The EXPECTED (fixed) behavior: the decision brings the two within tolerance even
     * while not both playing. The UNFIXED code returns [SyncAction.None], so this FAILS — proving
     * drift is only corrected while both play (C_sync).
     *
     * **Validates: Requirements 1.4**
     */
    @Property(tries = 500)
    fun driftIsCorrectedWhilePausedOrSeeked(
        @ForAll("pausedDivergedPairs") pair: PositionPair,
    ) {
        val action = SyncController.decideDrift(
            bothPlaying = false,
            beforePosMs = pair.beforePosMs,
            afterPosMs = pair.afterPosMs,
        )

        val (correctedBefore, correctedAfter) = apply(action, pair.beforePosMs, pair.afterPosMs)
        val correctedDivergence = abs(correctedBefore - correctedAfter)

        assertTrue(
            correctedDivergence <= SyncController.DRIFT_TOLERANCE_MS,
            "paused/seeked drift must still be corrected: decideDrift(false, " +
                "${pair.beforePosMs}, ${pair.afterPosMs}) = $action left divergence " +
                "$correctedDivergence ms (> ${SyncController.DRIFT_TOLERANCE_MS} ms tolerance)",
        )
    }

    // Feature: editor-compare-slider-fixes, Property 3 (Bug Condition): loop-boundary wrap handling.
    /**
     * Loop-boundary counterexample. With a 10s clip, `before` is near the end (9_995 ms) while
     * `after` has just wrapped to the start (5 ms). Modulo the duration the two are only ~10 ms
     * apart, so the correct decision is a wrap-aware no-op. The UNFIXED code sees a ~9_990 ms
     * absolute delta and re-seeks the freshly-wrapped `after` player back to 9_995 ms, treating a
     * wrap as real drift — so this FAILS.
     *
     * **Validates: Requirements 1.4**
     */
    @Test
    fun loopBoundaryWrapIsNotTreatedAsDrift() {
        val durationMs = 10_000L
        val beforePosMs = 9_995L // near the end
        val afterPosMs = 5L      // just wrapped to ~0 on loop

        val action = SyncController.decideDrift(
            bothPlaying = true,
            beforePosMs = beforePosMs,
            afterPosMs = afterPosMs,
        )

        // True divergence across the loop boundary: min(|a-b|, duration - |a-b|).
        val rawDelta = abs(beforePosMs - afterPosMs)
        val wrapAwareDivergence = min(rawDelta, durationMs - rawDelta)
        assertTrue(
            wrapAwareDivergence <= SyncController.DRIFT_TOLERANCE_MS,
            "sanity: the two are within tolerance modulo duration (=$wrapAwareDivergence ms)",
        )

        // Since they are already aligned modulo the loop, the fixed decision must not force a full
        // re-seek back to the end. On the unfixed code this returns SeekAfter(9995) -> FAILS.
        assertTrue(
            action == SyncAction.None,
            "loop wrap must not be treated as real drift: decideDrift(true, $beforePosMs, " +
                "$afterPosMs) = $action, but the two are only $wrapAwareDivergence ms apart " +
                "modulo the ${durationMs}ms duration",
        )
    }

    /**
     * `(beforePosMs, afterPosMs)` pairs over a realistic clip range whose absolute divergence is
     * always strictly beyond the drift tolerance, in both orderings, so every generated case is a
     * genuine drift that the fixed decision must correct.
     */
    @Provide
    fun pausedDivergedPairs(): Arbitrary<PositionPair> {
        val basePositions: Arbitrary<Long> = Arbitraries.longs().between(0L, 3_600_000L)
        // Magnitude strictly beyond the 100ms tolerance (101ms .. 60s), in both directions.
        val magnitudes: Arbitrary<Long> =
            Arbitraries.longs().between(SyncController.DRIFT_TOLERANCE_MS + 1L, 60_000L)
        val signs: Arbitrary<Boolean> = Arbitraries.of(true, false)

        return Combinators.combine(basePositions, magnitudes, signs)
            .`as` { beforePosMs, magnitude, afterLeads ->
                val afterPosMs = if (afterLeads) {
                    beforePosMs + magnitude
                } else {
                    (beforePosMs - magnitude).coerceAtLeast(0L)
                }
                // Guarantee the divergence is genuinely beyond tolerance even after coercion.
                if (abs(beforePosMs - afterPosMs) <= SyncController.DRIFT_TOLERANCE_MS) {
                    PositionPair(beforePosMs, beforePosMs + magnitude)
                } else {
                    PositionPair(beforePosMs, afterPosMs)
                }
            }
    }

    data class PositionPair(
        val beforePosMs: Long,
        val afterPosMs: Long,
    )
}
