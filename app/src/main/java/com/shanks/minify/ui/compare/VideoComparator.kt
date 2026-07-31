package com.shanks.minify.ui.compare

import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.shanks.minify.R
import com.shanks.minify.ui.ComparisonSource
import com.shanks.minify.ui.theme.ErrorRed
import com.shanks.minify.ui.theme.Surface1
import com.shanks.minify.ui.theme.Surface2
import com.shanks.minify.ui.theme.TextPrim
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/** Position polling cadence, matching the existing video editor loop. */
private const val SYNC_POLL_MS = 50L

/**
 * Small debounce for scrub seeks. Without this, dragging the slider can issue
 * dozens of seeks in rapid succession, causing ExoPlayer surfaces to appear
 * frozen while they try to satisfy every intermediate seek request.
 */
private const val SCRUB_SEEK_DEBOUNCE_MS = 80L

/**
 * After a user seek/scrub, give the players time to settle before drift
 * correction runs again. This prevents the sync controller from interpreting
 * transient pending-seek positions as real drift and "correcting" one player
 * back to an old position.
 */
private const val SEEK_SETTLE_MS = 500L

/**
 * Minimum interval between automatic drift corrections. This avoids seek
 * flooding if one player is slow to apply a correction.
 */
private const val DRIFT_CORRECTION_COOLDOWN_MS = 250L

/**
 * If the follower/original player is stuck buffering while the master is ready,
 * force a resync no more often than this.
 */
private const val BUFFERING_FORCE_SEEK_MS = 1_000L

/**
 * Audio routing for the comparator: exactly one player is audible so no echo
 * occurs (Req 7.4). Audio is routed from only the "after" edited/compressed
 * source; the "before" original source is muted.
 */
@VisibleForTesting
internal const val AFTER_VOLUME = 1f

@VisibleForTesting
internal const val BEFORE_VOLUME = 0f

/**
 * The pure outcome of a drift-correction decision, independent of [ExoPlayer].
 */
@VisibleForTesting
internal sealed interface SyncAction {
    /** No correction needed. */
    object None : SyncAction

    /** Re-seek the "before" player to [toMs]. */
    data class SeekBefore(val toMs: Long) : SyncAction

    /** Re-seek the "after" player to [toMs]. */
    data class SeekAfter(val toMs: Long) : SyncAction
}

/**
 * Drift-correction helper for the two comparison players.
 *
 * Important behavior change:
 * Because audio is routed from the "after" player, the "after" player is now
 * treated as the timing master during normal playback. The muted "before"
 * player is the one that gets re-seeked to stay aligned. This avoids audible
 * seeks in the compressed/edit output and prevents the original surface from
 * being dragged back to stale positions while the edited player is still
 * settling after a skip.
 */
@VisibleForTesting
internal object SyncController {
    /** Maximum allowed divergence between the two playback positions. */
    const val DRIFT_TOLERANCE_MS = 100L

    /**
     * When duration is unknown, a very large delta during playback is treated
     * as a possible loop-boundary wrap and left alone.
     */
    const val LOOP_WRAP_MIN_DELTA_MS = 3_000L

    /**
     * Pure drift decision without a known clip duration.
     *
     * This is retained for compatibility/testing. Production sync now prefers
     * [decideMasterSync], which treats the audible "after" player as master.
     */
    fun decideDrift(
        bothPlaying: Boolean,
        beforePosMs: Long,
        afterPosMs: Long,
    ): SyncAction {
        val delta = abs(beforePosMs - afterPosMs)
        if (delta <= DRIFT_TOLERANCE_MS) return SyncAction.None

        // Loop-boundary wrap heuristic when duration is unknown.
        if (bothPlaying && delta >= LOOP_WRAP_MIN_DELTA_MS) return SyncAction.None

        return if (beforePosMs > afterPosMs) {
            SyncAction.SeekAfter(beforePosMs)
        } else {
            SyncAction.SeekBefore(afterPosMs)
        }
    }

