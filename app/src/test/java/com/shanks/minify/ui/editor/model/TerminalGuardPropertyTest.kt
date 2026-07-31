package com.shanks.minify.ui.editor.model

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based test for [TerminalGuard], the single-terminal-outcome guard used by
 * `VideoCompressor` (Req 17.3).
 *
 * A compression export has several independent paths that may each try to report a
 * terminal result — the stall-timeout poller, the `Transformer.Listener` callbacks
 * (`onCompleted`/`onError`), and the CBR-retry branch. Exactly one of those signals
 * must be accepted and every later one rejected, no matter what order they arrive in
 * (including concurrently). [TerminalGuard.tryClaim] is the pure core of that rule,
 * so these properties exercise it across many competing-claim scenarios.
 */
class TerminalGuardPropertyTest {

    // Feature: media-editor-fixes, Property 17: Exactly one terminal outcome is accepted
    /**
     * Property 17: Exactly one terminal outcome is accepted.
     *
     * For any number N (>= 1) of competing claim attempts issued in sequence (any
     * order — the guard is order-agnostic), exactly one `tryClaim()` returns `true`
     * and all others return `false`. After any claim, `isClaimed` is `true`.
     *
     * **Validates: Requirements 17.3**
     */
    @Property(tries = 200)
    fun exactlyOneSequentialClaimIsAccepted(
        @ForAll @IntRange(min = 1, max = 500) attempts: Int,
    ) {
        val guard = TerminalGuard()

        // Before any attempt, nothing has been claimed.
        assertFalse(guard.isClaimed, "guard must start unclaimed")

        var accepted = 0
        repeat(attempts) {
            if (guard.tryClaim()) accepted++
            // Once any claim has happened, isClaimed must stay true forever.
            assertTrue(guard.isClaimed, "isClaimed must be true after any claim attempt")
        }

        assertEquals(
            1,
            accepted,
            "exactly one of $attempts competing claims must be accepted",
        )
    }

    // Feature: media-editor-fixes, Property 17: Exactly one terminal outcome is accepted
    /**
     * Property 17 (concurrent case): Exactly one terminal outcome is accepted even when
     * every competing signal races on its own thread.
     *
     * Launches N threads that each call `tryClaim()` at (as close as possible to) the
     * same instant via a shared start latch, mirroring the poller/listener/retry paths
     * racing to report a terminal outcome. Exactly one must win; all others must lose.
     *
     * **Validates: Requirements 17.3**
     */
    @Property(tries = 100)
    fun exactlyOneConcurrentClaimIsAccepted(
        @ForAll("threadCounts") threads: Int,
    ) {
        val guard = TerminalGuard()
        val accepted = AtomicInteger(0)
        val startGate = CountDownLatch(1)
        val ready = CountDownLatch(threads)
        val done = CountDownLatch(threads)

        val workers = (0 until threads).map {
            Thread {
                ready.countDown()
                // All threads block here, then are released together to maximize contention.
                startGate.await()
                if (guard.tryClaim()) accepted.incrementAndGet()
                done.countDown()
            }.apply { start() }
        }

        ready.await()
        startGate.countDown()
        done.await()
        workers.forEach { it.join() }

        assertEquals(
            1,
            accepted.get(),
            "exactly one of $threads concurrent claims must be accepted",
        )
        assertTrue(guard.isClaimed, "isClaimed must be true after concurrent claims")
        // Any further claim after the race is still rejected.
        assertFalse(guard.tryClaim(), "a claim after the winner must be rejected")
    }

    @Provide
    fun threadCounts(): Arbitrary<Int> = Arbitraries.integers().between(2, 32)
}
