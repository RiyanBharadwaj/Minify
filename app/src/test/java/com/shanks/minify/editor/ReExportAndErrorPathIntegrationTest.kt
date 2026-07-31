package com.shanks.minify.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration-style regression tests for the re-export and error-path behaviors.
 *
 * Feature: video-editor-fixes
 * **Validates: Requirements 4.1, 4.4, 5.6**
 * (Req 5.5 — monitoring-unavailable — is covered on-device by
 * `com.shanks.minify.media3.MonitoringUnavailableFailureTest`, since it drives
 * the Android-bound `CompressionMonitor` singleton.)
 *
 * ## Why this test wires the pure seams together
 * The re-export fix spans three collaborating pieces, each already covered in
 * isolation by a property test:
 *  - [SessionLaunchGuard.shouldLaunch] — a fresh session (new token) always
 *    relaunches, even on the identical source URI (Property 7 /
 *    [FreshLaunchPerSessionPropertyTest]).
 *  - the Activity draft lifecycle — an *exported* session clears its draft so a
 *    later session never inherits already-exported operations (Property 6 /
 *    `SessionIsolationPropertyTest` in `:videoeditor`).
 *  - [classifyEditorResult] — an `RESULT_OK`-with-error-and-no-output maps to
 *    `Failed(message)` (Property 8 / [EditorResultClassificationPropertyTest]).
 *
 * The real orchestration lives in `VideoEditorHost` (a `@Composable` needing the
 * Android runtime) and `VideoEditingActivity` (carrying `android.net.Uri` /
 * `VideoProject`, which the stubbed unit-test `android.jar` cannot construct).
 * So — matching the project's "pure model mirrors the host branches" strategy
 * (see [VideoHandoffSourcePreservationPropertyTest]) — this test drives faithful
 * pure models of those two seams and asserts the *combined* observable behavior:
 * the concrete reproduction of the original stale-re-export bug, and the
 * export-failure routing that preserves the selected source.
 */
class ReExportAndErrorPathIntegrationTest {

    private companion object {
        // Plain Int mirrors of android.app.Activity constants so this stays a
        // pure JVM unit test (the stubbed android.jar throws on real use).
        const val RESULT_OK = -1
        const val RESULT_CANCELED = 0
    }

    // --- Draft-lifecycle model (mirrors VideoEditingActivity + VideoDraftStore) ---

    /**
     * Pure mirror of `VideoDraftStore`, keyed by a `String` standing in for the
     * source video URI. `save` of an empty op list clears the draft, exactly like
     * the real store treats "no pending operations" as nothing to resume.
     */
    private class ModelDraftStore {
        private val drafts = mutableMapOf<String, List<String>>()
        fun save(source: String, ops: List<String>) {
            if (ops.isEmpty()) drafts.remove(source) else drafts[source] = ops
        }
        fun get(source: String): List<String>? = drafts[source]
        fun clear(source: String) { drafts.remove(source) }
    }

    /**
     * Runs one editing session against [store], mirroring the Activity lifecycle
     * from tasks 5.1/5.2: restore this source's draft, append this session's own
     * ops, then on export return the session's ops AND clear the draft
     * (`exportedThisSession` ⇒ `VideoDraftStore.clear`); on non-export exit, save
     * the ops for resume. Returns the exported ops, or null when not exported.
     */
    private fun runSession(
        store: ModelDraftStore,
        source: String,
        newOps: List<String>,
        export: Boolean,
    ): List<String>? {
        val restored = store.get(source) ?: emptyList()
        val sessionOps = restored + newOps
        return if (export) {
            store.clear(source)
            sessionOps
        } else {
            store.save(source, sessionOps)
            null
        }
    }

    // --- Host result-routing model (mirrors VideoEditorHost branches) ---

    /** Minimal Video-tab/host state around an editor result. */
    private data class HostState(
        /** The user's selected source video URI. */
        val selectedUri: String,
        /** Message surfaced via `onError`, else null. */
        val errorShown: String? = null,
        /** Whether the host returned to the tab (`onDismiss`). */
        val dismissed: Boolean = false,
    )

    /**
     * Pure model of `VideoEditorHost`'s handling of an [EditorResultKind]:
     *  - COMPLETED -> adopt the editor output as the new selected source
     *    (`onEdited(editedUri)` ⇒ `selectedUri = editedUri`).
     *  - FAILED    -> surface the message via `onError` and `onDismiss`, WITHOUT
     *    changing the selected source (Req 5.6).
     *  - CANCELLED -> `onDismiss` only; selected source preserved (Req 5.3).
     */
    private fun reduceEditorResult(
        state: HostState,
        kind: EditorResultKind,
        output: String?,
        message: String?,
    ): HostState = when (kind) {
        EditorResultKind.COMPLETED -> state.copy(selectedUri = output!!, dismissed = true)
        EditorResultKind.FAILED -> state.copy(errorShown = message, dismissed = true)
        EditorResultKind.CANCELLED -> state.copy(dismissed = true)
    }

    // ------------------------------------------------------------------------
    // 1. Re-export reproduction (Req 4.1, 4.4) — the original bug scenario.
    // ------------------------------------------------------------------------

