package com.shanks.minify.photo

/**
 * The image formats Minify can compress.
 *
 * Only these three formats are supported for photo compression (Req 8.4).
 * [JPEG] and [WEBP] are lossy (they expose a quality knob); [PNG] is lossless
 * (no quality knob, so it is compressed by progressive downscale instead).
 */
enum class ImageFormat {
    JPEG,
    PNG,
    WEBP;

    companion object {
        /**
         * Classify a MIME/type string into a supported [ImageFormat].
         *
         * Pure and total: accepts the canonical MIME types (`image/jpeg`,
         * `image/png`, `image/webp`) as well as the bare format tokens
         * (`jpeg`, `jpg`, `png`, `webp`), case-insensitively and trimmed.
         * Returns `null` for any unsupported or unrecognized input, including
         * `null` (Req 8.4).
         */
        fun fromMimeType(mime: String?): ImageFormat? {
            val normalized = mime?.trim()?.lowercase() ?: return null
            return when (normalized) {
                "image/jpeg", "image/jpg", "jpeg", "jpg" -> JPEG
                "image/png", "png" -> PNG
                "image/webp", "webp" -> WEBP
                else -> null
            }
        }

        /** True iff [mime] denotes a supported image format (Req 8.4). */
        fun isSupported(mime: String?): Boolean = fromMimeType(mime) != null
    }
}
