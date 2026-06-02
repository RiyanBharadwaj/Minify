@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.shanks.minify.ui

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.shanks.minify.media3.CompressionJob
import com.shanks.minify.media3.VideoCompressor
import com.shanks.minify.utils.VideoInfo
import com.shanks.minify.utils.getVideoInfo
import com.shanks.minify.utils.saveToGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private fun friendlyError(e: Exception): String {
    val msg = e.localizedMessage ?: e.javaClass.simpleName
    return when {
        msg.contains("codec",     ignoreCase = true) ||
                msg.contains("CodecInfo", ignoreCase = true) ->
            "Compression failed ❌ — this video format isn't supported on your device. Try switching codec (H.264 ↔ H.265)."
        msg.contains("permission", ignoreCase = true) ->
            "Compression failed ❌ — storage permission denied."
        msg.contains("space",  ignoreCase = true) ||
                msg.contains("ENOSPC", ignoreCase = true) ->
            "Compression failed ❌ — not enough storage space."
        msg.contains("timeout", ignoreCase = true) ->
            "Compression failed ❌ — timed out. Try a shorter video."
        else ->
            "Compression failed ❌ — try a different codec or target size."
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current

    var selectedUri     by rememberSaveable { mutableStateOf<Uri?>(null) }
    var videoInfo       by remember { mutableStateOf<VideoInfo?>(null) }
    var progress        by rememberSaveable { mutableFloatStateOf(0f) }
    var status          by rememberSaveable { mutableStateOf("Idle") }
    var sizePresetIndex by rememberSaveable { mutableIntStateOf(2) }
    var customSizeMb    by rememberSaveable { mutableStateOf<Float?>(null) }
    var useH265         by rememberSaveable { mutableStateOf(false) }
    var isCompressing   by rememberSaveable { mutableStateOf(false) }

    // Not saveable — compression cannot survive process death.
    val activeJob = remember { mutableStateOf<CompressionJob?>(null) }

    LaunchedEffect(selectedUri) {
        videoInfo = selectedUri?.let { uri ->
            withContext(Dispatchers.IO) { getVideoInfo(context, uri) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Minify",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Video Compressor",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        FileSection(selectedUri) { uri ->
            // FIX: fully reset compression state when a new file is picked,
            // including cancelling any in-flight job so we don't leak it.
            if (isCompressing) {
                activeJob.value?.cancel()
                activeJob.value  = null
                isCompressing    = false
            }
            selectedUri = uri
            progress    = 0f
            status      = "Idle"
        }

        AnimatedContent(
            targetState = when {
                isCompressing       -> "compressing"
                selectedUri != null -> "preview"
                else                -> "empty"
            },
            label = "preview_state"
        ) { state ->
            when (state) {
                "preview"     -> VideoPreview(selectedUri!!)
                "compressing" -> CompressionPlaceholder()
                else          -> Unit
            }
        }

        FunctionSection(
            selectedUri     = selectedUri,
            videoInfo       = videoInfo,
            sizePresetIndex = sizePresetIndex,
            customSizeMb    = customSizeMb,
            isCompressing   = isCompressing,
            useH265         = useH265,
            onPresetIndex   = { sizePresetIndex = it },
            onCustomSizeMb  = { customSizeMb = it },
            onStart         = { uri, effectiveMb ->
                isCompressing = true
                status        = "Processing…"
                progress      = 0f

                val output = File(context.cacheDir, "out_${System.currentTimeMillis()}.mp4")

                val job = VideoCompressor.compress(
                    context      = context,
                    inputUri     = uri,
                    outputPath   = output.absolutePath,
                    useH265      = useH265,
                    targetSizeMb = effectiveMb,
                    onProgress   = { progress = it },
                    onSuccess    = {
                        try {
                            saveToGallery(context, output)
                            status = "Done ✅"
                        } catch (e: Exception) {
                            status = "Save failed: ${e.localizedMessage}"
                        } finally {
                            output.delete()
                        }
                        isCompressing   = false
                        activeJob.value = null
                    },
                    onCancelled  = {
                        output.delete()
                        status          = "Cancelled"
                        progress        = 0f
                        isCompressing   = false
                        activeJob.value = null
                    },
                    onFailure    = { error ->
                        output.delete()
                        status          = friendlyError(error)
                        isCompressing   = false
                        activeJob.value = null
                    }
                )
                activeJob.value = job
            },
            onStop = {
                // FIX: guard against double-cancel (e.g. rapid taps on Stop).
                activeJob.value?.cancel()
                // activeJob is cleared in the onCancelled callback above.
            }
        )

        CodecToggle(useH265) { useH265 = it }

        ProgressSection(progress, status)
    }

    // Auto-clear terminal status messages after a short delay.
    LaunchedEffect(status) {
        if (status.startsWith("Done") || status == "Cancelled") {
            delay(3000)
            status   = "Idle"
            progress = 0f
        }
    }
}

@Composable
private fun CompressionPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                "Compressing…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun VideoPreview(uri: Uri) {
    val context = LocalContext.current

    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = false }
    }

    LaunchedEffect(uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.seekTo(0)
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp)),
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player  = player
                useController = true
                setBackgroundColor(android.graphics.Color.parseColor("#1C1C1E"))
                subtitleView?.visibility = android.view.View.GONE
            }
        }
    )
}