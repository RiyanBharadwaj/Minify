package com.shanks.minify.editor

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Property-based test for [classifyEditorResult], the pure branch decision behind
 * [LibreCutsEditContract.parseResult].
 *
 * The classification is completion-gated: an editor result maps to
 * [EditorResultKind.COMPLETED] strictly when the Activity result was `RESULT_OK`
 * (`isOk`) AND an output URI was produced (`hasOutput`). It maps to
 * [EditorResultKind.FAILED] only for the OK-but-no-output-with-error case, and to
 * [EditorResultKind.CANCELLED] in every other case (any non-OK result, or OK with
 * neither output nor error).
 */
class EditorResultClassificationPropertyTest {

    /**
     * The expected classification derived independently from the completion-gated
     * table, used as the oracle in both the property and exhaustive checks.
     */
    private fun expected(isOk: Boolean, hasOutput: Boolean, hasError: Boolean): EditorResultKind =
        when {
            isOk && hasOutput -> EditorResultKind.COMPLETED
            isOk && !hasOutput && hasError -> EditorResultKind.FAILED
            else -> EditorResultKind.CANCELLED
        }

    // Feature: video-editor-fixes, Property 8: Editor result classification is completion-gated
    @Property(tries = 200)
    fun classificationIsCompletionGated(
        @ForAll isOk: Boolean,
        @ForAll hasOutput: Boolean,
        @ForAll hasError: Boolean,
    ) {
        val actual = classifyEditorResult(isOk = isOk, hasOutput = hasOutput, hasError = hasError)

        // Completed IFF RESULT_OK with output present (regardless of hasError).
        assertEquals(
            isOk && hasOutput,
            actual == EditorResultKind.COMPLETED,
            "Completed must hold iff isOk && hasOutput " +
                "(isOk=$isOk, hasOutput=$hasOutput, hasError=$hasError)",
        )

        // Failed IFF OK with no output but an error.
        assertEquals(
            isOk && !hasOutput && hasError,
            actual == EditorResultKind.FAILED,
            "Failed must hold iff isOk && !hasOutput && hasError " +
                "(isOk=$isOk, hasOutput=$hasOutput, hasError=$hasError)",
        )

        // Cancelled otherwise: any non-OK, or OK with neither output nor error.
        assertEquals(
            !isOk || (isOk && !hasOutput && !hasError),
            actual == EditorResultKind.CANCELLED,
            "Cancelled must hold iff !isOk || (isOk && !hasOutput && !hasError) " +
                "(isOk=$isOk, hasOutput=$hasOutput, hasError=$hasError)",
        )

        // The three kinds are mutually exclusive and exhaustive, so match the oracle.
        assertEquals(expected(isOk, hasOutput, hasError), actual)
    }

    // Feature: video-editor-fixes, Property 8: Editor result classification is completion-gated
    @Test
    fun exhaustiveEightCombinations() {
        val bools = listOf(false, true)
        for (isOk in bools) {
            for (hasOutput in bools) {
                for (hasError in bools) {
                    assertEquals(
                        expected(isOk, hasOutput, hasError),
                        classifyEditorResult(isOk = isOk, hasOutput = hasOutput, hasError = hasError),
                        "combo (isOk=$isOk, hasOutput=$hasOutput, hasError=$hasError)",
                    )
                }
            }
        }
    }
}
