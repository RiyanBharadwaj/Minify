package com.shanks.minify.ui.editor.model

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for [EditHistory], the pure undo/redo "zipper" behind the
 * Unified_Media_Editor's history (Req 2.2, 6.1-6.6).
 *
 * Feature: unified-media-editor, Property 3: Undo/redo behaves as a non-destructive history zipper
 *
 * The strategy runs an arbitrary sequence of operations ([Op.Push]/[Op.Undo]/[Op.Redo]) against
 * both the real [EditHistory] and a simple, obviously-correct oracle model: a chronological
 * timeline of states plus a cursor marking the [EditHistory.present]. Everything before the cursor
 * is [EditHistory.past]; everything after it is [EditHistory.future]. At every step the real
 * history must match the oracle, which pins down all of the zipper's semantics at once.
 */
class EditHistoryPropertyTest {

    /** One operation applied to a history of [Int] states. */
    sealed interface Op {
        data class Push(val value: Int) : Op
        data object Undo : Op
        data object Redo : Op
    }

    /**
     * The oracle: an immutable timeline of states in chronological order together with a [cursor]
     * pointing at the current [present]. This is a deliberately naive, easy-to-audit model of the
     * zipper against which the real [EditHistory] is checked.
     */
    private data class Oracle(val timeline: List<Int>, val cursor: Int) {
        val present: Int get() = timeline[cursor]
        val past: List<Int> get() = timeline.subList(0, cursor)
        val future: List<Int> get() = timeline.subList(cursor + 1, timeline.size)
        val canUndo: Boolean get() = cursor > 0
        val canRedo: Boolean get() = cursor < timeline.size - 1

        /** A new edit truncates any redo future, appends [value], and advances the cursor to it. */
        fun push(value: Int): Oracle =
            Oracle(timeline.subList(0, cursor + 1) + value, cursor + 1)

        /** Step back one state when possible; otherwise a no-op. */
        fun undo(): Oracle = if (canUndo) copy(cursor = cursor - 1) else this

        /** Step forward one state when possible; otherwise a no-op. */
        fun redo(): Oracle = if (canRedo) copy(cursor = cursor + 1) else this
    }

    // Feature: unified-media-editor, Property 3: Undo/redo behaves as a non-destructive history zipper
    /**
     * For any initial state and any sequence of push/undo/redo operations, the real [EditHistory]
     * stays in lock-step with the oracle: identical `past`, `present`, `future`, `canUndo`, and
     * `canRedo` after every operation. This simultaneously establishes that:
     * pushing retains the previous state in `past` (non-destructive, never mutating a prior state);
     * `undo` restores the immediately previous state and `redo` reapplies the most recently undone
     * one; pushing after an undo clears the redo `future`; `canUndo` is false exactly at the initial
     * state and true after at least one edit; and `canRedo` is true exactly after an undo with no
     * intervening push. The test also spot-checks the initial invariant, the future-clearing on
     * push, the non-destructive retention, and the undo/redo round-trip inline.
     *
     * **Validates: Requirements 2.2, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6**
     */
    @Property(tries = 300)
    fun undoRedoBehavesAsANonDestructiveHistoryZipper(
        @ForAll("initials") initial: Int,
        @ForAll("opSequences") ops: List<Op>,
    ) {
        var history = EditHistory.of(initial)
        var oracle = Oracle(listOf(initial), cursor = 0)

        // Initial invariant: canUndo is false exactly at the fresh state (Req 6.1, 6.6); no redo yet.
        assertFalse(history.canUndo, "a fresh history must not allow undo")
        assertFalse(history.canRedo, "a fresh history must not allow redo")
        assertMatches(oracle, history)

        for (op in ops) {
            val previousPresent = history.present
            val hadUndoBefore = history.canUndo

            history = when (op) {
                is Op.Push -> history.push(op.value)
                Op.Undo -> history.undo()
                Op.Redo -> history.redo()
            }
            oracle = when (op) {
                is Op.Push -> oracle.push(op.value)
                Op.Undo -> oracle.undo()
                Op.Redo -> oracle.redo()
            }

            // The real history must always agree with the oracle model.
            assertMatches(oracle, history)

            when (op) {
                is Op.Push -> {
                    // Pushing records a brand-new edit: the redo future is cleared (Req 6.5) ...
                    assertTrue(history.future.isEmpty(), "push must clear the redo future")
                    // ... the new value becomes present, and the previous present is retained
                    // untouched as the last past entry (non-destructive, Req 2.2, 6.6).
                    assertEquals(op.value, history.present, "push must set the new value as present")
                    assertTrue(history.canUndo, "after a push there is always something to undo")
                    assertEquals(
                        previousPresent,
                        history.past.last(),
                        "push must retain the previous present as the newest past state",
                    )
                    // Undo immediately after a push round-trips back to the previous present.
                    assertEquals(
                        previousPresent,
                        history.undo().present,
                        "undo right after a push must restore the previous present",
                    )
                }

                Op.Undo -> if (hadUndoBefore) {
                    // undo then redo is a round-trip back to the state we left (Req 6.2, 6.4).
                    assertEquals(
                        previousPresent,
                        history.redo().present,
                        "redo must reapply the state that undo just reverted",
                    )
                    // After a real undo with no intervening push, redo is available (Req 6.3).
                    assertTrue(history.canRedo, "canRedo must be true after an undo")
                }

                Op.Redo -> { /* covered by the oracle equivalence check above */ }
            }
        }
    }

    /** Assert every observable facet of the real [EditHistory] matches the [Oracle]. */
    private fun assertMatches(oracle: Oracle, history: EditHistory<Int>) {
        assertEquals(oracle.present, history.present, "present mismatch")
        assertEquals(oracle.past, history.past, "past mismatch")
        assertEquals(oracle.future, history.future, "future mismatch")
        assertEquals(oracle.canUndo, history.canUndo, "canUndo mismatch")
        assertEquals(oracle.canRedo, history.canRedo, "canRedo mismatch")
    }

    @Provide
    fun initials(): Arbitrary<Int> = Arbitraries.integers().between(-1_000, 1_000)

    /**
     * Sequences of operations. Push values are drawn from a distinct high range so a pushed value is
     * easy to distinguish from initials in failure output; undo and redo are emitted freely so the
     * sequence exercises boundary no-ops as well as deep undo/redo chains.
     */
    @Provide
    fun opSequences(): Arbitrary<List<Op>> {
        val pushes: Arbitrary<Op> =
            Arbitraries.integers().between(10_000, 20_000).map { Op.Push(it) }
        val undos: Arbitrary<Op> = Arbitraries.just(Op.Undo)
        val redos: Arbitrary<Op> = Arbitraries.just(Op.Redo)
        // Bias slightly toward pushes so histories grow, then get undone/redone.
        val op: Arbitrary<Op> = Arbitraries.frequencyOf(
            Tuple.of(2, pushes),
            Tuple.of(1, undos),
            Tuple.of(1, redos),
        )
        return op.list().ofMaxSize(40)
    }
}
