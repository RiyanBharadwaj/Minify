package com.shanks.minify.ui.trim

/**
 * Formats a non-negative millisecond value as an `m:ss.mmm` string for trim
 * start/end/duration display.
 *
 * Generalized from `TrimBar.formatMs`, but preserves full millisecond precision
 * (three fractional digits) so the produced string parses back to the original
 * millisecond value (Property 16 round-trip).
 *
 * Examples: `0L -> "0:00.000"`, `1_000L -> "0:01.000"`, `61_234L -> "1:01.234"`.
 */
fun formatTrim(ms: Long): String {
    val totalSecs = ms / 1000L
    val m = totalSecs / 60
    val s = totalSecs % 60
    val millis = ms % 1000L
    return "%d:%02d.%03d".format(m, s, millis)
}
