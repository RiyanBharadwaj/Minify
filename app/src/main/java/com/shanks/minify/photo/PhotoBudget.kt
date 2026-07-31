package com.shanks.minify.photo

/**
 * Pure selector that chooses the highest-quality [PhotoParams] whose encoded
 * size fits within a target byte budget (Req 8.2, 8.7, 8.8).
 *
 * The class holds no Android/bitmap dependencies: the actual encode is injected
 * as a size probe (`encodedSizeFor`), so this logic is fully JVM-testable.
 *
 * ### Search space
 * The candidate list is a *domination chain*: each candidate has quality and
 * scale no lower than the next one, so its encoded size is no smaller than the
 * next one's for any monotone probe. Walking the chain from best to worst and
 * returning the first candidate that fits therefore yields the highest-quality
 * setting within the target, with the guarantee that no strictly higher-quality
 * setting also fits (Property 15).
 *
 * - Lossy formats ([ImageFormat.JPEG], [ImageFormat.WEBP]) first sweep quality
 *   downward at full resolution, then — only if the lowest quality at full
 *   resolution still overshoots — progressively downscale at the lowest quality.
 * - Lossless [ImageFormat.PNG] has no quality knob, so it falls back directly to
 *   a progressive downscale at a fixed canonical quality.
 */
object PhotoBudget {

    /** Highest lossy quality considered. */
    const val QUALITY_MAX = 100

    /** Lowest lossy quality considered before falling back to downscale. */
    const val QUALITY_MIN = 10

    /** Step between successive quality candidates. */
    const val QUALITY_STEP = 5

    /** Canonical quality reported for lossless PNG params (the encoder ignores it). */
    const val PNG_QUALITY = 100

    /** Largest scale (no downscale). */
    const val SCALE_MAX = 1.0f

    /** Smallest scale considered. */
    const val SCALE_MIN = 0.1f

    /** Step between successive scale candidates. */
    const val SCALE_STEP = 0.1f

    /**
     * Build the ordered candidate chain for [format], from highest quality to
     * lowest. Each candidate dominates the one after it (quality and scale never
     * increase), so encoded sizes are non-increasing along the list for any
     * monotone probe.
     */
    fun candidateParams(format: ImageFormat): List<PhotoParams> {
        val candidates = mutableListOf<PhotoParams>()
        when (format) {
            ImageFormat.PNG -> {
                // No quality knob: progressive downscale only.
                var scale = SCALE_MAX
                while (scale >= SCALE_MIN - 1e-4f) {
                    candidates += PhotoParams(format, PNG_QUALITY, roundScale(scale))
                    scale -= SCALE_STEP
                }
            }

            ImageFormat.JPEG, ImageFormat.WEBP -> {
                // Sweep quality at full resolution first.
                var quality = QUALITY_MAX
                while (quality >= QUALITY_MIN) {
                    candidates += PhotoParams(format, quality, SCALE_MAX)
                    quality -= QUALITY_STEP
                }
                // Then downscale at the lowest quality as a last resort.
                var scale = SCALE_MAX - SCALE_STEP
                while (scale >= SCALE_MIN - 1e-4f) {
                    candidates += PhotoParams(format, QUALITY_MIN, roundScale(scale))
                    scale -= SCALE_STEP
                }
            }
        }
        return candidates
    }

    /**
     * Choose the highest-quality [PhotoParams] whose probed encoded size is no
     * greater than [targetBytes].
     *
     * @param targetBytes the size budget in bytes (1 MB = 1,048,576 bytes).
     * @param encodedSizeFor a probe returning the encoded size in bytes for a
     *   given candidate. Expected to be monotone (larger quality/scale ⇒ larger
     *   size), though the function is total for any probe.
     * @param format the output format being compressed.
     * @return the best-fitting params, or `null` when even the lowest
     *   quality/scale overshoots the target (unachievable, Req 8.8).
     */
    fun selectParams(
        targetBytes: Long,
        encodedSizeFor: (PhotoParams) -> Long,
        format: ImageFormat,
    ): PhotoParams? =
        candidateParams(format).firstOrNull { encodedSizeFor(it) <= targetBytes }

    /** Round a scale factor to a single decimal place to avoid float drift. */
    private fun roundScale(scale: Float): Float = Math.round(scale * 10f) / 10f
}
