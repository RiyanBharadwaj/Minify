package com.shanks.minify.ui.editor.model

import android.os.TransactionTooLargeException
import androidx.compose.runtime.saveable.SaverScope
import androidx.lifecycle.SavedStateHandle

/**
 * Persists a bounded [EditHistory] of [MediaEditState] into a [SavedStateHandle]
 * and never lets a `SavedStateHandle` transaction failure crash the editor
 * (Req 1.4, 1.6, 1.7).
 *
 * Two independent guards keep persistence safe:
 *
 * - **Bounded payload (Req 1.6):** [buildPayload] serializes at most
 *   [MAX_PERSISTED_ENTRIES] `past` and `future` states via [MediaEditState.Saver].
 *   Combined with the already-bounded in-memory history (Req 1.5) this keeps the
 *   written payload within a bounded upper size, so the common path stays under
 *   the binder transaction limit by construction.
 * - **Degrade ladder (Req 1.7):** the write is wrapped so that a
 *   [TransactionTooLargeException] — including when it is the cause of a
 *   [RuntimeException] raised for any reason such as system memory pressure —
 *   never propagates. On failure it steps down the ladder:
 *     1. persist the full bounded history,
 *     2. else persist only the `present` state (dropping `past`/`future`),
 *     3. else persist nothing,
 *   in every case leaving the caller's in-memory history untouched rather than
 *   crashing.
 *
 * The serialized shape is `arrayListOf(pastSaved, presentSaved, futureSaved)`,
 * matching what the ViewModel restores on recreation.
 */
object EditorPersistence {

    /**
     * The maximum number of `past` (and `future`) states written to the
     * [SavedStateHandle], applied independently of the in-memory history bound as
     * a second guard on payload size (Req 1.6).
     */
    const val MAX_PERSISTED_ENTRIES: Int = 50

    /**
     * Persist [history] into [handle] under [key], degrading gracefully on a
     * [TransactionTooLargeException] (Req 1.4, 1.6, 1.7). Never throws for a
     * transaction-too-large failure; the in-memory [history] is left intact.
     */
    fun persist(handle: SavedStateHandle, key: String, history: EditHistory<MediaEditState>) {
        persistTo(history) { payload -> handle[key] = payload }
    }

    /**
     * The degrade ladder, decoupled from [SavedStateHandle] via [write] so the
     * fallback behavior is unit-testable without an Android binder: full bounded
     * payload → `present`-only → nothing. A non-transaction failure is
     * re-thrown; a [TransactionTooLargeException] (directly or as a cause) steps
     * down the ladder.
     */
    internal fun persistTo(
        history: EditHistory<MediaEditState>,
        maxEntries: Int = MAX_PERSISTED_ENTRIES,
        write: (ArrayList<Any?>) -> Unit,
    ) {
        // Step 1: the full (bounded) history.
        if (tryWrite(write, buildPayload(history, maxEntries))) return
        // Step 2: only the present state — no undo/redo depth, a much smaller payload.
        if (tryWrite(write, buildPayload(EditHistory.of(history.present), maxEntries))) return
        // Step 3: persist nothing; keep the in-memory history intact rather than crash.
    }

    /**
     * Attempt one [write] of [payload]. Returns `true` on success; returns
     * `false` when the write fails with a [TransactionTooLargeException] (so the
     * caller can degrade); re-throws any other failure.
     */
    private fun tryWrite(write: (ArrayList<Any?>) -> Unit, payload: ArrayList<Any?>): Boolean =
        try {
            write(payload)
            true
        } catch (t: Throwable) {
            if (isTransactionTooLarge(t)) false else throw t
        }

    /** True when [t] is, or is caused (at any depth) by, a [TransactionTooLargeException]. */
    private fun isTransactionTooLarge(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is TransactionTooLargeException) return true
            if (cause.cause === cause) break
            cause = cause.cause
        }
        return false
    }

    /**
     * Serialize [history] into the bundle-safe payload, capping the number of
     * persisted `past` and `future` entries at [maxEntries] (coerced to at least
     * `1`). The newest `past` entries and the nearest-to-redo `future` entries
     * are retained.
     */
    internal fun buildPayload(
        history: EditHistory<MediaEditState>,
        maxEntries: Int,
    ): ArrayList<Any?> {
        val saver = MediaEditState.Saver
        val scope = SaverScope { true }
        val cap = maxEntries.coerceAtLeast(1)

        val past = history.past.let { if (it.size > cap) it.subList(it.size - cap, it.size) else it }
        val future = history.future.let { if (it.size > cap) it.subList(0, cap) else it }

        val pastSaved = ArrayList<Any?>(past.size)
        for (s in past) pastSaved.add(with(saver) { scope.save(s) })
        val futureSaved = ArrayList<Any?>(future.size)
        for (s in future) futureSaved.add(with(saver) { scope.save(s) })
        val presentSaved = with(saver) { scope.save(history.present) }

        return arrayListOf(pastSaved, presentSaved, futureSaved)
    }
}
