package com.shanks.minify.ui.compare

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.math.abs

/**
 * Property-based test for the drift-correction decision that keeps the two comparison players
 * within [SyncController.DRIFT_TOLERANCE_MS] of each other.
 *
 * Feature: editor-compare-slider-fixes, Req 2.4 / Property 3 — "Videos stay synchronized across
 * play, pause, seek, scrub, and loop." Under the new contract [SyncController.decideDrift] corrects
 * drift **regardless of whether both players are actively playing**, so pause/seek/scrub divergence
 * is corrected as well as playback. This SUPERSEDES the old media-editor-ux-fixes Req 7.3 behavior,
 * which gated correction on `bothPlaying` and returned [SyncAction.None] whenever the players were
 * not both playing; that both-playing gate no longer holds.
 *
 * [SyncController.decideDrift] is the pure core of the ~50ms polling loop. Given whether both
 * players are playing and their current positions, it must:
 *
 *  - return [SyncAction.None] when the two positions are within [SyncController.DRIFT_TOLERANCE_MS]
 *    of each other,
 *  - return [SyncAction.None] when both players are playing AND the divergence is at least
 *    [SyncController.LOOP_WRAP_MIN_DELTA_MS] (a loop-boundary wrap, not real drift), and
 *  - otherwise direct a re-seek of the *lagging* player (the one further behind) to the *leader's*
 *    (further-ahead) position, so the two are brought back into alignment — whether or not both are
 *    playing.
 *
 * The generator deliberately spans small deltas (below the tolerance), mid-range deltas (beyond the
 * tolerance but below the loop-wrap threshold), and large deltas (at/above the loop-wrap threshold),
 * in both orderings and with `bothPlaying` both true and false, so every branch is exercised across
 * the iterations.
 */
class DriftCorrectionPropertyTest {

    /**
     * **Validates: Requirements 2.4**
     */
    @Property(tries = 500)
    fun driftCorrectionReseeksTheLaggingPlayer(
        @ForAll("scenarios") scenario: DriftScenario,
    ) {
        val action = SyncController.decideDrift(
            bothPlaying = scenario.bothPlaying,
            beforePosMs = scenario.beforePosMs,
            afterPosMs = scenario.afterPosMs,
        )

        val divergence = abs(scenario.beforePosMs - scenario.afterPosMs)
        val withinTolerance = divergence <= SyncController.DRIFT_TOLERANCE_MS
        val isLoopWrap = scenario.bothPlaying && divergence >= SyncController.LOOP_WRAP_MIN_DELTA_MS

        if (withinTolerance) {
            // Within tolerance: aligned, no correction regardless of playing state (Req 2.4).
            assertEquals(
                SyncAction.None,
                action,
                "expected no correction for bothPlaying=${scenario.bothPlaying}, " +
                    "before=${scenario.beforePosMs}, after=${scenario.afterPosMs} " +
                    "(divergence=$divergence <= ${SyncController.DRIFT_TOLERANCE_MS})",
            )
            return
        }

        if (isLoopWrap) {
            // A near-`duration` divergence while both players are playing is a loop-boundary wrap,
            // not real drift, so the players are left to realign (Req 2.3).
            assertEquals(
                SyncAction.None,
                action,
                "expected no correction for a loop-wrap divergence (bothPlaying=true, " +
                    "before=${scenario.beforePosMs}, after=${scenario.afterPosMs}, " +
                    "divergence=$divergence >= ${SyncController.LOOP_WRAP_MIN_DELTA_MS})",
            )
            return
        }

        // Beyond tolerance and not a loop wrap: the lagging player is re-seeked to the leader,
        // whether or not both are playing (Req 2.4 — supersedes the old both-playing gate).
        val leaderPos = maxOf(scenario.beforePosMs, scenario.afterPosMs)
        if (scenario.beforePosMs > scenario.afterPosMs) {
            // "after" lags -> seek it up to "before" (the leader).
            assertEquals(
                SyncAction.SeekAfter(leaderPos),
                action,
                "before (${scenario.beforePosMs}) leads after (${scenario.afterPosMs}); " +
                    "the lagging 'after' player must be re-seeked to the leader position " +
                    "(bothPlaying=${scenario.bothPlaying})",
            )
        } else {
            // "before" lags -> seek it up to "after" (the leader).
            assertEquals(
                SyncAction.SeekBefore(leaderPos),
                action,
                "after (${scenario.afterPosMs}) leads before (${scenario.beforePosMs}); " +
                    "the lagging 'before' player must be re-seeked to the leader position " +
                    "(bothPlaying=${scenario.bothPlaying})",
            )
        }

        // Whichever correction is chosen, it targets the leader's position, so applying it brings
        // the two back to exact alignment (0ms divergence, well within tolerance).
        val target = when (action) {
            is SyncAction.SeekAfter -> action.toMs
            is SyncAction.SeekBefore -> action.toMs
            SyncAction.None -> error("expected a correction beyond tolerance")
        }
        assertEquals(
            leaderPos,
            target,
            "the re-seek must target the leader (further-ahead) position",
        )
        assertTrue(
            target >= minOf(scenario.beforePosMs, scenario.afterPosMs),
            "the re-seek target must not move a player behind the lagging position",
        )
    }

    /**
     * A [bothPlaying] flag plus two positions. Positions are drawn over a realistic clip range and
     * combined with a signed delta that spans below the 100ms tolerance, the mid-range re-seek band,
     * and the loop-wrap band (>= [SyncController.LOOP_WRAP_MIN_DELTA_MS]), in both orderings, so the
     * None / SeekAfter / SeekBefore / loop-wrap branches are all hit for both playing states.
     */
    @Provide
    fun scenarios(): Arbitrary<DriftScenario> {
        val playing: Arbitrary<Boolean> = Arbitraries.of(true, false)
        val basePositions: Arbitrary<Long> = Arbitraries.longs().between(0L, 3_600_000L)
        // Signed delta from the base: |delta| up to ~5s, spanning inside the 100ms tolerance, the
        // mid-range re-seek band, and beyond the ~3s loop-wrap threshold, in both directions.
        val deltas: Arbitrary<Long> = Arbitraries.longs().between(-5_000L, 5_000L)

        return Combinators.combine(playing, basePositions, deltas)
            .`as` { bothPlaying, beforePosMs, delta ->
                val afterPosMs = (beforePosMs + delta).coerceAtLeast(0L)
                DriftScenario(bothPlaying, beforePosMs, afterPosMs)
            }
    }

    data class DriftScenario(
        val bothPlaying: Boolean,
        val beforePosMs: Long,
        val afterPosMs: Long,
    )
}
