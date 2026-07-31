package com.shanks.minify.editor

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based test for the video editor host's fresh-launch-per-session guard.
 *
 * // Feature: video-editor-fixes, Property 7: Each new editing session launches fresh
 *
 * **Validates: Requirements 4.6**
 *
 * ## What this exercises
 * [VideoEditorHost] launches LibreCuts exactly once per editing session. The real
 * guard is Compose runtime state (`rememberSaveable` + `LaunchedEffect(sessionKey)`),
 * which is not directly unit-testable on the pure JVM. Its DECISION LOGIC is
 * modeled by [SessionLaunchGuard.shouldLaunch] — a token that differs from the
 * last-launched token triggers exactly one launch; re-evaluating the same token
 * (a recomposition within the session) does not relaunch or re-deliver.
 *
 * These properties drive that pure predicate over generated token streams,
 * folding a `lastLaunchedToken` exactly as the host does, to assert:
 *  a. exactly one launch per DISTINCT new token (recompositions do not relaunch),
 *  b. two consecutive DIFFERENT tokens on the SAME source URI both relaunch,
 *  c. no re-delivery: once a token launches, feeding it again yields no launch.
 */
class FreshLaunchPerSessionPropertyTest {

    /** Result of folding a token stream through the guard. */
    private data class Fold(val launches: Int, val lastLaunchedToken: Int?)

    /**
     * Simulate the host processing a stream of session tokens (each element is a
     * recomposition/effect evaluation). Mirrors `VideoEditorHost`:
     * `if (shouldLaunch(token, last)) { last = token; launch() }`.
     */
    private fun simulate(tokenStream: List<Int>): Fold {
        var last: Int? = null
        var launches = 0
        for (token in tokenStream) {
            if (SessionLaunchGuard.shouldLaunch(token, last)) {
                last = token
                launches++
            }
        }
        return Fold(launches, last)
    }

    /**
     * (a) + (c): For a monotonically non-decreasing token stream (increasing new
     * sessions, with repeats modeling recompositions within the current session),
     * the launch count equals the number of times the current token CHANGED from
     * the previous element — i.e. exactly one launch per distinct run of a token,
     * and no relaunch/re-delivery while the token holds steady.
     */
    @Property(tries = 200)
    fun exactlyOneLaunchPerDistinctTokenNoRedelivery(
        @ForAll("monotonicTokenStreams") stream: List<Int>,
    ) {
        val fold = simulate(stream)

        // Expected launches: first element launches, then every position where the
        // token differs from its predecessor (a new session). Repeats do not launch.
        val expectedLaunches = if (stream.isEmpty()) {
            0
        } else {
            1 + stream.zipWithNext().count { (prev, next) -> prev != next }
        }

        assertEquals(
            expectedLaunches,
            fold.launches,
            "exactly one launch per distinct new token; recompositions must not relaunch",
        )

        // No re-delivery: replaying the final token again performs no further launch.
        if (stream.isNotEmpty()) {
            val lastToken = stream.last()
            assertFalse(
                SessionLaunchGuard.shouldLaunch(lastToken, fold.lastLaunchedToken),
                "a token that already launched must not launch again (no re-delivery)",
            )
        }
    }

    /**
     * (a) refined: the number of launches equals the number of DISTINCT-consecutive
     * tokens (length of the run-length-encoded stream), for any token stream.
     */
    @Property(tries = 200)
    fun launchCountEqualsNumberOfConsecutiveDistinctTokens(
        @ForAll("anyTokenStreams") stream: List<Int>,
    ) {
        val fold = simulate(stream)

        val distinctRuns = stream.fold(0 to (null as Int?)) { (count, prev), token ->
            if (token != prev) (count + 1) to token else count to prev
        }.first

        assertEquals(
            distinctRuns,
            fold.launches,
            "launch count must equal the number of consecutive-distinct token runs",
        )
    }

    /**
     * (b): Two consecutive DIFFERENT session tokens mapped to the SAME source URI
     * both relaunch — modeling a user editing the identical source twice in a row.
     * The launch count for the two-session stream is exactly 2.
     */
    @Property(tries = 200)
    fun twoConsecutiveSessionsOnSameSourceBothRelaunch(
        @ForAll("sourceUris") @Suppress("UNUSED_PARAMETER") sourceUri: String,
        @ForAll firstToken: Int,
        @ForAll @IntRange(min = 1, max = 1_000_000) delta: Int,
    ) {
        // Same source for both sessions; distinct monotonically increasing tokens.
        val secondToken = firstToken + delta // != firstToken since delta >= 1

        // Each session may include recomposition repeats of its own token.
        val stream = listOf(firstToken, firstToken, secondToken, secondToken, secondToken)

        val fold = simulate(stream)

        assertTrue(secondToken != firstToken, "guard property assumes distinct tokens")
        assertEquals(
            2,
            fold.launches,
            "two consecutive distinct-token sessions on the same source must both relaunch",
        )
    }

    // --- Generators -------------------------------------------------------

    /**
     * Monotonically non-decreasing token streams: models a real sequence of
     * editing sessions where the token only ever moves forward, with repeats
     * standing in for recompositions/effect re-evaluations within a session.
     */
    @Provide
    fun monotonicTokenStreams(): Arbitrary<List<Int>> =
        Arbitraries.integers().between(0, 5) // per-step increment (0 == recomposition)
            .list().ofMinSize(0).ofMaxSize(30)
            .map { increments ->
                var running = 0
                increments.map { step -> running += step; running }
            }

    /** Arbitrary token streams (not necessarily monotonic) for the RLE property. */
    @Provide
    fun anyTokenStreams(): Arbitrary<List<Int>> =
        Arbitraries.integers().between(0, 8)
            .list().ofMinSize(0).ofMaxSize(30)

    /** Realistic source URI shapes; the source is held constant across a session pair. */
    @Provide
    fun sourceUris(): Arbitrary<String> = Arbitraries.of(
        "content://media/external/video/media/42",
        "file:///storage/emulated/0/DCIM/clip.mp4",
        "content://com.android.providers.media.documents/document/video%3A17",
    )
}
