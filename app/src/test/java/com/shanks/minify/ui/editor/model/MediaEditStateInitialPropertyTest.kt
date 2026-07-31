package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.CropRect
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based tests for the fresh-state neutrality guarantee of
 * [MediaEditState.initial]: opening the editor on any Media_Item must present a
 * fully neutral state carrying no visible edit (Req 2.5).
 *
 * For any [MediaType] and any non-negative full duration, the freshly created
 * state must report [MediaEditState.isInitial] `== true`, have a neutral
 * [ColorGrade], an identity geometry (rotation `0`, not mirrored, full crop),
 * and the correct type-specific no-edit sub-model: a [VideoTimeline] with no
 * trim/split/speed/volume/mute edits for [MediaType.VIDEO], or the default
 * [PhotoSettings] for [MediaType.PHOTO].
 */
class MediaEditStateInitialPropertyTest {

    // Feature: unified-media-editor, Property 2: A fresh edit state is fully neutral
    @Property(tries = 200)
    fun freshStateIsFullyNeutral(
        @ForAll("initialStates") state: MediaEditState,
    ) {
        // The single fact the editor relies on to know nothing has been edited yet.
        assertTrue(state.isInitial, "a fresh initial state must be neutral: $state")

        // Color grade is neutral.
        assertTrue(state.color.isNeutral, "fresh color grade must be neutral: ${state.color}")

        // Geometry is the identity: no rotation, no mirror, full crop.
        assertEquals(0, state.geometry.rotationDegrees, "fresh geometry must not be rotated")
        assertFalse(state.geometry.mirrored, "fresh geometry must not be mirrored")
        assertEquals(CropRect.FULL, state.geometry.crop, "fresh geometry must have the full crop")

        // Exactly the type-specific no-edit sub-model is present.
        when (state.mediaType) {
            MediaType.VIDEO -> {
                val timeline = state.timeline
                assertNotNull(timeline, "a fresh video state must carry a timeline")
                assertNull(state.photo, "a fresh video state must not carry photo settings")
                assertEquals(0L, timeline!!.trim.startMs, "fresh video trim must start at 0")
                assertTrue(
                    timeline.isNoEdit,
                    "fresh video timeline must carry no split/speed/volume/mute edits: $timeline",
                )
            }

            MediaType.PHOTO -> {
                assertNull(state.timeline, "a fresh photo state must not carry a timeline")
                assertEquals(
                    PhotoSettings(),
                    state.photo,
                    "a fresh photo state must carry the default photo settings",
                )
            }
        }
    }

    /**
     * Fresh states for both media types across a wide, non-negative duration
     * range, including the zero-duration boundary. `initial` coerces any negative
     * input to zero, so negative durations are also exercised to confirm the
     * coercion still yields a neutral state.
     */
    @Provide
    fun initialStates(): Arbitrary<MediaEditState> {
        val types: Arbitrary<MediaType> =
            Arbitraries.of(*MediaType.entries.toTypedArray())
        // Mix negative, zero, and large positive durations to exercise coercion.
        val durations: Arbitrary<Long> = Arbitraries.longs().between(-1_000L, 24L * 60 * 60 * 1000)
        return Combinators.combine(types, durations)
            .`as` { type, duration -> MediaEditState.initial(type, duration) }
    }
}
