@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.shanks.minify.ui

import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.shanks.minify.utils.VideoInfo
import kotlinx.coroutines.delay

private val ScreenBg = Color(0xFF0D0B14)
private val Surface1 = Color(0xFF1A1625)
private val Surface2 = Color(0xFF251E35)
private val TextPrim = Color(0xFFFFFFFF)
private val TextSec  = Color(0xFF8E8E93)

@Composable
fun VideoEditorScreen(
    uri: Uri,
    videoInfo: VideoInfo,
    initial: EditState,
    onDone: (EditState) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary

    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(uri) { onDispose { player.release() } }
    var playerVideoAspect by remember { mutableFloatStateOf(0f) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                playerVideoAspect = videoSize.displayAspectRatio()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val playerView = remember(context) {
        // Use XML inflation to safely set surface_type="texture_view" without reflection
        val view = android.view.LayoutInflater.from(context).inflate(com.shanks.minify.R.layout.texture_player_view, null) as PlayerView
        view.apply {
            useController = false
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Keep FIT because the Compose container already matches the video aspect ratio.
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    val durationMs      = videoInfo.durationSecs * 1000L
    var trimStart       by remember { mutableLongStateOf(initial.trimStartMs) }
    var trimEnd         by remember { mutableLongStateOf(initial.trimEndMs ?: durationMs) }
    var cropRect        by remember { mutableStateOf(initial.cropRect ?: CropRect.FULL) }
    var arPreset        by remember { mutableStateOf(AspectRatioPreset.FREE) }
    var playheadMs      by remember { mutableLongStateOf(initial.trimStartMs) }
    var selectedTab     by remember { mutableIntStateOf(0) }
    val tabs            = listOf("Trim", "Crop")

    val latestTrimEnd   by rememberUpdatedState(trimEnd)
    val latestTrimStart by rememberUpdatedState(trimStart)

    LaunchedEffect(player) {
        while (true) {
            val pos = player.currentPosition
            playheadMs = pos.coerceIn(0L, durationMs)
            if (player.isPlaying && pos >= latestTrimEnd) {
                player.pause()
                player.seekTo(latestTrimStart)
            }
            delay(50)
        }
    }

    LaunchedEffect(trimStart) { player.seekTo(trimStart) }

    BackHandler { onDismiss() }

    val videoAspect        = if (playerVideoAspect > 0f) {
        playerVideoAspect
    } else {
        videoInfo.width.toFloat() / videoInfo.height.toFloat()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface1)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = accent, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                }
                Text("Edit Video", color = TextPrim, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = {
                    onDone(EditState(
                        trimStartMs = trimStart,
                        trimEndMs   = if (trimEnd >= durationMs) null else trimEnd,
                        cropRect    = if (cropRect == CropRect.FULL) null else cropRect
                    ))
                }) {
                    Text("Done", color = accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            // Tab selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface1)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEachIndexed { idx, label ->
                    val active = idx == selectedTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) accent else Surface2)
                            .height(38.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(onClick = { selectedTab = idx }, modifier = Modifier.fillMaxSize()) {
                            Text(
                                text       = label,
                                color      = if (active) Color.White else TextSec,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                fontSize   = 14.sp
                            )
                        }
                    }
                }
            }

            // Shared video area
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val fittedAspect = videoAspect.coerceIn(0.25f, 4f)
                val fittedWidth = minOf(maxWidth, maxHeight * fittedAspect)
                val fittedHeight = fittedWidth / fittedAspect

                Box(
                    modifier = Modifier
                        .size(fittedWidth, fittedHeight)
                ) {
                    AndroidView(
                        factory  = { playerView },
                        modifier = Modifier.fillMaxSize(),
                        update   = { view ->
                            if (view.player !== player) view.player = player
                            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    )

                    if (selectedTab == 1) {
                        CropOverlay(
                            crop         = cropRect,
                            lockedAspect = arPreset.ratio,
                            videoAspect  = videoAspect,
                            onCropChange = { cropRect = it },
                            modifier     = Modifier.fillMaxSize(),
                            handleColor  = Color.White,
                            borderColor  = accent,
                        )
                    }
                }
            }

            // Controls
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label    = "editor_controls",
                modifier = Modifier.fillMaxWidth()
            ) { tab ->
                when (tab) {
                    0 -> TrimControls(
                        durationMs    = durationMs,
                        trimStart     = trimStart,
                        trimEnd       = trimEnd,
                        playheadMs    = playheadMs,
                        onRangeChange = { s, e -> trimStart = s; trimEnd = e; player.seekTo(s) },
                        onPlayPause   = {
                            if (player.isPlaying) player.pause()
                            else { player.seekTo(trimStart); player.play() }
                        },
                        isPlaying     = player.isPlaying,
                        accentColor   = accent
                    )
                    1 -> CropControls(
                        cropRect    = cropRect,
                        arPreset    = arPreset,
                        onArPreset  = { preset ->
                            arPreset = preset
                            if (preset.ratio != null)
                                cropRect = CropRect.forAspectRatio(preset.ratio, videoAspect)
                        },
                        onReset     = { cropRect = CropRect.FULL; arPreset = AspectRatioPreset.FREE },
                        accent      = accent
                    )
                }
            }
        }
    }
}

// ── Trim controls ─────────────────────────────────────────────────────────────

@Composable
private fun TrimControls(
    durationMs: Long,
    trimStart: Long,
    trimEnd: Long,
    playheadMs: Long,
    onRangeChange: (Long, Long) -> Unit,
    onPlayPause: () -> Unit,
    isPlaying: Boolean,
    accentColor: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TrimBar(
            durationMs    = durationMs,
            startMs       = trimStart,
            endMs         = trimEnd,
            playheadMs    = playheadMs,
            onRangeChange = onRangeChange,
            modifier      = Modifier.fillMaxWidth(),
            accentColor   = accentColor,
            barHeight     = 64.dp
        )

        FilledTonalButton(
            onClick  = onPlayPause,
            shape    = RoundedCornerShape(12.dp),
            colors   = ButtonDefaults.filledTonalButtonColors(
                containerColor = accentColor, contentColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(
                text       = if (isPlaying) "⏸  Pause" else "▶  Play Selection",
                fontSize   = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Text(
            text     = "Drag the handles to set the trim range.",
            fontSize = 11.sp,
            color    = TextSec,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

// ── Crop controls ─────────────────────────────────────────────────────────────

@Composable
private fun CropControls(
    cropRect: CropRect,
    arPreset: AspectRatioPreset,
    onArPreset: (AspectRatioPreset) -> Unit,
    onReset: () -> Unit,
    accent: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AspectRatioChips(
            selected = arPreset,
            onSelect = onArPreset,
            modifier = Modifier.fillMaxWidth(),
            accentColor = accent
        )

        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(
                    "W" to "${(cropRect.width  * 100).toInt()}%",
                    "H" to "${(cropRect.height * 100).toInt()}%",
                    "X" to "${(cropRect.left   * 100).toInt()}%",
                    "Y" to "${(cropRect.top    * 100).toInt()}%",
                ).forEach { (label, value) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(value, color = TextPrim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(label, color = TextSec,  fontSize = 10.sp)
                    }
                }
            }
            TextButton(onClick = onReset) {
                Text("Reset", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Text(
            text     = "Drag corners or edges to crop. Tap a preset to lock the aspect ratio.",
            fontSize = 11.sp,
            color    = TextSec,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}
