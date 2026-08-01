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

        private const val BYTES_PER_MB = 1_048_576L
        private const val ABSOLUTE_MIN_MB = 0.1f

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

            // ── Auto-adjust instead of rejecting (was: Invalid) ──────────
            var safeTarget = targetSizeMb
            if (!safeTarget.isFinite() || safeTarget <= 0f) {
                safeTarget = ABSOLUTE_MIN_MB
            }
            val safeBeforeSize = beforeSize.coerceAtLeast(0L)
            if (safeBeforeSize > 0L) {
                val targetBytes = (safeTarget * BYTES_PER_MB).toLong()
                if (targetBytes > safeBeforeSize) {
                    safeTarget = safeBeforeSize / BYTES_PER_MB.toFloat()
                    safeTarget = safeTarget.coerceAtLeast(ABSOLUTE_MIN_MB)
                }
            }
            // ─────────────────────────────────────────────────────────────

            return Valid(
                inputUri = inputUri,
                outputPath = outputPath,
                codec = codec,
                targetSizeMb = safeTarget,
                beforeSize = safeBeforeSize,
            )
        }
    }
}
