package com.shanks.minify.logic

/**
 * Validates a requested target output size against the source file size before any
 * Media3 work begins. A valid target must be strictly positive and no larger than the
 * source: compressing to 0 bytes is impossible, and "compressing" to something larger
 * than the source is not compression at all (Req 2.8).
 *
 * The function is total: it returns a [Result] for every input and never throws.
 */
object TargetSizeValidation {

    /** Reason a target size was rejected. */
    enum class Reason { NON_POSITIVE, EXCEEDS_SOURCE }

    /** Outcome of validating a target size. */
    sealed interface Result {
        data object Valid : Result
        data class Invalid(val reason: Reason) : Result
    }

    /**
     * Returns [Result.Valid] if and only if `0 < targetBytes <= sourceBytes`.
     *
     * @param targetBytes requested maximum output size, in bytes.
     * @param sourceBytes source file size, in bytes.
     */
    fun validate(targetBytes: Long, sourceBytes: Long): Result = when {
        targetBytes <= 0L -> Result.Invalid(Reason.NON_POSITIVE)
        targetBytes > sourceBytes -> Result.Invalid(Reason.EXCEEDS_SOURCE)
        else -> Result.Valid
    }
}
