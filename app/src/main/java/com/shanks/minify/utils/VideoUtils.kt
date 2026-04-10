package com.shanks.minify.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

// Fixed: renamed return value to clearly indicate seconds, not milliseconds
fun getVideoInfo(context: Context, uri: Uri): Triple<Long, Int, Int> {
    val retriever = MediaMetadataRetriever()

    // Fixed: use try/finally so retriever.release() always runs
    try {
        retriever.setDataSource(context, uri)

        val durationSec = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull()?.div(1000L) ?: 0L

        val bitrateKbps = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_BITRATE
        )?.toIntOrNull()?.div(1000) ?: 0

        val height = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
        )?.toIntOrNull() ?: 720

        return Triple(durationSec, bitrateKbps, height)
    } finally {
        retriever.release()
    }
}

fun estimateSizeSmart(
    inputBitrateKbps: Int,
    durationSeconds: Long,
    targetBitrateKbps: Int,
    targetResolution: Int,
    originalResolution: Int
): Float {
    val audioBitrate = 128

    val resolutionScale = targetResolution.toFloat() / originalResolution
    val adjustedBitrate = (targetBitrateKbps * resolutionScale).toInt()

    val finalVideoBitrate = minOf(adjustedBitrate, inputBitrateKbps)
    val totalBitrate = finalVideoBitrate + audioBitrate

    // Fixed: removed stacked correctionFactor — it double-penalised high-bitrate
    // sources on top of an already resolution-scaled bitrate, skewing estimates high
    return (totalBitrate * durationSeconds) / 8f / 1024f
}

fun estimateSizeUltra(
    context: Context,
    inputPath: String,
    durationSeconds: Long,
    bitrate: Int,
    resolution: Int,
    onResult: (Float) -> Unit
) {
    val tempFile = File(context.cacheDir, "preview.mp4")

    val previewDuration = 3

    // Fixed: added -c:v mpeg4 and -b:a 128k; removed redundant -ss 0
    val command = "-y -t $previewDuration -i \"$inputPath\" " +
            "-vf scale=$resolution:-2 " +
            "-c:v mpeg4 " +
            "-b:v ${bitrate}k " +
            "-c:a aac " +
            "-b:a 128k " +
            "\"${tempFile.absolutePath}\""

    FFmpegKit.executeAsync(command) { session ->

        // Fixed: check ReturnCode instead of file existence to avoid corrupt file reads
        if (ReturnCode.isSuccess(session.returnCode) && tempFile.exists()) {
            val sizeBytes = tempFile.length()
            val sizeMB = sizeBytes / (1024f * 1024f)
            val estimatedFullSize = (sizeMB / previewDuration) * durationSeconds
            tempFile.delete()
            onResult(estimatedFullSize)
        } else {
            tempFile.delete()
            // Fixed: use -1f so callers can distinguish failure from a genuine 0 MB result
            onResult(-1f)
        }
    }
}