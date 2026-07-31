package com.shanks.minify.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Example-based unit tests for the LibreCuts result error-mapping edges.
 *
 * These complement the property test in [LibreCutsEditContractPropertyTest] by
 * pinning down the specific error edges called out in the design's Error Handling
 * section:
 *
 * - **Video export failure (Req 9.4):** FFmpeg failures are surfaced either as
 *   `RESULT_OK` carrying `EXTRA_ERROR` (-> `Failed`) or as `RESULT_CANCELED`
 *   (-> `Cancelled`); no output URI is returned.
 * - **Unreadable video (Req 5.5):** `VideoEditingActivity` in return-result mode
 *   detects an unreadable input and returns `Failed`/`Cancelled`.
 *
 * They assert against [classifyEditorResult] — the pure, Android-free branch
 * decision that `LibreCutsEditContract.parseResult` delegates to — because a
 * plain JVM unit test cannot construct real `Intent`/`Uri` instances (the
 * stubbed android.jar throws). The `(isOk, hasOutput, hasError)` triple is the
 * exact information `parseResult` extracts from the Activity result before
 * branching, so exercising the classifier is equivalent to exercising the
 * mapping without the Android framework.
 */
class LibreCutsEditContractMappingTest {

    private companion object {
        // Plain Int mirror of android.app.Activity constants to avoid touching
        // the stubbed android.jar in a local unit test.
        const val RESULT_OK = -1
        const val RESULT_CANCELED = 0
    }

    // --- Req 9.4: export-failure mapping -------------------------------------

    /**
     * Export failure reported as `RESULT_OK` + `EXTRA_ERROR` (no output) maps to
     * `FAILED` — the descriptive failure is surfaced to the caller. (Req 9.4)
     */
    @Test
    fun exportFailure_okWithErrorAndNoOutput_mapsToFailed() {
        val kind = classifyEditorResult(
            isOk = RESULT_OK == RESULT_OK,
            hasOutput = false,
            hasError = true,
        )
        assertEquals(EditorResultKind.FAILED, kind)
    }

    /**
     * Export failure reported as `RESULT_CANCELED` maps to `CANCELLED`,
     * regardless of any stray extras. (Req 9.4)
     */
    @Test
    fun exportFailure_resultCanceled_mapsToCancelled() {
        val kind = classifyEditorResult(
            isOk = RESULT_CANCELED == RESULT_OK,
            hasOutput = false,
            hasError = true,
        )
        assertEquals(EditorResultKind.CANCELLED, kind)
    }

    // --- Req 5.5: unreadable-video mapping -----------------------------------

    /**
     * Unreadable input surfaced as `RESULT_OK` + `EXTRA_ERROR` (no output) maps
     * to `FAILED` so the host can show a descriptive message. (Req 5.5)
     */
    @Test
    fun unreadableVideo_okWithError_mapsToFailed() {
        val kind = classifyEditorResult(
            isOk = RESULT_OK == RESULT_OK,
            hasOutput = false,
            hasError = true,
        )
        assertEquals(EditorResultKind.FAILED, kind)
    }

    /**
     * Unreadable input surfaced as `RESULT_CANCELED` maps to `CANCELLED`,
     * returning control to the Video tab with no output. (Req 5.5)
     */
    @Test
    fun unreadableVideo_resultCanceled_mapsToCancelled() {
        val kind = classifyEditorResult(
            isOk = RESULT_CANCELED == RESULT_OK,
            hasOutput = false,
            hasError = false,
        )
        assertEquals(EditorResultKind.CANCELLED, kind)
    }

    // --- Baseline: makes the failure cases meaningfully distinct -------------

    /**
     * Sanity baseline: a successful export (`RESULT_OK` + output present) maps to
     * `COMPLETED`, so the error mappings above are demonstrably distinct.
     */
    @Test
    fun successfulExport_okWithOutput_mapsToCompleted() {
        val kind = classifyEditorResult(
            isOk = RESULT_OK == RESULT_OK,
            hasOutput = true,
            hasError = false,
        )
        assertEquals(EditorResultKind.COMPLETED, kind)
    }
}
