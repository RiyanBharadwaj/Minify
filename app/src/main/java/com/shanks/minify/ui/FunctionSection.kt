@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.shanks.minify.ui

import android.net.Uri
import androidx.compose.animation.*
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
import com.shanks.minify.logic.SizeResult
import com.shanks.minify.logic.SizeSelection
import com.shanks.minify.ui.theme.*
import com.shanks.minify.utils.VideoInfo
import kotlin.math.*

private val TextSec   = Color(0xFF8E8E93)

private fun generatePresets(minMb: Float, maxMb: Float, maxSteps: Int = 6): List<Float> {
    if (maxMb <= minMb) return listOf(minMb)
    val steps = maxSteps.coerceAtLeast(2)
    val logMin = ln(minMb.toDouble())
    val logMax = ln(maxMb.toDouble())
    val raw = (0 until steps).map { i -> exp(logMin + i.toDouble() / (steps - 1) * (logMax - logMin)).toFloat() }
    val distinct = raw.map { roundToNice(it) }.distinct().sorted().toMutableList()
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

private fun buildPresets(bitrateKbps: Int, durationSecs: Long, width: Int, height: Int): List<Float> {
    if (durationSecs <= 0 || bitrateKbps <= 0 || width <= 0 || height <= 0) return listOf(5f, 10f, 25f, 50f, 100f, 200f)
    val srcMb = (bitrateKbps.toFloat() * durationSecs) / 8_000f
    val maxMb = roundToNice(srcMb)

    // Sub-1 MB ladder (0.1..0.9 in exact 0.1 MB steps), floored at 0.1 MB (Req 4.1/4.2/4.6).
    // Only include entries that don't exceed the source size.
    val smallLadder = SizeSelection.smallTargetPresets().filter { it <= maxMb }

    // Entire achievable range is below 1 MB: use only the sub-1 MB ladder.
    if (maxMb < 1f) {
        return smallLadder.ifEmpty { listOf(SizeSelection.ABS_MIN_MB) }
    }

    // Larger presets: preserve existing log-spaced behaviour from >= 1 MB up.
    val largeMin = max(1f, roundToNice(srcMb * 0.08f))
    val largePresets = if (maxMb <= largeMin + 0.5f) {
        listOf(largeMin, maxMb).distinct()
    } else {
        val steps = when {
            maxMb - largeMin < 3f  -> 2; maxMb - largeMin < 8f  -> 3; maxMb - largeMin < 20f -> 4
            maxMb - largeMin < 50f -> 5; else -> 6
        }
        generatePresets(largeMin, maxMb, steps)
    }

    // For sources >= 1 MB, do NOT offer the sub-1 MB ladder: shrinking a large
    // video below 1 MB via a preset makes no sense. Users who genuinely want a
    // sub-1 MB target can still enter it via the custom-size field. The sub-1 MB
    // ladder only appears when the whole achievable range is below 1 MB (handled
    // by the `maxMb < 1f` branch above, i.e. the input itself is under 1 MB).
    return largePresets
}

private fun formatPresetLabel(mb: Float): String = when {
    mb >= 1000f -> "${"%.0f".format(mb / 1000f)} GB"
    mb < 1f -> "${"%.1f".format(SizeSelection.roundToTenth(mb))} MB"   // Req 4.3: 0.1 MB resolution
    mb < 10f && mb != mb.toInt().toFloat() -> "${"%.1f".format(mb)} MB"
    else -> "${mb.toInt()} MB"
}

/** Render a custom target-size value for the text field, preserving the decimal when sub-integer. */
private fun formatCustomText(mb: Float): String =
    if (mb == mb.toInt().toFloat()) mb.toInt().toString() else "%.1f".format(mb)

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
    onStart: (Uri, Float) -> Unit
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
    var customText     by remember { mutableStateOf(customSizeMb?.let { formatCustomText(it) } ?: "") }
    var customErrorMsg by remember { mutableStateOf<String?>(null) }
    val customError = customErrorMsg != null

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
                        // Parse decimal input via SizeSelection (Req 4.4/4.5/4.7).
                        when (val result = SizeSelection.validateCustom(raw)) {
                            is SizeResult.Ok -> {
                                customErrorMsg = null
                                onCustomSizeMb(result.mb)   // valid [0.1, ..) passes straight through
                            }
                            SizeResult.NotPositive ->
                                customErrorMsg = "Enter a positive number"
                            SizeResult.BelowMinimum ->
                                customErrorMsg = "Minimum size is ${SizeSelection.ABS_MIN_MB} MB"
                        }
                    },
                    label         = { Text("Custom size (MB)") },
                    isError       = customError,
                    supportingText = customErrorMsg?.let { msg -> { Text(msg) } },
                    singleLine    = true,
                    enabled       = !isCompressing && mainEnabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
                            val current = SizeSelection.roundToTenth(presets[clampedIdx])
                            customText = formatCustomText(current)
                            customErrorMsg = null
                            onCustomSizeMb(current)
                        } else {
                            customErrorMsg = null
                            onCustomSizeMb(null)
                        }
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

            // Start control only. This section is hidden entirely during a
            // Compression_Session (Req 5.1); the cancel control lives in the
            // always-visible status area (Req 5.6).
            Button(
                onClick  = { selectedUri?.let { onStart(it, effectiveMb) } },
                enabled  = mainEnabled && !isCompressing && selectedUri != null && (customSizeMb == null || !customError),
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