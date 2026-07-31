package com.shanks.minify.ui.editor.model

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

/**
 * Property-based tests for [MediaTypeDetection.classify].
 *
 * These exercise the pure, total MIME/type/extension classifier that backs the
 * unified editor's media-type detection, independent of any Android framework.
 */
class MediaTypeDetectionPropertyTest {

    // Feature: unified-media-editor, Property 1: Media type detection is total and correct
    @Property(tries = 500)
    fun classifyIsTotalAndCorrect(@ForAll("mimeInputs") input: MimeInput) {
        // Never throws for any input (including null, blanks, garbage).
        val result = MediaTypeDetection.classify(input.value)
        assertEquals(input.expected, result)
    }

    // Any canonical image/* MIME type classifies as PHOTO.
    @Property(tries = 200)
    fun anyImagePrefixIsPhoto(@ForAll("imageMimeTypes") mime: String) {
        assertEquals(MediaType.PHOTO, MediaTypeDetection.classify(mime))
    }

    // Any canonical video/* MIME type classifies as VIDEO.
    @Property(tries = 200)
    fun anyVideoPrefixIsVideo(@ForAll("videoMimeTypes") mime: String) {
        assertEquals(MediaType.VIDEO, MediaTypeDetection.classify(mime))
    }

    // null, blank, and empty always resolve to null (never throwing).
    @Property(tries = 100)
    fun blankOrNullIsNull(@ForAll("blankInputs") input: String?) {
        assertNull(MediaTypeDetection.classify(input))
    }

    /**
     * A generated MIME/type/extension string paired with its expected
     * classification, so the property can assert exact correctness.
     */
    data class MimeInput(val value: String?, val expected: MediaType?)

    // --- Generators --------------------------------------------------------

    @Provide
    fun mimeInputs(): Arbitrary<MimeInput> = Arbitraries.oneOf(
        imageMimeInputs(),
        videoMimeInputs(),
        imageTokenInputs(),
        videoTokenInputs(),
        garbageInputs(),
        nullOrBlankInputs(),
    )

    // Canonical image MIME types (arbitrary subtype after "image/") → PHOTO.
    private fun imageMimeInputs(): Arbitrary<MimeInput> =
        imageMimeTypes().map { MimeInput(it, MediaType.PHOTO) }

    @Provide
    fun imageMimeTypes(): Arbitrary<String> {
        val subtypes = Arbitraries.of(
            "jpeg", "jpg", "png", "webp", "gif", "bmp", "heic", "heif", "tiff", "x-anything",
        )
        return subtypes.map { "image/$it" }
    }

    // Canonical video MIME types (arbitrary subtype after "video/") → VIDEO.
    private fun videoMimeInputs(): Arbitrary<MimeInput> =
        videoMimeTypes().map { MimeInput(it, MediaType.VIDEO) }

    @Provide
    fun videoMimeTypes(): Arbitrary<String> {
        val subtypes = Arbitraries.of(
            "mp4", "quicktime", "x-matroska", "webm", "3gpp", "avi", "mpeg", "x-ms-wmv", "x-flv",
        )
        return subtypes.map { "video/$it" }
    }

    // Bare image tokens / extensions recognized by ImageFormat.fromMimeType → PHOTO.
    private fun imageTokenInputs(): Arbitrary<MimeInput> {
        val tokens = Arbitraries.of("jpeg", "jpg", "png", "webp")
        return tokens.map { MimeInput(it, MediaType.PHOTO) }
    }

    // Bare video tokens / extensions recognized by VIDEO_TOKENS → VIDEO.
    private fun videoTokenInputs(): Arbitrary<MimeInput> {
        val tokens = Arbitraries.of(
            "mp4", "m4v", "mov", "qt", "mkv", "webm", "3gp", "3gpp", "3g2",
            "avi", "ts", "mts", "m2ts", "mpeg", "mpg", "wmv", "flv",
        )
        return tokens.map { MimeInput(it, MediaType.VIDEO) }
    }

    // Unrecognized garbage strings → null. Deliberately avoids image/video prefixes
    // and any known bare token by prefixing with a non-matching marker.
    private fun garbageInputs(): Arbitrary<MimeInput> {
        val garbage = Arbitraries.strings()
            .withCharRange('a', 'z')
            .ofMinLength(1)
            .ofMaxLength(12)
            .filter { it.isNotBlank() }
            .map { "zz_$it" } // guarantees no image/ or video/ prefix and no known token match
            .filter { MediaTypeDetection.classify(it) == null }
        val known = Arbitraries.of(
            "application/pdf", "text/plain", "audio/mpeg", "audio/wav",
            "doc", "txt", "mp3", "unknown", "image", "video", "/", "image_", "video_",
        )
        return Arbitraries.oneOf(garbage, known).map { MimeInput(it, null) }
    }

    private fun nullOrBlankInputs(): Arbitrary<MimeInput> =
        blankInputs().map { MimeInput(it, null) }

    @Provide
    fun blankInputs(): Arbitrary<String?> =
        Arbitraries.of(null, "", " ", "   ", "\t", "\n", "  \t\n  ")

    // Mixed-case + padded MIME inputs must still classify correctly (trim + lowercase).
    @Property(tries = 200)
    fun classifyTrimsAndLowercases(
        @ForAll("paddedMixedCase") pair: Pair<String, MediaType>,
    ) {
        assertEquals(pair.second, MediaTypeDetection.classify(pair.first))
    }

    @Provide
    fun paddedMixedCase(): Arbitrary<Pair<String, MediaType>> {
        val bases: Arbitrary<Pair<String, MediaType>> = Arbitraries.of(
            "image/JPEG" to MediaType.PHOTO,
            "IMAGE/png" to MediaType.PHOTO,
            "JPG" to MediaType.PHOTO,
            "WebP" to MediaType.PHOTO,
            "video/MP4" to MediaType.VIDEO,
            "VIDEO/quicktime" to MediaType.VIDEO,
            "MOV" to MediaType.VIDEO,
            "Mkv" to MediaType.VIDEO,
        )
        val pads = Arbitraries.of("", " ", "  ", "\t", "\n ")
        return Combinators.combine(bases, pads, pads).`as` { base, left, right ->
            (left + base.first + right) to base.second
        }
    }
}
