package com.shanks.minify.ui

enum class CodecChoice(val label: String, val subtitle: String, val mime: String) {
    H264("H.264 / AVC", "Wider compatibility, fastest", android.media.MediaFormat.MIMETYPE_VIDEO_AVC),
    H265("H.265 / HEVC", "Better compression, slower", android.media.MediaFormat.MIMETYPE_VIDEO_HEVC),
    AV1("AV1", "Best compression, slowest — Newer Android phones only", "video/av01")
}
