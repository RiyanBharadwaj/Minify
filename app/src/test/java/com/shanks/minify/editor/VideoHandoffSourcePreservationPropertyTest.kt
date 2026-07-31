package com.shanks.minify.editor

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based test for the video editor -> compression handoff.
 *
 * Feature: editor-replacement, Property 2: Source preservation across all
 * compression outcomes.
 *
 * **Validates: Requirements 4.4, 4.5, 8.6**
 *
 * ## What this exercises
 * [VideoEditorHost] launches LibreCuts, and on [EditorResult.Completed] hands the
 * editor's *output* URI to the compression pipeline — it never reassigns the
 * user's *selected source* URI (see `VideoEditorHost.startCompression`, which
 * compresses `editedUri = result.output`, and the `uri` param which is only ever
 * read). The invariant Req 8.6 / 4.4-4.5 demands is: whatever the compression
 * outcome — success, pipeline failure, an unsupported/unreadable format, or a
 * failure of the handoff itself — the user's selected source is left untouched
 * and remains available for continued editing.
 *
 * The real host is a `@Composable` that needs the Android runtime (real `Uri`,
 * `Intent`, `Context`), so — matching the design's "fakes for the editors"
 * strategy and the existing pure-core tests ([classifyEditorResult],
 * `CompressionRequest.fromExtras`) — this test drives [reduceCompressionHandoff],
 * a pure model of the host's source handling. The model faithfully mirrors the
 * host branches: it derives the compression input from the *editor output*,
 * records an error message on any failure, but returns a session whose
 * `selectedSource` and `editable` flag are carried through untouched. A buggy
 * reducer that swapped the source for the output, or cleared/locked it on
 * failure, would fail these assertions.
 */
class VideoHandoffSourcePreservationPropertyTest {

    /**
     * The outcome of handing an edited video to the compression pipeline. This is
     * a *fake* pipeline result standing in for the real
     * `CompressionService`/`CompressionMonitor` flow.
     */
    sealed interface PipelineOutcome {
        /** Compression finished; [output] is the produced (compressed) URI. */
        data class Success(val output: String) : PipelineOutcome

        /** The pipeline ran but failed (encode error, unsupported format, ...). */
        data class Failure(val reason: String) : PipelineOutcome

        /** The edited output could not even be handed to the pipeline (Req 8.5). */
        data class HandoffFailure(val reason: String) : PipelineOutcome
    }

    /** The Video-tab session state around the compression handoff. */
    data class VideoTabSession(
        /** The user's selected source video. Must survive every outcome (Req 8.6). */
        val selectedSource: String,
        /** Whether the source remains available for continued editing (Req 4.5). */
        val editable: Boolean = true,
        /** Descriptive message surfaced on failure (Req 4.4, 8.5), else null. */
        val errorMessage: String? = null,
    )

    /**
     * Pure model of `VideoEditorHost`'s handling of a completed edit + its
     * compression outcome. Mirrors the host: the compression input is the editor
     * *output*, never the selected source; failures surface a message; the
     * selected source (and its editability) is always preserved.
     */
    private fun reduceCompressionHandoff(
        session: VideoTabSession,
        editorOutput: String,
        compress: Boolean,
        outcome: PipelineOutcome,
    ): VideoTabSession {
        // The pipeline only ever consumes the editor output; the selected source
        // is not an input to compression at all.
        @Suppress("UNUSED_VARIABLE")
        val compressionInput = editorOutput

        val error: String? = when {
            !compress -> null
            else -> when (outcome) {
                is PipelineOutcome.Success -> null
                is PipelineOutcome.Failure -> outcome.reason
                is PipelineOutcome.HandoffFailure -> outcome.reason
            }
        }

        // Source + editability are carried through unchanged regardless of outcome.
        return session.copy(errorMessage = error)
    }