    /**
     * Pure drift decision with a known clip duration.
     *
     * This version is wrap-aware: near the loop boundary, a small wrap-aware
     * divergence is not treated as drift.
     *
     * Retained for compatibility/testing. Production sync now prefers
     * [decideMasterSync].
     */
    fun decideDrift(
        bothPlaying: Boolean,
        beforePosMs: Long,
        afterPosMs: Long,
        durationMs: Long,
    ): SyncAction {
        if (durationMs <= 0L) return decideDrift(bothPlaying, beforePosMs, afterPosMs)

        val rawDelta = abs(beforePosMs - afterPosMs)
        if (rawDelta > durationMs) return SyncAction.None

        val wrapAwareDivergence = min(rawDelta, durationMs - rawDelta)
        if (wrapAwareDivergence <= DRIFT_TOLERANCE_MS) return SyncAction.None

        // If the raw delta is more than half the duration, the smaller position
        // is usually the one that has already wrapped to the next loop.
        val beforeLeads = if (rawDelta <= durationMs / 2L) {
            beforePosMs > afterPosMs
        } else {
            beforePosMs < afterPosMs
        }

        return if (beforeLeads) {
            SyncAction.SeekAfter(beforePosMs.coerceIn(0L, durationMs))
        } else {
            SyncAction.SeekBefore(afterPosMs.coerceIn(0L, durationMs))
        }
    }

    /**
     * Production sync decision: keep the muted "before" player aligned to the
     * audible "after" player.
     *
     * This avoids seeking the audible player during normal drift correction,
     * which would otherwise cause audio glitches and can make the comparator
     * feel unstable after skipping.
     */
    fun decideMasterSync(
        beforePosMs: Long,
        afterPosMs: Long,
        durationMs: Long,
    ): SyncAction {
        if (beforePosMs < 0L || afterPosMs < 0L) return SyncAction.None

        val target = if (durationMs > 0L) {
            afterPosMs.coerceIn(0L, durationMs)
        } else {
            afterPosMs
        }

        val rawDelta = abs(beforePosMs - target)
        if (rawDelta <= DRIFT_TOLERANCE_MS) return SyncAction.None

        if (durationMs > 0L) {
            // Wrap-aware check only makes sense while both positions are within
            // the known duration range.
            if (rawDelta <= durationMs) {
                val wrapAwareDivergence = min(rawDelta, durationMs - rawDelta)
                if (wrapAwareDivergence <= DRIFT_TOLERANCE_MS) return SyncAction.None
            }
        } else {
            // Unknown duration: avoid chasing a possible loop wrap.
            if (rawDelta >= LOOP_WRAP_MIN_DELTA_MS) return SyncAction.None
        }

        return SyncAction.SeekBefore(target)
    }

    /**
     * Applies master/follower drift correction.
     *
     * @return true if a seek was issued.
     */
    fun correctFollower(
        before: ExoPlayer,
        after: ExoPlayer,
    ): Boolean {
        val afterPos = after.currentPosition
        val beforePos = before.currentPosition

        if (afterPos < 0L || beforePos < 0L) return false

        // Do not chase the master while it is still settling.
        if (after.playbackState == Player.STATE_IDLE ||
            after.playbackState == Player.STATE_BUFFERING
        ) {
            return false
        }

        // Normal corrections are only applied when the follower is not already
        // buffering. A separate forced recovery path handles stuck buffering.
        if (before.playbackState == Player.STATE_IDLE ||
            before.playbackState == Player.STATE_BUFFERING
        ) {
            return false
        }

        val durationMs = firstPositive(after.duration, before.duration)

        return when (val action = decideMasterSync(beforePos, afterPos, durationMs)) {
            is SyncAction.SeekBefore -> {
                before.safeSeekTo(action.toMs)
                true
            }

            is SyncAction.SeekAfter -> false
            SyncAction.None -> false
        }
    }

    private fun firstPositive(vararg values: Long): Long =
        values.firstOrNull { it > 0L } ?: 0L
}

