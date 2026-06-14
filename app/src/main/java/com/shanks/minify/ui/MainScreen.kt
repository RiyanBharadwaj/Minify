@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.shanks.minify.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.shanks.minify.media3.CompressionJob
import com.shanks.minify.media3.VideoCompressor
import com.shanks.minify.utils.VideoInfo
import com.shanks.minify.utils.getVideoInfo
import com.shanks.minify.utils.saveToGallery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val BgDark   = Color(0xFF0D0B14)
private val Surface1 = Color(0xFF1A1625)
private val Surface2 = Color(0xFF251E35)
private val AccentCyan = Color(0xFF32D2F0)
private val TextPrim = Color(0xFFFFFFFF)
private val TextSec  = Color(0xFF8E8E93)
private val ErrorRed = Color(0xFFFF453A)
private val GreenOk  = Color(0xFF30D158)

private fun friendlyError(e: Exception): String {
    val msg = e.localizedMessage ?: e.javaClass.simpleName
    return when {
        msg.contains("codec", ignoreCase = true) ||
                msg.contains("CodecInfo", ignoreCase = true) ->
            "Codec not supported — try a different one."
        msg.contains("permission", ignoreCase = true) ->
            "Storage permission denied."
        msg.contains("space", ignoreCase = true) ||
                msg.contains("ENOSPC", ignoreCase = true) ->
            "Not enough storage space."
        else -> "Compression failed — try a different codec or size."
    }
}

