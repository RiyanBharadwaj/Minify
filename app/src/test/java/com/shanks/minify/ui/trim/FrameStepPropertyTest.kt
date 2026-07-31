package com.shanks.minify.ui.trim

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.math.roundToLong

/**
 * Property-based tests for [FrameStep], the pure frame-accurate scrubbing math
 * behind the video trimmer.
 *
 * The frame-step size is the reciprocal of the frame rate (`round(1000 / fps)` ms)
 * and stepping forward then backward from a position with at least one frame of
 * room on each side returns to the original position. Stepping past a bound clamps.
 */
class FrameStepPropertyTest {

    // Feature: media-editing-suite, Property 17: Frame-step size is the reciprocal of the frame rate and is reversible
    @Property(tries = 500)
    fun frameStepSizeIsReciprocalAndReversible(
        @ForAll("scenarios") scenario: StepScenario,
    ) {
        val (fps, minMs, posMs, maxMs) = scenario

        // Frame-step size equals round(1000 / fps) ms.
        val expectedStep = (1000.0 / fps).roundToLong().coerceAtLeast(1L)
        val step = FrameStep.frameStepMs(fps)
        assertEquals(
            expectedStep,
            step,
            "frameStepMs($fps) must equal round(1000/fps) = $expectedStep",
        )

        // Reversibility: with at least one frame of room on each side, a forward
        // step followed by a backward step is a no-op (no clamping occurs).
        val forward = FrameStep.stepForward(posMs, fps, minMs, maxMs)
        val roundTrip = FrameStep.stepBackward(forward, fps, minMs, maxMs)
        assertEquals(
            posMs,
            roundTrip,
            "stepBackward(stepForward($posMs)) must return to $posMs " +
                "(fps=$fps, step=$step, min=$minMs, max=$maxMs, forward=$forward)",
        )

        // Clamping at the upper bound: stepping forward from maxMs stays at maxMs.
        assertEquals(
            maxMs,
            FrameStep.stepForward(maxMs, fps, minMs, maxMs),
            "stepForward(maxMs=$maxMs) must clamp to maxMs (fps=$fps, step=$step)",
        )

        // Clamping at the lower bound: stepping backward from minMs stays at minMs.
        assertEquals(
            minMs,
            FrameStep.stepBackward(minMs, fps, minMs, maxMs),
            "stepBackward(minMs=$minMs) must clamp to minMs (fps=$fps, step=$step)",
        )
    }

    /**
     * A frame rate in `[1, 120]`, a window `[minMs, maxMs]`, and a position strictly
     * inside it with at least one frame-step of room on each side so that a forward
     * step and the following backward step never clamp.
     */
    @Provide
    fun scenarios(): Arbitrary<StepScenario> {
        val fpsArb: Arbitrary<Float> = Arbitraries.floats().between(1f, 120f)

        return fpsArb.flatMap { fps ->
            val step = FrameStep.frameStepMs(fps)

            // Lower bound anywhere from 0 up to ~1 hour.
            val minArb: Arbitrary<Long> = Arbitraries.longs().between(0L, 3_600_000L)

            minArb.flatMap { minMs ->
                // At least one full step of room below the position.
                val roomBelow: Arbitrary<Long> =
                    Arbitraries.longs().between(step, step + 10_000L)
                // At least one full step of room above the position.
                val roomAbove: Arbitrary<Long> =
                    Arbitraries.longs().between(step, step + 10_000L)

                Combinators.combine(roomBelow, roomAbove).`as` { below, above ->
                    val posMs = minMs + below
                    val maxMs = posMs + above
                    StepScenario(fps, minMs, posMs, maxMs)
                }
            }
        }
    }

    data class StepScenario(
        val fps: Float,
        val minMs: Long,
        val posMs: Long,
        val maxMs: Long,
    )
}
