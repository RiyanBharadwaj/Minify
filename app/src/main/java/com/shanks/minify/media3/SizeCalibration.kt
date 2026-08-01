package com.shanks.minify.media3

import android.content.Context
import com.shanks.minify.ui.CodecChoice
import kotlin.math.abs

object SizeCalibration {

    private const val PREFS_NAME = "size_calibration_v1"
    private const val BYTES_PER_MB = 1_048_576L
    private const val TARGET_AIM = 0.985f
    private const val MIN_FACTOR = 0.55f
    private const val MAX_FACTOR = 1.25f
    private const val TOLERANCE = 0.03f

    fun adjustedTargetMb(
        context: Context,
        codec: CodecChoice,
        targetSizeMb: Float,
        beforeSizeBytes: Long,
        removeAudio: Boolean,
        speed: Float?,
    ): Float {
        if (targetSizeMb <= 0f) return targetSizeMb

        val key = key(codec, targetSizeMb, removeAudio, speed)
        val factor = getFactor(context, codec, key, targetSizeMb)
        var adjusted = targetSizeMb * factor

        if (beforeSizeBytes > 0L) {
            val sourceMb = beforeSizeBytes / BYTES_PER_MB.toFloat()
            adjusted = adjusted.coerceAtMost(sourceMb * TARGET_AIM)
        }
        // The duration-aware minimum is enforced later in VideoCompressor.compress
        // via TargetClamp.clamp(), which has access to effective duration.
        return adjusted.coerceAtLeast(0.1f)
    }

    fun record(
        context: Context,
        codec: CodecChoice,
        userTargetSizeMb: Float,
        actualSizeBytes: Long,
        removeAudio: Boolean,
        speed: Float?,
    ) {
        if (userTargetSizeMb <= 0f || actualSizeBytes <= 10_000L) return
        if (speed != null && abs(speed - 1f) > 0.05f) return

        val desiredBytes = userTargetSizeMb * BYTES_PER_MB.toFloat() * TARGET_AIM
        val observedRatio = desiredBytes / actualSizeBytes.toFloat()
        if (!observedRatio.isFinite() || observedRatio <= 0f) return

        val key = key(codec, userTargetSizeMb, removeAudio, speed)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val factorKey = "factor_$key"
        val sampleKey = "samples_$key"
        val oldFactor = prefs.getFloat(factorKey, defaultFactor(userTargetSizeMb))
        val samples = prefs.getInt(sampleKey, 0)

        val actualVsDesired = actualSizeBytes.toFloat() / desiredBytes
        if (samples > 1 && actualVsDesired > 1f - TOLERANCE && actualVsDesired < 1f + TOLERANCE) {
            return
        }

        val alpha = when {
            samples == 0 -> 0.85f
            samples == 1 -> 0.55f
            samples < 4 -> 0.35f
            else -> 0.20f
        }
        val candidate = (oldFactor * observedRatio).coerceIn(MIN_FACTOR, MAX_FACTOR)
        val updated = (oldFactor * (1f - alpha) + candidate * alpha).coerceIn(MIN_FACTOR, MAX_FACTOR)

        // Per-codec global prior: lets a brand-new size bucket start from what this
        // device/codec has already demonstrated, instead of the static default.
        val globalKey = "global_${codec.name}"
        val oldGlobal = prefs.getFloat(globalKey, updated)
        val newGlobal = (oldGlobal * 0.85f + updated * 0.15f).coerceIn(MIN_FACTOR, MAX_FACTOR)

        prefs.edit()
            .putFloat(factorKey, updated)
            .putInt(sampleKey, samples + 1)
            .putFloat(globalKey, newGlobal)
            .apply()

        android.util.Log.d(
            "SizeCalibration",
            "key=$key old=$oldFactor observedRatio=$observedRatio updated=$updated " +
                    "global=$newGlobal samples=${samples + 1}"
        )
    }

    private fun getFactor(context: Context, codec: CodecChoice, key: String, targetSizeMb: Float): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bucketKey = "factor_$key"
        if (prefs.contains(bucketKey)) {
            return prefs.getFloat(bucketKey, defaultFactor(targetSizeMb))
        }
        // No bucket history: seed from the device/codec global prior when available,
        // blended with the static default so a brand-new device still gets a safe value.
        val global = prefs.getFloat("global_${codec.name}", -1f)
        val static = defaultFactor(targetSizeMb)
        return if (global > 0f) {
            (static * 0.4f + global * 0.6f).coerceIn(MIN_FACTOR, MAX_FACTOR)
        } else {
            static
        }
    }

    private fun defaultFactor(targetSizeMb: Float): Float {
        // First-pass VBR overshoot compensation. MediaCodec VBR/ABR overshoots its
        // requested average proportionally MORE on short/small clips (rate control
        // never converges). Bias under target so the first export lands at/under
        // target; calibration converges up or down within 1-2 exports.
        return when {
            targetSizeMb < 5f   -> 0.78f
            targetSizeMb < 10f  -> 0.80f
            targetSizeMb < 25f  -> 0.84f
            targetSizeMb < 50f  -> 0.87f
            targetSizeMb < 100f -> 0.90f
            targetSizeMb < 250f -> 0.93f
            else                -> 0.95f
        }
    }

    private fun key(codec: CodecChoice, targetSizeMb: Float, removeAudio: Boolean, speed: Float?): String {
        val sizeBucket = when {
            targetSizeMb < 10f  -> "s0_10"
            targetSizeMb < 25f  -> "s10_25"
            targetSizeMb < 50f  -> "s25_50"
            targetSizeMb < 100f -> "s50_100"
            targetSizeMb < 250f -> "s100_250"
            else                -> "s250p"
        }
        val audioBucket = if (removeAudio) "na" else "a"
        val speedBucket = when {
            speed == null || abs(speed - 1f) <= 0.05f -> "1x"
            speed < 1f -> "lt1x"
            else -> "gt1x"
        }
        return "${codec.name}_${sizeBucket}_${audioBucket}_$speedBucket"
    }
}