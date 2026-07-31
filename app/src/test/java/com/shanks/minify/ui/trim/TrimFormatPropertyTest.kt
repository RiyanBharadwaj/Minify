package com.shanks.minify.ui.trim

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based test for [formatTrim], the pure `m:ss.mmm` time formatter used for trim
 * start/end/duration display.
 *
 * The formatter must preserve full millisecond precision so that the produced string parses
 * back to the exact input millisecond value (round-trip).
 */
class TrimFormatPropertyTest {

    // Feature: media-editing-suite, Property 16: Trim time formatting round-trips
    @Property(tries = 500)
    fun trimTimeFormattingRoundTrips(@ForAll("nonNegativeMillis") ms: Long) {
        val formatted = formatTrim(ms)

        // Sanity: the string matches the expected m:ss.mmm shape (minutes >= 1 digit,
        // seconds exactly two digits, millis exactly three digits).
        assertTrue(
            formatted.matches(Regex("""\d+:[0-5]\d\.\d{3}""")),
            "formatTrim($ms) produced '$formatted' which is not a valid m:ss.mmm string",
        )

        val parsed = parseTrim(formatted)
        assertEquals(
            ms,
            parsed,
            "formatTrim($ms) = '$formatted' parsed back to $parsed, expected $ms",
        )
    }

    /**
     * Parses an `m:ss.mmm` string back into milliseconds:
     * `ms = m * 60000 + ss * 1000 + mmm`.
     */
    private fun parseTrim(text: String): Long {
        val (minutesPart, rest) = text.split(":", limit = 2)
        val (secondsPart, millisPart) = rest.split(".", limit = 2)
        val minutes = minutesPart.toLong()
        val seconds = secondsPart.toLong()
        val millis = millisPart.toLong()
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    /**
     * Non-negative millisecond values spanning zero, sub-second, single/multi-minute, and
     * multi-hour ranges (up to ~10 hours).
     */
    @Provide
    fun nonNegativeMillis(): Arbitrary<Long> =
        Arbitraries.longs().between(0L, 36_000_000L)
}
