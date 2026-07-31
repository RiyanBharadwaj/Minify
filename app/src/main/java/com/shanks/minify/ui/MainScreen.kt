package com.shanks.minify.ui

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.shanks.minify.logic.CodecDefault
import com.shanks.minify.logic.ProgressClamp
import com.shanks.minify.ui.editor.video.NativeVideoEditor
import com.shanks.minify.media3.CompressionMonitor
import com.shanks.minify.media3.CompressionService
import com.shanks.minify.media3.VideoCompressor
import com.shanks.minify.platform.MediaOperation
import com.shanks.minify.ui.nav.VideoTabState
import com.shanks.minify.ui.NativeAdView
import com.shanks.minify.ui.theme.*
import com.shanks.minify.utils.*
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private fun friendlyError(e: Exception): String =
    friendlyErrorText(e.localizedMessage ?: e.javaClass.simpleName)

/**
 * Best-effort source byte size for the before/after comparison. Prefers the
 * provider's declared SIZE column, then falls back to the asset descriptor
 * length. Returns `null` if the size cannot be determined. Never throws.
 */
private fun querySourceBytes(context: android.content.Context, uri: Uri): Long? {
    try {
        context.contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        val size = cursor.getLong(idx)
                        if (size > 0L) return size
                    }
                }
            }
    } catch (_: Exception) {
    }
    return try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            afd.length.takeIf { it >= 0L }
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Maps an explicit failure reason to user-facing text describing a video
 * compression failure. The reason originates from VideoCompressor's
 * preflight/runtime failures and is carried to the UI by the status flow as
 * "error:<reason>". Unrecognized reasons fall back to a generic message so the
 * user always gets an actionable hint (Req 2.3, 7.6).
 */
private fun friendlyErrorText(msg: String): String = when {
    // Source metadata unreadable (Req 2.7). Checked before the dimension branch
    // because the unreadable message also mentions dimensions.
    msg.contains("unreadable", ignoreCase = true) ||
        msg.contains("Source video", ignoreCase = true) ->
        "Source video is unreadable — try a different file."
    // Invalid target size (Req 2.8): non-positive or larger than the source.
    msg.contains("Target size", ignoreCase = true) ->
        "Invalid target size — choose a size between 0.1 MB and the source file size."
    // Invalid output dimensions / budget (Req 2.9).
    msg.contains("output budget", ignoreCase = true) ||
        msg.contains("dimension", ignoreCase = true) ->
        "Invalid video dimensions — try a different size or crop."
    // Unsupported codec (Req 2.4).
    msg.contains("codec", ignoreCase = true) ||
        msg.contains("CodecInfo", ignoreCase = true) ->
        "Codec not supported — try a different one."
    // Stalled export (Req 2.6).
    msg.contains("stalled", ignoreCase = true) ->
        "Compression stalled — try again or use a different codec."
    // No / low storage space.
    msg.contains("space", ignoreCase = true) ||
        msg.contains("ENOSPC", ignoreCase = true) ->
        "Not enough storage space — free up space and try again."
    msg.contains("permission", ignoreCase = true) ->
        "Storage permission denied."
    else -> "Compression failed — try a different codec or size."
}

// Lets a local `by` delegate read/write a property on VideoTabState. Because
// the target properties are backed by Compose mutableStateOf, reads made
// through these delegates still subscribe the composition to state changes,
// so the existing body can keep using plain `selectedUri`, `editState`, etc.
private operator fun <T> KMutableProperty0<T>.getValue(thisRef: Any?, property: KProperty<*>): T = get()
private operator fun <T> KMutableProperty0<T>.setValue(thisRef: Any?, property: KProperty<*>, value: T) = set(value)

/**
 * Thin wrapper kept so existing callers (e.g. MainActivity) keep compiling
 * until tab wiring lands (task 12.6). It remembers a session-scoped
 * [VideoTabState] — seeding the initial codec the same way MainScreen used to —
 * and delegates to [VideoTab].
 */
