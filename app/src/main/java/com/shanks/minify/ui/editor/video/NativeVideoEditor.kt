package com.shanks.minify.ui.editor.video

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.shanks.minify.R
import com.shanks.minify.ui.AspectRatioPreset
import com.shanks.minify.ui.CropRect
import com.shanks.minify.ui.EditState
import com.shanks.minify.ui.displayAspectRatio
import com.shanks.minify.ui.theme.*
import com.shanks.minify.utils.VideoInfo
import com.shanks.minify.utils.getVideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

private enum class EditorMode { TRIM, CROP }

private sealed interface ArChoice {
    object Free : ArChoice
    object Original : ArChoice
    data class Ratio(val value: Float, val label: String) : ArChoice
}

@OptIn(UnstableApi::class)
@Composable
fun NativeVideoEditor(
    uri: Uri,
    initialEditState: EditState = EditState(),
    onDone: (EditState) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var videoInfo by remember(uri) { mutableStateOf<VideoInfo?>(null) }
    var editState by remember(uri) { mutableStateOf(initialEditState) }
    var isPlaying by remember { mutableStateOf(false) }
    var playerVideoAr by remember { mutableFloatStateOf(0f) }
    var mode by remember { mutableStateOf(EditorMode.TRIM) }
    var showGrid by remember { mutableStateOf(true) }
    var lockedAr by remember { mutableStateOf<Float?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var playheadMs by remember { mutableLongStateOf(0L) }
    var lastSeek by remember { mutableLongStateOf(0L) }

    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE }
    }

    LaunchedEffect(uri) {
        videoInfo = getVideoInfo(context, uri)
        player.setMediaItem(MediaItem.Builder().setUri(uri).build())
        player.prepare()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            @UnstableApi
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                playerVideoAr = videoSize.displayAspectRatio()
            }
        }
        player.addListener(listener)
        onDispose { player.release() }
    }

    LaunchedEffect(isPlaying) { if (isPlaying) player.play() else player.pause() }

    // Keep the loop inside the trimmed segment; ~20 fps playhead updates.
    LaunchedEffect(player, editState.trimStartMs, editState.trimEndMs) {
        while (true) {
            val end = editState.trimEndMs
                ?: (videoInfo?.durationSecs?.times(1000L) ?: Long.MAX_VALUE)
            val pos = player.currentPosition
            if (pos < editState.trimStartMs || pos >= end) player.seekTo(editState.trimStartMs)
            playheadMs = player.currentPosition
            delay(50.milliseconds)
        }
    }

    // Auto-hide the center controls shortly after playback starts.
    LaunchedEffect(isPlaying) {
        if (isPlaying) { delay(1500.milliseconds); controlsVisible = false }
        else controlsVisible = true
    }

    // Entering crop mode pauses playback so frames can be lined up precisely.
    LaunchedEffect(mode) { if (mode == EditorMode.CROP) isPlaying = false }

    BackHandler { onDismiss() }

    val durationMs = videoInfo?.durationSecs?.times(1000L) ?: 0L
    val trimStart = editState.trimStartMs
    val trimEnd = editState.trimEndMs ?: durationMs
    val fallbackAr = videoInfo?.let { it.width.toFloat() / it.height.toFloat() } ?: (16f / 9f)
    val videoAr = if (playerVideoAr > 0f) playerVideoAr else fallbackAr

    fun throttledSeek(ms: Long) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSeek > 90L) { lastSeek = now; player.seekTo(ms) }
    }

    fun applyTrim(s: Long, e: Long) {
        editState = editState.copy(
            trimStartMs = if (s < 300L) 0L else s,
            trimEndMs = if (durationMs - e < 300L) null else e,
        )
    }

    fun applyCrop(c: CropRect?) {
        editState = editState.copy(cropRect = c?.takeIf { !it.isNearlyFull() })
    }

    fun resetCrop() {
        val from = editState.cropRect ?: return
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch {
            val a = Animatable(0f)
            a.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) {
                applyCrop(lerpCrop(from, CropRect.FULL, value))
            }
        }
    }

    val accentColor = MaterialTheme.colorScheme.primary
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            EditorTopBar(
                subtitle = videoInfo?.let { "${formatMs(durationMs)} · ${it.width}×${it.height}" } ?: " ",
                onDismiss = onDismiss,
                onDone = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onDone(editState)
                },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // ── Stage ──────────────────────────────────────────────────────
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            if (!controlsVisible) controlsVisible = true
                            else { isPlaying = !isPlaying; controlsVisible = true }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Ambient accent glow instead of flat black letterbox.
                Canvas(Modifier.matchParentSize()) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(accentColor.copy(alpha = 0.14f), Color.Transparent),
                            center = Offset(size.width / 2f, size.height * 0.38f),
                            radius = size.maxDimension * 0.8f,
                        )
                    )
                }

                var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val view = android.view.LayoutInflater.from(ctx)
                            .inflate(R.layout.texture_player_view, null, false) as PlayerView
                        view.apply {
                            useController = false
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            @UnstableApi
                            this.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        playerViewRef = view
                        view
                    },
                )
                LaunchedEffect(playerViewRef, player) {
                    val view = playerViewRef ?: return@LaunchedEffect
                    while (!view.isAttachedToWindow) delay(50.milliseconds)
                    view.player = player
                }

                if (mode == EditorMode.CROP) {
                    VideoCropOverlay(
                        crop = editState.cropRect ?: CropRect.FULL,
                        lockedAspect = lockedAr,
                        videoAspect = videoAr,
                        showGrid = showGrid,
                        onCropChange = { applyCrop(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Time HUD
                Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = controlsVisible,
                        enter = fadeIn() + slideInVertically { -it / 2 },
                        exit = fadeOut(),
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                "${formatMs(playheadMs.coerceIn(0L, durationMs))} / ${formatMs(durationMs)}",
                                color = Color.White,
                                fontSize = 12.sp,
                                style = TextStyle(fontFeatureSettings = "tnum"),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }

                // Center play/pause, scales on press, auto-hides while playing.
                Box {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = controlsVisible,
                        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.8f),
                        exit = fadeOut(tween(220)) + scaleOut(tween(220), targetScale = 0.85f),
                    ) {
                        CenterPlayButton(isPlaying = isPlaying, onToggle = { isPlaying = !isPlaying })
                    }
                }
            }

            // ── Control panel ──────────────────────────────────────────────
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Surface1, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModeSwitcher(mode = mode, onSelect = { mode = it })

                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (slideInHorizontally(tween(240)) { dir * it / 6 } + fadeIn(tween(240)))
                            .togetherWith(slideOutHorizontally(tween(240)) { -dir * it / 6 } + fadeOut(tween(240)))
                    },
                    label = "ModeTransition"
                ) { m ->
                    when (m) {
                        EditorMode.TRIM -> TrimPanel(
                            uri = uri,
                            durationMs = durationMs,
                            trimStartMs = trimStart,
                            trimEndMs = trimEnd,
                            playheadMs = playheadMs,
                            hasTrim = editState.hasTrim,
                            onTrimChange = { s, e -> applyTrim(s, e) },
                            onScrub = { ms -> throttledSeek(ms) },
                            onDragState = { dragging -> if (dragging) isPlaying = false },
                            onReset = {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                editState = editState.copy(trimStartMs = 0L, trimEndMs = null)
                                player.seekTo(0L)
                            },
                        )
                        EditorMode.CROP -> CropPanel(
                            videoInfo = videoInfo,
                            videoAr = videoAr,
                            crop = editState.cropRect,
                            lockedAr = lockedAr,
                            showGrid = showGrid,
                            onShowGrid = { showGrid = it },
                            onLockedAr = { lockedAr = it },
                            onCropChange = { applyCrop(it) },
                            onReset = { resetCrop() },
                        )
                    }
                }
            }
        }
    }
}

