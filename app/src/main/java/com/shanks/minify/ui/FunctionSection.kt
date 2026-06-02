@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.shanks.minify.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanks.minify.media3.VideoCompressor
import com.shanks.minify.utils.VideoInfo
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

// Absolute minimum target we ever offer, in MB
private const val ABS_MIN_MB = 1f

/**
 * Generates up to [maxSteps] logarithmically-spaced size targets between [minMb] and [maxMb],
 * rounded to human-friendly values. Duplicate values are collapsed so the list is always distinct.
 *
 * Log spacing is used because the perceptual difference between 1 MB and 5 MB is similar to
 * 10 MB and 50 MB — a linear spacing would crowd everything at the small end.
 */
private fun generatePresets(minMb: Float, maxMb: Float, maxSteps: Int = 6): List<Float> {
    if (maxMb <= minMb) return listOf(minMb)

    val steps = maxSteps.coerceAtLeast(2)
    val logMin = ln(minMb.toDouble())
    val logMax = ln(maxMb.toDouble())

    val raw = (0 until steps).map { i ->
        val t = i.toDouble() / (steps - 1)
        exp(logMin + t * (logMax - logMin)).toFloat()
    }

    // Round each value to a human-friendly number
    val rounded = raw.map { mb -> roundToNice(mb) }

    // Collapse duplicates while preserving order, then ensure min and max are exact
    val distinct = rounded.distinct().sorted().toMutableList()
    if (distinct.isEmpty()) return listOf(minMb, maxMb)
    distinct[0] = minMb
    distinct[distinct.lastIndex] = maxMb

    return distinct.distinct().sorted()
}

/**
 * Rounds [mb] to a value that looks natural in a UI label.
 * < 5 MB   → round to 1 decimal  (e.g. 1.3 MB)
 * 5–20 MB  → round to nearest 1  (e.g. 8 MB, 15 MB)
 * 20–100   → round to nearest 5  (e.g. 25 MB, 60 MB)
 * > 100    → round to nearest 10 (e.g. 120 MB, 250 MB)
 */
private fun roundToNice(mb: Float): Float = when {
    mb < 5f   -> (mb * 10).roundToInt() / 10f
    mb < 20f  -> mb.roundToInt().toFloat()
    mb < 100f -> ((mb / 5).roundToInt() * 5).toFloat()
    else      -> ((mb / 10).roundToInt() * 10).toFloat()
}

/**
 * Estimates the source video size in MB from metadata.
 * Uses total bitrate × duration; returns null if either is unavailable.
 */
private fun estimatedSrcMb(info: VideoInfo): Float? {
    if (info.durationSecs <= 0 || info.bitrateKbps <= 0) return null
    // bitrateKbps already includes audio in most containers
    return (info.bitrateKbps.toFloat() * info.durationSecs) / 8_000f
}

/**
 * Builds the adaptive preset list for the given video.
 *
 * - Max = source size, floored to a nice value (we can't make it bigger)
 * - Min = max(ABS_MIN_MB, sourceMb * 0.08)  — roughly 8 % of source
 * - Up to 6 log-spaced steps; fewer if the range is too narrow for 6 distinct values
 */
private fun buildPresets(info: VideoInfo?): List<Float> {
    val srcMb = info?.let { estimatedSrcMb(it) }

    // No video or no usable metadata → return a sensible default list
    if (srcMb == null || srcMb <= 0f) {
        return listOf(5f, 10f, 25f, 50f, 100f, 200f)
    }

    val maxMb = roundToNice(srcMb)
    val minMb = max(ABS_MIN_MB, roundToNice(srcMb * 0.08f))

    // If the full range only fits 1–2 distinct values, just return those
    if (maxMb <= minMb + 0.5f) return listOf(minMb, maxMb).distinct()

    // Number of steps: 6 normally, fewer for very small ranges
    val rangeMb = maxMb - minMb
    val steps = when {
        rangeMb < 3f  -> 2
        rangeMb < 8f  -> 3
        rangeMb < 20f -> 4
        rangeMb < 50f -> 5
        else          -> 6
    }

    return generatePresets(minMb, maxMb, steps)
}

private fun formatPresetLabel(mb: Float): String {
    return when {
        mb >= 1000f -> "${"%.0f".format(mb / 1000f)} GB"
        mb < 10f && mb != mb.toInt().toFloat() -> "${"%.1f".format(mb)} MB"
        else -> "${mb.toInt()} MB"
    }
}

@Composable
fun FunctionSection(
    selectedUri: Uri?,
    videoInfo: VideoInfo?,
    sizePresetIndex: Int,
    customSizeMb: Float?,
    isCompressing: Boolean,
    useH265: Boolean,
    onPresetIndex: (Int) -> Unit,
    onCustomSizeMb: (Float?) -> Unit,
    onStart: (Uri, Float) -> Unit,
    onStop: () -> Unit
) {
    val presets = remember(videoInfo) { buildPresets(videoInfo) }
    val maxIndex = (presets.size - 1).coerceAtLeast(0)

    val clampedIndex = sizePresetIndex.coerceIn(0, maxIndex)
    LaunchedEffect(clampedIndex, sizePresetIndex) {
        if (clampedIndex != sizePresetIndex) onPresetIndex(clampedIndex)
    }

    val effectiveMb: Float = customSizeMb ?: presets[clampedIndex]

    var customText by remember { mutableStateOf(customSizeMb?.toInt()?.toString() ?: "") }
    var customError by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Header row ────────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Target Size",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = if (customSizeMb != null)
                            "${formatPresetLabel(customSizeMb)} (custom)"
                        else
                            formatPresetLabel(presets[clampedIndex]),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Slider ────────────────────────────────────────────────────────
            AnimatedVisibility(
                visible = customSizeMb == null,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Slider(
                        value = clampedIndex.toFloat(),
                        onValueChange = { onPresetIndex(it.toInt().coerceIn(0, maxIndex)) },
                        valueRange = 0f..maxIndex.toFloat(),
                        steps = (presets.size - 2).coerceAtLeast(0),
                        enabled = !isCompressing,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        presets.forEach { mb ->
                            Text(
                                text = formatPresetLabel(mb),
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // ── Custom size input ─────────────────────────────────────────────
            AnimatedVisibility(
                visible = customSizeMb != null,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { raw ->
                        customText = raw
                        val parsed = raw.trim().toFloatOrNull()
                        customError = parsed == null || parsed <= 0f
                        if (!customError && parsed != null) onCustomSizeMb(parsed)
                    },
                    label = { Text("Custom size (MB)") },
                    isError = customError,
                    supportingText = if (customError) {
                        { Text("Enter a positive number") }
                    } else null,
                    singleLine = true,
                    enabled = !isCompressing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Custom size toggle ────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = customSizeMb != null,
                    onCheckedChange = { checked ->
                        if (checked) {
                            val current = presets[clampedIndex]
                            customText = if (current == current.toInt().toFloat())
                                current.toInt().toString()
                            else
                                "%.1f".format(current)
                            onCustomSizeMb(current)
                        } else {
                            onCustomSizeMb(null)
                        }
                    },
                    enabled = !isCompressing
                )
                Text(
                    text = "Custom size",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Start / Stop button ───────────────────────────────────────────
            if (isCompressing) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor   = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Stop Compression", modifier = Modifier.padding(vertical = 4.dp))
                }
            } else {
                Button(
                    onClick = { selectedUri?.let { onStart(it, effectiveMb) } },
                    enabled = selectedUri != null && (customSizeMb == null || !customError),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Start Compression", modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}