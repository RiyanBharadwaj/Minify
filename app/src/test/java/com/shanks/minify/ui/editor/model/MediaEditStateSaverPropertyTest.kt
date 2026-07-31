// Feature: unified-media-editor, Property 25: The edit state round-trips through its Saver, preserving undo/redo availability
package com.shanks.minify.ui.editor.model

import androidx.compose.runtime.saveable.SaverScope
import com.shanks.minify.photo.ImageEditModel
import com.shanks.minify.ui.CropRect
import com.shanks.minify.ui.trim.TrimRange
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull

/**
 * Property-based tests for [MediaEditState] persistence.
 *
 * Feature: unified-media-editor, Property 25: The edit state round-trips through its Saver,
 * preserving undo/redo availability.
 *
 * The Media_Edit_State survives configuration changes and process death via `rememberSaveable`
 * through [MediaEditState.Saver] (Req 17.1). Restoring a state must also restore the undo/redo
 * availability consistent with that state (Req 17.4). Because the composite state and the pure
 * [EditHistory] carry no Android dependency, both the state round-trip and the preserved
 * `canUndo`/`canRedo` can be property-tested on the JVM. The Saver is exercised through a
 * permissive [SaverScope], mirroring the existing `ImageEditModelSaverPropertyTest`.
 */
class MediaEditStateSaverPropertyTest {

    /** A permissive [SaverScope]: everything is considered "can-be-saved". */
    private val saverScope = SaverScope { true }

    /** Save then restore a [MediaEditState] through its [MediaEditState.Saver]. */
    private fun roundTrip(state: MediaEditState): MediaEditState {
        val saved = with(MediaEditState.Saver) { saverScope.save(state) }
        assertNotNull(saved)
        val restored = MediaEditState.Saver.restore(saved!!)
        assertNotNull(restored)
        return restored!!
    }

    /**
     * Feature: unified-media-editor, Property 25: The edit state round-trips through its Saver,
     * preserving undo/redo availability.
     *
     * For any [MediaEditState] (either Media_Type, arbitrary geometry/color/timeline/photo),
     * saving via [MediaEditState.Saver] and then restoring produces an equal state (Req 17.1).
     *
     * **Validates: Requirements 17.1**
     */
    @Property(tries = 200)
    fun editStateRoundTripsThroughItsSaver(
        @ForAll("editStates") state: MediaEditState,
    ) {
        assertEquals(state, roundTrip(state))
    }

    /**
     * Feature: unified-media-editor, Property 25: The edit state round-trips through its Saver,
     * preserving undo/redo availability.
     *
     * For any [EditHistory] of [MediaEditState] values, round-tripping every recorded state
     * through the Saver reproduces an equal history: the same present state and the same
     * `canUndo`/`canRedo` availability (Req 17.1, 17.4).
     *
     * **Validates: Requirements 17.1, 17.4**
     */
    @Property(tries = 200)
    fun editHistoryRoundTripsPreservingUndoRedoAvailability(
        @ForAll("editHistories") history: EditHistory<MediaEditState>,
    ) {
        val restored = EditHistory(
            past = history.past.map { roundTrip(it) },
            present = roundTrip(history.present),
            future = history.future.map { roundTrip(it) },
        )

        // The present state is preserved exactly.
        assertEquals(history.present, restored.present)
        // The whole history (past + present + future) is preserved.
        assertEquals(history, restored)
        // Undo/redo availability is restored consistent with that state (Req 17.4).
        assertEquals(history.canUndo, restored.canUndo)
        assertEquals(history.canRedo, restored.canRedo)
    }

    // --- Generators ----------------------------------------------------------

    @Provide
    fun editStates(): Arbitrary<MediaEditState> =
        Arbitraries.oneOf(videoStates(), photoStates())

    /** An [EditHistory] whose recorded states all share one Media_Type. */
    @Provide
    fun editHistories(): Arbitrary<EditHistory<MediaEditState>> {
        val videoHistories = historyOf(videoStates())
        val photoHistories = historyOf(photoStates())
        return Arbitraries.oneOf(videoHistories, photoHistories)
    }

    private fun historyOf(states: Arbitrary<MediaEditState>): Arbitrary<EditHistory<MediaEditState>> {
        val pasts = states.list().ofMaxSize(4)
        val futures = states.list().ofMaxSize(4)
        return Combinators.combine(pasts, states, futures)
            .`as` { past, present, future ->
                EditHistory(past = past, present = present, future = future)
            }
    }