@Composable
fun MainScreen(
    currentAccent: com.shanks.minify.ui.theme.AppAccent,
    currentAccentColor: androidx.compose.ui.graphics.Color,
    onAccentChange: (com.shanks.minify.ui.theme.AppAccent, androidx.compose.ui.graphics.Color) -> Unit,
) {
    val context = LocalContext.current

    var selectedUri     by rememberSaveable { mutableStateOf<Uri?>(null) }
    var videoInfo       by remember { mutableStateOf<VideoInfo?>(null) }
    var progress        by rememberSaveable { mutableFloatStateOf(0f) }
    var status          by rememberSaveable { mutableStateOf("") }
    var sizePresetIdx   by rememberSaveable { mutableIntStateOf(2) }
    var customSizeMb    by rememberSaveable { mutableStateOf<Float?>(null) }
    var codecChoice     by rememberSaveable { mutableStateOf(CodecChoice.H264) }
    var isCompressing   by rememberSaveable { mutableStateOf(false) }
    var editState       by rememberSaveable(stateSaver = EditState.Saver) { mutableStateOf(EditState()) }
    var showEditor      by remember { mutableStateOf(false) }
    var showSettings    by remember { mutableStateOf(false) }

    var isPlaying       by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var playerInstance  by remember { mutableStateOf<ExoPlayer?>(null) }

    var beforeSizeBytes by remember { mutableLongStateOf(0L) }
    var afterSizeBytes  by remember { mutableLongStateOf(0L) }
    var showCompressionCompleteDialog by remember { mutableStateOf(false) }

    val activeJob = remember { mutableStateOf<CompressionJob?>(null) }
    val mainEnabled = !showEditor && !showSettings

    val effectiveDurationSecs = remember(videoInfo, editState) {
        val full = videoInfo?.durationSecs ?: 0L
        val trimStart = editState.trimStartMs / 1000L
        val trimEnd   = editState.trimEndMs?.div(1000L) ?: full
        (trimEnd - trimStart).coerceAtLeast(1L)
    }

    val effectiveWidth = remember(videoInfo, editState) {
        val baseW = videoInfo?.width ?: 1920
        val crop = editState.cropRect ?: CropRect.FULL
        (baseW * crop.width).toInt().coerceAtLeast(1)
    }

    val effectiveHeight = remember(videoInfo, editState) {
        val baseH = videoInfo?.height ?: 1080
        val crop = editState.cropRect ?: CropRect.FULL
        (baseH * crop.height).toInt().coerceAtLeast(1)
    }

    LaunchedEffect(selectedUri) {
        videoInfo = selectedUri?.let { uri ->
            withContext(Dispatchers.IO) { getVideoInfo(context, uri) }
        }
        editState = EditState()
        isPlaying = false
        currentPositionMs = 0L
        playerInstance?.stop()
        playerInstance = null
        beforeSizeBytes = 0L
        afterSizeBytes = 0L
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 56.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AppHeader(onSettings = { if (mainEnabled) showSettings = true })

            FileSection(
                selectedUri = selectedUri,
                enabled     = !isCompressing && mainEnabled,
                onSelect    = { uri ->
                    if (isCompressing) { activeJob.value?.cancel(); activeJob.value = null; isCompressing = false }
                    selectedUri = uri; progress = 0f; status = ""
                }
            )

            AnimatedVisibility(
                visible = selectedUri != null,
                enter   = fadeIn() + expandVertically(),
                exit    = fadeOut() + shrinkVertically()
            ) {
                PreviewWithEditButton(
                    uri            = selectedUri,
                    videoInfo      = videoInfo,
                    editState      = editState,
                    isCompressing  = isCompressing,
                    mainEnabled    = mainEnabled,
                    onEdit         = { if (mainEnabled) showEditor = true },
                    isPlaying      = isPlaying,
                    onPlayPause    = { isPlaying = it },
                    currentPosition = currentPositionMs,
                    onPositionChanged = { currentPositionMs = it },
                    onPlayerReady  = { playerInstance = it }
                )
            }

            AnimatedVisibility(visible = editState.hasEdits && selectedUri != null) {
                EditSummaryRow(editState)
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FunctionSection(
                    selectedUri       = selectedUri,
                    videoInfo         = videoInfo,
                    effectiveDurationSecs = effectiveDurationSecs,
                    effectiveWidth    = effectiveWidth,
                    effectiveHeight   = effectiveHeight,
                    sizePresetIndex   = sizePresetIdx,
                    customSizeMb      = customSizeMb,
                    isCompressing     = isCompressing,
                    mainEnabled       = mainEnabled,
                    codecChoice       = codecChoice,
                    onPresetIndex     = { sizePresetIdx = it },
                    onCustomSizeMb    = { customSizeMb = it },
                    onStart           = { uri, effectiveMb ->
                        isCompressing = true; status = ""; progress = 0f
                        CoroutineScope(Dispatchers.IO).launch {
                            beforeSizeBytes = try {
                                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                                } ?: 0L
                            } catch (e: Exception) { 0L }
                            withContext(Dispatchers.Main) {
                                val output = File(context.cacheDir, "out_${System.currentTimeMillis()}.mp4")
                                val job = VideoCompressor.compress(
                                    context      = context,
                                    inputUri     = uri,
                                    outputPath   = output.absolutePath,
                                    codecChoice  = codecChoice,
                                    targetSizeMb = effectiveMb,
                                    editState    = editState,
                                    onProgress   = { progress = it },
                                    onSuccess    = {
                                        afterSizeBytes = output.length()
                                        try { saveToGallery(context, output); status = "done" }
                                        catch (e: Exception) { status = "error:Save failed: ${e.localizedMessage}" }
                                        finally { output.delete() }
                                        isCompressing = false; activeJob.value = null
                                    },
                                    onCancelled  = {
                                        output.delete(); status = "cancelled"; progress = 0f
                                        isCompressing = false; activeJob.value = null
                                    },
                                    onFailure    = { e ->
                                        output.delete(); status = "error:${friendlyError(e)}"
                                        isCompressing = false; activeJob.value = null
                                    }
                                )
                                activeJob.value = job
                            }
                        }
                    },
                    onStop = { activeJob.value?.cancel() }
                )
                CodecSelector(
                    selected = codecChoice,
                    onChange = { codecChoice = it },
                    enabled  = !isCompressing && mainEnabled
                )
            }

            AnimatedVisibility(visible = isCompressing || status.isNotEmpty()) {
                StatusCard(progress = progress, status = status, isCompressing = isCompressing)
            }
        }

        // Stylish compression-complete dialog
        if (showCompressionCompleteDialog) {
            val accent = MaterialTheme.colorScheme.primary
            AlertDialog(
                onDismissRequest = {
                    showCompressionCompleteDialog = false
                    status = ""
                    progress = 0f
                },
                icon = {
                    // Circular icon with checkmark
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    listOf(accent, accent.copy(alpha = 0.4f))
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✓",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                },
                title = {
                    Text(
                        text = "Compression Complete",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = TextPrim
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Original size
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Original", color = TextSec, fontSize = 14.sp)
                            Text(formatFileSize(beforeSizeBytes), color = TextPrim, fontSize = 14.sp)
                        }
                        // Compressed size
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Compressed", color = TextSec, fontSize = 14.sp)
                            Text(formatFileSize(afterSizeBytes), color = TextPrim, fontSize = 14.sp)
                        }
                        // Divider
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(accent.copy(alpha = 0.2f))
                        )
                        // Reduction
                        if (beforeSizeBytes > 0 && afterSizeBytes > 0) {
                            val reduction = ((beforeSizeBytes - afterSizeBytes).toFloat() / beforeSizeBytes * 100).toInt()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Reduction", color = TextSec, fontSize = 14.sp)
                                Text(
                                    "$reduction%",
                                    color = GreenOk,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showCompressionCompleteDialog = false
                            status = ""
                            progress = 0f
                        },
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text("OK", color = accent, fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Surface1,
                titleContentColor = TextPrim,
                textContentColor = TextPrim,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }

    // Full-screen editor
    AnimatedVisibility(
        visible  = showEditor && selectedUri != null && videoInfo != null,
        enter    = slideInHorizontally { it },
        exit     = slideOutHorizontally { it },
        modifier = Modifier.fillMaxSize()
    ) {
        if (selectedUri != null && videoInfo != null) {
            VideoEditorScreen(
                uri       = selectedUri!!,
                videoInfo = videoInfo!!,
                initial   = editState,
                onDone    = { newEdit -> editState = newEdit; showEditor = false },
                onDismiss = { showEditor = false }
            )
        }
    }

    // Full-screen settings
    AnimatedVisibility(
        visible  = showSettings,
        enter    = slideInHorizontally { it },
        exit     = slideOutHorizontally { it },
        modifier = Modifier.fillMaxSize()
    ) {
        SettingsScreen(
            currentAccent      = currentAccent,
            currentAccentColor = currentAccentColor,
            onAccentChange     = onAccentChange,
            onBack             = { showSettings = false }
        )
    }

    LaunchedEffect(status) {
        if (status == "done") {
            showCompressionCompleteDialog = true
        } else if (status == "cancelled" || status.startsWith("error:")) {
            delay(4000)
            status = ""
            progress = 0f
        }
    }
}

// ── Sub-composables ────────────────────────────────────────────────────────

@Composable
private fun AppHeader(onSettings: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        Brush.radialGradient(listOf(AccentCyan, accent)),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("M", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
            Column {
                Text("Minify", color = TextPrim, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text("Video Compressor", color = TextSec, fontSize = 12.sp)
            }
        }
        IconButton(onClick = onSettings) {
            Text(text = "⚙", fontSize = 22.sp, color = TextSec)
        }
    }
}

internal fun VideoSize.displayAspectRatio(): Float {
    if (width <= 0 || height <= 0) return 0f
    val raw = width.toFloat() * pixelWidthHeightRatio / height.toFloat()
    return if (unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270) 1f / raw else raw
}

@Composable
private fun PreviewWithEditButton(
    uri: Uri?,
    videoInfo: VideoInfo?,
    editState: EditState,
    isCompressing: Boolean,
    mainEnabled: Boolean,
    onEdit: () -> Unit,
    isPlaying: Boolean,
    onPlayPause: (Boolean) -> Unit,
    currentPosition: Long,
    onPositionChanged: (Long) -> Unit,
    onPlayerReady: (ExoPlayer) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary

    var playerVideoAr by remember { mutableFloatStateOf(0f) }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    LaunchedEffect(player) { onPlayerReady(player) }
    LaunchedEffect(isPlaying) { player.playWhenReady = isPlaying }

    var sliderPos by remember { mutableFloatStateOf(0f) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekTarget by remember { mutableLongStateOf(-1L) }

    val fullDurationMs = videoInfo?.durationSecs?.times(1000L) ?: 0L
    val trimStartMs   = editState.trimStartMs
    val trimEndMs     = editState.trimEndMs ?: fullDurationMs
    val displayDurationMs = (trimEndMs - trimStartMs).coerceAtLeast(0L)

    var mediaItemReady by remember { mutableStateOf(false) }

    LaunchedEffect(player) {
        while (true) {
            if (!isSeeking) {
                onPositionChanged(player.currentPosition)
            } else if (seekTarget >= 0L) {
                val pos = player.currentPosition
                if (kotlin.math.abs(pos - seekTarget) < 100L) {
                    onPositionChanged(pos)
                    isSeeking = false
                    seekTarget = -1L
                }
            }
            delay(150)
        }
    }

    LaunchedEffect(currentPosition) {
        if (!isSeeking) {
            val fraction = if (displayDurationMs > 0) currentPosition.toFloat() / displayDurationMs else 0f
            sliderPos = fraction.coerceIn(0f, 1f)
        }
    }

    fun performSeek(targetMs: Long) {
        val clamped = targetMs.coerceIn(0L, displayDurationMs)
        seekTarget = clamped
        isSeeking = true
        player.seekTo(clamped)
        sliderPos = (clamped.toFloat() / displayDurationMs).coerceIn(0f, 1f)
        onPositionChanged(clamped)
    }

    fun skip(deltaMs: Long) {
        val newPos = (player.currentPosition + deltaMs).coerceIn(0L, displayDurationMs)
        performSeek(newPos)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                playerVideoAr = videoSize.displayAspectRatio()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(uri) { playerVideoAr = 0f }

    LaunchedEffect(uri, editState) {
        uri ?: return@LaunchedEffect
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(editState.trimStartMs)
                    .setEndPositionMs(editState.trimEndMs ?: C.TIME_END_OF_SOURCE)
                    .build()
            )
            .build()
        player.setMediaItem(mediaItem)
        mediaItemReady = true
    }

    DisposableEffect(Unit) { onDispose { player.release() } }

    val crop = editState.cropRect ?: CropRect.FULL
    val videoAr = when {
        playerVideoAr > 0f -> playerVideoAr
        videoInfo != null   -> videoInfo.width.toFloat() / videoInfo.height.toFloat()
        else                -> 16f / 9f
    }.coerceIn(0.25f, 4f)

    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val maxVideoHeight = 400.dp
                val maxHeightPx = with(density) { maxVideoHeight.toPx() }
                val availableWidthPx = with(density) { maxWidth.toPx() }
                val fittedW = minOf(availableWidthPx, maxHeightPx * videoAr)
                val fittedH = fittedW / videoAr
                val videoHeightDp = with(density) { fittedH.toDp() }
                val videoWidthDp = with(density) { fittedW.toDp() }

                Box(
                    modifier = Modifier
                        .size(width = videoWidthDp, height = videoHeightDp)
                        .clip(RoundedCornerShape(12.dp))
                        .clipToBounds()
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val view = android.view.LayoutInflater.from(ctx)
                                .inflate(com.shanks.minify.R.layout.texture_player_view, null) as PlayerView
                            view.apply {
                                useController = false
                                setBackgroundColor(android.graphics.Color.BLACK)
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                            playerViewRef = view
                            view
                        },
                        update = { /* player is set below */ }
                    )

                    LaunchedEffect(playerViewRef, player, mediaItemReady) {
                        val view = playerViewRef ?: return@LaunchedEffect
                        while (!view.isAttachedToWindow) {
                            delay(50)
                        }
                        view.player = player
                        delay(300)
                        if (mediaItemReady && player.playbackState == Player.STATE_IDLE) {
                            player.prepare()
                        }
                    }

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cw = size.width
                        val ch = size.height
                        val left   = crop.left   * cw
                        val top    = crop.top    * ch
                        val right  = crop.right  * cw
                        val bottom = crop.bottom * ch

                        val dimColor = Color(0x88000000)
                        if (top > 0f) drawRect(dimColor, Offset(0f, 0f), Size(cw, top))
                        if (bottom < ch) drawRect(dimColor, Offset(0f, bottom), Size(cw, ch - bottom))
                        if (left > 0f) drawRect(dimColor, Offset(0f, top), Size(left, bottom - top))
                        if (right < cw) drawRect(dimColor, Offset(right, top), Size(cw - right, bottom - top))

                        drawRect(
                            color = accent,
                            topLeft = Offset(left, top),
                            size = Size(right - left, bottom - top),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                        )
                    }

                    if (!isCompressing) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                        ) {
                            FilledTonalButton(
                                onClick = onEdit,
                                enabled = mainEnabled,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (editState.hasEdits) accent else Color(0xCC1C1C1E),
                                    contentColor   = if (editState.hasEdits) Color(0xFF1C1C1E) else TextPrim
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text       = if (editState.hasEdits) "✏ Edited" else "✏ Edit",
                                    fontSize   = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            VideoControlBar(
                isPlaying = isPlaying,
                onPlayPause = { onPlayPause(!isPlaying) },
                currentPosition = currentPosition,
                duration = displayDurationMs,
                onSeek = { pos -> performSeek(pos) },
                onSkipBackward = { skip(-5000L) },
                onSkipForward  = { skip(5000L) },
                accentColor = accent,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun VideoControlBar(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val progress = if (duration > 0) (currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onSkipBackward) {
                Text("⏪", fontSize = 18.sp, color = accentColor)
            }
            TextButton(onClick = onPlayPause) {
                Text(
                    if (isPlaying) "⏸" else "▶",
                    fontSize = 24.sp,
                    color = accentColor
                )
            }
            TextButton(onClick = onSkipForward) {
                Text("⏩", fontSize = 18.sp, color = accentColor)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = formatDuration(currentPosition),
                color = TextSec,
                fontSize = 11.sp
            )
            Slider(
                value = progress,
                onValueChange = { fraction ->
                    val pos = (fraction * duration).toLong()
                    onSeek(pos)
                },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = Surface2
                )
            )
            Text(
                text = formatDuration(duration),
                color = TextSec,
                fontSize = 11.sp
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

@Composable
private fun EditSummaryRow(editState: EditState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface2, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Edits applied:", color = TextSec, fontSize = 12.sp)
        if (editState.hasTrim) {
            val start = editState.trimStartMs / 1000f
            val end   = (editState.trimEndMs ?: 0L) / 1000f
            Chip(text = "✂ %.1fs–%.1fs".format(start, end))
        }
        if (editState.hasCrop) {
            val cr = editState.cropRect!!
            Chip(text = "⬜ ${(cr.width * 100).toInt()}×${(cr.height * 100).toInt()}%")
        }
    }
}

@Composable
private fun Chip(text: String) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = accent.copy(alpha = 0.18f)
    ) {
        Text(
            text     = text,
            color    = accent,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun StatusCard(progress: Float, status: String, isCompressing: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val isDone      = status == "done"
    val isCancelled = status == "cancelled"
    val isError     = status.startsWith("error:")
    val errorMsg    = if (isError) status.removePrefix("error:") else ""

    val bg = when {
        isDone      -> Color(0xFF0A2A14)
        isError     -> Color(0xFF2A0A0A)
        isCancelled -> Surface1
        else        -> Surface1
    }
    val accentColor = when {
        isDone  -> GreenOk
        isError -> ErrorRed
        else    -> accent
    }

    Card(
        shape  = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text  = when {
                        isDone      -> "✅  Saved to gallery"
                        isCancelled -> "Cancelled"
                        isError     -> "❌  $errorMsg"
                        isCompressing -> "Compressing…"
                        else        -> ""
                    },
                    color    = if (isDone) GreenOk else if (isError) ErrorRed else TextPrim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                if (isCompressing) {
                    Text(
                        "${(progress * 100).toInt()}%",
                        color    = accentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (isCompressing) {
                LinearProgressIndicator(
                    progress          = { progress },
                    modifier          = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color             = accentColor,
                    trackColor        = Surface2,
                )
            }
        }
    }
}