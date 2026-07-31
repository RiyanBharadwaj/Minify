package com.shanks.minify.ui.trim

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.math.abs

/**
 * Property-based test for the alignment guarantee that lets every timeline layer share a single
 * [TimelineMapping]. The Video_Trimmer draws four layers over the same timeline:
 *
 *  - the filmstrip and the split markers position content by `contentWidthPx() * fractionOf(t)`
 *    (see `contentXPx` in VideoTrimmerScreen and the split marker loop in TrimPanel/TimelinePanel),
 *  - the ruler and the trim handles resolve screen positions by `timeToPx(t)`.
 *
 * Because they all read positions from the *same* mapping, a given time must land on the same
 * content coordinate for every layer, at every zoom scale. This test proves:
 *
 *  1. `timeToPx(t)` is a single deterministic function, so any number of layers calling it agree
 *     exactly.
 *  2. The `fractionOf`-based content position used by the filmstrip and split markers equals the
 *     `timeToPx`-based content position of the ruler and handles (within float rounding) at every
 *     zoom scale reached via `zoomBy`.
 *  3. Screen positions stay aligned: the fraction-based content x shifted by `scrollPx` equals
 *     `timeToPx(t)`.
 */
class TimelineSharedMappingAlignmentPropertyTest {

    // Feature: media-editor-ux-fixes, Property 17: Timeline layers derive positions from one shared mapping
    @Property(tries = 500)
    fun timelineLayersDerivePositionsFromOneSharedMapping(
        @ForAll("scenarios") scenario: AlignmentScenario,
    ) {
        // One shared mapping, then zoomed to an arbitrary scale: every layer reads from this.
        val base = TimelineMapping(
            durationMs = scenario.durationMs,
            pxPerMs = scenario.pxPerMs,
            scrollPx = scenario.scrollPx,
        )
        val mapping = base.zoomBy(scenario.zoomFactor)
        val timeMs = scenario.timeMs

        // The filmstrip and split markers lay content out with scrollPx = 0 (scroll is applied by
        // the surrounding ScrollState), exactly like contentXPx / the split-marker loop.
        val contentMapping = mapping.copy(scrollPx = 0f)
        val contentWidthPx = contentMapping.contentWidthPx().coerceAtLeast(0f)

        // ---- (1) timeToPx is a single deterministic function: all layers calling it agree. ----
        val rulerPx = mapping.timeToPx(timeMs)
        val trimHandlePx = mapping.timeToPx(timeMs)
        assertEquals(
            rulerPx,
            trimHandlePx,
            "ruler and trim-handle screen x for time=$timeMs must be identical (same mapping)",
        )

        // ---- (2) fraction-based content x (filmstrip, split markers) agrees with the ----
        // ---- timeToPx-based content x (ruler, handles) at this zoom scale.               ----
        val filmstripContentX = contentWidthPx * contentMapping.fractionOf(timeMs)
        val splitMarkerContentX = contentWidthPx * contentMapping.fractionOf(timeMs)
        // Content coordinate implied by the screen mapping is timeToPx(t) + scrollPx.
        val rulerContentX = mapping.timeToPx(timeMs) + mapping.scrollPx

        // Tolerance scales with the content magnitude to absorb float rounding in the
        // divide-then-multiply of fractionOf at large content widths.
        val tolerance = (contentWidthPx * CONTENT_REL_TOLERANCE + 1f).toDouble()

        assertEquals(
            filmstripContentX.toDouble(),
            splitMarkerContentX.toDouble(),
            "filmstrip and split-marker content x for time=$timeMs must be identical (same mapping)",
        )
        assertEquals(
            rulerContentX.toDouble(),
            filmstripContentX.toDouble(),
            tolerance,
            "filmstrip content x ($filmstripContentX) must align with ruler/handle content x " +
                "($rulerContentX) for time=$timeMs at pxPerMs=${mapping.pxPerMs}",
        )

        // ---- (3) Screen alignment: fraction-based content x minus scroll equals timeToPx. ----
        val filmstripScreenX = filmstripContentX - mapping.scrollPx
        assertTrue(
            abs(filmstripScreenX - rulerPx) <= tolerance,
            "filmstrip screen x ($filmstripScreenX) must equal ruler/handle timeToPx ($rulerPx) " +
                "for time=$timeMs at pxPerMs=${mapping.pxPerMs}, scrollPx=${mapping.scrollPx}",
        )

        // The closed form must also hold: timeToPx(t) == t * pxPerMs - scrollPx at any zoom.
        assertEquals(
            (timeMs * mapping.pxPerMs - mapping.scrollPx),
            rulerPx,
            "timeToPx($timeMs) must equal timeMs * pxPerMs - scrollPx at pxPerMs=${mapping.pxPerMs}",
        )
    }

    /**
     * A positive [durationMs], a positive finite base [pxPerMs], an arbitrary [scrollPx], a
     * [timeMs] in `[0, durationMs]`, and a [zoomFactor] applied via [TimelineMapping.zoomBy] so the
     * alignment is checked across many zoom scales. The scale bound keeps `contentWidthPx` within
     * a range where float rounding stays meaningful.
     */
    @Provide
    fun scenarios(): Arbitrary<AlignmentScenario> {
        val durations: Arbitrary<Long> = Arbitraries.longs().between(1L, 3_600_000L)
        val scales: Arbitrary<Float> =
            Arbitraries.floats().between(TimelineMapping.MIN_PX_PER_MS, 10f).ofScale(7)
                .filter { it > 0f && it.isFinite() }
        val scrolls: Arbitrary<Float> =
            Arbitraries.floats().between(-100_000f, 100_000f).ofScale(3).filter { it.isFinite() }
        val zoomFactors: Arbitrary<Float> =
            Arbitraries.floats().between(0.1f, 16f).ofScale(4).filter { it > 0f && it.isFinite() }

        return durations.flatMap { durationMs ->
            val times: Arbitrary<Long> = Arbitraries.longs().between(0L, durationMs)
            Combinators.combine(scales, scrolls, times, zoomFactors)
                .`as` { pxPerMs, scrollPx, timeMs, zoomFactor ->
                    AlignmentScenario(durationMs, pxPerMs, scrollPx, timeMs, zoomFactor)
                }
        }
    }

    data class AlignmentScenario(
        val durationMs: Long,
        val pxPerMs: Float,
        val scrollPx: Float,
        val timeMs: Long,
        val zoomFactor: Float,
    )

    companion object {
        /** Relative tolerance for aligning content x across the fraction and timeToPx paths. */
        private const val CONTENT_REL_TOLERANCE = 1e-4f
    }
}
