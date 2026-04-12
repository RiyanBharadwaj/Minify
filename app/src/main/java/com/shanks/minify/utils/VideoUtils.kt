package com.shanks.minify.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

fun getVideoInfo(context: Context, uri: Uri): Triple<Long, Int, Int> {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()?.div(1000L) ?: 0L
        val bit = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toInt()?.div(1000) ?: 0
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 720
        return Triple(dur, bit, h)
    } finally { retriever.release() }
}