@Composable
fun MainScreen(
    currentAccent: AppAccent,
    currentAccentColor: Color,
    onAccentChange: (AppAccent, Color) -> Unit,
) {
    val videoState = rememberSaveable(saver = VideoTabState.Saver) {
        VideoTabState(
            initialCodec = CodecDefault.initialChoice { CodecAvailability.isSupported(it) }
        )
    }
    VideoTab(
        videoState = videoState,
        currentAccent = currentAccent,
        currentAccentColor = currentAccentColor,
        onAccentChange = onAccentChange,
    )
}

/**
 * The Video tab body. Its user-entered inputs (selected file, size preset,
 * custom size, codec, edits) are hoisted into [VideoTabState] so they survive
 * tab switches within a session (Req 3.6). The compression flow observes
 * [CompressionMonitor], so a running session is unaffected by tab switches
 * (Req 3.4, 3.7).
 */
@Composable
fun VideoTab(
    videoState: VideoTabState,
    currentAccent: AppAccent,
    currentAccentColor: Color,
    onAccentChange: (AppAccent, Color) -> Unit,
) {
    val context = LocalContext.current

    // Request the SAVE_VIDEO permission set (per API level) before starting a
    // compression that will save to the gallery; on denial, name the missing
    // permission and halt so no compression starts and media is left unchanged.
    val runPermissioned = rememberMediaPermissionRunner(onDenied = { name ->
        android.widget.Toast.makeText(
            context,
            "$name is required to save the compressed video. Operation cancelled.",
            android.widget.Toast.LENGTH_LONG
        ).show()
    })

    // Hoisted user-entered state lives in VideoTabState (survives tab switches).
    var selectedUri     by videoState::selectedUri
    var videoInfo       by remember { mutableStateOf<VideoInfo?>(null) }
    
    val progress        by CompressionMonitor.progress.collectAsStateWithLifecycle()
    val status          by CompressionMonitor.status.collectAsStateWithLifecycle()
    val isCompressing   by CompressionMonitor.isCompressing.collectAsStateWithLifecycle()
    val beforeSizeBytes by CompressionMonitor.beforeSizeBytes.collectAsStateWithLifecycle()
    val afterSizeBytes  by CompressionMonitor.afterSizeBytes.collectAsStateWithLifecycle()
    val afterUri        by CompressionMonitor.afterUri.collectAsStateWithLifecycle()

    var sizePresetIdx   by videoState::sizePresetIdx
    var customSizeMb    by videoState::customSizeMb
    var codecChoice     by videoState::codecChoice

    var editState       by videoState::editState
    var showEditor      by rememberSaveable { mutableStateOf(false) }
    var showSettings    by remember { mutableStateOf(value = false) }

    var isPlaying       by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var playerInstance  by remember { mutableStateOf<ExoPlayer?>(null) }

    var showCompressionCompleteDialog by remember { mutableStateOf(false) }

    // Full-screen before/after Comparison overlay (Video mode). Opened from the
    // completion dialog once a compressed output Uri is available (Req 11.1).
    var showComparison by remember { mutableStateOf(false) }

    val mainEnabled = !showSettings

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
        CompressionMonitor.resetStatus()
    }

    // Root overlay container: keeps the main content, the full-screen editor,
    // and the full-screen settings as *stacked* siblings so the editor/settings
    // overlays cover the tab body instead of being laid out beneath it.
    Box(modifier = Modifier.fillMaxSize()) {

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 56.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppHeader(onSettings = { if (mainEnabled) showSettings = true })

            FileSection(
                selectedUri = selectedUri,
                enabled     = !isCompressing && mainEnabled,
                onSelect    = { uri ->
                    if (isCompressing) { CompressionService.stop(context) }
                    selectedUri = uri
                    CompressionMonitor.resetStatus()
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
                    onEdit         = { showEditor = true },
                    isPlaying      = isPlaying,
                    onPlayPause    = { isPlaying = it },
                    currentPosition = currentPositionMs,
                    onPositionChanged = { currentPositionMs = it },
                    onPlayerReady  = { playerInstance = it },
                )
            }

            AnimatedVisibility(visible = (editState.hasEdits && selectedUri != null)) {
                EditSummaryRow(editState)
            }

            // Size_Picker (FunctionSection) + Codec_Selector leave the tree while a
            // session runs, and return within 500 ms when it ends (Req 5.1/5.2/5.3/5.4/5.7).
            // Short enter/exit keeps the show/hide well under the 500 ms bound.
            AnimatedVisibility(
                visible = !isCompressing,
                enter   = fadeIn(tween(200)) + expandVertically(tween(200)),
                exit    = fadeOut(tween(200)) + shrinkVertically(tween(200))
            ) {
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
                        runPermissioned(MediaOperation.SAVE_VIDEO) {
                            // Drive the shared compression pipeline directly through
                            // CompressionService, using the user's selected MB budget
                            // and the tab's current trim/crop EditState (#1, #4).
                            val outputPath = File(
                                context.cacheDir,
                                "editor_out_${System.currentTimeMillis()}.mp4",
                            ).absolutePath
                            CompressionService.start(
                                context = context,
                                inputUri = uri,
                                outputPath = outputPath,
                                codec = codecChoice,
                                targetSizeMb = effectiveMb,
                                editState = editState,
                                beforeSize = querySourceBytes(context, uri) ?: 0L,
                            )
                        }
                    }
                )
                CodecSelector(
                    selected = codecChoice,
                    onChange = { codecChoice = it },
                    enabled  = !isCompressing && mainEnabled
                )
              }
            }

            AnimatedVisibility(visible = isCompressing || status.isNotEmpty()) {
                // Cancel lives in the always-visible status area so it stays reachable
                // while the Size_Picker/Codec_Selector are hidden (Req 5.6).
                StatusCard(
                    progress      = progress,
                    status        = status,
                    isCompressing = isCompressing,
                    onCancel      = { CompressionService.stop(context) }
                )
            }

            NativeAdView()
        }

        // Stylish compression-complete dialog
        if (showCompressionCompleteDialog) {
            val accent = MaterialTheme.colorScheme.primary
            AlertDialog(
                onDismissRequest = {
                    showCompressionCompleteDialog = false
                    CompressionMonitor.resetStatus()
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
                    Text("Compression Complete", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrim)
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("Original" to beforeSizeBytes, "Compressed" to afterSizeBytes).forEach { (label, size) ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, color = TextSec, fontSize = 14.sp)
                                Text(formatFileSize(size), color = TextPrim, fontSize = 14.sp)
                            }
                        }
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(accent.copy(alpha = 0.2f)))
                        if (beforeSizeBytes > 0 && afterSizeBytes > 0) {
                            val reduction = ((beforeSizeBytes - afterSizeBytes).toFloat() / beforeSizeBytes * 100).toInt()
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Reduction", color = TextSec, fontSize = 14.sp)
                                Text("$reduction%", color = GreenOk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showCompressionCompleteDialog = false
                            CompressionMonitor.resetStatus()
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text("OK", color = accent, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    // Open the before/after Comparison screen in Video mode. Only
                    // available once both the original and the saved compressed
                    // output Uris are known (Req 11.1).
                    if (selectedUri != null && afterUri != null) {
                        TextButton(
                            onClick = {
                                showCompressionCompleteDialog = false
                                showComparison = true
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text("Compare", color = accent, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                containerColor = Surface1,
                titleContentColor = TextPrim,
                textContentColor = TextPrim,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }

    // Full-screen native video editor overlay
    AnimatedVisibility(
        visible  = showEditor && selectedUri != null,
        enter    = slideInHorizontally { it },
        exit     = slideOutHorizontally { it },
        modifier = Modifier.fillMaxSize()
    ) {
        selectedUri?.let { uri ->
            NativeVideoEditor(
                uri = uri,
                initialEditState = editState,
                onDone = { newState ->
                    editState = newState
                    showEditor = false
                },
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

    // Full-screen before/after Comparison overlay (Video mode). "before" is the
    // originally selected video; "after" is the saved compressed output surfaced
    // by the monitor (Req 11.1).
    AnimatedVisibility(
        visible  = showComparison && selectedUri != null && afterUri != null,
        enter    = slideInHorizontally { it },
        exit     = slideOutHorizontally { it },
        modifier = Modifier.fillMaxSize()
    ) {
        val before = selectedUri
        val after  = afterUri
        if (before != null && after != null) {
            ComparisonScreen(
                source  = ComparisonSource.Videos(before = before, after = after),
                onClose = { showComparison = false }
            )
        }
    }

    } // end root overlay Box

    LaunchedEffect(status) {
        if (status == "done") {
            showCompressionCompleteDialog = true
        } else if (status == "cancelled" || status.startsWith("error:")) {
            delay(duration = 4000.milliseconds)
            CompressionMonitor.resetStatus()
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
            AppIconImage(modifier = Modifier.size(40.dp))
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

@androidx.media3.common.util.UnstableApi
internal fun VideoSize.displayAspectRatio(): Float {
    if ((width <= 0) || (height <= 0)) return 0f
    val raw = width.toFloat() * pixelWidthHeightRatio / height.toFloat()
    @Suppress("DEPRECATION")
    return if ((unappliedRotationDegrees == 90) || (unappliedRotationDegrees == 270)) 1f / raw else raw
}

@androidx.media3.common.util.UnstableApi
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
    onPlayerReady: (ExoPlayer) -> Unit,
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
    var isSeeking by remember { mutableStateOf(value = false) }
    var seekTarget by remember { mutableLongStateOf(-1L) }

    val fullDurationMs = videoInfo?.durationSecs?.times(1000L) ?: 0L
    val trimStartMs   = editState.trimStartMs
    val trimEndMs     = editState.trimEndMs ?: fullDurationMs
    val displayDurationMs = (trimEndMs - trimStartMs).coerceAtLeast(0L)

    var mediaItemReady by remember { mutableStateOf(value = false) }

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
            delay(duration = 150.milliseconds)
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
            @androidx.media3.common.util.UnstableApi
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
        // Swapping the media item (e.g. after adopting an edited video) must
        // re-prepare the player even though `mediaItemReady` was already
        // `true` from a prior load — a `true -> true` state write is a no-op
        // in Compose and would never re-trigger the guarded effect below that
        // used to gate prepare() on that flag. Preparing directly here, right
        // after the new item is set, guarantees the edited video is actually
        // decoded/rendered instead of the stale previous frame lingering.
        player.setMediaItem(mediaItem)
        player.prepare()
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
                                .inflate(com.shanks.minify.R.layout.texture_player_view, null, false) as PlayerView
                            view.apply {
                                useController = false
                                setBackgroundColor(android.graphics.Color.BLACK)
                                @androidx.media3.common.util.UnstableApi
                                this.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                            playerViewRef = view
                            view
                        }
                    ) { /* player is set below */ }

                    LaunchedEffect(playerViewRef, player, mediaItemReady) {
                        val view = playerViewRef ?: return@LaunchedEffect
                        while (!view.isAttachedToWindow) {
                            delay(50.milliseconds)
                        }
                        view.player = player
                        delay(300.milliseconds)
                        if (mediaItemReady && (player.playbackState == Player.STATE_IDLE)) {
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
private fun StatusCard(
    progress: Float,
    status: String,
    isCompressing: Boolean,
    onCancel: () -> Unit = {},
) {
    val accent = MaterialTheme.colorScheme.primary
    val isDone      = status == "done"
    val isCancelled = status == "cancelled"
    val isError     = status.startsWith("error:")
    // Map the explicit failure reason carried by the status flow ("error:<reason>")
    // to friendly, user-facing text (Req 2.3, 7.6).
    val errorMsg    = if (isError) friendlyErrorText(status.removePrefix("error:")) else ""

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
                    // Display progress as an integer percentage clamped to [0, 100] (Req 5.5).
                    Text(
                        "${ProgressClamp.toPercent(progress)}%",
                        color    = accentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (isCompressing) {
                LinearProgressIndicator(
                    progress          = { progress.coerceIn(0f, 1f) },
                    modifier          = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color             = accentColor,
                    trackColor        = Surface2,
                )
                // Always-visible cancel control while a session runs (Req 5.6). The
                // Size_Picker/Codec_Selector are hidden during compression, so the
                // cancel must live here in the status area.
                Button(
                    onClick  = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = ErrorRed,
                        contentColor   = Color.White
                    )
                ) {
                    Text("Stop Compression", modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}
