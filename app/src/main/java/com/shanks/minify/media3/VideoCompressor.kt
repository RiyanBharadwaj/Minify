package com.shanks.minify.media3

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import com.shanks.minify.utils.getVideoInfo

@UnstableApi
object VideoCompressor {

    fun compress(
        context: Context,
        inputUri: Uri,
        outputPath: String,
        useH265: Boolean,
        quality: Int,
        onProgress: (Float) -> Unit,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        val mimeType = if (useH265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264

        val scale = when (quality) {
            0 -> 0.3f; 1 -> 0.45f; 2 -> 0.6f; 3 -> 0.75f; 4 -> 0.9f; else -> 1.0f
        }

        // Get source height and compute a 16-pixel-aligned target height.
        // Most H.264/H.265 encoders require dimensions to be multiples of 16.
        // Unaligned sizes (e.g. 132px after scaling a short clip) cause a codec
        // exception — this was the real cause of the "2-second video" failure.
        val (_, _, srcHeight) = getVideoInfo(context, inputUri)
        val targetHeight = alignTo16((srcHeight * scale).toInt()).coerceAtLeast(16)

        // Use Presentation to set an exact aligned output height;
        // it preserves aspect ratio automatically.
        val presentation = Presentation.createForHeight(targetHeight)

        val decoderFactory = DefaultDecoderFactory.Builder(context)
            .setEnableDecoderFallback(true)
            .build()

        val assetLoaderFactory = DefaultAssetLoaderFactory(
            context,
            decoderFactory,
            Clock.DEFAULT,
            null
        )

        val progressHolder = ProgressHolder()
        var pollRunnable: Runnable? = null

        val transformer = Transformer.Builder(context)
            .setAssetLoaderFactory(assetLoaderFactory)
            .setEncoderFactory(DefaultEncoderFactory.Builder(context).setEnableFallback(true).build())
            .setVideoMimeType(mimeType)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    pollRunnable?.let { mainHandler.removeCallbacks(it) }
                    onProgress(1f)
                    onSuccess()
                }
                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    e: ExportException
                ) {
                    pollRunnable?.let { mainHandler.removeCallbacks(it) }
                    onFailure(e)
                }
            })
            .build()

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
            .setEffects(Effects(emptyList(), listOf(presentation)))
            .build()

        try {
            transformer.start(editedMediaItem, outputPath)
        } catch (e: Exception) {
            onFailure(e)
            return
        }

        pollRunnable = object : Runnable {
            override fun run() {
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress / 100f)
                }
                mainHandler.postDelayed(this, 300)
            }
        }
        mainHandler.postDelayed(pollRunnable!!, 300)
    }

    /** Round up to the nearest multiple of 16 (required by most video encoders). */
    private fun alignTo16(value: Int): Int = (value + 15) / 16 * 16
}