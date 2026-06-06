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
        val codedW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()  ?: 1280
        val codedH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
        val bps    = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()      ?: 0
        val dur    = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?.div(1000L) ?: 0L

        // METADATA_KEY_VIDEO_ROTATION returns the display rotation in degrees.
        // KEY_VIDEO_WIDTH/HEIGHT are always the *coded* (bitstream) dimensions —
        // a portrait phone video is stored as a landscape bitstream with rotation=90.
        // We store display-space dimensions in VideoInfo so every caller (computeParams,
        // preset builder, UI) sees the correct width/height without needing to know
        // about rotation independently.
        val rotation     = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        val displaySwap  = rotation == 90 || rotation == 270
        val w = if (displaySwap) codedH else codedW
        val h = if (displaySwap) codedW else codedH

        // Derive playback fps from frame count ÷ duration first.
        // METADATA_KEY_CAPTURE_FRAMERATE is the *sensor* rate — for slow-motion
        // video this is 120/240fps even though playback is 30fps, which makes the
        // pipeline process far more frames than actually exist in the container
        // and wildly inflates the time estimate and bitrate calculation.
        val frameCount = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            ?.toFloatOrNull()
        val fps = when {
            // Frame count ÷ duration = true playback rate
            frameCount != null && frameCount > 0f && dur > 0L ->
                (frameCount / dur.toFloat()).coerceIn(1f, 120f)
            // Fall back to capture rate only if frame count unavailable,
            // capped at 60 — anything above is almost certainly a sensor rate
            else ->
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()
                    ?.coerceIn(1f, 60f)
                    ?: 30f
        }

        return VideoInfo(w, h, bps / 1000, dur, fps)  // w/h are display-space
    } finally {
        retriever.release()
    }
}