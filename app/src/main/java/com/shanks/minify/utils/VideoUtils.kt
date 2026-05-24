package com.shanks.minify.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

data class VideoInfo(
    val width: Int,
    val height: Int,
    val bitrateKbps: Int,
    val durationSecs: Long,
    val frameRate: Float
)

fun getVideoInfo(context: Context, uri: Uri): VideoInfo {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val w   = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1280
        val h   = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 720
        val bps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toInt() ?: 0
        val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()?.div(1000L) ?: 0L
        val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloat()
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toFloat()
                ?.div(dur.coerceAtLeast(1).toFloat())
            ?: 30f
        return VideoInfo(w, h, bps / 1000, dur, fps)
    } finally {
        retriever.release()
    }
}