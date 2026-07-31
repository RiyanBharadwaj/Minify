package com.shanks.minify.ui.editor.model

import com.shanks.minify.photo.ImageFormat

/**
 * The classification of a Media_Item opened by the unified editor.
 *
 * A [Media_Item] is either a still [PHOTO] or a moving [VIDEO]; the editor
 * detects this once at open and configures the visible tool set accordingly
 * (Req 1.2–1.5).
 */
enum class MediaType {
    PHOTO,
    VIDEO,
}

/**
 * Pure, total classification of a content type / MIME string into a [MediaType].
 *
 * This mirrors the MIME/extension detection approach in
 * [com.shanks.minify.photo.PhotoCompressor]'s `resolveFormat`: the primary probe
 * is the canonical MIME type (`image/` or `video/` prefix), with a fallback to the bare
 * format/extension token via [ImageFormat.fromMimeType] for `file://`-style
 * inputs that only carry an extension.
 *
 * The function is **total** and **never throws**: it returns [MediaType.PHOTO]
 * for image types, [MediaType.VIDEO] for video types, and `null` for any
 * unresolved input including `null`, blanks, and unrecognized tokens (Req 1.2,
 * 1.5).
 */
object MediaTypeDetection {

    /**
     * Classify [mimeOrType] — a MIME type (e.g. `image/jpeg`, `video/mp4`), a
     * bare format token (e.g. `jpg`, `mp4`), or a file extension — into a
     * [MediaType].
     *
     * @return [MediaType.PHOTO] for image content, [MediaType.VIDEO] for video
     *   content, or `null` when the type cannot be resolved. Never throws.
     */
    fun classify(mimeOrType: String?): MediaType? {
        val normalized = mimeOrType?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return null

        // Primary probe: canonical MIME top-level type.
        when {
            normalized.startsWith("image/") -> return MediaType.PHOTO
            normalized.startsWith("video/") -> return MediaType.VIDEO
        }

        // Fallback: bare image token / extension, mirroring PhotoCompressor.resolveFormat.
        if (ImageFormat.fromMimeType(normalized) != null) return MediaType.PHOTO

        // Fallback: bare video token / extension.
        if (normalized in VIDEO_TOKENS) return MediaType.VIDEO

        return null
    }

    /**
     * Bare video format tokens / extensions recognized as [MediaType.VIDEO] when
     * no canonical `video/` MIME type is present (e.g. a `file://` URI whose
     * only signal is its extension).
     */
    private val VIDEO_TOKENS: Set<String> = setOf(
        "mp4",
        "m4v",
        "mov",
        "qt",
        "mkv",
        "webm",
        "3gp",
        "3gpp",
        "3g2",
        "avi",
        "ts",
        "mts",
        "m2ts",
        "mpeg",
        "mpg",
        "wmv",
        "flv",
    )
}
