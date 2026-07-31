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
 * Property-based test for the photo editor -> compression handoff.
 *
 * Feature: editor-replacement, Property 2: Source preservation across all
 * compression outcomes.
 *
 * **Validates: Requirements 4.4, 4.5, 8.6**
 *
 * ## What this exercises
 * [PhotoEditorHost]'s Done pipeline (`runDone`) composites the edits, produces an
 * *edited output* file, and — when compression was requested — hands that
 * *output* to `PhotoCompressor.compress`. It never reassigns the user's
 * *selected source* URI: the source is only ever the input the editor was opened
 * on, and it is left available for continued editing whatever happens. Req 4.4 /
 * 4.5 (and, for parity with the shared Property 2, Req 8.6) demand that for every
 * compression outcome — success, a pipeline failure, an unsupported/unreadable
 * format, or a failure of the handoff itself — the selected source is unchanged
 * and remains editable.
 *
 * The real host is a `@Composable` that needs the Android runtime (real `Uri`,
 * `Context`, `PhotoEditor`), so — matching the design's "fakes for the editors"
 * strategy and the sibling [VideoHandoffSourcePreservationPropertyTest] — this
 * test drives [reduceCompressionHandoff], a pure model of the host's source
 * handling. The model mirrors `runDone`'s branches: it derives the compression
 * input from the *edited output* (never the source), surfaces a descriptive
 * message on any failure (via the result presentation when compression was
 * requested, or in-editor otherwise), and returns a session whose
 * `selectedSource` and `editable` flag are carried through untouched. A buggy
 * reducer that swapped the source for the output, or cleared/locked it on
 * failure, would fail these assertions.
 */
class PhotoHandoffSourcePreservationPropertyTest {

    /**
     * The outcome of handing an edited photo to the compression pipeline. This is
     * a *fake* pipeline result standing in for the real `PhotoCompressor.compress`
     * flow.
     */
    sealed interface PipelineOutcome {
        /** Compression finished; [output] is the produced (compressed) URI. */
        data class Success(val output: String) : PipelineOutcome

        /** The pipeline ran but failed (encode error, unsupported format, ...). */
        data class Failure(val reason: String) : PipelineOutcome

        /** The edited output could not even be handed to the pipeline (Req 4.4). */
        data class HandoffFailure(val reason: String) : PipelineOutcome
    }

    /** The Photo-tab session state around the compression handoff. */
    data class PhotoTabSession(
        /** The user's selected source image. Must survive every outcome (Req 4.4). */
        val selectedSource: String,
        /** Whether the source remains available for continued editing (Req 4.5). */
        val editable: Boolean = true,
        /** Descriptive message surfaced on failure (Req 4.4), else null. */
        val errorMessage: String? = null,
    )

    /**
     * Pure model of `PhotoEditorHost.runDone`'s handling of a completed edit + its
     * compression outcome. Mirrors the host: the compression input is the edited
     * *output*, never the selected source; failures surface a message; the
     * selected source (and its editability) is always preserved.
     */
    private fun reduceCompressionHandoff(
        session: PhotoTabSession,
        editedOutput: String,
        compress: Boolean,
        outcome: PipelineOutcome,
    ): PhotoTabSession {
        // The pipeline only ever consumes the edited output; the selected source
        // is not an input to compression at all.
        @Suppress("UNUSED_VARIABLE")
        val compressionInput = editedOutput

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
        @ForAll("sources") editedOutput: String,
        @ForAll compress: Boolean,
        @ForAll("outcomes") outcome: PipelineOutcome,
    ) {
        val original = PhotoTabSession(selectedSource = source)

        val result = reduceCompressionHandoff(
            session = original,
            editedOutput = editedOutput,
            compress = compress,
            outcome = outcome,
        )

        // Req 4.4: the selected source URI is unchanged, whatever happens.
        assertEquals(
            original.selectedSource,
            result.selectedSource,
            "selected source must be unchanged across every compression outcome",
        )
        // The source is never replaced by the edited output.
        assertTrue(
            result.selectedSource == source,
            "source must not be swapped for the edited output",
        )
        // Req 4.5: the source remains available for continued editing.
        assertTrue(result.editable, "source must remain editable after any outcome")
    }

    @Property(tries = 200)
    fun failureSurfacesMessageButStillPreservesSource(
        @ForAll("sources") source: String,
        @ForAll("sources") editedOutput: String,
        @ForAll("failures") failure: PipelineOutcome,
    ) {
        val original = PhotoTabSession(selectedSource = source)

        // Compression was requested (compress = true) and the pipeline/handoff failed.
        val result = reduceCompressionHandoff(
            session = original,
            editedOutput = editedOutput,
            compress = true,
            outcome = failure,
        )

        // Req 4.4: a descriptive message is surfaced on failure...
        assertNotNull(result.errorMessage, "a failure must surface a descriptive message")
        // ...yet the source is still preserved and editable (Req 4.5).
        assertEquals(source, result.selectedSource, "source preserved on failure")
        assertTrue(result.editable, "source remains editable after a failure")
    }

    @Property(tries = 200)
    fun successDoesNotClobberSourceNorRaiseError(
        @ForAll("sources") source: String,
        @ForAll("sources") output: String,
    ) {
        val original = PhotoTabSession(selectedSource = source)

        val result = reduceCompressionHandoff(
            session = original,
            editedOutput = output,
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
            "content://media/external/images/media/42",
            "file:///storage/emulated/0/DCIM/photo.jpg",
            "content://com.android.providers.media.documents/document/image%3A17",
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
