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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.shanks.minify.media3.VideoCompressor
import com.shanks.minify.utils.saveToGallery
import kotlinx.coroutines.delay
import java.io.File

@UnstableApi
@Composable
fun MainScreen() {
    val context = LocalContext.current

    // rememberSaveable so state survives config changes (e.g. screen rotation mid-compression)
    var selectedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var progress by rememberSaveable { mutableFloatStateOf(0f) }
    var status by rememberSaveable { mutableStateOf("Idle") }
    var quality by rememberSaveable { mutableIntStateOf(2) }
    var useH265 by rememberSaveable { mutableStateOf(false) }
    var isCompressing by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
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
            selectedUri = uri
            progress = 0f
            status = "Idle"
            isCompressing = false
        }

        // Animated swap between: empty / video preview / compression spinner
        AnimatedContent(
            targetState = when {
                isCompressing -> "compressing"
                selectedUri != null -> "preview"
                else -> "empty"
            },
            label = "preview_state"
        ) { state ->
            when (state) {
                "preview" -> VideoPreview(selectedUri!!)
                "compressing" -> CompressionPlaceholder()
                else -> Unit
            }
        }

        FunctionSection(
            selectedUri = selectedUri,
            quality = quality,
            isCompressing = isCompressing,
            onQuality = { quality = it },
            onStart = { uri ->
                isCompressing = true
                status = "Processing…"
                progress = 0f

                val output = File(context.cacheDir, "out_${System.currentTimeMillis()}.mp4")

                VideoCompressor.compress(
                    context = context,
                    inputUri = uri,
                    outputPath = output.absolutePath,
                    useH265 = useH265,
                    quality = quality,
                    onProgress = { progress = it },
                    onSuccess = {
                        try {
                            saveToGallery(context, output)
                            status = "Done ✅"
                        } catch (e: Exception) {
                            status = "Save failed: ${e.localizedMessage}"
                        } finally {
                            // Always delete the cache temp file after saving
                            output.delete()
                        }
                        isCompressing = false
                    },
                    onFailure = { error ->
                        output.delete() // Clean up temp file on failure too
                        status = "Error: ${error.localizedMessage}"
                        isCompressing = false
                    }
                )
            }
        )

        CodecToggle(useH265) { useH265 = it }

        ProgressSection(progress, status)
    }

    LaunchedEffect(status) {
        if (status.startsWith("Done")) {
            delay(3000)
            status = "Idle"
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

@UnstableApi
@Composable
fun VideoPreview(uri: Uri) {
    val context = LocalContext.current

    // Single stable player for the lifetime of this composable — not keyed on uri.
    // Rebuilding the player on each URI change caused a race: the old player was
    // released AFTER the new one was assigned to PlayerView, blanking the preview.
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = false
        }
    }

    // Swap media item whenever uri changes — no player rebuild needed.
    LaunchedEffect(uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.seekTo(0)
    }

    // Release only when the composable leaves the composition entirely.
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
                this.player = player
                useController = true
                // Override ExoPlayer's default black background with a neutral dark gray
                setBackgroundColor(android.graphics.Color.parseColor("#1C1C1E"))
                subtitleView?.visibility = android.view.View.GONE
            }
        }
        // No update block needed — player reference is stable
    )
}