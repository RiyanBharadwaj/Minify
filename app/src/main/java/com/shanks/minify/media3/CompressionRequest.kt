package com.shanks.minify.media3

import com.shanks.minify.ui.CodecChoice

/**
 * Pure, Android-Intent-independent validation of a compression start request.
 *
 * The values here mirror the extras consumed by [CompressionService.onStartCommand]
 * (inputUri, outputPath, codec, targetSizeMb, editState presence, beforeSize) but the
 * validation logic is expressed over already-extracted primitives so it can be unit
 * tested on the JVM without an Android [android.content.Intent].
 */
sealed interface CompressionRequest {
    /**
     * A fully validated request. All fields are guaranteed non-blank / parsed.
     */
    data class Valid(
        val inputUri: String,
        val outputPath: String,
        val codec: CodecChoice,
        val targetSizeMb: Float,
        val beforeSize: Long,
    ) : CompressionRequest

    /**
     * The request could not be validated. [reason] describes the first failure found.
     */
    data class Invalid(val reason: String) : CompressionRequest

    companion object {
        /**
         * Validate the already-extracted intent extras.
         *
         * Returns [Invalid] (never throws) when:
         *  - [inputUri] is null or blank
         *  - [outputPath] is null or blank
         *  - [codecName] is null or does not correspond to a valid [CodecChoice]
         *  - [editStatePresent] is false
         *
         * Otherwise returns [Valid] with the parsed fields.
         */
        fun fromExtras(
            inputUri: String?,
            outputPath: String?,
            codecName: String?,
            editStatePresent: Boolean,
            targetSizeMb: Float,
            beforeSize: Long,
        ): CompressionRequest {
            if (inputUri.isNullOrBlank()) {
                return Invalid("inputUri is missing or blank")
            }
            if (outputPath.isNullOrBlank()) {
                return Invalid("outputPath is missing or blank")
            }
            if (codecName == null) {
                return Invalid("codec is missing")
            }
            val codec = CodecChoice.entries.firstOrNull { it.name == codecName }
                ?: return Invalid("codec '$codecName' is not a valid CodecChoice")
            if (!editStatePresent) {
                return Invalid("editState is missing")
            }
            if (targetSizeMb <= 0f || !targetSizeMb.isFinite()) {
                return Invalid("targetSizeMb must be > 0")
            }
            if (beforeSize < 0L) {
                return Invalid("beforeSize must be >= 0")
            }
            if (beforeSize > 0L) {
                val targetBytes = (targetSizeMb * 1_048_576f).toLong()
                if (targetBytes > beforeSize) {
                    return Invalid("Target size is larger than the source file")
                }
            }
            return Valid(
                inputUri = inputUri,
                outputPath = outputPath,
                codec = codec,
                targetSizeMb = targetSizeMb,
                beforeSize = beforeSize,
            )
        }
    }
}
