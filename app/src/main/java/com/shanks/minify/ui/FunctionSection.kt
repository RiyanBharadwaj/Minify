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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanks.minify.utils.VideoInfo
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

private val Surface1  = Color(0xFF1A1625)
private val Surface2  = Color(0xFF251E35)
private val TextPrim  = Color(0xFFFFFFFF)
private val TextSec   = Color(0xFF8E8E93)
private val ErrorRed  = Color(0xFFFF453A)

private const val ABS_MIN_MB = 1f

// ── Preset helpers using effective (post‑edit) dimensions ───────────────

private fun generatePresets(minMb: Float, maxMb: Float, maxSteps: Int = 6): List<Float> {
    if (maxMb <= minMb) return listOf(minMb)
    val steps  = maxSteps.coerceAtLeast(2)
    val logMin = ln(minMb.toDouble())
    val logMax = ln(maxMb.toDouble())
    val raw    = (0 until steps).map { i ->
        val t = i.toDouble() / (steps - 1)
        exp(logMin + t * (logMax - logMin)).toFloat()
    }
    val rounded  = raw.map { roundToNice(it) }
    val distinct = rounded.distinct().sorted().toMutableList()
    if (distinct.isEmpty()) return listOf(minMb, maxMb)
    distinct[0] = minMb; distinct[distinct.lastIndex] = maxMb
    return distinct.distinct().sorted()
}

private fun roundToNice(mb: Float): Float = when {
    mb < 5f   -> (mb * 10).roundToInt() / 10f
    mb < 20f  -> mb.roundToInt().toFloat()
    mb < 100f -> ((mb / 5).roundToInt() * 5).toFloat()
    else      -> ((mb / 10).roundToInt() * 10).toFloat()
}

private fun estimatedSrcMb(
    bitrateKbps: Int,
    durationSecs: Long
): Float? {
    if (durationSecs <= 0 || bitrateKbps <= 0) return null
    return (bitrateKbps.toFloat() * durationSecs) / 8_000f
}

private fun buildPresets(
    bitrateKbps: Int,
    durationSecs: Long,
    width: Int,
    height: Int
): List<Float> {
    // Use default fallback if no useful info
    if (durationSecs <= 0 || bitrateKbps <= 0 || width <= 0 || height <= 0) {
        return listOf(5f, 10f, 25f, 50f, 100f, 200f)
    }

    val srcMb = estimatedSrcMb(bitrateKbps, durationSecs) ?: return listOf(5f, 10f, 25f, 50f, 100f, 200f)
    val maxMb = roundToNice(srcMb)
    val minMb = max(ABS_MIN_MB, roundToNice(srcMb * 0.08f))
    if (maxMb <= minMb + 0.5f) return listOf(minMb, maxMb).distinct()
    val rangeMb = maxMb - minMb
    val steps = when {
        rangeMb < 3f  -> 2; rangeMb < 8f  -> 3; rangeMb < 20f -> 4
        rangeMb < 50f -> 5; else           -> 6
    }
    return generatePresets(minMb, maxMb, steps)
}

private fun formatPresetLabel(mb: Float): String = when {
    mb >= 1000f -> "${"%.0f".format(mb / 1000f)} GB"
    mb < 10f && mb != mb.toInt().toFloat() -> "${"%.1f".format(mb)} MB"
    else -> "${mb.toInt()} MB"
}

// ── Composable ──────────────────────────────────────────────────────────

