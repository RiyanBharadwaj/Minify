package com.shanks.minify.ui

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.shanks.minify.ffmpeg.VideoCompressor
import com.shanks.minify.utils.copyUriToFile
import com.shanks.minify.utils.saveVideoToGallery
import java.io.File
import kotlin.math.roundToInt

@Composable
fun FunctionSection(
    selectedUri: Uri?,
    onProgress: (Float) -> Unit,
    onSuccess: () -> Unit,
    onFailure: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current

    val qualityOptions = listOf("Poor", "Okay", "Normal", "Good", "Excellent", "Max")
    val resolutionOptions = listOf(144, 240, 360, 480, 720, 1080)
    val formatOptions = listOf("mp4", "mkv")
    val bitrateOptions = listOf(500, 800, 1500, 2500, 4000, 6000)

    var qualityIndex by remember { mutableStateOf(2f) }
    var resolutionIndex by remember { mutableStateOf(4f) }
    var formatIndex by remember { mutableStateOf(0) }

    var maxQualityIndex by remember { mutableStateOf(5f) }
    var maxResolutionIndex by remember { mutableStateOf(5f) }

    val qIndex = qualityIndex.roundToInt().coerceIn(0, maxQualityIndex.roundToInt())
    val rIndex = resolutionIndex.roundToInt().coerceIn(0, maxResolutionIndex.roundToInt())

    val bitrate = bitrateOptions[qIndex]

    var expanded by remember { mutableStateOf(false) }
    var estimatedSize by remember { mutableStateOf<Float?>(null) }
    var estimating by remember { mutableStateOf(false) }

    // Extract original video stats and cap sliders to prevent upscaling
    LaunchedEffect(selectedUri) {
        if (selectedUri == null) {
            maxResolutionIndex = 5f
            maxQualityIndex = 5f
            return@LaunchedEffect
        }

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, selectedUri)

        val videoHeight = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
        )?.toInt() ?: 1080

        val videoWidth = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
        )?.toInt() ?: 1920

        val videoBitrateKbps = (retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_BITRATE
        )?.toLong() ?: 6000000L) / 1000L

        retriever.release()

        // Use the shorter side to handle both portrait and landscape correctly
        val videoShortSide = minOf(videoHeight, videoWidth)

        maxResolutionIndex = resolutionOptions
            .indexOfLast { it <= videoShortSide }
            .takeIf { it >= 0 }?.toFloat() ?: 5f

        maxQualityIndex = bitrateOptions
            .indexOfLast { it <= videoBitrateKbps }
            .takeIf { it >= 0 }?.toFloat() ?: 5f

        // Clamp current selections if they exceed the new max
        if (resolutionIndex > maxResolutionIndex) resolutionIndex = maxResolutionIndex
        if (qualityIndex > maxQualityIndex) qualityIndex = maxQualityIndex
    }

    // Estimate output file size using a 2-second preview encode
    LaunchedEffect(qIndex, rIndex, selectedUri) {
        if (selectedUri == null) {
            estimatedSize = null
            return@LaunchedEffect
        }

        estimating = true

        val inputFile = copyUriToFile(context, selectedUri)

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, selectedUri)

        val durationMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLong() ?: 0L

        retriever.release()

        val previewFile = File(context.cacheDir, "preview.mp4")

        val res = resolutionOptions[rIndex]
        val scaleFilter = "scale=if(gt(iw\\,ih)\\,$res\\,-2):if(gt(iw\\,ih)\\,-2\\,$res)"

        val command = "-y -i \"${inputFile.absolutePath}\" " +
                "-t 2 " +
                "-vf $scaleFilter " +
                "-c:v mpeg4 " +
                "-b:v ${bitrate}k " +
                "-c:a aac " +
                "-b:a 128k " +
                "\"${previewFile.absolutePath}\""

        FFmpegKit.executeAsync(command) { session ->
            if (ReturnCode.isSuccess(session.returnCode)) {
                val previewSize = previewFile.length()
                val durationSec = durationMs / 1000f
                val estimatedBytes = previewSize * (durationSec / 2f)
                estimatedSize = estimatedBytes / 1024f / 1024f
            }

            previewFile.delete()
            estimating = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {

        Text("Function", color = Color.White)

        Spacer(Modifier.height(10.dp))

        Text("Quality: ${qualityOptions[qIndex]}", color = Color.White)
        Slider(
            value = qualityIndex,
            onValueChange = { qualityIndex = it },
            valueRange = 0f..maxQualityIndex,
            steps = if (maxQualityIndex > 0) maxQualityIndex.toInt() - 1 else 0
        )

        Spacer(Modifier.height(10.dp))

        Text("Resolution: ${resolutionOptions[rIndex]}p", color = Color.White)
        Slider(
            value = resolutionIndex,
            onValueChange = { resolutionIndex = it },
            valueRange = 0f..maxResolutionIndex,
            steps = if (maxResolutionIndex > 0) maxResolutionIndex.toInt() - 1 else 0
        )

        Spacer(Modifier.height(10.dp))

        Text("Format", color = Color.White)

        Box {
            Button(onClick = { expanded = true }) {
                Text(formatOptions[formatIndex])
            }

            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                formatOptions.forEachIndexed { index, format ->
                    DropdownMenuItem(
                        text = { Text(format) },
                        onClick = {
                            formatIndex = index
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        when {
            estimating -> Text("Estimating...", color = Color.Gray)
            estimatedSize != null -> Text(
                "Estimated size: %.2f MB".format(estimatedSize),
                color = Color.Gray
            )
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                if (selectedUri == null) return@Button

                val inputFile = copyUriToFile(context, selectedUri)
                val tempOutput = File(context.cacheDir, "output.${formatOptions[formatIndex]}")

                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, selectedUri)

                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLong() ?: 0L

                retriever.release()

                VideoCompressor.compress(
                    inputFile.absolutePath,
                    tempOutput.absolutePath,
                    bitrate,
                    resolutionOptions[rIndex],
                    durationMs,
                    onProgress,
                    {
                        saveVideoToGallery(context, tempOutput)
                        onSuccess()
                    },
                    onFailure
                )
            },
            enabled = selectedUri != null
        ) {
            Text("Start")
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(onClick = { onClear() }) {
            Text("Clear")
        }
    }
}