package com.shanks.minify.ui.editor.model

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A minimal, Android-independent single-claim guard (Req 17.3).
 *
 * Wraps an [AtomicBoolean] to provide a "first caller wins" latch: the very first
 * invocation of [tryClaim] returns `true`, and every subsequent invocation returns
 * `false`, no matter how many callers race concurrently. This makes it safe to use
 * as a single-terminal-outcome guard where several independent paths (e.g. a
 * stall-timeout poller, a `Transformer.Listener` callback, or a retry request) may
 * each attempt to report a terminal result, but exactly one must be accepted.
 *
 * The class is deliberately tiny and pure so it can be exercised by a JVM property
 * test without any Android dependencies.
 */
class TerminalGuard {
    private val claimed = AtomicBoolean(false)

    /**
     * Attempts to claim the single terminal outcome.
     *
     * Returns `true` exactly once — for the first caller to win the race — and
     * `false` for every caller thereafter. This is a compare-and-set on the
     * underlying [AtomicBoolean], so it is safe under concurrent access.
     */
    fun tryClaim(): Boolean = claimed.compareAndSet(false, true)

    /** True once any caller has successfully claimed via [tryClaim]. */
    val isClaimed: Boolean
        get() = claimed.get()
}
