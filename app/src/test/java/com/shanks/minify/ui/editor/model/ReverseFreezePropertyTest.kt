package com.shanks.minify.ui.editor.model

import com.shanks.minify.ui.trim.TrimRange
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

/**
 * Property-based tests for [VideoTimeline.withReverse] and [VideoTimeline.withFreeze], the pure
 * recording of the unified editor's optional reverse (Req 9.2) and freeze-frame (Req 9.4) edits.
 *
 * These effects are optional tools: when applied they must be recorded faithfully on the timeline,
 * and clearing the freeze (passing `null`) must remove any previously recorded effect.
 */
class ReverseFreezePropertyTest {

    // Feature: unified-media-editor, Property 19: Optional reverse and freeze-frame edits are recorded when applied
    /**
     * Feature: unified-media-editor, Property 19: Optional reverse and freeze-frame edits are
     * recorded when applied.
     *
     * For any valid [VideoTimeline], any boolean reverse flag, and any [FreezeFrame]:
     * `withReverse(b).reverse == b`; `withFreeze(ff).freeze == ff`; and `withFreeze(null).freeze`
     * is `null` (clearing the effect).
     *
     * **Validates: Requirements 9.2, 9.4**
     */
    @Property(tries = 300)
    fun optionalReverseAndFreezeFrameEditsAreRecordedWhenApplied(
        @ForAll("trims") trim: TrimRange,
        @ForAll reverse: Boolean,
        @ForAll("freezes") freeze: FreezeFrame,
    ) {
        val timeline = VideoTimeline(trim = trim)

        // Reverse is recorded exactly as applied.
        assertEquals(
            reverse,
            timeline.withReverse(reverse).reverse,
            "withReverse($reverse) must record reverse=$reverse",
        )

        // A freeze-frame effect is recorded exactly as applied.
        assertEquals(
            freeze,
            timeline.withFreeze(freeze).freeze,
            "withFreeze($freeze) must record the freeze effect",
        )

        // Clearing the freeze removes any previously recorded effect.
        val withThenCleared = timeline.withFreeze(freeze).withFreeze(null)
        assertNull(
            withThenCleared.freeze,
            "withFreeze(null) must clear a previously recorded freeze effect",
        )
    }

    /**
     * A valid [TrimRange]: whole ms, `startMs < endMs`, at least the 500ms minimum duration.
     */
    @Provide
    fun trims(): Arbitrary<TrimRange> {
        val starts = Arbitraries.longs().between(0L, 100_000L)
        val extraDurations = Arbitraries.longs().between(TrimRange.MIN_DURATION_MS, 50_000L)
        return Combinators.combine(starts, extraDurations)
            .`as` { start, extra -> TrimRange(startMs = start, endMs = start + extra) }
    }

    /**
     * Arbitrary [FreezeFrame] values with `atMs >= 0` and `holdMs >= 0`, spanning zero and large
     * positions/holds to exercise the full valid input space.
     */
    @Provide
    fun freezes(): Arbitrary<FreezeFrame> {
        val atMs = Arbitraries.longs().between(0L, 1_000_000L)
        val holdMs = Arbitraries.longs().between(0L, 1_000_000L)
        return Combinators.combine(atMs, holdMs)
            .`as` { at, hold -> FreezeFrame(atMs = at, holdMs = hold) }
    }
}
