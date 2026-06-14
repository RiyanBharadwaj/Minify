package com.shanks.minify.ui

enum class CodecChoice {
    H264, H265, AV1;

    val label: String get() = when (this) {
        H264 -> "H.264 / AVC"
        H265 -> "H.265 / HEVC"
        AV1  -> "AV1"
    }

    val subtitle: String get() = when (this) {
        H264 -> "Wider compatibility, fastest"
        H265 -> "Better compression, slower"
        AV1  -> "Best compression, slowest — Newer Android phones only"
    }

    val mime: String get() = when (this) {
        H264 -> android.media.MediaFormat.MIMETYPE_VIDEO_AVC
        H265 -> android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
        AV1  -> "video/av01"
    }
}