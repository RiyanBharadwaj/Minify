package com.shanks.minify.ui

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FileSection(
    onFileSelected: (Pair<Uri, String>) -> Unit,
    fileName: String,
    selectedUri: Uri?
) {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            // Fixed: extract actual display name from URI instead of hardcoding
            val name = context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                if (index >= 0) cursor.getString(index) else null
            } ?: "Unknown"
            onFileSelected(Pair(it, name))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text("File", color = Color.White)
        Spacer(modifier = Modifier.height(10.dp))

        if (selectedUri == null) {
            Button(onClick = { launcher.launch("video/*") }) { Text("Select a video") }
        } else {
            key(selectedUri) {
                VideoPreview(uri = selectedUri)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(fileName, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPreview(uri: Uri) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var startPlayer by remember { mutableStateOf(false) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var supportStatus by remember { mutableStateOf<VideoSupportStatus>(VideoSupportStatus.Checking) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            val retriever = MediaMetadataRetriever()
            try {
                extractor.setDataSource(context, uri, null)
                var isHevc = false
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.contains("hevc", true) || mime.contains("h265", true)) {
                        isHevc = true
                        break
                    }
                }

                retriever.setDataSource(context, uri)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0

                // Fixed: recycle both fullBitmap and old thumbnail to prevent memory leaks
                val fullBitmap = retriever.getFrameAtTime(0)
                fullBitmap?.let {
                    val scale = 300f / it.width
                    val scaled = Bitmap.createScaledBitmap(it, 300, (it.height * scale).toInt(), true)
                    it.recycle()
                    thumbnail = scaled
                }

                supportStatus = when {
                    isHevc -> VideoSupportStatus.Unsupported("HEVC (H.265) is not supported for preview.")
                    width > 1920 || height > 1920 -> VideoSupportStatus.Unsupported("4K resolution is too high for preview.")
                    else -> VideoSupportStatus.Supported
                }
            } catch (e: Exception) {
                supportStatus = VideoSupportStatus.Unsupported("Could not analyze video file.")
            } finally {
                extractor.release()
                retriever.release()
            }
        }
    }

    // Fixed: recycle thumbnail bitmap when composable leaves composition
    DisposableEffect(uri) {
        onDispose {
            thumbnail?.recycle()
            thumbnail = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Color.Black, RoundedCornerShape(8.dp))
    ) {
        if (!startPlayer) {
            thumbnail?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    alpha = if (supportStatus is VideoSupportStatus.Unsupported) 0.5f else 1f
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (val status = supportStatus) {
                    is VideoSupportStatus.Checking -> CircularProgressIndicator(color = Color.White)
                    is VideoSupportStatus.Unsupported -> {
                        Text("Unsupported", color = Color.Red, style = MaterialTheme.typography.titleMedium)
                        // Fixed: added Spacer between title and reason
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(status.reason, color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                    is VideoSupportStatus.Supported -> {
                        Button(onClick = { startPlayer = true }) { Text("Play Preview") }
                    }
                }
            }
        } else {
            val exoPlayer = remember {
                val rf = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
                ExoPlayer.Builder(context, rf).build().apply {
                    setMediaItem(MediaItem.fromUri(uri))
                    prepare()
                    playWhenReady = true
                }
            }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> exoPlayer.playWhenReady = false
                        // Fixed: resume playback when returning to app
                        Lifecycle.Event.ON_RESUME -> exoPlayer.playWhenReady = true
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    exoPlayer.release()
                }
            }

            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

sealed class VideoSupportStatus {
    object Checking : VideoSupportStatus()
    object Supported : VideoSupportStatus()
    data class Unsupported(val reason: String) : VideoSupportStatus()
}