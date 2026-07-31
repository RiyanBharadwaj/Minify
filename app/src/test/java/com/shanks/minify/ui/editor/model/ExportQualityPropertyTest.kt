package com.shanks.minify.ui.editor.model

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Property-based and unit tests for [ExportQuality] selection recording.
 *
 * Feature: unified-media-editor, Property 21: Export quality selection is recorded
 *
 * Selecting an [ExportQuality] on a photo [PhotoSettings] must record exactly
 * that value (Req 10.2), and each quality maps to a concrete target-size budget
 * handed to the photo compressor (Req 10.1).
 */
class ExportQualityPropertyTest {

    // Feature: unified-media-editor, Property 21: Export quality selection is recorded
    /**
     * For any [ExportQuality] selection, the [PhotoSettings.quality] recorded in
     * the Media_Edit_State equals the selected value.
     *
     * **Validates: Requirements 10.2**
     */
    @Property(tries = 100)
    fun selectedQualityIsRecorded(@ForAll("qualities") selected: ExportQuality) {
        val settings = PhotoSettings(quality = selected)

        assertEquals(
            selected,
            settings.quality,
            "PhotoSettings must record the selected quality $selected, was ${settings.quality}",
        )
    }

    @Provide
    fun qualities(): Arbitrary<ExportQuality> =
        Arbitraries.of(*ExportQuality.entries.toTypedArray())

    /**
     * The concrete [ExportQuality] to target-megabyte mapping handed to the
     * photo compression pipeline (Req 10.1).
     */
    @Test
    fun exportQualityMapsToConcreteTargetMb() {
        assertEquals(8f, ExportQuality.HIGH.targetSizeMb, "HIGH must map to 8 MB")
        assertEquals(4f, ExportQuality.MEDIUM.targetSizeMb, "MEDIUM must map to 4 MB")
        assertEquals(2f, ExportQuality.LOW.targetSizeMb, "LOW must map to 2 MB")
    }

    /**
     * A higher quality never permits a smaller budget than a lower quality:
     * HIGH >= MEDIUM >= LOW (Req 10.1).
     */
    @Test
    fun targetMbIsMonotonicAcrossQualities() {
        assertTrue(
            ExportQuality.HIGH.targetSizeMb >= ExportQuality.MEDIUM.targetSizeMb,
            "HIGH (${ExportQuality.HIGH.targetSizeMb}) must be >= MEDIUM (${ExportQuality.MEDIUM.targetSizeMb})",
        )
        assertTrue(
            ExportQuality.MEDIUM.targetSizeMb >= ExportQuality.LOW.targetSizeMb,
            "MEDIUM (${ExportQuality.MEDIUM.targetSizeMb}) must be >= LOW (${ExportQuality.LOW.targetSizeMb})",
        )
    }
}
