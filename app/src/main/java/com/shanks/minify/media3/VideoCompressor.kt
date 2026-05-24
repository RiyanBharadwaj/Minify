package com.shanks.minify.media3

import android.content.Context
import android.media.MediaCodecInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import com.shanks.minify.utils.getVideoInfo
import kotlin.math.min
import kotlin.math.roundToInt

@UnstableApi
object VideoCompressor {

    private const val TAG = "VideoCompressor"

    val SCALE = floatArrayOf(0.35f, 0.50f, 0.62f, 0.75f, 0.85f, 1.0f)

    val KBPS_PER_MP_H264 = intArrayOf(
        800,
        1_800,
        4_700,
        7_000,
        10_000,
        15_000,
    )

    val ABS_FLOOR_BPS = intArrayOf(
        80_000, 150_000, 300_000, 500_000, 800_000, 1_200_000
    )

    // Single overhead multiplier: accounts for container overhead + encoder
    // min-bitrate floor rounding. Do NOT inflate this to "fix" predictions —
    // if the encoder is wildly off, fix the encoder target, not this constant.
    const val OVERHEAD_FACTOR = 1.12f

    fun computeTargetBitrateBps(
        tier: Int,
        srcBitrateKbps: Int,
        srcWidth: Int,
        srcHeight: Int,
        useH265: Boolean
    ): Int {
        val scale       = SCALE[tier]
        val outW        = ((srcWidth  * scale).roundToInt() + 15) / 16 * 16
        val outH        = ((srcHeight * scale).roundToInt() + 15) / 16 * 16
        val outputMp    = (outW * outH) / 1_000_000f
        val codecFactor = if (useH265) 0.50f else 1.0f
        val floorBps    = ABS_FLOOR_BPS[tier]

        val resolutionTarget = (KBPS_PER_MP_H264[tier] * outputMp * codecFactor * 1000).roundToInt()
        val srcCapBps        = (srcBitrateKbps * 1000 * codecFactor * 0.80f).roundToInt()

        return min(resolutionTarget, srcCapBps).coerceAtLeast(floorBps)
    }

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
        val tier = quality.coerceIn(0, SCALE.lastIndex)

        val info       = getVideoInfo(context, inputUri)
        val srcWidth   = info.width
        val srcHeight  = info.height

        val scale        = SCALE[tier]
        val targetHeight = alignTo16((srcHeight * scale).toInt()).coerceAtLeast(16)
        val targetWidth  = alignTo16((srcWidth  * scale).toInt()).coerceAtLeast(16)

        val targetBitrate = computeTargetBitrateBps(
            tier          = tier,
            srcBitrateKbps = info.bitrateKbps,
            srcWidth      = srcWidth,
            srcHeight     = srcHeight,
            useH265       = useH265
        )

        val outputMp = (targetWidth * targetHeight) / 1_000_000f

        val progressHolder = ProgressHolder()
        var pollRunnable: Runnable? = null

        fun stopPolling() = pollRunnable?.let { mainHandler.removeCallbacks(it) }

        fun startPolling(transformer: Transformer) {
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

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
            .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(targetHeight))))
            .build()

        fun buildAndStart(mimeType: String, onErr: (ExportException) -> Unit) {
            val codecFactor = if (mimeType == MimeTypes.VIDEO_H265) 0.50f else 1.0f

            val profile = if (mimeType == MimeTypes.VIDEO_H265) {
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
            } else {
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            }

            val level = if (mimeType == MimeTypes.VIDEO_H265) {
                MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41
            } else {
                MediaCodecInfo.CodecProfileLevel.AVCLevel41
            }

            Log.d(TAG, "Starting encode — codec=$mimeType tier=$tier " +
                    "outputMp=${"%.2f".format(outputMp)}MP " +
                    "targetBitrate=${targetBitrate / 1000}kbps " +
                    "height=${targetHeight}px")

            val encoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(targetBitrate)
                .setEncodingProfileLevel(profile, level)
                .build()

            val transformer = Transformer.Builder(context)
                .setAssetLoaderFactory(
                    DefaultAssetLoaderFactory(
                        context,
                        DefaultDecoderFactory.Builder(context)
                            .setEnableDecoderFallback(true)
                            .build(),
                        Clock.DEFAULT,
                        null
                    )
                )
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setEnableFallback(true)
                        .setRequestedVideoEncoderSettings(encoderSettings)
                        .build()
                )
                .setVideoMimeType(mimeType)
                .addListener(object : Transformer.Listener {

                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        stopPolling()

                        val avgBitrateKbps = if (info.durationSecs > 0) {
                            (exportResult.fileSizeBytes * 8L) / (info.durationSecs * 1000L)
                        } else 0L

                        Log.d(TAG, "Encode complete — " +
                                "requestedCodec=$mimeType " +
                                "actualCodec=${exportResult.videoMimeType} " +
                                "avgBitrate=${avgBitrateKbps}kbps " +
                                "fileSize=${exportResult.fileSizeBytes / 1024}KB")

                        onProgress(1f)
                        onSuccess()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        e: ExportException
                    ) {
                        stopPolling()
                        Log.e(TAG, "Encode failed — codec=$mimeType reason=${e.message}")
                        onErr(e)
                    }
                })
                .build()

            try {
                transformer.start(editedMediaItem, outputPath)
                startPolling(transformer)
            } catch (e: Exception) {
                onFailure(e)
            }
        }

        if (useH265) {
            buildAndStart(MimeTypes.VIDEO_H265) {
                Log.w(TAG, "H.265 failed, retrying with H.264")
                buildAndStart(MimeTypes.VIDEO_H264) { e -> onFailure(e) }
            }
        } else {
            buildAndStart(MimeTypes.VIDEO_H264) { e -> onFailure(e) }
        }
    }

    private fun alignTo16(value: Int): Int = (value + 15) / 16 * 16
}