    /**
     * Two consecutive editing sessions on the SAME source each produce an export
     * reflecting THAT session's edits, with no carryover from the first export.
     *
     * This wires the launch guard together with the draft-clear lifecycle: both
     * sessions relaunch (distinct tokens), and because session 1 cleared its
     * draft on export, session 2 starts fresh and exports exactly its own ops.
     * This is the concrete reproduction of the stale-re-export bug (Req 4.4) and
     * confirms the second session's edits are applied (Req 4.1).
     */
    @Test
    fun twoSessionsSameSource_eachExportsOwnEdits_noCarryover() {
        val store = ModelDraftStore()
        val source = "content://media/external/video/media/42"

        // --- Session 1: fresh launch, edits {trim}, export. ---
        val token1 = 1
        assertTrue(
            SessionLaunchGuard.shouldLaunch(token1, lastLaunchedToken = null),
            "the first session must launch the editor",
        )
        val session1Edits = listOf("trim")
        val exported1 = runSession(store, source, session1Edits, export = true)
        assertEquals(
            session1Edits, exported1,
            "session 1's export must reflect exactly its own edits",
        )

        // --- Session 2: SAME source, distinct token, edits {crop}, export. ---
        val token2 = 2
        assertTrue(
            SessionLaunchGuard.shouldLaunch(token2, lastLaunchedToken = token1),
            "a new session on the same source URI must still relaunch fresh (Req 4.6)",
        )
        val session2Edits = listOf("crop")
        val exported2 = runSession(store, source, session2Edits, export = true)

        // The heart of the bug fix: session 2 reflects ONLY its own edits.
        assertEquals(
            session2Edits, exported2,
            "session 2's export must reflect its own edits with no carryover from session 1",
        )
        assertFalse(
            exported2!!.contains("trim"),
            "session 2 must not re-apply session 1's already-exported operations (Req 4.4)",
        )
    }

    /**
     * Recompositions within a single session (the guard re-evaluated with the
     * same token) must NOT relaunch or re-deliver a prior result — pairs with the
     * fresh-relaunch assertion above to pin the full guard contract used by the
     * two-session flow.
     */
    @Test
    fun sameSessionRecomposition_doesNotRelaunch() {
        val token = 7
        assertTrue(
            SessionLaunchGuard.shouldLaunch(token, lastLaunchedToken = null),
            "first evaluation of a token launches",
        )
        assertFalse(
            SessionLaunchGuard.shouldLaunch(token, lastLaunchedToken = token),
            "re-evaluating the same token (recomposition) must not relaunch/re-deliver",
        )
    }

    // ------------------------------------------------------------------------
    // 2. Export-failure path (Req 5.6).
    // ------------------------------------------------------------------------

    /**
     * On export failure the editor returns `RESULT_OK` with an error message and
     * no output; that classifies as `Failed(message)`, and the host surfaces the
     * message via `onError`/`onDismiss` while PRESERVING the selected source.
     */
    @Test
    fun exportFailure_showsDescriptiveError_andPreservesSelectedSource() {
        val source = "content://media/external/video/media/99"
        val message = "Export failed: encoder error"

        // Classification: OK + error + no output -> FAILED (reuses the pure fn).
        val kind = classifyEditorResult(
            isOk = RESULT_OK == RESULT_OK,
            hasOutput = false,
            hasError = true,
        )
        assertEquals(EditorResultKind.FAILED, kind, "export failure must classify as Failed")

        // Host routing: Failed -> onError(message) + onDismiss, source unchanged.
        val before = HostState(selectedUri = source)
        val after = reduceEditorResult(before, kind, output = null, message = message)

        assertEquals(message, after.errorShown, "a descriptive error message must be surfaced")
        assertTrue(after.dismissed, "the host must return control to the tab (onDismiss)")
        assertEquals(
            source, after.selectedUri,
            "the selected source must be preserved on export failure (Req 5.6)",
        )
    }

    /**
     * Contrast baseline: a successful export DOES adopt the editor output as the
     * selected source, so the failure branch above is demonstrably distinct and
     * the source-preservation assertion is meaningful.
     */
    @Test
    fun successfulExport_adoptsEditorOutputAsSource() {
        val source = "content://media/external/video/media/99"
        val output = "file:///data/user/0/com.shanks.minify/cache/editor_out_123.mp4"

        val kind = classifyEditorResult(
            isOk = RESULT_OK == RESULT_OK,
            hasOutput = true,
            hasError = false,
        )
        assertEquals(EditorResultKind.COMPLETED, kind)

        val after = reduceEditorResult(HostState(selectedUri = source), kind, output = output, message = null)
        assertEquals(output, after.selectedUri, "a completed export is adopted as the next source")
        assertNull(after.errorShown, "a successful export surfaces no error")
    }

    /**
     * Back / cancel (`RESULT_CANCELED`, no output) returns control to the tab
     * without producing an export and preserves the selected source (Req 5.3),
     * rounding out the result-routing branches exercised alongside the failure
     * path.
     */
    @Test
    fun cancel_returnsToTab_withoutExport_sourcePreserved() {
        val source = "content://media/external/video/media/99"

        val kind = classifyEditorResult(
            isOk = RESULT_CANCELED == RESULT_OK,
            hasOutput = false,
            hasError = false,
        )
        assertEquals(EditorResultKind.CANCELLED, kind)

        val after = reduceEditorResult(HostState(selectedUri = source), kind, output = null, message = null)
        assertEquals(source, after.selectedUri, "cancel preserves the selected source")
        assertNull(after.errorShown, "cancel surfaces no error")
        assertTrue(after.dismissed, "cancel returns control to the tab")
        assertNotNull(after.selectedUri, "source remains available for continued editing")
    }
}
