package com.shanks.minify.ui.compare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit test for single-source comparator audio (Requirement 7.4).
 *
 * The [VideoComparator] hosts two players and routes audio from only the "after"
 * (edited) source while muting the "before" (original) source so no echo occurs.
 * The two audible states are fixed at construction from the pure
 * [BEFORE_VOLUME] / [AFTER_VOLUME] constants and never mutated by the playback
 * controls, so asserting that exactly one of them is non-zero verifies the
 * "exactly one audible" invariant without a running Composable or ExoPlayer.
 */
class SingleSourceComparatorAudioTest {

    // Feature: media-editor-ux-fixes, Requirement 7.4: exactly one comparison source is audible
    @Test
    fun exactlyOneSourceIsAudible() {
        val nonZeroCount = listOf(BEFORE_VOLUME, AFTER_VOLUME).count { it != 0f }

        assertEquals(
            1,
            nonZeroCount,
            "Exactly one of BEFORE_VOLUME/AFTER_VOLUME must be non-zero so audio is routed " +
                "from a single source and no echo occurs (was BEFORE_VOLUME=$BEFORE_VOLUME, " +
                "AFTER_VOLUME=$AFTER_VOLUME)",
        )
    }

    // Feature: media-editor-ux-fixes, Requirement 7.4: audio comes from the "after" edited source, "before" is muted
    @Test
    fun afterSourceIsAudibleAndBeforeSourceIsMuted() {
        // Audio is routed from only the "after" edited source.
        assertNotEquals(0f, AFTER_VOLUME, "the \"after\" edited source must carry audio")
        assertTrue(AFTER_VOLUME > 0f, "the \"after\" edited source volume must be positive, was $AFTER_VOLUME")

        // The "before" original source is muted to avoid echo.
        assertEquals(0f, BEFORE_VOLUME, 0f, "the \"before\" original source must be muted")
    }
}
