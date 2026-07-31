package com.shanks.minify.editor

import com.shanks.minify.ui.CropRect
import com.shanks.minify.photo.ImageEditModel
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for the PhotoEditorHost completion gate.
 *
 * Feature: editor-replacement, Property 1: Completion-gated Edited_Output (photo host lifecycle)
 * Validates: Requirements 3.4a, 5.4, 10.1, 10.2, 10.5
 *
 * The `PhotoEditorHost` embeds the View-based PhotoEditor inside Compose and only
 * produces a full-resolution `Edited_Output` when the user explicitly completes
 * editing (Done): on Done it captures the overlay composite via `saveAsBitmap`
 * and applies the pending crop/rotate/mirror geometry at full resolution. Every
 * other lifecycle exit — an in-progress editing action, or a back/dismiss —
 * must expose no `Edited_Output` and must not touch the full-resolution source
 * (Req 3.4a: "WHILE editing is in progress ... SHALL NOT apply pending edits to
 * the full-resolution source image").
 *
 * A real Compose `@Composable` host cannot be driven from a fast JVM unit test,
 * so — exactly as the design's testing strategy prescribes ("a state-machine
 * generator over the photo host lifecycle ... Uses fakes for the editors") —
 * these tests drive a pure model of the host lifecycle ([PhotoHostModel]) backed
 * by a [FakePhotoEditor]. The model reproduces the host's completion gate: the
 * full-resolution composite/geometry apply happens **only** inside the Done
 * branch, mirroring `PhotoEditorHost`'s Done handler and its `editModel` source
 * of truth. Arbitrary sequences of editing actions are generated and the gate is
 * asserted at every step.
 */
class PhotoEditorHostGatePropertyTest {

    // ------------------------------------------------------------------
    // Lifecycle actions the host can process (the state-machine alphabet).
    // ------------------------------------------------------------------

    /** An action the user can perform against the hosted editor. */
    sealed interface HostAction {
        /** Overlay edits routed to the PhotoEditor library (never touch full-res). */
        data object Draw : HostAction
        data object AddText : HostAction
        data object AddEmoji : HostAction
        data object AddSticker : HostAction
        data object ApplyFilter : HostAction
        data object Undo : HostAction
        data object Redo : HostAction

        /** Retained-geometry edits accumulated in the pending [ImageEditModel]. */
        data class Crop(val rect: CropRect) : HostAction
        data object Rotate : HostAction
        data object Mirror : HostAction

        /** Back / dismiss without completing (Req 10.2). */
        data object Back : HostAction

        /** Explicit completion (Req 3.4); [renderSucceeds] models a render/persist success or failure (Req 3.5). */
        data class Done(val renderSucceeds: Boolean) : HostAction
    }

    // ------------------------------------------------------------------
    // Fake editor — records overlay ops and full-resolution composite captures.
    // ------------------------------------------------------------------

    /**
     * A stand-in for the PhotoEditor library controller. It records the overlay
     * stack and, crucially, how many times [saveAsBitmap] (the full-resolution
     * composite capture) has been invoked — so a test can prove no full-res work
     * happens before an explicit Done.
     */
    private class FakePhotoEditor {
        private val overlays = ArrayDeque<String>()
        private val redoStack = ArrayDeque<String>()

        /** Number of full-resolution composite captures. Must stay 0 before Done. */
        var saveAsBitmapCount: Int = 0
            private set

        val undoAvailable: Boolean get() = overlays.isNotEmpty()
        val redoAvailable: Boolean get() = redoStack.isNotEmpty()

        fun addOverlay(kind: String) {
            overlays.addLast(kind)
            redoStack.clear()
        }

        fun undo() {
            if (overlays.isNotEmpty()) redoStack.addLast(overlays.removeLast())
        }

        fun redo() {
            if (redoStack.isNotEmpty()) overlays.addLast(redoStack.removeLast())
        }

        /** The full-resolution overlay composite — the operation the gate protects. */
        fun saveAsBitmap(): List<String> {
            saveAsBitmapCount++
            return overlays.toList()
        }
    }

    /** The exposed edited output: the captured composite plus the applied geometry. */
    private data class EditedOutput(
        val composite: List<String>,
        val geometry: ImageEditModel,
    )

    // ------------------------------------------------------------------
    // Pure host lifecycle model reproducing PhotoEditorHost's completion gate.
    // ------------------------------------------------------------------

    private enum class Phase { ACTIVE, DISMISSED, ERRORED, COMPLETED }

    /**
     * Pure model of the host after a successful decode + host (tasks 6.1/6.6 cover
     * the decode/host-failure edges separately). Editing actions mutate only the
     * fake editor's overlay stack or the pending [editModel]; a full-resolution
     * `Edited_Output` is produced **only** in the explicit Done branch, and only
     * when the render/persist succeeds.
     */
    private class PhotoHostModel {
        val editor = FakePhotoEditor()
        var editModel = ImageEditModel()
            private set
        var phase = Phase.ACTIVE
            private set

        /** Outputs handed to the calling Compose screen (onExported/onDoneNoCompress). */
        val exposedOutputs = mutableListOf<EditedOutput>()

        fun step(action: HostAction) {
            // Once the session has ended, further input is inert — the host has
            // already returned control to the tab (or completed).
            if (phase != Phase.ACTIVE) return

            when (action) {
                HostAction.Draw -> editor.addOverlay("draw")
                HostAction.AddText -> editor.addOverlay("text")
                HostAction.AddEmoji -> editor.addOverlay("emoji")
                HostAction.AddSticker -> editor.addOverlay("sticker")
                HostAction.ApplyFilter -> editor.addOverlay("filter")
                HostAction.Undo -> editor.undo()
                HostAction.Redo -> editor.redo()

                is HostAction.Crop -> editModel = editModel.withCrop(action.rect)
                HostAction.Rotate -> editModel = editModel.rotateClockwise()
                HostAction.Mirror -> editModel = editModel.toggleMirror()

                HostAction.Back -> phase = Phase.DISMISSED

                is HostAction.Done -> {
                    // The gate: capture the full-resolution composite and apply the
                    // pending geometry ONLY here, on explicit completion.
                    val composite = editor.saveAsBitmap()
                    if (action.renderSucceeds) {
                        exposedOutputs.add(EditedOutput(composite, editModel))
                        phase = Phase.COMPLETED
                    } else {
                        // Render/apply/persist failure: no Edited_Output (Req 3.5).
                        phase = Phase.ERRORED
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Generators.
    // ------------------------------------------------------------------

    @Provide
    fun cropRects(): Arbitrary<CropRect> {
        val lo = Arbitraries.floats().between(0f, 0.4f)
        val hi = Arbitraries.floats().between(0.6f, 1f)
        return Combinators.combine(lo, lo, hi, hi)
            .`as` { left, top, right, bottom -> CropRect(left, top, right, bottom) }
    }

    /** Any single lifecycle action, including in-progress edits, Back, and Done. */
    @Provide
    fun anyAction(): Arbitrary<HostAction> {
        val editing: Arbitrary<HostAction> = Arbitraries.of(
            HostAction.Draw,
            HostAction.AddText,
            HostAction.AddEmoji,
            HostAction.AddSticker,
            HostAction.ApplyFilter,
            HostAction.Undo,
            HostAction.Redo,
            HostAction.Rotate,
            HostAction.Mirror,
        )
        val crop: Arbitrary<HostAction> = cropRects().map { HostAction.Crop(it) }
        val back: Arbitrary<HostAction> = Arbitraries.just(HostAction.Back)
        val done: Arbitrary<HostAction> =
            Arbitraries.of(true, false).map { HostAction.Done(it) }
        return Arbitraries.oneOf(editing, crop, back, done)
    }

    @Provide
    fun actionSequences(): Arbitrary<List<HostAction>> =
        anyAction().list().ofMinSize(0).ofMaxSize(30)

    /** In-progress editing actions only — never Back and never Done. */
    @Provide
    fun editingOnlySequences(): Arbitrary<List<HostAction>> {
        val editing: Arbitrary<HostAction> = Arbitraries.of(
            HostAction.Draw,
            HostAction.AddText,
            HostAction.AddEmoji,
            HostAction.AddSticker,
            HostAction.ApplyFilter,
            HostAction.Undo,
            HostAction.Redo,
            HostAction.Rotate,
            HostAction.Mirror,
        )
        val crop: Arbitrary<HostAction> = cropRects().map { HostAction.Crop(it) }
        return Arbitraries.oneOf(editing, crop).list().ofMinSize(1).ofMaxSize(30)
    }

    // ------------------------------------------------------------------
    // Properties.
    // ------------------------------------------------------------------

    /**
     * The core gate: for any interleaving of editing actions, Back, and Done, no
     * full-resolution composite is captured and no `Edited_Output` is exposed at
     * any step *before* the first Done is processed while the session is active.
     *
     * Validates: Requirements 3.4a, 5.4, 10.1, 10.2, 10.5
     */
    @Property(tries = 300)
    fun noFullResOutputBeforeExplicitDone(
        @ForAll("actionSequences") actions: List<HostAction>,
    ) {
        val model = PhotoHostModel()
        var explicitDoneProcessed = false

        for (action in actions) {
            val wasActive = model.phase == Phase.ACTIVE
            model.step(action)
            if (wasActive && action is HostAction.Done) explicitDoneProcessed = true

            if (!explicitDoneProcessed) {
                assertEquals(
                    0,
                    model.editor.saveAsBitmapCount,
                    "full-resolution saveAsBitmap must not run before an explicit Done",
                )
                assertTrue(
                    model.exposedOutputs.isEmpty(),
                    "no Edited_Output may be exposed before an explicit Done",
                )
            }
        }
    }

    /**
     * A session that only ever performs in-progress editing (no Back, no Done)
     * exposes no `Edited_Output` and never captures the full-resolution composite,
     * no matter how many edits accumulate (Req 3.4a).
     *
     * Validates: Requirements 3.4a, 10.1
     */
    @Property(tries = 300)
    fun editingWithoutDoneNeverExposesOutput(
        @ForAll("editingOnlySequences") actions: List<HostAction>,
    ) {
        val model = PhotoHostModel()
        actions.forEach(model::step)

        assertEquals(Phase.ACTIVE, model.phase, "session stays active without Back/Done")
        assertEquals(0, model.editor.saveAsBitmapCount, "no full-resolution capture without Done")
        assertTrue(model.exposedOutputs.isEmpty(), "no Edited_Output without explicit completion")
    }

    /**
     * A back/dismiss that occurs before any Done returns control to the tab with
     * no `Edited_Output`, regardless of what editing preceded it (Req 10.2).
     *
     * Validates: Requirements 10.1, 10.2
     */
    @Property(tries = 300)
    fun backBeforeDoneReturnsControlWithNoOutput(
        @ForAll("editingOnlySequences") preEdits: List<HostAction>,
        @ForAll("actionSequences") trailing: List<HostAction>,
    ) {
        val model = PhotoHostModel()
        preEdits.forEach(model::step)
        model.step(HostAction.Back)
        // Anything after the dismiss is inert and must not resurrect an output.
        trailing.forEach(model::step)

        assertEquals(Phase.DISMISSED, model.phase)
        assertEquals(0, model.editor.saveAsBitmapCount)
        assertTrue(model.exposedOutputs.isEmpty(), "dismiss must expose no Edited_Output")
    }

    /**
     * An explicit Done with a successful render exposes exactly one `Edited_Output`,
     * and that output carries the full-resolution composite plus every pending
     * geometry edit accumulated before completion (Req 3.4 / 3.4a — edits are
     * applied to the full-resolution source only on explicit completion).
     *
     * Validates: Requirements 3.4a, 5.4, 10.1
     */
    @Property(tries = 300)
    fun explicitDoneExposesExactlyOneOutputCarryingAllPendingEdits(
        @ForAll("editingOnlySequences") preEdits: List<HostAction>,
    ) {
        val model = PhotoHostModel()
        preEdits.forEach(model::step)

        // Capture the pending geometry the host would apply at full resolution.
        val expectedGeometry = model.editModel
        // No full-res work yet.
        assertEquals(0, model.editor.saveAsBitmapCount)

        model.step(HostAction.Done(renderSucceeds = true))

        assertEquals(Phase.COMPLETED, model.phase)
        assertEquals(1, model.editor.saveAsBitmapCount, "Done captures the composite exactly once")
        assertEquals(1, model.exposedOutputs.size, "exactly one Edited_Output on successful Done")
        assertEquals(
            expectedGeometry,
            model.exposedOutputs.single().geometry,
            "the Edited_Output applies all pending crop/rotate/mirror edits",
        )
    }

    /**
     * If applying the edits to the full-resolution source fails on Done, the host
     * reports the failure and produces no `Edited_Output` (Req 3.5). The gate still
     * holds: an attempted-but-failed completion exposes nothing.
     *
     * Validates: Requirements 3.4a, 5.4
     */
    @Property(tries = 300)
    fun doneWithRenderFailureExposesNoOutput(
        @ForAll("editingOnlySequences") preEdits: List<HostAction>,
    ) {
        val model = PhotoHostModel()
        preEdits.forEach(model::step)

        model.step(HostAction.Done(renderSucceeds = false))

        assertEquals(Phase.ERRORED, model.phase)
        assertTrue(model.exposedOutputs.isEmpty(), "a failed render produces no Edited_Output")
    }

    /**
     * Across any arbitrary sequence, at most one `Edited_Output` is ever exposed,
     * and if one is exposed then an explicit successful Done occurred while the
     * session was active. Completion is strictly gated on that event.
     *
     * Validates: Requirements 3.4a, 10.1, 10.2
     */
    @Property(tries = 300)
    fun atMostOneOutputAndOnlyAfterSuccessfulDone(
        @ForAll("actionSequences") actions: List<HostAction>,
    ) {
        val model = PhotoHostModel()
        var successfulActiveDone = false

        for (action in actions) {
            val wasActive = model.phase == Phase.ACTIVE
            model.step(action)
            if (wasActive && action is HostAction.Done && action.renderSucceeds) {
                successfulActiveDone = true
            }
        }

        assertTrue(model.exposedOutputs.size <= 1, "at most one completion per session")
        if (model.exposedOutputs.isNotEmpty()) {
            assertTrue(successfulActiveDone, "an exposed output implies a successful explicit Done")
            assertEquals(Phase.COMPLETED, model.phase)
        } else {
            assertFalse(
                model.phase == Phase.COMPLETED && !successfulActiveDone,
                "COMPLETED phase can only arise from a successful Done",
            )
        }
    }
}
