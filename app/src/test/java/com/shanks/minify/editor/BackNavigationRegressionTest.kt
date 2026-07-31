package com.shanks.minify.editor

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Focused regression coverage for back-navigation out of the Video_Editor (Req 5.3): navigating
 * back WITHOUT completing an export must return control to the Video_Tab WITHOUT producing an
 * `Exported_Video`.
 *
 * // Feature: video-editor-fixes
 *
 * The editor→app handoff maps the Activity result into an [EditorResult] purely through
 * [classifyEditorResult]. "Returned without an Exported_Video" is exactly the
 * [EditorResultKind.CANCELLED] classification: only [EditorResultKind.COMPLETED] carries an output
 * URI, and completion is gated strictly on `RESULT_OK && hasOutput`.
 *
 * A back press surfaces as a non-OK Activity result (`isOk == false`); the design also treats an
 * `RESULT_OK` result that carries neither an output nor an error as a cancel (the editor closed
 * without producing an export). Both of these must classify as CANCELLED so no `Exported_Video` is
 * handed back. This complements the broad completion-gating test (task 8.2) with the specific
 * back-navigation framing required by task 10.3.
 *
 * **Validates: Requirements 5.3**
 */
class BackNavigationRegressionTest {

    // Feature: video-editor-fixes
    /**
     * A back press / cancel (`isOk == false`) always returns without an Exported_Video: it
     * classifies as CANCELLED and NEVER as COMPLETED, for every `(hasOutput, hasError)` the
     * result intent might happen to carry (Req 5.3).
     */
    @Property(tries = 200)
    fun backNavigationAlwaysReturnsWithoutExport(
        @ForAll hasOutput: Boolean,
        @ForAll hasError: Boolean,
    ) {
        val kind = classifyEditorResult(isOk = false, hasOutput = hasOutput, hasError = hasError)

        assertEquals(
            EditorResultKind.CANCELLED,
            kind,
            "a non-OK (back/cancel) result must classify as CANCELLED (no Exported_Video) " +
                "regardless of stray extras (hasOutput=$hasOutput, hasError=$hasError)",
        )
        assertNotEquals(
            EditorResultKind.COMPLETED,
            kind,
            "back-navigation must never surface an Exported_Video",
        )
    }

    // Feature: video-editor-fixes
    /**
     * Closing the editor with `RESULT_OK` but neither an output nor an error (i.e. no export was
     * produced) also returns without an Exported_Video — classified as CANCELLED (Req 5.3).
     */
    @Test
    fun okWithNoOutputAndNoErrorReturnsWithoutExport() {
        assertEquals(
            EditorResultKind.CANCELLED,
            classifyEditorResult(isOk = true, hasOutput = false, hasError = false),
            "OK with no output and no error means the editor closed without producing an export",
        )
    }

    // Feature: video-editor-fixes
    /**
     * Guard the flip side: an Exported_Video is only ever surfaced when an export actually
     * completed (`RESULT_OK && hasOutput`). This anchors the back-navigation guarantee by showing
     * COMPLETED cannot arise from a cancel path (Req 5.3).
     */
    @Test
    fun exportedVideoRequiresCompletedExport() {
        assertEquals(
            EditorResultKind.COMPLETED,
            classifyEditorResult(isOk = true, hasOutput = true, hasError = false),
            "an Exported_Video is surfaced only on a completed export",
        )
        // No non-completed combination yields COMPLETED.
        assertNotEquals(EditorResultKind.COMPLETED, classifyEditorResult(false, true, true))
        assertNotEquals(EditorResultKind.COMPLETED, classifyEditorResult(true, false, true))
        assertNotEquals(EditorResultKind.COMPLETED, classifyEditorResult(true, false, false))
    }
}