@Composable
fun FunctionSection(
    selectedUri: Uri?,
    videoInfo: VideoInfo?,
    effectiveDurationSecs: Long,
    effectiveWidth: Int,
    effectiveHeight: Int,
    sizePresetIndex: Int,
    customSizeMb: Float?,
    isCompressing: Boolean,
    mainEnabled: Boolean = true,
    codecChoice: CodecChoice,
    onPresetIndex: (Int) -> Unit,
    onCustomSizeMb: (Float?) -> Unit,
    onStart: (Uri, Float) -> Unit,
    onStop: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary

    // Build presets from the effective (post‑edit) values
    val presets = remember(effectiveDurationSecs, effectiveWidth, effectiveHeight, videoInfo) {
        buildPresets(
            bitrateKbps = videoInfo?.bitrateKbps ?: 0,
            durationSecs = effectiveDurationSecs,
            width = effectiveWidth,
            height = effectiveHeight
        )
    }

    val maxIndex   = (presets.size - 1).coerceAtLeast(0)
    val clampedIdx = sizePresetIndex.coerceIn(0, maxIndex)

    LaunchedEffect(clampedIdx, sizePresetIndex) {
        if (clampedIdx != sizePresetIndex) onPresetIndex(clampedIdx)
    }

    val effectiveMb: Float = customSizeMb ?: presets[clampedIdx]
    var customText  by remember { mutableStateOf(customSizeMb?.toInt()?.toString() ?: "") }
    var customError by remember { mutableStateOf(false) }

    Card(
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Target Size",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSec
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = accent.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = if (customSizeMb != null)
                            "${formatPresetLabel(customSizeMb)} (custom)"
                        else
                            formatPresetLabel(presets[clampedIdx]),
                        fontSize = 12.sp,
                        color    = accent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            AnimatedVisibility(
                visible = customSizeMb == null,
                enter   = expandVertically(),
                exit    = shrinkVertically()
            ) {
                Column {
                    Slider(
                        value         = clampedIdx.toFloat(),
                        onValueChange = { onPresetIndex(it.toInt().coerceIn(0, maxIndex)) },
                        valueRange    = 0f..maxIndex.toFloat(),
                        steps         = (presets.size - 2).coerceAtLeast(0),
                        enabled       = !isCompressing && mainEnabled,
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = SliderDefaults.colors(
                            thumbColor            = accent,
                            activeTrackColor      = accent,
                            inactiveTrackColor    = Surface2,
                            activeTickColor       = accent.copy(alpha = 0.4f),
                            inactiveTickColor     = Surface2,
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        presets.forEach { mb ->
                            Text(
                                text     = formatPresetLabel(mb),
                                fontSize = 9.sp,
                                color    = TextSec.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = customSizeMb != null,
                enter   = expandVertically(),
                exit    = shrinkVertically()
            ) {
                OutlinedTextField(
                    value         = customText,
                    onValueChange = { raw ->
                        customText = raw
                        val parsed = raw.trim().toFloatOrNull()
                        customError = parsed == null || parsed <= 0f
                        if (!customError && parsed != null) onCustomSizeMb(parsed)
                    },
                    label         = { Text("Custom size (MB)") },
                    isError       = customError,
                    supportingText = if (customError) {
                        { Text("Enter a positive number") }
                    } else null,
                    singleLine    = true,
                    enabled       = !isCompressing && mainEnabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = accent,
                        focusedLabelColor    = accent,
                        cursorColor          = accent,
                        unfocusedBorderColor = TextSec.copy(alpha = 0.4f),
                        unfocusedLabelColor  = TextSec,
                    ),
                    modifier      = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier          = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked        = customSizeMb != null,
                    onCheckedChange = { checked ->
                        if (checked) {
                            val current = presets[clampedIdx]
                            customText = if (current == current.toInt().toFloat())
                                current.toInt().toString() else "%.1f".format(current)
                            onCustomSizeMb(current)
                        } else { onCustomSizeMb(null) }
                    },
                    enabled = !isCompressing && mainEnabled,
                    colors  = CheckboxDefaults.colors(
                        checkedColor   = accent,
                        checkmarkColor = Color.White,
                        uncheckedColor = TextSec
                    )
                )
                Text(
                    text  = "Custom size",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSec
                )
            }

            Spacer(Modifier.height(8.dp))

            if (isCompressing) {
                Button(
                    onClick  = onStop,
                    enabled  = mainEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = ErrorRed,
                        contentColor   = Color.White
                    )
                ) {
                    Text("Stop Compression", modifier = Modifier.padding(vertical = 4.dp))
                }
            } else {
                Button(
                    onClick  = { selectedUri?.let { onStart(it, effectiveMb) } },
                    enabled  = mainEnabled && selectedUri != null && (customSizeMb == null || !customError),
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor         = accent,
                        contentColor           = Color.White,
                        disabledContainerColor = accent.copy(alpha = 0.38f),
                        disabledContentColor   = Color.White.copy(alpha = 0.6f)
                    )
                ) {
                    Text("Start Compression", modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}