// ── Top bar ────────────────────────────────────────────────────────────────

@Composable
private fun EditorTopBar(subtitle: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).background(Color.Black).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(painterResource(R.drawable.ic_close_24), "Cancel", tint = Color.White)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Edit Video", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Text(
                    subtitle,
                    color = TextSec,
                    fontSize = 10.sp,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    letterSpacing = 0.6.sp,
                )
            }
        }
        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF141416)),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.padding(end = 6.dp),
        ) {
            Text("Done", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

// ── Mode switcher (sliding segmented control) ──────────────────────────────

@Composable
private fun ModeSwitcher(mode: EditorMode, onSelect: (EditorMode) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val segW = maxWidth / 2
        val indicatorX by animateDpAsState(
            targetValue = if (mode == EditorMode.CROP) segW else 0.dp,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
            label = "IndicatorX"
        )
        Surface(color = Surface2, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.height(46.dp)) {
                Box(
                    Modifier
                        .width(segW)
                        .fillMaxHeight()
                        .offset { IntOffset(indicatorX.roundToPx(), 0) }
                        .padding(4.dp)
                        .background(accent, RoundedCornerShape(11.dp))
                )
                Row(Modifier.fillMaxSize()) {
                    EditorMode.entries.forEach { m ->
                        val selected = m == mode
                        Row(
                            Modifier.weight(1f).fillMaxHeight().clickable { onSelect(m) },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painterResource(if (m == EditorMode.TRIM) R.drawable.ic_trim_24 else R.drawable.ic_crop_24),
                                contentDescription = null,
                                tint = if (selected) Color(0xFF141416) else TextSec,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                if (m == EditorMode.TRIM) "Trim" else "Crop",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) Color(0xFF141416) else TextSec,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Trim panel ─────────────────────────────────────────────────────────────

@Composable
private fun TrimPanel(
    uri: Uri,
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    playheadMs: Long,
    hasTrim: Boolean,
    onTrimChange: (Long, Long) -> Unit,
    onScrub: (Long) -> Unit,
    onDragState: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val span = (trimEndMs - trimStartMs).coerceAtLeast(0L)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MicroLabel("SELECTION")
            Spacer(Modifier.weight(1f))
            Text(
                "${formatMs(trimStartMs)} – ${formatMs(trimEndMs)}",
                color = TextPrim,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
            Spacer(Modifier.width(10.dp))
            Surface(color = accent.copy(alpha = 0.16f), shape = RoundedCornerShape(6.dp)) {
                Text(
                    "%.1fs".format(span / 1000f),
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        if (durationMs > 0L) {
            Box(Modifier.padding(top = 34.dp)) { // headroom for the time bubble
                FilmstripTrimBar(
                    uri = uri,
                    durationMs = durationMs,
                    trimStartMs = trimStartMs,
                    trimEndMs = trimEndMs,
                    playheadMs = playheadMs,
                    onTrimChange = onTrimChange,
                    onScrub = onScrub,
                    onDragState = onDragState,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Box(Modifier.height(64.dp).fillMaxWidth().background(Surface2, RoundedCornerShape(10.dp)))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(visible = hasTrim, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("Reset", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Drag handles · min 1s", color = TextSec, fontSize = 11.sp)
        }
    }
}

@Composable
private fun FilmstripTrimBar(
    uri: Uri,
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    playheadMs: Long,
    onTrimChange: (Long, Long) -> Unit,
    onScrub: (Long) -> Unit,
    onDragState: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary

    val currentStart by rememberUpdatedState(trimStartMs)
    val currentEnd by rememberUpdatedState(trimEndMs)
    val currentPlayhead by rememberUpdatedState(playheadMs)

    BoxWithConstraints(modifier.height(72.dp)) {
        val wPx = constraints.maxWidth.toFloat()
        val thumbTarget = with(density) { 56.dp.toPx() }
        val frameCount = ceil(wPx / thumbTarget).toInt().coerceIn(6, 16)
        val frames = rememberFilmstrip(context, uri, durationMs, frameCount)

        val handleW = with(density) { 22.dp.toPx() }
        val msPerPx = durationMs / wPx
        val snapPx = with(density) { 8.dp.toPx() }
        val minSpanMs = 1000L

        val xs = (trimStartMs.toFloat() / durationMs) * wPx
        val xe = (trimEndMs.toFloat() / durationMs) * wPx
        val xp = ((playheadMs.toFloat() / durationMs) * wPx).coerceIn(0f, wPx)

        // Active gesture: 0 = start handle, 1 = end handle, 2 = scrub
        var active by remember { mutableStateOf<Int?>(null) }
        var bubbleMs by remember { mutableLongStateOf(0L) }
        var baseline by remember { mutableLongStateOf(0L) }
        var accPx by remember { mutableFloatStateOf(0f) }
        var lastDragMs by remember { mutableLongStateOf(0L) }

        // The playhead steps aside while a trim handle owns the gesture,
        // and fattens while it's the one being dragged.
        val playheadAlpha by animateFloatAsState(
            if (active == 0 || active == 1) 0f else 1f, tween(150), label = "PlayheadAlpha"
        )
        val scrubBoost by animateFloatAsState(
            if (active == 2) 1f else 0f, tween(150), label = "ScrubBoost"
        )

        Box(Modifier.fillMaxSize()) {
            // 1 · Filmstrip
            if (frames.isEmpty()) {
                Box(Modifier.fillMaxSize().background(Surface2))
            } else {
                Row(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))) {
                    frames.forEach { ib ->
                        Image(
                            bitmap = ib,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }

            // 2 · Dim + selection frame + playhead
            Canvas(Modifier.fillMaxSize()) {
                val dim = Color.Black.copy(alpha = 0.62f)
                drawRect(dim, topLeft = Offset.Zero, size = Size(xs, size.height))
                drawRect(dim, topLeft = Offset(xe, 0f), size = Size(size.width - xe, size.height))
                drawRoundRect(
                    accent,
                    topLeft = Offset(xs, with(density) { 1.5.dp.toPx() }),
                    size = Size((xe - xs).coerceAtLeast(0f), size.height - with(density) { 3.dp.toPx() }),
                    cornerRadius = CornerRadius(with(density) { 9.dp.toPx() }),
                    style = Stroke(with(density) { 3.dp.toPx() }),
                )
                if (playheadAlpha > 0.01f) {
                    val lineW = with(density) { 2.dp.toPx() } + scrubBoost * with(density) { 1.dp.toPx() }
                    val knobW = with(density) { 10.dp.toPx() } + scrubBoost * with(density) { 6.dp.toPx() }
                    val knobH = with(density) { 12.dp.toPx() } + scrubBoost * with(density) { 4.dp.toPx() }
                    drawLine(
                        Color.White.copy(alpha = playheadAlpha),
                        Offset(xp, 0f), Offset(xp, size.height),
                        strokeWidth = lineW,
                    )
                    drawRoundRect(
                        Color.White.copy(alpha = playheadAlpha),
                        topLeft = Offset(xp - knobW / 2f, 0f),
                        size = Size(knobW, knobH),
                        cornerRadius = CornerRadius(knobW / 2f),
                    )
                }
            }

            // 3 · Handles — display only; input is owned by layer 4.
            @Composable
            fun Handle(index: Int, xPx: Float) {
                val scale by animateFloatAsState(if (active == index) 1.18f else 1f, label = "HandleScale")
                Box(
                    Modifier
                        .width(22.dp)
                        .fillMaxHeight()
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .offset { IntOffset((xPx - handleW / 2f).roundToInt(), 0) }
                        .background(accent, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.width(2.5.dp).height(15.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(1.5.dp)))
                        Box(Modifier.width(2.5.dp).height(15.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(1.5.dp)))
                    }
                }
            }
            Handle(0, xs)
            Handle(1, xe)

            // 4 · One gesture owner for the whole bar. Touches are classified
            // by proximity — handles always outrank the playhead, the playhead
            // outranks the strip — so nothing can steal a drag from under your
            // finger, and the thin playhead line is never the only way to scrub.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(durationMs, wPx) {
                        val handleHalf = with(density) { 24.dp.toPx() }   // 48dp hit zone on a 22dp visual
                        val playheadHalf = with(density) { 16.dp.toPx() } // 32dp grab zone on a 2dp line
                        val slop = viewConfiguration.touchSlop

                        fun msAt(x: Float) = (x * msPerPx).toLong().coerceIn(0L, durationMs)

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val p0 = down.position
                            val xsN = (currentStart.toFloat() / durationMs) * wPx
                            val xeN = (currentEnd.toFloat() / durationMs) * wPx
                            val xpN = ((currentPlayhead.toFloat() / durationMs) * wPx).coerceIn(0f, wPx)

                            val dStart = abs(p0.x - xsN)
                            val dEnd = abs(p0.x - xeN)

                            // -1 = tap-to-seek, 0/1 = trim handles, 2 = scrub
                            var mode = when {
                                min(dStart, dEnd) <= handleHalf -> if (dStart <= dEnd) 0 else 1
                                abs(p0.x - xpN) <= playheadHalf -> 2
                                p0.x in xsN..xeN -> 2
                                else -> -1
                            }
                            var moved = false
                            var last = p0
                            accPx = 0f

                            if (mode == 0 || mode == 1) {
                                baseline = if (mode == 0) currentStart else currentEnd
                                active = mode
                                bubbleMs = baseline
                                lastDragMs = baseline
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDragState(true)
                                down.consume()
                            } else if (mode == 2) {
                                active = 2
                                val ms = msAt(p0.x).coerceIn(currentStart, currentEnd)
                                bubbleMs = ms
                                lastDragMs = ms
                                onScrub(ms)
                                onDragState(true)
                                down.consume()
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val ev = event.changes.firstOrNull() ?: break
                                if (!ev.pressed) break
                                val p = ev.position
                                if (!moved && (p - p0).getDistance() > slop) {
                                    moved = true
                                    if (mode == -1) {
                                        // A wandering "tap" is promoted to a scrub
                                        // instead of mis-seeking on release.
                                        mode = 2
                                        active = 2
                                        val ms = msAt(p.x).coerceIn(currentStart, currentEnd)
                                        bubbleMs = ms
                                        lastDragMs = ms
                                        onScrub(ms)
                                        onDragState(true)
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                                if (moved) {
                                    ev.consume()
                                    when (mode) {
                                        0 -> {
                                            accPx += p.x - last.x
                                            var ns = baseline + (accPx * msPerPx).toLong()
                                            if (ns < with(density) { snapPx * msPerPx }) ns = 0L
                                            ns = ns.coerceIn(0L, currentEnd - minSpanMs)
                                            lastDragMs = ns
                                            bubbleMs = ns
                                            onTrimChange(ns, currentEnd)
                                            onScrub(ns)
                                        }
                                        1 -> {
                                            accPx += p.x - last.x
                                            var ne = baseline + (accPx * msPerPx).toLong()
                                            if (durationMs - ne < with(density) { snapPx * msPerPx }) ne = durationMs
                                            ne = ne.coerceIn(currentStart + minSpanMs, durationMs)
                                            lastDragMs = ne
                                            bubbleMs = ne
                                            onTrimChange(currentStart, ne)
                                            onScrub(ne)
                                        }
                                        2 -> {
                                            val ms = msAt(p.x).coerceIn(currentStart, currentEnd)
                                            bubbleMs = ms
                                            lastDragMs = ms
                                            onScrub(ms)
                                        }
                                    }
                                }
                                last = p
                            }

                            when {
                                mode == -1 -> {
                                    // Clean tap on the strip → seek there.
                                    onDragState(true)
                                    onScrub(msAt(p0.x))
                                }
                                mode == 0 || mode == 1 -> onScrub(lastDragMs) // settle on the exact frame
                            }
                            if (mode != -1) onDragState(false)
                            active = null
                        }
                    }
            )

            // 5 · Time bubble over the active gesture
            AnimatedVisibility(
                visible = active != null,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(160)),
                modifier = Modifier.align(Alignment.TopStart).offset {
                    val bx = when (active) { 0 -> xs; 1 -> xe; else -> xp } - with(density) { 30.dp.toPx() }
                    IntOffset(
                        bx.roundToInt().coerceIn(0, (wPx - with(density) { 60.dp.toPx() }).roundToInt()),
                        (-36).dp.toPx().roundToInt(),
                    )
                },
            ) {
                TimeBubble(formatMs(bubbleMs))
            }
        }
    }
}

// ── Filmstrip extraction ───────────────────────────────────────────────────

@Composable
private fun rememberFilmstrip(
    context: android.content.Context,
    uri: Uri,
    durationMs: Long,
    count: Int,
): List<ImageBitmap> {
    var frames by remember(uri, count) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    LaunchedEffect(uri, count) {
        frames = withContext(Dispatchers.IO) { extractFrames(context, uri, durationMs, count) }
    }
    return frames
}

private fun extractFrames(context: android.content.Context, uri: Uri, durationMs: Long, count: Int): List<ImageBitmap> {
    val retriever = MediaMetadataRetriever()
    return try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            retriever.setDataSource(fd.fileDescriptor)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            (0 until count).mapNotNull { i ->
                val tMs = if (count <= 1) 0L else durationMs * i / (count - 1)
                val bmp = retriever.getFrameAtTime(tMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return@mapNotNull null
                val oriented = if (rotation != 0) {
                    Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                } else bmp
                val scale = 140f / oriented.height
                val sized = if (scale < 1f) {
                    Bitmap.createScaledBitmap(oriented, (oriented.width * scale).toInt().coerceAtLeast(1), 140, true)
                } else oriented
                sized.asImageBitmap()
            }
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    } finally {
        try { retriever.release() } catch (_: Exception) { }
    }
}

// ── Crop panel ─────────────────────────────────────────────────────────────

@Composable
private fun CropPanel(
    videoInfo: VideoInfo?,
    videoAr: Float,
    crop: CropRect?,
    lockedAr: Float?,
    showGrid: Boolean,
    onShowGrid: (Boolean) -> Unit,
    onLockedAr: (Float?) -> Unit,
    onCropChange: (CropRect?) -> Unit,
    onReset: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val haptics = LocalHapticFeedback.current

    val chips = remember(videoAr) {
        buildList<ArChoice> {
            add(ArChoice.Free)
            add(ArChoice.Original)
            AspectRatioPreset.entries.filter { it.ratio != null }.forEach {
                add(ArChoice.Ratio(it.ratio!!, it.label))
            }
        }
    }
    val selected: ArChoice = when {
        lockedAr == null -> ArChoice.Free
        abs(lockedAr - videoAr) < 0.01f -> ArChoice.Original
        else -> chips.firstOrNull { it is ArChoice.Ratio && abs(it.value - lockedAr!!) < 0.01f } ?: ArChoice.Free
    }

    val c = crop ?: CropRect.FULL
    val srcW = videoInfo?.width ?: 0
    val srcH = videoInfo?.height ?: 0
    val outW = (srcW * c.width).toInt().coerceAtLeast(1)
    val outH = (srcH * c.height).toInt().coerceAtLeast(1)
    val outText = "${srcW}×${srcH}  →  ${outW}×${outH} px"

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MicroLabel("ASPECT RATIO")
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(
                visible = crop != null,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("Reset", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chips.forEach { chip ->
                AspectChip(
                    choice = chip,
                    selected = chip == selected,
                    videoAr = videoAr,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        when (chip) {
                            is ArChoice.Free -> onLockedAr(null)
                            is ArChoice.Original -> { onLockedAr(videoAr); onCropChange(null) }
                            is ArChoice.Ratio -> {
                                onLockedAr(chip.value)
                                onCropChange(CropRect.forAspectRatio(chip.value, videoAr))
                            }
                        }
                    },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Grid toggle
            Surface(
                color = if (showGrid) accent.copy(alpha = 0.16f) else Surface2,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.clickable { onShowGrid(!showGrid) },
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val gridColor = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.size(12.dp)) {
                        val t = gridColor
                        val g = if (showGrid) t else TextSec
                        val third = size.width / 3f
                        for (k in 1..2) {
                            drawLine(g, Offset(third * k, 0f), Offset(third * k, size.height), strokeWidth = 1.4f)
                            drawLine(g, Offset(0f, third * k), Offset(size.width, third * k), strokeWidth = 1.4f)
                        }
                    }
                    Text(
                        "Thirds",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (showGrid) accent else TextSec,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            AnimatedContent(
                targetState = outText,
                transitionSpec = { fadeIn(tween(150)).togetherWith(fadeOut(tween(150))) },
                label = "OutText"
            ) { txt ->
                Text(txt, color = TextSec, fontSize = 12.sp, style = TextStyle(fontFeatureSettings = "tnum"))
            }
        }

        Text("Drag to reposition · corners to resize", color = TextSec, fontSize = 11.sp)
    }
}

@Composable
private fun AspectChip(choice: ArChoice, selected: Boolean, videoAr: Float, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val bg by animateColorAsState(if (selected) accent else Surface2, label = "BgColor")
    val fg by animateColorAsState(if (selected) Color(0xFF141416) else TextSec, label = "FgColor")
    val scale by animateFloatAsState(if (selected) 1f else 0.94f, label = "Scale")

    val ratio: Float = when (choice) {
        is ArChoice.Free -> 1.5f
        is ArChoice.Original -> videoAr
        is ArChoice.Ratio -> choice.value
    }
    val label = when (choice) {
        is ArChoice.Free -> "Free"
        is ArChoice.Original -> "Original"
        is ArChoice.Ratio -> choice.label
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(onClick = onClick),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val glyphW: Float
            val glyphH: Float
            if (ratio >= 1f) { glyphW = 20f; glyphH = 20f / ratio } else { glyphH = 20f; glyphW = 20f * ratio }
            Box(
                Modifier
                    .width(glyphW.dp)
                    .height(glyphH.dp)
                    .background(
                        Color.Transparent,
                        RoundedCornerShape(if (choice is ArChoice.Original) 4.dp else 2.dp),
                    )
                    .then(
                        Modifier.border(
                            width = 1.6.dp,
                            color = fg,
                            shape = RoundedCornerShape(if (choice is ArChoice.Original) 4.dp else 2.dp),
                        )
                    )
            )
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = fg)
        }
    }
}

// ── Crop overlay ───────────────────────────────────────────────────────────

@Composable
private fun VideoCropOverlay(
    crop: CropRect,
    lockedAspect: Float?,
    videoAspect: Float,
    showGrid: Boolean,
    onCropChange: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary

    val currentCrop by rememberUpdatedState(crop)
    val currentLocked by rememberUpdatedState(lockedAspect)
    var dragging by remember { mutableStateOf(false) }
    val gridAlpha by animateFloatAsState(
        targetValue = if (dragging) 0.55f else if (showGrid) 0.18f else 0f,
        animationSpec = tween(180),
        label = "GridAlpha"
    )

    BoxWithConstraints(modifier) {
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        // FIT-letterboxed video rect inside the stage
        val videoRect = if (cw / ch > videoAspect) {
            val vw = ch * videoAspect
            Rect((cw - vw) / 2f, 0f, (cw + vw) / 2f, ch)
        } else {
            val vh = cw / videoAspect
            Rect(0f, (ch - vh) / 2f, cw, (ch + vh) / 2f)
        }
        val rect by rememberUpdatedState(videoRect)

        val cornerLen = with(density) { 24.dp.toPx() }
        val stroke = with(density) { 3.dp.toPx() }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val cornerHit = 34.dp.toPx()
                    val edgeHit = 22.dp.toPx()
                    val minFrac = 0.15f

                    var dragMode: Any? = null
                    var last = Offset.Zero

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val p0 = down.position
                        val r0 = currentCrop.toPx(rect)
                        val minW = rect.width * minFrac
                        val minH = rect.height * minFrac
                        last = p0

                        val corners = listOf(
                            Offset(r0.left, r0.top), Offset(r0.right, r0.top),
                            Offset(r0.right, r0.bottom), Offset(r0.left, r0.bottom),
                        )
                        val ci = corners.indexOfFirst { (p0 - it).getDistance() < cornerHit }
                        dragMode = when {
                            ci >= 0 -> {
                                val ax = if (ci == 0 || 3 == ci) r0.right else r0.left
                                val ay = if (ci < 2) r0.bottom else r0.top
                                Pair("corner", Offset(ax, ay))
                            }
                            currentLocked == null && abs(p0.x - r0.left) < edgeHit && p0.y in r0.top..r0.bottom -> 0
                            currentLocked == null && abs(p0.x - r0.right) < edgeHit && p0.y in r0.top..r0.bottom -> 1
                            currentLocked == null && abs(p0.y - r0.top) < edgeHit && p0.x in r0.left..r0.right -> 2
                            currentLocked == null && abs(p0.y - r0.bottom) < edgeHit && p0.x in r0.left..r0.right -> 3
                            r0.contains(p0) -> "move"
                            else -> null
                        }
                        if (dragMode != null) {
                            dragging = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            down.consume()
                            var cur = r0
                            while (true) {
                                val event = awaitPointerEvent()
                                val ev = event.changes.firstOrNull() ?: break
                                if (!ev.pressed) break
                                ev.consume()
                                val p = ev.position
                                val locked = currentLocked
                                cur = when (val m = dragMode) {
                                    "move" -> {
                                        val d = p - last
                                        val nl = (cur.left + d.x).coerceIn(rect.left, rect.right - cur.width)
                                        val nt = (cur.top + d.y).coerceIn(rect.top, rect.bottom - cur.height)
                                        Rect(nl, nt, nl + cur.width, nt + cur.height)
                                    }
                                    is Pair<*, *> -> {
                                        val anchor = m.second as Offset
                                        var wPx = abs(p.x - anchor.x).coerceAtLeast(minW)
                                        var hPx = abs(p.y - anchor.y).coerceAtLeast(minH)
                                        if (locked != null) {
                                            if (wPx / cur.width >= hPx / cur.height) hPx = wPx / locked
                                            else wPx = hPx * locked
                                        }
                                        val maxW = if (p.x >= anchor.x) rect.right - anchor.x else anchor.x - rect.left
                                        val maxH = if (p.y >= anchor.y) rect.bottom - anchor.y else anchor.y - rect.top
                                        wPx = wPx.coerceAtMost(maxW)
                                        hPx = if (locked != null) wPx / locked else hPx.coerceAtMost(maxH)
                                        if (locked != null) wPx = hPx * locked
                                        val l = if (p.x >= anchor.x) anchor.x else anchor.x - wPx
                                        val t = if (p.y >= anchor.y) anchor.y else anchor.y - hPx
                                        Rect(l, t, l + wPx, t + hPx)
                                    }
                                    is Int -> {
                                        when (m) {
                                            0 -> Rect(p.x.coerceIn(rect.left, cur.right - minW), cur.top, cur.right, cur.bottom)
                                            1 -> Rect(cur.left, cur.top, p.x.coerceIn(cur.left + minW, rect.right), cur.bottom)
                                            2 -> Rect(cur.left, p.y.coerceIn(rect.top, cur.bottom - minH), cur.right, cur.bottom)
                                            else -> Rect(cur.left, cur.top, cur.right, p.y.coerceIn(cur.top + minH, rect.bottom))
                                        }
                                    }
                                    else -> cur
                                }
                                last = p
                                onCropChange(cur.normalize(rect))
                            }
                            dragging = false
                            dragMode = null
                        }
                    }
                },
        ) {
            val r = currentCrop.toPx(rect)
            val dim = Color.Black.copy(alpha = 0.6f)
            // Dim outside the crop (within the video frame)
            drawRect(dim, topLeft = Offset(rect.left, rect.top), size = Size(rect.width, (r.top - rect.top).coerceAtLeast(0f)))
            drawRect(dim, topLeft = Offset(rect.left, r.bottom), size = Size(rect.width, (rect.bottom - r.bottom).coerceAtLeast(0f)))
            drawRect(dim, topLeft = Offset(rect.left, r.top), size = Size((r.left - rect.left).coerceAtLeast(0f), r.height))
            drawRect(dim, topLeft = Offset(r.right, r.top), size = Size((rect.right - r.right).coerceAtLeast(0f), r.height))

            // Rule-of-thirds grid (brightens while dragging)
            if (gridAlpha > 0.01f) {
                val g = Color.White.copy(alpha = gridAlpha)
                val tw = r.width / 3f
                val th = r.height / 3f
                for (k in 1..2) {
                    drawLine(g, Offset(r.left + tw * k, r.top), Offset(r.left + tw * k, r.bottom), strokeWidth = 1.4f)
                    drawLine(g, Offset(r.left, r.top + th * k), Offset(r.right, r.top + th * k), strokeWidth = 1.4f)
                }
            }

            // Border
            drawRect(Color.White.copy(alpha = 0.9f), topLeft = Offset(r.left, r.top), size = Size(r.width, r.height), style = Stroke(1.6f))

            // Corner handles (L-shapes)
            val pts = listOf(
                Triple(Offset(r.left, r.top), Offset(1f, 1f), 0),
                Triple(Offset(r.right, r.top), Offset(-1f, 1f), 1),
                Triple(Offset(r.right, r.bottom), Offset(-1f, -1f), 2),
                Triple(Offset(r.left, r.bottom), Offset(1f, -1f), 3),
            )
            pts.forEach { (o, d, _) ->
                drawLine(accent, o, Offset(o.x + d.x * cornerLen, o.y), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(accent, o, Offset(o.x, o.y + d.y * cornerLen), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }

            // Edge handles only when aspect is free
            if (currentLocked == null) {
                val ew = with(density) { 20.dp.toPx() }
                val eh = with(density) { 4.dp.toPx() }
                val pill = Color.White.copy(alpha = 0.85f)
                drawRoundRect(pill, topLeft = Offset(r.left + r.width / 2 - ew / 2, r.top - eh / 2), size = Size(ew, eh), cornerRadius = CornerRadius(eh / 2))
                drawRoundRect(pill, topLeft = Offset(r.left + r.width / 2 - ew / 2, r.bottom - eh / 2), size = Size(ew, eh), cornerRadius = CornerRadius(eh / 2))
                drawRoundRect(pill, topLeft = Offset(r.left - eh / 2, r.top + r.height / 2 - ew / 2), size = Size(eh, ew), cornerRadius = CornerRadius(eh / 2))
                drawRoundRect(pill, topLeft = Offset(r.right - eh / 2, r.top + r.height / 2 - ew / 2), size = Size(eh, ew), cornerRadius = CornerRadius(eh / 2))
            }
        }
    }
}

private fun CropRect.toPx(v: Rect): Rect {
    val w = v.width
    val h = v.height
    return Rect(v.left + left * w, v.top + top * h, v.left + right * w, v.top + bottom * h)
}

private fun Rect.normalize(v: Rect): CropRect {
    val w = v.width
    val h = v.height
    return CropRect(
        ((left - v.left) / w).coerceIn(0f, 1f),
        ((top - v.top) / h).coerceIn(0f, 1f),
        ((right - v.left) / w).coerceIn(0f, 1f),
        ((bottom - v.top) / h).coerceIn(0f, 1f),
    )
}

private fun lerpCrop(a: CropRect, b: CropRect, t: Float) = CropRect(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t,
    a.bottom + (b.bottom - a.bottom) * t,
)

private fun CropRect.isNearlyFull() =
    left < 0.004f && top < 0.004f && right > 1f - 0.004f && bottom > 1f - 0.004f

// ── Shared bits ────────────────────────────────────────────────────────────

@Composable
private fun CenterPlayButton(isPlaying: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, label = "Scale")
    Box(
        Modifier
            .size(68.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (isPlaying) R.drawable.ic_pause_24 else R.drawable.ic_play_24),
            contentDescription = "Play/Pause",
            tint = Color.White,
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun MicroLabel(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, color = TextSec)
}

@Composable
private fun TimeBubble(text: String) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(color = accent, shape = RoundedCornerShape(7.dp), shadowElevation = 6.dp) {
        Text(
            text,
            color = Color(0xFF141416),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(fontFeatureSettings = "tnum"),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSecs = ms / 1000
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return "%02d:%02d".format(mins, secs)
}