    private fun videoStates(): Arbitrary<MediaEditState> =
        Combinators.combine(geometries(), colorGrades(), timelines())
            .`as` { geometry, color, timeline ->
                MediaEditState(
                    mediaType = MediaType.VIDEO,
                    geometry = geometry,
                    color = color,
                    timeline = timeline,
                    photo = null,
                )
            }

    private fun photoStates(): Arbitrary<MediaEditState> =
        Combinators.combine(geometries(), colorGrades(), photoSettings())
            .`as` { geometry, color, photo ->
                MediaEditState(
                    mediaType = MediaType.PHOTO,
                    geometry = geometry,
                    color = color,
                    timeline = null,
                    photo = photo,
                )
            }

    // --- Sub-model generators ------------------------------------------------

    private fun geometries(): Arbitrary<ImageEditModel> {
        val rotations = Arbitraries.of(0, 90, 180, 270)
        val mirrors = Arbitraries.of(true, false)
        return Combinators.combine(rotations, mirrors, crops())
            .`as` { rotation, mirrored, crop ->
                ImageEditModel(rotationDegrees = rotation, mirrored = mirrored, crop = crop)
            }
    }

    private fun crops(): Arbitrary<CropRect> {
        val coord = Arbitraries.floats().between(0f, 1f)
        return Combinators.combine(coord, coord, coord, coord)
            .`as` { left, top, right, bottom -> CropRect(left, top, right, bottom) }
    }

    private fun colorGrades(): Arbitrary<ColorGrade> {
        val filters = Arbitraries.of(*Filter.entries.toTypedArray())
        val vignettes = Arbitraries.floats().between(0f, 1f)
        return Combinators.combine(adjustments(), filters, vignettes)
            .`as` { adjustments, filter, vignette ->
                ColorGrade(adjustments = adjustments, filter = filter, vignette = vignette)
            }
    }

    private fun adjustments(): Arbitrary<Adjustments> {
        // Build a full, in-range value map so the Saver's field-by-field encoding round-trips
        // exactly. Each kind draws a value within its own [min, max] range.
        val kinds = AdjustmentKind.entries
        val valueArbitraries: List<Arbitrary<Float>> = kinds.map { kind ->
            Arbitraries.floats().between(kind.min, kind.max)
        }
        return Combinators.combine(valueArbitraries).`as` { values ->
            val map = kinds.mapIndexed { index, kind -> kind to values[index] }.toMap()
            Adjustments(map)
        }
    }

    private fun timelines(): Arbitrary<VideoTimeline> {
        val trims = Combinators.combine(
            Arbitraries.longs().between(0L, 1_000_000L),
            Arbitraries.longs().between(1L, 1_000_000L),
        ).`as` { start, len -> TrimRange(startMs = start, endMs = start + len) }
        val splits = Arbitraries.longs().between(1L, 2_000_000L).list().ofMaxSize(5)
        val speeds = Arbitraries.of(*PlaybackSpeed.entries.toTypedArray())
        val volumes = Arbitraries.floats().between(0f, VideoTimeline.MAX_VOLUME)
        val muteds = Arbitraries.of(true, false)
        val reverses = Arbitraries.of(true, false)
        val freezes = freezeFrames().injectNull(0.4)

        return Combinators.combine(trims, splits, speeds, volumes, muteds, reverses, freezes)
            .`as` { trim, split, speed, volume, muted, reverse, freeze ->
                VideoTimeline(
                    trim = trim,
                    splits = split,
                    speed = speed,
                    volume = volume,
                    muted = muted,
                    reverse = reverse,
                    freeze = freeze,
                )
            }
    }

    private fun freezeFrames(): Arbitrary<FreezeFrame> =
        Combinators.combine(
            Arbitraries.longs().between(0L, 1_000_000L),
            Arbitraries.longs().between(0L, 1_000_000L),
        ).`as` { atMs, holdMs -> FreezeFrame(atMs = atMs, holdMs = holdMs) }

    private fun photoSettings(): Arbitrary<PhotoSettings> {
        val qualities = Arbitraries.of(*ExportQuality.entries.toTypedArray())
        val resizes = Combinators.combine(
            Arbitraries.integers().between(1, 10_000),
            Arbitraries.integers().between(1, 10_000),
        ).`as` { width, height -> OutputSize(width = width, height = height) }.injectNull(0.4)
        return Combinators.combine(qualities, resizes)
            .`as` { quality, resize -> PhotoSettings(quality = quality, resize = resize) }
    }
}
