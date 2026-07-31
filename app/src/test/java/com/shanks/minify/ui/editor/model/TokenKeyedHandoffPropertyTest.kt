package com.shanks.minify.ui.editor.model

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

/**
 * Property-based test for [TokenKeyedHandoff], the token-keyed one-shot hand-off
 * used by `CompressionService` to carry non-Parcelable Media3 passes from the
 * caller to the service keyed by export token (Req 17.1).
 *
 * The hand-off must behave like a per-token mailbox: each token retrieves exactly
 * the value that was stashed under it (never another token's value), the
 * retrieval is one-shot (a second [TokenKeyedHandoff.takeAndRemove] returns
 * `null`), tokens that were never stashed return `null`, and the holder
 * self-cleans so no entries linger once every token has been taken.
 */
class TokenKeyedHandoffPropertyTest {

    // Feature: media-editor-fixes, Property 16: Token-keyed handoff returns exactly its own passes and self-cleans
    /**
     * Property 16: Token-keyed handoff returns exactly its own passes and
     * self-cleans.
     *
     * For any set of distinct tokens each mapped to a distinct value: after
     * putting them all, [TokenKeyedHandoff.takeAndRemove] returns exactly that
     * token's value; a second take of the same token returns `null` (one-shot,
     * self-cleaning); tokens that were never stashed return `null`; and after all
     * tokens have been taken `size == 0`. An interleaved put/take ordering
     * produces the same per-token results.
     *
     * **Validates: Requirements 17.1**
     */
    @Property(tries = 200)
    fun handoffReturnsOwnValueAndSelfCleans(
        @ForAll("scenarios") scenario: Scenario,
    ) {
        val entries = scenario.entries
        val handoff = TokenKeyedHandoff<String>()

        // --- Batch case: put everything first, then take. ---
        for ((token, value) in entries) {
            handoff.put(token, value)
        }
        assertEquals(entries.size, handoff.size, "every stashed token should be present")

        for ((token, value) in entries) {
            // Each token retrieves exactly its own value.
            assertEquals(value, handoff.takeAndRemove(token), "token $token must return its own value")
            // One-shot: a second take of the same token returns null (self-clean).
            assertNull(handoff.takeAndRemove(token), "second take of token $token must be null")
        }

        // A token that was never stashed returns null and does not affect state.
        assertNull(handoff.takeAndRemove(scenario.absentToken), "absent token must return null")

        // After removing all, nothing lingers.
        assertEquals(0, handoff.size, "holder must be empty after all tokens are taken")

        // --- Interleaved case: alternate put and take across the same entries. ---
        val interleaved = TokenKeyedHandoff<String>()
        for ((token, value) in entries) {
            interleaved.put(token, value)
            // Immediately take the value just stashed: it must be exactly this one.
            assertEquals(value, interleaved.takeAndRemove(token), "interleaved take of $token must return its own value")
            // And the holder is emptied again by the self-cleaning removal.
            assertEquals(0, interleaved.size, "interleaved holder must be empty after each take")
        }
    }

    @Provide
    fun scenarios(): Arbitrary<Scenario> {
        // Distinct tokens: a set of distinct longs. Combined with distinct values
        // by pairing so each token maps to a distinct value.
        val tokenSets: Arbitrary<Set<Long>> =
            Arbitraries.longs().between(0L, 10_000L).set().ofMinSize(0).ofMaxSize(30)
        return tokenSets.map { tokens ->
            val ordered = tokens.toList()
            // Distinct values: index-tagged strings guarantee uniqueness.
            val entries = ordered.mapIndexed { index, token -> token to "value-$index-$token" }
            // An absent token: pick one guaranteed to be outside the stashed set.
            val absent = (ordered.maxOrNull() ?: 0L) + 1L
            Scenario(entries, absent)
        }
    }

    data class Scenario(
        val entries: List<Pair<Long, String>>,
        val absentToken: Long,
    )
}
