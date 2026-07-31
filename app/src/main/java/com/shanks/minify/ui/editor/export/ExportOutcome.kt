package com.shanks.minify.ui.editor.export

import java.io.File

/**
 * The result of handing an edited source to Minify's compression pipelines.
 *
 * On [Success] the caller receives the saved output [File] plus the original and
 * output sizes in bytes (surfaced to the UI in MB). On [Failure] the reason is a
 * human-readable string; the source is always left unchanged and the process
 * keeps running.
 *
 * Retained after the editor replacement: the new [PhotoEditorHost]
 * [com.shanks.minify.editor.PhotoEditorHost] reports its compression handoff
 * result through this type, and the Photo tab maps it onto its result card. The
 * legacy `MediaExporter` that also produced it was removed with the unified
 * editor.
 */
sealed interface ExportOutcome {
    data class Success(
        val output: File,
        val originalBytes: Long,
        val outputBytes: Long,
    ) : ExportOutcome

    data class Failure(val reason: String) : ExportOutcome
}
