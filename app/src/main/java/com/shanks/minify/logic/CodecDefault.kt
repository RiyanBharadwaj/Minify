package com.shanks.minify.logic

import com.shanks.minify.ui.CodecChoice

/**
 * Pure selector for the encoder that should be selected by default when the
 * Video tab is first displayed in an app session.
 *
 * H.265 (HEVC) is preferred for its better compression efficiency; H.264 (AVC)
 * is the fallback when H.265 is unsupported on the device.
 */
object CodecDefault {

    /**
     * Returns the initial [CodecChoice] to present as selected.
     *
     * @param isSupported predicate reporting whether a given [CodecChoice] is
     *   supported on the current device (typically backed by the codec
     *   availability check).
     * @return [CodecChoice.H265] when H.265 is supported, otherwise
     *   [CodecChoice.H264].
     */
    fun initialChoice(isSupported: (CodecChoice) -> Boolean): CodecChoice =
        if (isSupported(CodecChoice.H265)) CodecChoice.H265 else CodecChoice.H264
}
