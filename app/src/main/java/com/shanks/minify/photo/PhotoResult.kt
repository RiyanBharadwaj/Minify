package com.shanks.minify.photo

import java.io.File

/**
 * The outcome of a photo compression request.
 *
 * On [Success] the caller receives the saved output file together with the
 * original and compressed sizes in bytes (surfaced to the UI in MB, Req 8.6).
 * On [Failure] the source file is always left unchanged (Req 8.4, 8.5, 8.8).
 */
sealed interface PhotoResult {
    data class Success(
        val outputFile: File,
        val originalBytes: Long,
        val compressedBytes: Long,
    ) : PhotoResult

    data class Failure(val reason: PhotoFailure) : PhotoResult
}

/**
 * The reason a photo compression failed.
 *
 * - [UNSUPPORTED_FORMAT]: the input is not JPEG/PNG/WebP (Req 8.4).
 * - [UNACHIEVABLE_TARGET]: even the lowest quality/scale exceeds the target (Req 8.8).
 * - [ENCODE_ERROR]: decoding or encoding the bitmap failed.
 * - [SAVE_ERROR]: persisting the output to the gallery failed (Req 8.5).
 */
enum class PhotoFailure {
    UNSUPPORTED_FORMAT,
    UNACHIEVABLE_TARGET,
    ENCODE_ERROR,
    SAVE_ERROR,
}
