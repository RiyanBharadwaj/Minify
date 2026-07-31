package com.shanks.minify.photo

/**
 * A concrete set of encode parameters for a photo compression attempt.
 *
 * @property format the output [ImageFormat].
 * @property quality the lossy quality in `[0, 100]` (ignored for lossless
 *   [ImageFormat.PNG], where it is held at a canonical value).
 * @property scale the linear downscale factor in `(0, 1]`; `1.0` keeps the
 *   source resolution, smaller values shrink both dimensions.
 */
data class PhotoParams(
    val format: ImageFormat,
    val quality: Int,
    val scale: Float,
)
