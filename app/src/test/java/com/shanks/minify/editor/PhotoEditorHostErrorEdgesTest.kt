package com.shanks.minify.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Example-based unit tests for the `PhotoEditorHost` error edges.
 *
 * These pin down the three photo-host failure edges called out in the design's
 * Error Handling section, each governed by a requirement:
 *
 * - **Undecodable image (Req 1.5):** on a decode failure the host surfaces a
 *   descriptive error and returns control to the Photo tab (dismiss) — and does
 *   not host a `PhotoEditorView` or produce an `Edited_Output`.
 * - **Full-resolution apply failure (Req 3.5):** if applying the pending edits to
 *   the full-resolution source fails, the host reports the edit as failed with a
 *   descriptive message and produces **no** `Edited_Output`.
 * - **Hosting impossible (Req 10.4):** if the `AndroidView` cannot host the
 *   `PhotoEditorView`, the host fires `onError` and **never** launches a separate
 *   Activity — the failure is reported, not worked around.
 *
 * They assert against [photoHostReaction] — the pure, Android-free decision that
 * `PhotoEditorHost` routes each error branch through — because a plain JVM unit
 * test cannot drive a Compose composable or construct real `Bitmap`/`Uri`/`View`
 * instances (the stubbed android.jar throws). The composable's decode and hosting
 * branches call this exact function, so exercising it is equivalent to exercising
 * the host's error handling without the Android/Compose runtime (mirrors the
 * approach in [LibreCutsEditContractMappingTest]).
 *
 * _Requirements: 1.5, 3.5, 10.4_
 */
class PhotoEditorHostErrorEdgesTest {

    // --- Req 1.5: undecodable image ------------------------------------------

    /**
     * An undecodable image surfaces the descriptive decode message and returns
     * control to the Photo tab (dismiss), producing no `Edited_Output` and never
     * launching an Activity. (Req 1.5)
     */
    @Test
    fun undecodableImage_reportsErrorAndDismisses() {
        val reaction = photoHostReaction(PhotoHostFailure.DECODE)

        assertEquals(PhotoHostMessages.DECODE, reaction.message)
        assertTrue(reaction.message.isNotBlank(), "decode error message must be descriptive")
        assertTrue(reaction.dismiss, "Req 1.5: a decode failure returns control to the Photo tab")
        assertFalse(reaction.launchesActivity, "no error edge launches a separate Activity")
        assertFalse(reaction.producesEditedOutput, "a decode failure produces no Edited_Output")
    }

    // --- Req 3.5: full-resolution apply failure ------------------------------

    /**
     * A full-resolution apply failure reports the edit as failed with a
     * descriptive message and produces **no** `Edited_Output`; it neither
     * dismisses nor launches an Activity. (Req 3.5)
     */
    @Test
    fun fullResolutionApplyFailure_reportsFailedWithNoOutput() {
        val reaction = photoHostReaction(PhotoHostFailure.APPLY)

        assertEquals(PhotoHostMessages.APPLY, reaction.message)
        assertTrue(reaction.message.isNotBlank(), "apply error message must be descriptive")
        assertFalse(reaction.producesEditedOutput, "Req 3.5: an apply failure produces no Edited_Output")
        assertFalse(reaction.launchesActivity, "no error edge launches a separate Activity")
    }

    // --- Req 10.4: hosting impossible ----------------------------------------

    /**
     * When the `PhotoEditorView` cannot be hosted, the host fires `onError` with a
     * descriptive message and **never** launches a separate Activity. (Req 10.4)
     */
    @Test
    fun hostingImpossible_firesOnErrorWithNoActivityLaunch() {
        val reaction = photoHostReaction(PhotoHostFailure.HOSTING)

        assertEquals(PhotoHostMessages.HOSTING, reaction.message)
        assertTrue(reaction.message.isNotBlank(), "hosting error message must be descriptive")
        assertFalse(
            reaction.launchesActivity,
            "Req 10.4: a hosting failure is reported, never worked around with an Activity",
        )
        assertFalse(reaction.producesEditedOutput, "a hosting failure produces no Edited_Output")
    }

    // --- Cross-cutting invariants over every failure edge --------------------

    /**
     * No photo-host failure edge — decode (1.5), apply (3.5), or hosting (10.4) —
     * ever launches a separate Activity or produces an `Edited_Output`, and every
     * edge carries a non-blank descriptive message.
     */
    @Test
    fun noFailureEdgeLaunchesActivityOrProducesOutput() {
        PhotoHostFailure.entries.forEach { failure ->
            val reaction = photoHostReaction(failure)
            assertFalse(reaction.launchesActivity, "$failure must not launch a separate Activity (Req 10.4)")
            assertFalse(reaction.producesEditedOutput, "$failure must not produce an Edited_Output")
            assertTrue(reaction.message.isNotBlank(), "$failure must carry a descriptive message")
        }
    }

    /**
     * Only a decode failure dismisses back to the Photo tab; hosting and apply
     * failures report in place without dismissing (they keep the editor context).
     */
    @Test
    fun onlyDecodeFailureDismissesToPhotoTab() {
        assertTrue(photoHostReaction(PhotoHostFailure.DECODE).dismiss)
        assertFalse(photoHostReaction(PhotoHostFailure.HOSTING).dismiss)
        assertFalse(photoHostReaction(PhotoHostFailure.APPLY).dismiss)
    }
}
