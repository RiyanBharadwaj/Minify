package com.shanks.minify.ffmpeg

import android.util.Log
import com.arthenica.ffmpegkit.*

object VideoCompressor {

    fun compress(
        input: String,
        output: String,
        bitrate: Int,
        resolution: Int,
        durationMs: Long,
        onProgress: (Float) -> Unit,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {

        val scaleFilter = "scale=if(gt(iw\\,ih)\\,$resolution\\,-2):if(gt(iw\\,ih)\\,-2\\,$resolution)"

        val command = "-y -i \"$input\" " +
                "-vf $scaleFilter " +
                "-c:v mpeg4 " +
                "-b:v ${bitrate}k " +
                "-c:a aac " +
                "-b:a 128k " +
                "\"$output\""

        FFmpegKit.executeAsync(
            command,

            { session ->
                if (ReturnCode.isSuccess(session.returnCode)) {
                    onProgress(1f)
                    onSuccess()
                } else {
                    Log.e("APP", "Compression failed: ${session.failStackTrace}")
                    onFailure()
                }
            },

            { log ->
                Log.d("FFMPEG_LOG", log.message)
            },

            { stats ->
                val time = stats.time.toFloat()

                if (durationMs > 0 && time >= 0) {
                    val progress = (time / durationMs).coerceIn(0f, 1f)
                    onProgress(progress)
                }
            }
        )
    }
}