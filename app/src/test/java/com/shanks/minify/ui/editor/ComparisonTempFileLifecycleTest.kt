package com.shanks.minify.ui.editor

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Example-based unit tests for the Before/After comparison temp-file lifecycle
 * (Req 10.1, 10.2).
 *
 * The production lifecycle lives inside [MediaEditorScreen] as the `closeComparison()`
 * choke point (deletes the backing temp render file on close) which is also invoked
 * before creating a new comparison (single choke point, task 11.1). Because that logic
 * is inside a Composable and not directly unit-testable, this test models the same
 * semantics with a small [FakeComparisonHolder] and drives it with **real** temp files
 * created under a JUnit [TempDir], asserting the two guarantees:
 *
 *  - Req 10.1: reopening (rendering a new comparison) first closes the previous one,
 *    deleting its temp file, so repeated comparisons never leak orphaned files.
 *  - Req 10.2: closing a comparison deletes the temp file it created.
 */
class ComparisonTempFileLifecycleTest {

    /**
     * Faithful stand-in for the `comparisonTemp` state and `closeComparison()` choke
     * point in [MediaEditorScreen]. It mirrors the production semantics exactly:
     *
     *  - [open] is the single choke point for starting a new comparison: it first
     *    calls [close] (deleting any previous temp file) and only then adopts the new
     *    temp file — matching the `closeComparison()`-before-render ordering.
     *  - [close] deletes the current temp file if it exists and clears the holder.
     */
    private class FakeComparisonHolder {
        var source: Any? = null
            private set
        var temp: File? = null
            private set

        fun open(newSource: Any, newTemp: File) {
            // Single choke point: close any previous comparison (deleting its temp
            // render file) before adopting the new one.
            close()
            temp = newTemp
            source = newSource
        }

        fun close() {
            source = null
            temp?.let { if (it.exists()) it.delete() }
            temp = null
        }
    }

    private fun newTempRender(dir: File, name: String): File {
        val file = File(dir, name)
        file.writeText("render bytes")
        assertTrue(file.exists(), "precondition: temp render file should exist after creation")
        return file
    }

    // --- Req 10.2: closing deletes the current temp file ----------------------

    @Test
    fun `closing deletes the current temp file`(@TempDir dir: File) {
        val holder = FakeComparisonHolder()
        val temp = newTempRender(dir, "compare-current.tmp")
        holder.open(newSource = "images", newTemp = temp)

        holder.close()

        assertFalse(temp.exists(), "closing must delete the temp render file it created (Req 10.2)")
        assertNull(holder.temp, "the holder clears its temp reference on close")
        assertNull(holder.source, "the holder clears its comparison source on close")
    }

    @Test
    fun `closing with no open comparison is a no-op and does not crash`() {
        val holder = FakeComparisonHolder()

        // No temp file has been adopted; closing must be safe.
        holder.close()

        assertNull(holder.temp, "temp stays null when nothing was open")
        assertNull(holder.source, "source stays null when nothing was open")
    }

    // --- Req 10.1: reopening deletes the previous temp file -------------------

    @Test
    fun `reopening deletes the previous temp file before adopting the new one`(@TempDir dir: File) {
        val holder = FakeComparisonHolder()
        val previous = newTempRender(dir, "compare-previous.tmp")
        holder.open(newSource = "images-1", newTemp = previous)

        val current = newTempRender(dir, "compare-current.tmp")
        holder.open(newSource = "images-2", newTemp = current)

        assertFalse(previous.exists(), "reopening must delete the previous temp file (Req 10.1)")
        assertTrue(current.exists(), "the newly rendered comparison temp file is retained while open")
        assertSame(current, holder.temp, "the holder now tracks the current temp file")

        // And closing the current comparison still deletes its file (Req 10.2).
        holder.close()
        assertFalse(current.exists(), "closing deletes the current temp file (Req 10.2)")
        assertNull(holder.temp)
    }

    @Test
    fun `repeated reopening never leaves an orphaned temp file`(@TempDir dir: File) {
        val holder = FakeComparisonHolder()
        val created = mutableListOf<File>()

        repeat(5) { i ->
            val temp = newTempRender(dir, "compare-$i.tmp")
            created.add(temp)
            holder.open(newSource = "images-$i", newTemp = temp)
        }

        // Every temp file except the currently open one must have been deleted on reopen.
        created.dropLast(1).forEachIndexed { i, file ->
            assertFalse(file.exists(), "temp file #$i should have been deleted when the next comparison opened")
        }
        assertTrue(created.last().exists(), "only the current comparison's temp file remains while open")

        // Closing the last comparison leaves nothing behind.
        holder.close()
        assertTrue(created.none { it.exists() }, "no orphaned temp files remain after the final close")
    }
}
