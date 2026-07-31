// Feature: media-editor-ux-fixes, Task 3.3: deletedSections persistence round-trip
package com.shanks.minify.ui.editor.model

import androidx.compose.runtime.saveable.SaverScope
import com.shanks.minify.ui.trim.TrimRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Example-based unit tests verifying that [MediaEditState.Saver] round-trips a
 * [VideoTimeline] carrying one or more [VideoTimeline.deletedSections] (Req 10.5).
 *
 * The Saver encodes `deletedSections` as an interleaved `LongArray`
 * `[start0, end0, start1, end1, ...]` (implemented in task 3.1). These tests build a
 * concrete [MediaEditState] with deleted sections, persist it through a permissive
 * [SaverScope] (mirroring `MediaEditStateSaverPropertyTest`), restore it, and assert
 * the restored timeline's `deletedSections` equals the original.
 *
 * **Validates: Requirements 10.5**
 */
class MediaEditStateDeletedSectionsSaverTest {

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

    @Test
    fun saverRoundTripsTimelineWithASingleDeletedSection() {
        val timeline = VideoTimeline(
            trim = TrimRange(startMs = 0L, endMs = 10_000L),
            splits = listOf(4_000L),
            deletedSections = listOf(Segment(startMs = 0L, endMs = 4_000L)),
        )
        val state = MediaEditState(mediaType = MediaType.VIDEO, timeline = timeline)

        val restored = roundTrip(state)

        assertEquals(
            timeline.deletedSections,
            restored.timeline?.deletedSections,
            "restored timeline must preserve the single deleted section",
        )
        assertEquals(state, restored, "the whole state must round-trip unchanged")
    }

    @Test
    fun saverRoundTripsTimelineWithMultipleDeletedSections() {
        val timeline = VideoTimeline(
            trim = TrimRange(startMs = 1_000L, endMs = 20_000L),
            splits = listOf(5_000L, 12_000L),
            deletedSections = listOf(
                Segment(startMs = 1_000L, endMs = 5_000L),
                Segment(startMs = 12_000L, endMs = 20_000L),
            ),
        )
        val state = MediaEditState(mediaType = MediaType.VIDEO, timeline = timeline)

        val restored = roundTrip(state)

        assertEquals(
            timeline.deletedSections,
            restored.timeline?.deletedSections,
            "restored timeline must preserve all deleted sections in order",
        )
        assertEquals(state, restored, "the whole state must round-trip unchanged")
    }
}
