package com.shanks.minify.ui.editor.model

import android.os.TransactionTooLargeException
import androidx.lifecycle.SavedStateHandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Example-based unit tests for [EditorPersistence]'s `TransactionTooLargeException`
 * degrade ladder (Req 1.7).
 *
 * The ladder is exercised through the internal [EditorPersistence.persistTo] seam
 * with a fake writer that stands in for the `SavedStateHandle` write and throws
 * [TransactionTooLargeException] on demand — `SavedStateHandle` itself is `final`
 * and never raises the transaction failure in a JVM test, so the write side is
 * faked to simulate the binder limit being exceeded. The tests assert the ladder
 * never crashes on a transaction-too-large failure, steps down
 * (full → present-only → nothing), and leaves the caller's in-memory history
 * untouched. The public [EditorPersistence.persist] entry point is covered on the
 * happy path via a real [SavedStateHandle].
 */
class EditorPersistenceTest {

    private val key = "history"

    /** A photo state whose vignette makes it individually identifiable. */
    private fun photoState(vignette: Float): MediaEditState =
        MediaEditState.initial(MediaType.PHOTO)
            .copy(color = ColorGrade().withVignette(vignette))

    /** A history with a non-empty past and future so the "full" payload is distinguishable. */
    private fun sampleHistory(): EditHistory<MediaEditState> =
        EditHistory(
            past = listOf(photoState(0.1f), photoState(0.2f), photoState(0.3f)),
            present = photoState(0.4f),
            future = listOf(photoState(0.5f)),
        )

    /**
     * A fake `SavedStateHandle` write. Records every attempted payload and the one
     * that was ultimately committed, throwing whatever [shouldThrow] returns for a
     * given payload (or nothing when it returns `null`).
     */
    private class RecordingWriter(
        private val shouldThrow: (ArrayList<Any?>) -> Throwable?,
    ) : (ArrayList<Any?>) -> Unit {
        val attempts = mutableListOf<ArrayList<Any?>>()
        var committed: ArrayList<Any?>? = null

        override fun invoke(payload: ArrayList<Any?>) {
            attempts.add(payload)
            shouldThrow(payload)?.let { throw it }
            committed = payload
        }
    }

    /** The persisted `past` list from a `[past, present, future]` payload. */
    private fun pastOf(payload: ArrayList<Any?>): List<*> = payload[0] as List<*>

    /** The persisted `future` list from a `[past, present, future]` payload. */
    private fun futureOf(payload: ArrayList<Any?>): List<*> = payload[2] as List<*>

    /** Restore the `present` state from a `[past, present, future]` payload. */
    private fun presentOf(payload: ArrayList<Any?>): MediaEditState =
        MediaEditState.Saver.restore(payload[1] as Any)!!

    // --- Happy path -----------------------------------------------------------

    @Test
    fun `persist writes the full history through a real SavedStateHandle`() {
        val handle = SavedStateHandle()
        val history = sampleHistory()

        EditorPersistence.persist(handle, key, history)

        val payload = handle.get<ArrayList<Any?>>(key)
        assertNotNull(payload, "the full history must be persisted on the happy path")
        assertEquals(history.past.size, pastOf(payload!!).size, "all past entries persisted")
        assertEquals(history.future.size, futureOf(payload).size, "all future entries persisted")
        assertEquals(history.present, presentOf(payload), "the present state round-trips")
    }

    // --- Degrade ladder (Req 1.7) --------------------------------------------

    @Test
    fun `falls back to present-only when the full payload is too large`() {
        val history = sampleHistory()
        // Throw only while the payload still carries undo/redo depth (a non-empty past),
        // simulating the full history exceeding the transaction limit.
        val writer = RecordingWriter { payload ->
            if (pastOf(payload).isNotEmpty()) TransactionTooLargeException() else null
        }

        EditorPersistence.persistTo(history, write = writer)

        assertEquals(2, writer.attempts.size, "ladder tries the full payload, then present-only")
        val committed = writer.committed
        assertNotNull(committed, "the present-only fallback must succeed")
        assertTrue(pastOf(committed!!).isEmpty(), "fallback drops the past")
        assertTrue(futureOf(committed).isEmpty(), "fallback drops the future")
        assertEquals(history.present, presentOf(committed), "fallback preserves the present state")
    }

    @Test
    fun `persists nothing without crashing when every write is too large`() {
        val history = sampleHistory()
        val snapshot = history.copy()
        val writer = RecordingWriter { TransactionTooLargeException() }

        // Must not throw even though both ladder steps fail.
        EditorPersistence.persistTo(history, write = writer)

        assertEquals(2, writer.attempts.size, "ladder exhausts full then present-only")
        assertNull(writer.committed, "nothing is committed when every write is too large")
        // In-memory history is untouched by the failed persistence.
        assertEquals(snapshot, history, "the in-memory history is preserved intact")
        assertTrue(history.canUndo, "undo availability is preserved")
        assertTrue(history.canRedo, "redo availability is preserved")
    }

    @Test
    fun `handles a TransactionTooLargeException wrapped as the cause of a RuntimeException`() {
        val history = sampleHistory()
        val writer = RecordingWriter { payload ->
            if (pastOf(payload).isNotEmpty()) {
                RuntimeException("write failed", TransactionTooLargeException())
            } else {
                null
            }
        }

        // A RuntimeException whose cause is the transaction failure must degrade, not crash.
        EditorPersistence.persistTo(history, write = writer)

        val committed = writer.committed
        assertNotNull(committed, "the ladder must recover via the present-only fallback")
        assertTrue(pastOf(committed!!).isEmpty(), "recovered payload is present-only")
        assertEquals(history.present, presentOf(committed), "present state preserved on recovery")
    }

    @Test
    fun `re-throws a failure that is not a transaction-too-large error`() {
        val history = sampleHistory()
        val writer = RecordingWriter { IllegalStateException("unrelated failure") }

        assertThrows(IllegalStateException::class.java) {
            EditorPersistence.persistTo(history, write = writer)
        }
        assertEquals(1, writer.attempts.size, "an unrelated failure aborts the ladder immediately")
        assertNull(writer.committed, "nothing is committed when the write fails unexpectedly")
    }

    // --- Payload bounding (second guard) -------------------------------------

    @Test
    fun `buildPayload caps the number of persisted past and future entries`() {
        val history = EditHistory(
            past = List(10) { photoState(it.toFloat() / 100f) },
            present = photoState(0.9f),
            future = List(8) { photoState(0.5f + it.toFloat() / 100f) },
        )

        val payload = EditorPersistence.buildPayload(history, maxEntries = 3)

        assertEquals(3, pastOf(payload).size, "past entries capped at maxEntries")
        assertEquals(3, futureOf(payload).size, "future entries capped at maxEntries")
        assertEquals(history.present, presentOf(payload), "the present state is always persisted")
        assertFalse(pastOf(payload).isEmpty(), "at least the newest past entries are kept")
    }
}
