package com.shanks.minify.ui.editor.model

/**
 * A pure, Android-independent undo/redo history modeled as a "zipper" over a
 * timeline of immutable states of type [T].
 *
 * The current state is [present]. Everything strictly before it (oldest first)
 * lives in [past]; everything that was undone and can be redone (nearest first)
 * lives in [future]. Because every state is retained rather than mutated in
 * place, the history is fully non-destructive: applying an edit never alters a
 * previously recorded state (Req 2.2).
 *
 * ```
 * past = [s0, s1, s2]   present = s3   future = [s4, s5]
 *          older -> ->                   next-to-redo -> ->
 * ```
 *
 * Semantics:
 * - [push] records a brand-new edit: it moves [present] into [past] and clears
 *   the redo [future], so a fresh edit after an undo discards the previously
 *   undone states (Req 6.5).
 * - [undo] steps one state back: [present] is prepended to [future] and the last
 *   element of [past] becomes the new [present]. It is a no-op when [canUndo] is
 *   false (Req 6.2, 6.6).
 * - [redo] steps one state forward: the first element of [future] becomes the new
 *   [present] and the previous [present] is appended to [past]. It is a no-op
 *   when [canRedo] is false (Req 6.4).
 * - [canUndo] is false exactly at the initial state (empty [past]) and true after
 *   at least one recorded edit (Req 6.1, 6.6); [canRedo] is true exactly while at
 *   least one undo has been performed with no intervening [push] (Req 6.3).
 *
 * This carries all the undo/redo logic for the unified editor so it can be
 * property-tested on the JVM without any Android or view-model dependency.
 */
data class EditHistory<T>(
    /** States strictly before [present], oldest first. */
    val past: List<T>,
    /** The current state. */
    val present: T,
    /** Undone states available to redo, nearest (most recently undone) first. */
    val future: List<T>,
) {
    /** True when there is at least one recorded state to revert to (Req 6.1, 6.6). */
    val canUndo: Boolean get() = past.isNotEmpty()

    /** True when there is at least one undone state to reapply (Req 6.3). */
    val canRedo: Boolean get() = future.isNotEmpty()

    /**
     * Record a new edit. Moves [present] into [past] and sets [next] as the new
     * [present], clearing the redo [future] (Req 6.5).
     */
    fun push(next: T): EditHistory<T> =
        EditHistory(
            past = past + present,
            present = next,
            future = emptyList(),
        )

    /**
     * Revert to the immediately previous recorded state. Prepends [present] to
     * [future] and pops the last element of [past] into [present]. Returns this
     * unchanged when [canUndo] is false (Req 6.2, 6.6).
     */
    fun undo(): EditHistory<T> {
        if (!canUndo) return this
        return EditHistory(
            past = past.dropLast(1),
            present = past.last(),
            future = listOf(present) + future,
        )
    }

    /**
     * Reapply the most recently undone state. Appends [present] to [past] and
     * pops the first element of [future] into [present]. Returns this unchanged
     * when [canRedo] is false (Req 6.4).
     */
    fun redo(): EditHistory<T> {
        if (!canRedo) return this
        return EditHistory(
            past = past + present,
            present = future.first(),
            future = future.drop(1),
        )
    }

    companion object {
        /**
         * A fresh history at its initial state: no [past] (so [canUndo] is false)
         * and no [future] (so [canRedo] is false), with [initial] as the
         * [present].
         */
        fun <T> of(initial: T): EditHistory<T> =
            EditHistory(past = emptyList(), present = initial, future = emptyList())
    }
}

/**
 * Record a new edit like [EditHistory.push] (moves [EditHistory.present] into
 * [EditHistory.past] and clears the redo [EditHistory.future]), but cap the
 * retained undo depth: after pushing, the oldest [EditHistory.past] entries are
 * dropped from the front so that `past.size <= maxEntries` (Req 1.5).
 *
 * [maxEntries] is coerced to at least `1`, so at least one prior state is always
 * retained. This keeps the in-memory history — and therefore the payload
 * persisted to `SavedStateHandle` — bounded regardless of how many edits occur.
 *
 * Pure and Android-independent, so it can be property-tested on the JVM.
 */
fun <T> EditHistory<T>.pushBounded(next: T, maxEntries: Int): EditHistory<T> {
    val cap = maxEntries.coerceAtLeast(1)
    val pushed = past + present
    val trimmed = if (pushed.size > cap) pushed.subList(pushed.size - cap, pushed.size) else pushed
    return EditHistory(
        past = trimmed.toList(),
        present = next,
        future = emptyList(),
    )
}
