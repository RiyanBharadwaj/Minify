package com.shanks.minify.editor

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Property-based tests for the LibreCuts result contract.
 *
 * Feature: editor-replacement, Property 1: Completion-gated Edited_Output
 * Validates: Requirements 3.4a, 5.4, 10.1, 10.2, 10.5
 *
 * These tests exercise [classifyEditorResult] — the pure branch decision that
 * `LibreCutsEditContract.parseResult` delegates to. Testing the pure function
 * keeps this a fast, deterministic JVM property test with no Android runtime
 * (real `Intent`/`Uri` cannot be constructed in a local unit test).
 */
class LibreCutsEditContractPropertyTest {

    private companion object {
        // Plain Int mirrors of android.app.Activity constants to avoid touching
        // the stubbed android.jar in a local unit test.
        const val RESULT_OK = -1
        const val RESULT_CANCELED = 0
    }

    /** resultCodes ∈ {OK, CANCELED, other arbitrary ints}. */
    @Provide
    fun resultCodes(): Arbitrary<Int> =
        Arbitraries.oneOf(
            Arbitraries.just(RESULT_OK),
            Arbitraries.just(RESULT_CANCELED),
            Arbitraries.integers(),
        )

    /**
     * COMPLETED (i.e. an Edited_Output is exposed) iff `code == RESULT_OK && hasOutput`.
     * In every other case the result is CANCELLED or FAILED — never COMPLETED.
     */
    @Property(tries = 200)
    fun completedIffOkAndOutputPresent(
        @ForAll("resultCodes") code: Int,
        @ForAll hasOutput: Boolean,
        @ForAll hasError: Boolean,
    ) {
        val isOk = code == RESULT_OK
        val kind = classifyEditorResult(isOk = isOk, hasOutput = hasOutput, hasError = hasError)

        val expectCompleted = isOk && hasOutput
        assertEquals(
            expectCompleted,
            kind == EditorResultKind.COMPLETED,
            "COMPLETED iff RESULT_OK && output present (code=$code, hasOutput=$hasOutput)",
        )
    }

    /**
     * The full mapping is exactly specified for every branch:
     * - OK && output            -> COMPLETED
     * - OK && !output && error   -> FAILED
     * - OK && !output && !error  -> CANCELLED
     * - !OK                      -> CANCELLED (regardless of output/error)
     */
    @Property(tries = 200)
    fun mappingMatchesSpecForEveryBranch(
        @ForAll("resultCodes") code: Int,
        @ForAll hasOutput: Boolean,
        @ForAll hasError: Boolean,
    ) {
        val isOk = code == RESULT_OK
        val kind = classifyEditorResult(isOk = isOk, hasOutput = hasOutput, hasError = hasError)

        val expected = when {
            !isOk -> EditorResultKind.CANCELLED
            hasOutput -> EditorResultKind.COMPLETED
            hasError -> EditorResultKind.FAILED
            else -> EditorResultKind.CANCELLED
        }
        assertEquals(expected, kind)
    }

    /** No non-OK result may ever expose an Edited_Output, whatever the extras. */
    @Property(tries = 200)
    fun nonOkNeverCompletes(
        @ForAll("nonOkCodes") code: Int,
        @ForAll hasOutput: Boolean,
        @ForAll hasError: Boolean,
    ) {
        val kind = classifyEditorResult(isOk = code == RESULT_OK, hasOutput = hasOutput, hasError = hasError)
        assertEquals(EditorResultKind.CANCELLED, kind)
    }

    @Provide
    fun nonOkCodes(): Arbitrary<Int> =
        Arbitraries.integers().filter { it != RESULT_OK }
}
