package com.shanks.minify.ui

import androidx.compose.runtime.saveable.SaverScope
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull

/**
 * Property-based tests for [EditState] persistence.
 *
 * Feature: media-editing-suite, Property 24: EditState round-trips through its Saver
 *
 * [EditState] survives activity recreation via `rememberSaveable` through the reused
 * [EditState.Saver], a compose [androidx.compose.runtime.saveable.Saver] that encodes to
 * `floatArrayOf(trimStartMs, trimEndMs (-1 = null), left, top, right, bottom (-1 each = no crop))`.
 * Saving and then restoring must reproduce an equal [EditState].
 */
class EditStateSaverPropertyTest {

    /** A permissive [SaverScope]: everything is considered "can-be-saved". */
    private val saverScope = SaverScope { true }

    /**
     * Feature: media-editing-suite, Property 24: EditState round-trips through its Saver
     *
     * For any [EditState] (trimStartMs, nullable trimEndMs, nullable cropRect), saving via
     * [EditState.Saver] and then restoring produces an equal [EditState].
     *
     * **Validates: Requirements 18.5**
     */
    @Property(tries = 500)
    fun editStateRoundTripsThroughItsSaver(
        @ForAll("editStates") state: EditState,
    ) {
        val saved = with(EditState.Saver) { saverScope.save(state) }
        assertNotNull(saved)

        val restored = EditState.Saver.restore(saved!!)

        assertEquals(state, restored)
    }

    @Provide
    fun editStates(): Arbitrary<EditState> {
        // The Saver encodes trim positions as Float, so integers must stay within the
        // exactly-representable range of a 32-bit float (|x| < 2^24) to survive the
        // Long -> Float -> Long round-trip. 16,000,000 ms (~4.4h) is well inside that.
        val trimStarts = Arbitraries.longs().between(0L, 16_000_000L)
        // trimEndMs is nullable; the Saver stores null as the -1 sentinel, so non-null
        // values must be >= 0.
        val trimEnds = Arbitraries.longs().between(0L, 16_000_000L).injectNull(0.3)
        val crops = crops().injectNull(0.3)
        val splits = Arbitraries.longs().between(0L, 16_000_000L).list().ofMaxSize(10)

        return Combinators.combine(trimStarts, trimEnds, crops, splits)
            .`as` { start, end, crop, s ->
                EditState(trimStartMs = start, trimEndMs = end, cropRect = crop, splits = s)
            }
    }

    @Provide
    fun crops(): Arbitrary<CropRect> {
        // Coordinates in [0,1]; left >= 0 keeps them distinct from the -1 "no crop" sentinel.
        val coord = Arbitraries.floats().between(0f, 1f)
        return Combinators.combine(coord, coord, coord, coord)
            .`as` { left, top, right, bottom -> CropRect(left, top, right, bottom) }
    }
}
