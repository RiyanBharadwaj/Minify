package com.shanks.minify.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.shanks.minify.media3.VideoCompressor
import com.shanks.minify.utils.VideoInfo
import kotlin.math.min
import kotlin.math.roundToInt

private val QUALITY_LABELS = listOf("Poor", "Okay", "Normal", "Good", "Excellent", "Max")

private data class TierPreview(
    val estimatedBitrateKbps: Int,
    val sizeLabel: String?,
    val resolution: String,
    val isSrcCapBinding: Boolean
)

@UnstableApi
private fun computeTierPreview(tier: Int, info: VideoInfo, useH265: Boolean): TierPreview {
    val scale        = VideoCompressor.SCALE[tier]
    val outW         = ((info.width  * scale).roundToInt() + 15) / 16 * 16
    val outH         = ((info.height * scale).roundToInt() + 15) / 16 * 16
    val codecFactor  = if (useH265) 0.50f else 1.0f

    val outputMp     = (outW * outH) / 1_000_000f
    val resTgt       = (VideoCompressor.KBPS_PER_MP_H264[tier] * outputMp * codecFactor).roundToInt()
    val srcCap       = (info.bitrateKbps * codecFactor * 0.80f).roundToInt()
    val floorKbps    = VideoCompressor.ABS_FLOOR_BPS[tier] / 1000
    val isSrcCapBinding = srcCap < resTgt

    val rawTargetKbps = min(resTgt, srcCap).coerceAtLeast(floorKbps)
    val estimatedVideoBitrateKbps = (rawTargetKbps * VideoCompressor.OVERHEAD_FACTOR).roundToInt()

    val sizeLabel = if (info.durationSecs <= 0) null else {
        // Fallback audio estimate until audioBitrateKbps is added to VideoInfo.
        // ~12% of source total bitrate, clamped to realistic AAC range.
        val audioKbps = (info.bitrateKbps * 0.12f).roundToInt().coerceIn(64, 192)
        val totalKbps = estimatedVideoBitrateKbps + audioKbps
        val mb        = (totalKbps * info.durationSecs) / 8_000f

        if (isSrcCapBinding) {
            "~${"%.1f".format(mb * 0.85f)}–${"%.1f".format(mb * 1.20f)} MB"
        } else {
            "~${"%.1f".format(mb * 0.90f)}–${"%.1f".format(mb * 1.10f)} MB"
        }
    }

    return TierPreview(
        estimatedBitrateKbps = estimatedVideoBitrateKbps,
        sizeLabel            = sizeLabel,
        resolution           = "${outW}×${outH}",
        isSrcCapBinding      = isSrcCapBinding
    )
}

private fun formatBitrate(kbps: Int): String = when {
    kbps >= 1_000 -> "${"%.1f".format(kbps / 1_000f)} Mbps"
    else          -> "$kbps kbps"
}

@UnstableApi
@Composable
fun FunctionSection(
    selectedUri: Uri?,
    videoInfo: VideoInfo?,
    quality: Int,
    isCompressing: Boolean,
    useH265: Boolean,
    onQuality: (Int) -> Unit,
    onStart: (Uri) -> Unit
) {
    val tierPreviews = remember(videoInfo, useH265) {
        videoInfo?.let { info -> (0..5).map { computeTierPreview(it, info, useH265) } }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Quality",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = QUALITY_LABELS[quality],
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            AnimatedVisibility(visible = tierPreviews != null) {
                tierPreviews?.get(quality)?.let { preview ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = preview.resolution,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            text = buildString {
                                append(formatBitrate(preview.estimatedBitrateKbps))
                                if (preview.sizeLabel != null) append(" · ${preview.sizeLabel}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Slider(
                value = quality.toFloat(),
                onValueChange = { onQuality(it.toInt().coerceIn(0, 5)) },
                valueRange = 0f..5f,
                steps = 4,
                enabled = !isCompressing,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                QUALITY_LABELS.forEach { label ->
                    Text(
                        text = label.first().toString(),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { selectedUri?.let(onStart) },
                enabled = selectedUri != null && !isCompressing,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (isCompressing) "Compressing…" else "Start Compression",
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}