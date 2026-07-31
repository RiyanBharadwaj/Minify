package com.shanks.minify.ui.editor.model

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [ExportToken.routes], the pure routing predicate behind the unified
 * editor's cancelable video export (Req 11.3, 11.4).
 *
 * Because `CompressionMonitor` is a global singleton, repeated exports can race and a superseded
 * export can still emit a completion/cancellation event. The screen remembers the token of the
 * export it triggered and routes an event only when its token matches, so stale events from
 * earlier exports are ignored.
 */
class ExportTokenRoutingPropertyTest {

    // Feature: media-editor-fixes, Property 10: Export completion routes only to the matching request token
    /**
     * Feature: media-editor-fixes, Property 10: Export completion routes only to the matching
     * request token.
     *
     * For any sequence of export tokens and any remembered token, a completion or cancellation
     * event is routed to the result handler if and only if its token equals the remembered token;
     * events carrying a stale (non-matching) token are ignored.
     *
     * **Validates: Requirements 11.3, 11.4**
     */
    @Property(tries = 300)
    fun completionRoutesOnlyToMatchingToken(
        @ForAll("tokens") eventTokens: List<Long>,
        @ForAll("token") rememberedToken: Long,
    ) {
        // Route each event in the sequence: exactly the events whose token equals the remembered
        // token are delivered; every other (stale) event is ignored.
        val routed = eventTokens.filter { ExportToken.routes(it, rememberedToken) }

        // The routing predicate is precisely token equality for every event.
        for (eventToken in eventTokens) {
            assertEquals(
                eventToken == rememberedToken,
                ExportToken.routes(eventToken, rememberedToken),
                "routes($eventToken, $rememberedToken) must equal ($eventToken == $rememberedToken)",
            )
        }

        // Every routed event carries exactly the remembered token (no stale event slips through).
        assertTrue(
            routed.all { it == rememberedToken },
            "only events with the remembered token $rememberedToken may be routed, got $routed",
        )

        // No event carrying a different token is ever routed.
        assertFalse(
            eventTokens.any { it != rememberedToken && ExportToken.routes(it, rememberedToken) },
            "a stale token must never be routed",
        )
    }

    // Feature: media-editor-fixes, Property 10: Export completion routes only to the matching request token
    /**
     * Feature: media-editor-fixes, Property 10 (reflexivity/symmetry of the predicate).
     *
     * The routing decision depends only on equality of the two tokens: an event is always routed
     * to its own token, and swapping the arguments never changes the decision.
     *
     * **Validates: Requirements 11.3, 11.4**
     */
    @Property(tries = 300)
    fun routingIsEqualityOfTokens(
        @ForAll("token") eventToken: Long,
        @ForAll("token") rememberedToken: Long,
    ) {
        // An event is always routed to a handler remembering its own token.
        assertTrue(
            ExportToken.routes(eventToken, eventToken),
            "an event must route to a handler remembering its own token",
        )

        // The decision is symmetric in its arguments.
        assertEquals(
            ExportToken.routes(eventToken, rememberedToken),
            ExportToken.routes(rememberedToken, eventToken),
            "routing decision must be symmetric",
        )
    }

    /**
     * Export tokens. `CompressionMonitor` increments its token on each `onStart`, so tokens are
     * non-negative and monotonically increasing in practice; the generator keeps the range small
     * so distinct and matching tokens are both exercised frequently.
     */
    @Provide
    fun token(): Arbitrary<Long> = Arbitraries.longs().between(0L, 20L)

    /**
     * Sequences of export tokens, modeling the stream of completion/cancellation events a handler
     * may observe (including stale events from superseded exports).
     */
    @Provide
    fun tokens(): Arbitrary<List<Long>> = token().list().ofMinSize(0).ofMaxSize(12)
}
