package com.shanks.minify.ui.editor.model

import androidx.compose.runtime.saveable.SaverScope
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based test for [EditHistory.pushBounded] and the bounded payload it
 * enables the Editor_ViewModel to persist to `SavedStateHandle`.
 *
 * The Editor_ViewModel caps its retained undo history to a maximum number of
 * entries (Req 1.5) so that the serialized payload written to `SavedStateHandle`
 * stays within a bounded size and cannot raise a `TransactionTooLargeException`
 * (Req 1.6). Both guarantees follow from the pure [EditHistory.pushBounded]
 * front-trim: no matter how many edits occur, `past` never grows past the cap,
 * and therefore the set of states serialized for persistence
 * (`past + present + future`) never exceeds `maxEntries + 1` entries.
 *
 * The payload here is modeled exactly as the Editor_ViewModel would build it: the
 * retained history states serialized field-by-field through
 * [MediaEditState.Saver]. The property asserts the *entry count* of that payload
 * is bounded, which is the decidable core of Req 1.6.
 */
class EditHistoryBoundedPropertyTest {

    /** A permissive [SaverScope]: everything is considered "can-be-saved". */
    private val saverScope = SaverScope { true }

    // Feature: media-editor-fixes, Property 2: Edit history and its persisted payload are bounded
    /**
     * Property 2: Edit history and its persisted payload are bounded.
     *
     * For any initial history, any list of pushed states, and any maximum entry
     * count (>= 1): the resulting history's `past` size never exceeds the
     * maximum, the present equals the most recently pushed state (or the initial
     * state when nothing was pushed), and the entry count of the payload built
     * for `SavedStateHandle` never exceeds the configured bound — independent of
     * how many edits occurred.
     *
     * **Validates: Requirements 1.5, 1.6**
     */
    @Property(tries = 300)
    fun editHistoryAndPersistedPayloadAreBounded(
        @ForAll("scenarios") scenario: Scenario,
    ) {
        val maxEntries = scenario.maxEntries
        val initial = state(scenario.initialTag)
        val pushed = scenario.pushTags.map { state(it) }

        // Build the history by pushing every state through the bounded push.
        var history = EditHistory.of(initial)
        for (next in pushed) {
            history = history.pushBounded(next, maxEntries)
        }

        // Req 1.5: the retained undo depth (`past`) never exceeds the cap, no
        // matter how many edits occurred. pushBounded also always clears the redo
        // `future`, so it stays empty.
        assertTrue(
            history.past.size <= maxEntries,
            "past.size=${history.past.size} must not exceed maxEntries=$maxEntries " +
                "after ${pushed.size} pushes",
        )
        assertTrue(
            history.future.isEmpty(),
            "pushBounded must always clear the redo future",
        )

        // The present equals the most recently pushed state (or the initial state
        // when nothing was pushed).
        val expectedPresent = pushed.lastOrNull() ?: initial
        assertEquals(
            expectedPresent,
            history.present,
            "present must equal the most recently pushed state",
        )

        // Req 1.6: the payload persisted to SavedStateHandle serializes exactly
        // the retained states (past + present + future). Because the history is
        // bounded, the payload's entry count never exceeds maxEntries + 1
        // (the capped `past` plus the single `present`), regardless of how many
        // edits happened.
        val payload = buildPayload(history)
        assertEquals(
            history.past.size + 1 + history.future.size,
            payload.size,
            "payload must serialize one entry per retained state",
        )
        assertTrue(
            payload.size <= maxEntries + 1,
            "payload entry count=${payload.size} must not exceed the bound " +
                "maxEntries+1=${maxEntries + 1}",
        )
    }

    /**
     * Builds the persistence payload the Editor_ViewModel writes to
     * `SavedStateHandle`: one bundle-safe serialized entry per retained history
     * state, in timeline order (`past` oldest-first, then `present`, then
     * `future`).
     */
    private fun buildPayload(history: EditHistory<MediaEditState>): List<Any?> {
        val states = history.past + history.present + history.future
        return states.map { with(MediaEditState.Saver) { saverScope.save(it) } }
    }

    /**
     * A distinguishable [MediaEditState] keyed by [tag]: a photo edit state whose
     * vignette carries the tag so that two states are equal iff their tags match.
     */
    private fun state(tag: Float): MediaEditState {
        val base = MediaEditState.initial(MediaType.PHOTO)
        return base.copy(color = base.color.copy(vignette = tag))
    }

    @Provide
    fun scenarios(): Arbitrary<Scenario> {
        // At least 1 (pushBounded coerces to 1, but generate the valid domain).
        val maxEntries: Arbitrary<Int> = Arbitraries.integers().between(1, 12)
        // Distinct tags in [0, 1] so pushed states are distinguishable.
        val tags: Arbitrary<Float> = Arbitraries.floats().between(0f, 1f).ofScale(4)
        val initialTag: Arbitrary<Float> = tags
        // Lists long enough to far exceed the cap, proving the bound holds.
        val pushTags: Arbitrary<List<Float>> = tags.list().ofMinSize(0).ofMaxSize(80)
        return Combinators.combine(maxEntries, initialTag, pushTags)
            .`as` { max, initial, pushes -> Scenario(max, initial, pushes) }
    }

    data class Scenario(
        val maxEntries: Int,
        val initialTag: Float,
        val pushTags: List<Float>,
    )
}
