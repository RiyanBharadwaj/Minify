package com.shanks.minify.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

data class VideoInfo(
    val width: Int,
    val height: Int,
    val bitrateKbps: Int,
    val durationSecs: Long,
    val frameRate: Float,
)

fun getVideoInfo(context: Context, uri: Uri): VideoInfo {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val codedW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()  ?: 1280
        val codedH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
        val bps    = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()      ?: 0
        val dur    = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?.div(1000L) ?: 0L

        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        val displaySwap = (rotation == 90) || (rotation == 270)
        val w = if (displaySwap) codedH else codedW
        val h = if (displaySwap) codedW else codedH

        // minSdk is 28, so METADATA_KEY_VIDEO_FRAME_COUNT is always available.
        val frameCount = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            ?.toFloatOrNull()
        val fps = when {
            ((frameCount != null) && (frameCount > 0f) && (dur > 0L)) ->
                (frameCount / dur.toFloat()).coerceIn(1f, 120f)
            else ->
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()
                    ?.coerceIn(1f, 60f)
                    ?: 30f
        }

        return VideoInfo(w, h, bps / 1000, dur, fps)
    } finally {
        retriever.release()
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1048576 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1073741824 -> "%.1f MB".format(bytes / 1048576.0)
        else -> "%.2f GB".format(bytes / 1073741824.0)
    }
}