/**
 * Synchronized overlaid-wipe before/after video comparator.
 *
 * Fixes applied in this version:
 *
 * 1. The compressed/"after" player is the master clock and UI position source.
 * 2. The original/"before" player follows the master only after the master has
 *    accepted a new position, instead of both players being flooded with seeks.
 * 3. Scrubbing seeks are debounced, and only the master is seeked while dragging.
 * 4. On release, both players are explicitly synchronized.
 * 5. Closest-sync seeking is used to reduce the chance of the original decoder
 *    hanging on an exact seek.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoComparator(
    source: ComparisonSource,
) {
    val context = LocalContext.current

    val (beforeUri, afterUri) = remember(source) {
        when (source) {
            is ComparisonSource.Videos -> source.before to source.after
            else -> Uri.EMPTY to Uri.EMPTY
        }
    }

    // Two players — the "after" edited source carries audio (Req 7.4);
    // "before" is muted.
    val beforePlayer = remember(beforeUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(beforeUri))
            prepare()
            playWhenReady = false
            volume = BEFORE_VOLUME
            repeatMode = Player.REPEAT_MODE_ALL
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
    }

    val afterPlayer = remember(afterUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(afterUri))
            prepare()
            playWhenReady = false
            volume = AFTER_VOLUME
            repeatMode = Player.REPEAT_MODE_ALL
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Wipe divider position in [0,1], starting centered (Req 6.3).
    var dividerFraction by remember { mutableFloatStateOf(DividerOps.DEFAULT_DIVIDER_FRACTION) }

    // Scrub/seek state.
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableLongStateOf(0L) }
    var lastUserSeekMs by remember { mutableLongStateOf(0L) }
    var lastBeforeSyncSeekMs by remember { mutableLongStateOf(0L) }
    var scrubSeekJob by remember { mutableStateOf<Job?>(null) }

    val coroutineScope = rememberCoroutineScope()

    // Reset transient UI state when the source changes.
    LaunchedEffect(beforeUri, afterUri) {
        errorMessage = null
        isPlaying = false
        positionMs = 0L
        durationMs = 0L
        scrubPositionMs = 0L
        lastUserSeekMs = 0L
        lastBeforeSyncSeekMs = 0L
        dividerFraction = DividerOps.DEFAULT_DIVIDER_FRACTION
        scrubSeekJob?.cancel()
    }

    /**
     * Seeks the muted/original follower to the current master position if the
     * two are meaningfully diverged.
     */
    val alignBeforeToAfter: () -> Unit = {
        val target = afterPlayer.currentPosition
        val beforePos = beforePlayer.currentPosition

        if (target >= 0L &&
            beforePos >= 0L &&
            abs(beforePos - target) > SyncController.DRIFT_TOLERANCE_MS
        ) {
            lastBeforeSyncSeekMs = SystemClock.elapsedRealtime()
            beforePlayer.safeSeekTo(target)
        }
    }

    // Release both players when the comparator leaves composition (Req 7.6).
    // This is created before the listener effect so listeners are removed first
    // on disposal.
    DisposableEffect(beforePlayer, afterPlayer) {
        onDispose {
            scrubSeekJob?.cancel()
            beforePlayer.release()
            afterPlayer.release()
        }
    }

    // Per-player listeners: surface load errors by side (Req 7.8), and make the
    // original player follow the compressed/master player after seeks/loops.
    DisposableEffect(beforePlayer, afterPlayer) {
        val beforeListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorMessage = "Original video failed to load: ${error.errorCodeName}"
            }
        }

        val afterListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorMessage = "Compressed video failed to load: ${error.errorCodeName}"
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // While scrubbing, only the master is seeked repeatedly. The
                // follower is synchronized on release to avoid seek flooding.
                if (isScrubbing) return

                val target = newPosition.positionMs
                if (target < 0L) return

                val beforePos = beforePlayer.currentPosition
                if (beforePos < 0L ||
                    abs(beforePos - target) > SyncController.DRIFT_TOLERANCE_MS
                ) {
                    lastBeforeSyncSeekMs = SystemClock.elapsedRealtime()
                    beforePlayer.safeSeekTo(target)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (isScrubbing) return

                if (playbackState == Player.STATE_READY) {
                    alignBeforeToAfter()
                }
            }

            override fun onIsPlayingChanged(isNowPlaying: Boolean) {
                if (isScrubbing) return

                if (isNowPlaying) {
                    alignBeforeToAfter()
                }
            }
        }

        beforePlayer.addListener(beforeListener)
        afterPlayer.addListener(afterListener)

        onDispose {
            beforePlayer.removeListener(beforeListener)
            afterPlayer.removeListener(afterListener)
        }
    }

    // Pause both players when the screen moves to the background (Req 7.7).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, beforePlayer, afterPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                beforePlayer.pause()
                afterPlayer.pause()
                isPlaying = false
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Position polling + drift correction (~50ms). Keeps the follower aligned
    // to the master and recovers if the follower gets stuck buffering.
    LaunchedEffect(beforePlayer, afterPlayer) {
        var lastDriftCorrectionMs = 0L
        var lastForcedBufferingSeekMs = 0L

        while (true) {
            val now = SystemClock.elapsedRealtime()

            // While the user is scrubbing, or immediately after any seek, do not
            // let drift correction fight pending seeks.
            val settling = isScrubbing ||
                    now - lastUserSeekMs < SEEK_SETTLE_MS ||
                    now - lastBeforeSyncSeekMs < SEEK_SETTLE_MS

            if (!settling && now - lastDriftCorrectionMs > DRIFT_CORRECTION_COOLDOWN_MS) {
                if (SyncController.correctFollower(beforePlayer, afterPlayer)) {
                    lastDriftCorrectionMs = now
                } else if (beforePlayer.playbackState == Player.STATE_BUFFERING &&
                    afterPlayer.playbackState == Player.STATE_READY &&
                    now - lastForcedBufferingSeekMs > BUFFERING_FORCE_SEEK_MS
                ) {
                    // Forced recovery: if the original player is stuck buffering
                    // while the master is ready, re-seek it to the master.
                    val target = afterPlayer.currentPosition
                    if (target >= 0L) {
                        lastForcedBufferingSeekMs = now
                        lastDriftCorrectionMs = now
                        lastBeforeSyncSeekMs = now
                        beforePlayer.safeSeekTo(target)
                    }
                }
            }

            // Use the audible/master player as the UI position source.
            positionMs = if (isScrubbing) {
                scrubPositionMs
            } else {
                afterPlayer.safePosition()
            }

            durationMs = afterPlayer.safeDuration().takeIf { it > 0L }
                ?: beforePlayer.safeDuration()

            if (!settling) {
                isPlaying = afterPlayer.isPlaying || beforePlayer.isPlaying
            }

            delay(SYNC_POLL_MS.milliseconds)
        }
    }

    /**
     * Seeks only the master/compressed player. Used while scrubbing so the
     * follower/original player is not flooded with intermediate seeks.
     */
    val seekAfterOnly: (Long) -> Unit = { target ->
        val safeDuration = durationMs.coerceAtLeast(1L)
        val safeTarget = target.coerceIn(0L, safeDuration)

        scrubPositionMs = safeTarget
        positionMs = safeTarget
        lastUserSeekMs = SystemClock.elapsedRealtime()

        afterPlayer.safeSeekTo(safeTarget)
    }

    /**
     * Seeks both players and marks the time so drift correction backs off while
     * the players settle.
     */
    val seekBoth: (Long) -> Unit = { target ->
        val safeDuration = durationMs.coerceAtLeast(1L)
        val safeTarget = target.coerceIn(0L, safeDuration)

        scrubPositionMs = safeTarget
        positionMs = safeTarget
        lastUserSeekMs = SystemClock.elapsedRealtime()
        lastBeforeSyncSeekMs = SystemClock.elapsedRealtime()

        // Seek master first, then follower.
        afterPlayer.safeSeekTo(safeTarget)
        beforePlayer.safeSeekTo(safeTarget)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp),
    ) {
        // Shared overlay:
        // bottom = original/before
        // top = edited/after, cropped by divider
        CompareWipeOverlay(
            dividerFraction = dividerFraction,
            onDividerFractionChange = { dividerFraction = it },
            labelBefore = "Original",
            labelAfter = "Compressed",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 4.dp)
                .background(Color.Black),
            bottom = {
                ComparatorPlayerSurface(
                    player = beforePlayer,
                    modifier = Modifier.fillMaxSize(),
                )
            },
            top = {
                ComparatorPlayerSurface(
                    player = afterPlayer,
                    modifier = Modifier.fillMaxSize(),
                )
            },
        )

        errorMessage?.let { message ->
            Text(
                text = message,
                color = ErrorRed,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Single control row fanning out to both players.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface1)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val safeDuration = durationMs.coerceAtLeast(1L)

            val sliderPosition = (if (isScrubbing) scrubPositionMs else positionMs)
                .toFloat()
                .coerceIn(0f, safeDuration.toFloat())

            Slider(
                value = sliderPosition,
                enabled = durationMs > 0L,
                onValueChange = { value ->
                    val target = value.toLong().coerceIn(0L, safeDuration)

                    // Update UI immediately for a responsive slider.
                    isScrubbing = true
                    scrubPositionMs = target
                    positionMs = target
                    lastUserSeekMs = SystemClock.elapsedRealtime()

                    // While dragging, seek only the master/compressed player.
                    // The original player is synchronized on release.
                    scrubSeekJob?.cancel()
                    scrubSeekJob = coroutineScope.launch {
                        delay(SCRUB_SEEK_DEBOUNCE_MS.milliseconds)
                        seekAfterOnly(target)
                    }
                },
                onValueChangeFinished = {
                    scrubSeekJob?.cancel()
                    seekBoth(scrubPositionMs)
                    isScrubbing = false
                },
                valueRange = 0f..safeDuration.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Surface2,
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        if (isPlaying) {
                            beforePlayer.pause()
                            afterPlayer.pause()
                            isPlaying = false
                        } else {
                            alignBeforeToAfter()
                            beforePlayer.play()
                            afterPlayer.play()
                            isPlaying = true
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = if (isPlaying) "⏸  Pause" else "▶  Play",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Button(
                    onClick = {
                        seekBoth(0L)
                        beforePlayer.play()
                        afterPlayer.play()
                        isPlaying = true
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Surface2,
                        contentColor = TextPrim,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "↺  Replay",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

private fun ExoPlayer.safePosition(): Long =
    currentPosition.takeIf { it >= 0L } ?: 0L

private fun ExoPlayer.safeDuration(): Long =
    duration.takeIf { it > 0L } ?: 0L

private fun ExoPlayer.safeSeekTo(positionMs: Long) {
    val target = positionMs.takeIf { it >= 0L } ?: 0L
    val safeDuration = duration.takeIf { it > 0L }

    if (safeDuration != null) {
        seekTo(target.coerceIn(0L, safeDuration))
    } else {
        seekTo(target)
    }
}

/**
 * A single comparison surface: a texture-backed [PlayerView] bound to [player],
 * scaled with [AspectRatioFrameLayout.RESIZE_MODE_FIT] so it preserves aspect
 * ratio within the [modifier] bounds.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ComparatorPlayerSurface(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val playerView = remember(context) {
        val view = android.view.LayoutInflater.from(context)
            .inflate(R.layout.texture_player_view, null, false) as PlayerView

        view.apply {
            useController = false
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    AndroidView(
        factory = { playerView },
        modifier = modifier,
    ) { view ->
        if (view.player !== player) {
            view.player = player
        }
    }
}