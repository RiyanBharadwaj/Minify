package com.shanks.minify.ui

import android.media.MediaCodecList

object CodecAvailability {

    data class CodecStatus(
        val supported: Boolean,
        // true = hardware-accelerated, false = software-only (too slow for practical use)
        val isHardware: Boolean,
        val unavailableReason: String? = null
    )

    private val cache = mutableMapOf<CodecChoice, CodecStatus>()

    fun getStatus(choice: CodecChoice): CodecStatus = cache.getOrPut(choice) {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val encoder = codecList.codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.contains(choice.mime)
        }

        when {
            encoder == null ->
                CodecStatus(supported = false, isHardware = false, unavailableReason = "not supported on this device")
            isKnownSoftwareEncoder(encoder.name) ->
                CodecStatus(false, false, "necessary hardware doesn't exist")
            else ->
                CodecStatus(true, isHardware = true)
        }
    }

    fun isSupported(choice: CodecChoice): Boolean = getStatus(choice).supported

    private fun isKnownSoftwareEncoder(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("c2.android.") ||
                lower.startsWith("omx.google.")
    }
}