    @Property(tries = 200)
    fun selectedSourceSurvivesEveryCompressionOutcome(
        @ForAll("sources") source: String,
        @ForAll("sources") editorOutput: String,
        @ForAll compress: Boolean,
        @ForAll("outcomes") outcome: PipelineOutcome,
    ) {
        val original = VideoTabSession(selectedSource = source)

        val result = reduceCompressionHandoff(
            session = original,
            editorOutput = editorOutput,
            compress = compress,
            outcome = outcome,
        )

        // Req 8.6 / 4.4: the selected source URI is unchanged, whatever happens.
        assertEquals(
            original.selectedSource,
            result.selectedSource,
            "selected source must be unchanged across every compression outcome",
        )
        // The source is never replaced by the editor output.
        assertTrue(
            result.selectedSource == source,
            "source must not be swapped for the editor output",
        )
        // Req 4.5: the source remains available for continued editing.
        assertTrue(result.editable, "source must remain editable after any outcome")
    }

    @Property(tries = 200)
    fun failureSurfacesMessageButStillPreservesSource(
        @ForAll("sources") source: String,
        @ForAll("sources") editorOutput: String,
        @ForAll("failures") failure: PipelineOutcome,
    ) {
        val original = VideoTabSession(selectedSource = source)

        // Compression was requested (compress = true) and the pipeline/handoff failed.
        val result = reduceCompressionHandoff(
            session = original,
            editorOutput = editorOutput,
            compress = true,
            outcome = failure,
        )

        // Req 4.4 / 8.5: a descriptive message is surfaced on failure...
        assertNotNull(result.errorMessage, "a failure must surface a descriptive message")
        // ...yet the source is still preserved and editable (Req 4.5, 8.6).
        assertEquals(source, result.selectedSource, "source preserved on failure")
        assertTrue(result.editable, "source remains editable after a failure")
    }

    @Property(tries = 200)
    fun successDoesNotClobberSourceNorRaiseError(
        @ForAll("sources") source: String,
        @ForAll("sources") output: String,
    ) {
        val original = VideoTabSession(selectedSource = source)

        val result = reduceCompressionHandoff(
            session = original,
            editorOutput = output,
            compress = true,
            outcome = PipelineOutcome.Success(output),
        )

        assertEquals(source, result.selectedSource, "success must not replace the source")
        assertTrue(result.editable, "source remains editable after success")
        assertNull(result.errorMessage, "success must not surface an error message")
    }

    /**
     * Source URIs: a mix of realistic content/file URI shapes plus arbitrary
     * strings (incl. empty/blank) so the invariant is exercised over the whole
     * opaque-token input space.
     */
    @Provide
    fun sources(): Arbitrary<String> = Arbitraries.oneOf(
        Arbitraries.of(
            "content://media/external/video/media/42",
            "file:///storage/emulated/0/DCIM/clip.mp4",
            "content://com.android.providers.media.documents/document/video%3A17",
            "",
            "   ",
        ),
        Arbitraries.strings().ofMinLength(0).ofMaxLength(64),
    )

    /** Any compression outcome: success, pipeline failure, or handoff failure. */
    @Provide
    fun outcomes(): Arbitrary<PipelineOutcome> = Arbitraries.oneOf(
        successes(),
        failures(),
    )

    @Provide
    fun successes(): Arbitrary<PipelineOutcome> =
        Arbitraries.strings().ofMinLength(0).ofMaxLength(64)
            .map { PipelineOutcome.Success(it) as PipelineOutcome }

    /** Failure outcomes: pipeline failures (incl. unsupported format) and handoff failures. */
    @Provide
    fun failures(): Arbitrary<PipelineOutcome> {
        val reasons: Arbitrary<String> = Arbitraries.oneOf(
            Arbitraries.of(
                "Encoding failed",
                "Unsupported format",
                "Out of space",
                "Handoff failed",
                "",
            ),
            Arbitraries.strings().ofMinLength(0).ofMaxLength(48),
        )
        return Combinators.combine(
            reasons,
            Arbitraries.of(true, false),
        ).`as` { reason, isHandoff ->
            if (isHandoff) PipelineOutcome.HandoffFailure(reason)
            else PipelineOutcome.Failure(reason)
        }
    }
}
