package com.shanks.minify.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope

/**
 * Holds all user-applied edits. Passed from the editor sheet into the compressor.
 *
 * @param trimStartMs  Start of the kept segment in milliseconds (0 = keep from beginning)
 * @param trimEndMs    End of the kept segment in milliseconds (null = keep to end)
 * @param cropRect     Normalised crop rectangle in [0,1] space relative to the display frame.
 *                     null = no crop (full frame).
 */
data class EditState(
    val trimStartMs: Long = 0L,
    val trimEndMs: Long? = null,
    val cropRect: CropRect? = null
) {
    val hasTrim: Boolean  get() = trimStartMs > 0L || trimEndMs != null
    val hasCrop: Boolean  get() = cropRect != null
    val hasEdits: Boolean get() = hasTrim || hasCrop

    companion object {
        /**
         * Custom Saver so EditState survives configuration changes via rememberSaveable.
         * Encodes to a FloatArray: [trimStartMs, trimEndMs (-1 = null), left, top, right, bottom (-1 each = no crop)]
         */
        val Saver: Saver<EditState, Any> = Saver(
            save = { state ->
                floatArrayOf(
                    state.trimStartMs.toFloat(),
                    state.trimEndMs?.toFloat() ?: -1f,
                    state.cropRect?.left   ?: -1f,
                    state.cropRect?.top    ?: -1f,
                    state.cropRect?.right  ?: -1f,
                    state.cropRect?.bottom ?: -1f,
                )
            },
            restore = { saved ->
                val arr = saved as FloatArray
                EditState(
                    trimStartMs = arr[0].toLong(),
                    trimEndMs   = if (arr[1] < 0f) null else arr[1].toLong(),
                    cropRect    = if (arr[2] < 0f) null else CropRect(arr[2], arr[3], arr[4], arr[5])
                )
            }
        )
    }
}

/**
 * Normalised crop rectangle. All values in [0, 1] relative to the video's display dimensions.
 */
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float  get() = right - left
    val height: Float get() = bottom - top
    val aspectRatio: Float get() = if (height > 0f) width / height else 1f

    companion object {
        val FULL = CropRect(0f, 0f, 1f, 1f)

        fun forAspectRatio(targetAr: Float, videoAr: Float): CropRect {
            return if (targetAr > videoAr) {
                val h   = videoAr / targetAr
                val top = (1f - h) / 2f
                CropRect(0f, top, 1f, top + h)
            } else {
                val w    = targetAr / videoAr
                val left = (1f - w) / 2f
                CropRect(left, 0f, left + w, 1f)
            }
        }
    }
}

/** Well-known aspect ratio presets shown in the crop UI. */
enum class AspectRatioPreset(val label: String, val ratio: Float?) {
    FREE("Free",  null),
    R16_9("16:9", 16f / 9f),
    R9_16("9:16", 9f / 16f),
    R1_1("1:1",   1f),
    R4_3("4:3",   4f / 3f),
    R3_4("3:4",   3f / 4f),
}