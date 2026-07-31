package com.shanks.minify.media3

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.shanks.minify.logic.BitrateBudget
import com.shanks.minify.logic.BudgetResult
import com.shanks.minify.logic.TargetSizeValidation
import com.shanks.minify.logic.VideoBudget
import com.shanks.minify.ui.CodecAvailability
import com.shanks.minify.ui.CodecChoice
import com.shanks.minify.ui.CropRect
import com.shanks.minify.ui.EditState
import com.shanks.minify.ui.editor.model.PlanSegment
import com.shanks.minify.ui.editor.model.RotationGeometry
import com.shanks.minify.ui.editor.model.TerminalGuard
import com.shanks.minify.utils.getVideoInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(UnstableApi::class)
class CompressionJob(
    private val cancelFlag: AtomicBoolean,
    private var transformer: Transformer?,
    // Optional extra cancellation hook. Used by service-backed handles (e.g. the
    // one MediaExporter.exportVideo hands back) whose real Transformer lives inside
    // an asynchronously-started CompressionService: cancelling the returned handle
    // stops the service instead of touching a Transformer this object never held.
    // Defaults to null so the existing in-process callers are unaffected.
    private val onCancel: (() -> Unit)? = null,
) {
    /**
     * True if this job controls nothing: [transformer] never got a chance to start
     * (e.g. construction failed) and there is no [onCancel] hook either. A
     * service-backed handle with an [onCancel] is therefore not dead.
     */
    val isDead: Boolean
        get() = transformer == null && onCancel == null

    fun cancel() {
        cancelFlag.set(true)
        transformer?.cancel()
        onCancel?.invoke()
    }

    /** Used internally once a fallback retry replaces the underlying transformer. */
    internal fun rebind(newTransformer: Transformer?) {
        transformer = newTransformer
    }
}

@OptIn(UnstableApi::class)
object VideoCompressor {

    private const val TAG = "VideoCompressor"

    /** 1 MB = 1,048,576 bytes, matching the size math used across the app. */
    private const val BYTES_PER_MB = 1_048_576L

    // Progress state can legitimately sit in WAITING_FOR_AVAILABILITY / UNAVAILABLE for a
    // while on slower devices, but it shouldn't sit there forever. If we haven't seen a
    // real, advancing progress value in this long, something upstream (codec init,
    // muxer negotiation) is stuck and we stop polling rather than spin silently forever.
    private const val PROGRESS_STALL_TIMEOUT_MS = 60_000L
    private const val ACTIVE_PROGRESS_STALL_TIMEOUT_MS = 30_000L
    private const val PROGRESS_POLL_INTERVAL_MS = 200L

    // One-pass quality tuning.
    //
    // Size accuracy is learned adaptively in CompressionService (SizeCalibration), so here
    // we stay as close to the computed budget as possible and only enforce floors plus
    // low-bitrate geometry protection.
    private const val MIN_VIDEO_BITRATE_BPS = 250_000
    private const val MIN_AUDIO_BITRATE_BPS = 48_000
    private const val MIN_OUTPUT_DIMENSION = 240
    private const val MIN_OUTPUT_FPS = 24

    fun compress(
        context: Context,
        inputUri: Uri,
        outputPath: String,
        codecChoice: CodecChoice,
        targetSizeMb: Float,
        editState: EditState = EditState(),
        // Optional unified-media-editor edit passes. All default to a no-op so existing
        // callers (e.g. CompressionService's trim + crop path) keep the exact same
        // behavior. When supplied by MediaExporter, these carry the color/geometry
        // effects, audio volume/mute processors, and playback speed for the export.
        videoEffects: List<Effect> = emptyList(),
        audioProcessors: List<AudioProcessor> = emptyList(),
        speed: Float? = null,
        removeAudio: Boolean = false,
        // Optional clockwise rotation (Req 3.4) recorded on the unified-media-editor
        // geometry, normalized to {0, 90, 180, 270}. A 90/270 rotation swaps the
        // exported frame's width/height, so the Presentation output dimensions are
        // derived from RotationGeometry.displayedSize(...) below rather than the raw
        // (source-space) budget dimensions; otherwise a rotated frame would be
        // letterboxed/squished by SCALE_TO_FIT. Defaults to 0 (no swap) so every
        // existing trim + crop caller keeps its exact behavior.
        rotationDegrees: Int = 0,
        // Optional playback plan from the unified media editor (Req 7.3). When supplied
        // (non-empty) by MediaExporter for a timeline that carries reverse and/or freeze,
        // the export sequence is built from these ordered PlanSegments instead of the
        // splits-based boundaries below: freeze entries are realized as held-frame clips.
        // Defaults to empty so every existing caller (trim + crop + splits) keeps its exact
        // behavior. See createComposition() for the reverse limitation note.
        plan: List<PlanSegment> = emptyList(),
        onProgress: (Float) -> Unit,
        onSuccess: () -> Unit,
        onCancelled: () -> Unit,
        onFailure: (Exception) -> Unit,
    ): CompressionJob {

        val cancelFlag = AtomicBoolean(false)

        // Distinct from cancelFlag (see startProgressPolling's supersededFlag doc): flips to
        // true the moment a retry replaces the transformer currently being polled, so that
        // transformer's own poller stops reporting instead of racing the new one.
        var currentAttemptSuperseded = AtomicBoolean(false)

        // Single shared terminal-outcome guard scoped to this one compress(...) invocation
        // (Req 17.3). Whichever path first reaches a terminal state — onCompleted→onSuccess,
        // onError→onFailure, or the poller's cancel/stall→onCancelled/onFailure — wins the
        // compareAndSet and routes exactly one caller callback; every later path sees it
        // already set and routes nothing. This closes the leak where the stall-timeout poller
        // reports onFailure (and cancels the transformer) WITHOUT setting cancelFlag, letting a
        // subsequent onError (which only checks cancelFlag) spin up a CBR retry that keeps
        // running after failure was already reported. The retry branch itself does NOT set
        // terminal: a genuine first-time retry is a fresh attempt whose own listener/poller
        // routes the eventual outcome, so it is only started when terminal is still false.
        val terminal = TerminalGuard()

        fun cleanupPartialOutput() {
            try {
                val f = File(outputPath)
                if (f.exists() && !f.delete()) {
                    Log.w(TAG, "Could not delete partial output at $outputPath")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up partial output at $outputPath", e)
            }
        }

        // Every pre-flight rejection funnels through here: clean up any partial output for
        // symmetry with the runtime failure paths (Req 2.5), report the reason via onFailure
        // without throwing (Req 2.2), and hand back a dead job (isDead == true) so the caller
        // sees that the session never started.
        fun failPreflight(message: String): CompressionJob {
            Log.w(TAG, "Pre-flight validation failed: $message")
            cleanupPartialOutput()
            onFailure(IllegalStateException(message))
            return CompressionJob(cancelFlag, null)
        }

        // --- Pre-flight: selected codec must be available on this device (Req 2.4). ---
        val codecStatus = CodecAvailability.getStatus(codecChoice)
        if (!codecStatus.supported) {
            return failPreflight(
                "Selected codec ${codecChoice.name} is unsupported: " +
                        (codecStatus.unavailableReason ?: "not available on this device")
            )
        }

        val info = getVideoInfo(context, inputUri)

        // --- Pre-flight: source metadata must be readable (Req 2.7). ---
        if (info.width <= 0 || info.height <= 0 || info.durationSecs <= 0L) {
            return failPreflight(
                "Source video is unreadable (dimensions ${info.width}x${info.height}, " +
                        "duration ${info.durationSecs}s)"
            )
        }

        // --- Pre-flight: target size must be > 0 and <= source size (Req 2.8). ---
        val targetBytes = (targetSizeMb * BYTES_PER_MB).toLong()
        val sourceBytes = querySourceBytes(context, inputUri)
        if (sourceBytes != null) {
            when (val result = TargetSizeValidation.validate(targetBytes, sourceBytes)) {
                is TargetSizeValidation.Result.Invalid -> return failPreflight(
                    when (result.reason) {
                        TargetSizeValidation.Reason.NON_POSITIVE ->
                            "Target size must be greater than 0 (got $targetSizeMb MB)"
                        TargetSizeValidation.Reason.EXCEEDS_SOURCE ->
                            "Target size ($targetSizeMb MB) is larger than the source file"
                    }
                )
                TargetSizeValidation.Result.Valid -> Unit
            }
        } else if (targetBytes <= 0L) {
            // Source size could not be determined from the input Uri, so only the positivity
            // half of the check is enforced for this session (the <= source half is skipped).
            return failPreflight("Target size must be greater than 0 (got $targetSizeMb MB)")
        }

        val sourceDurationMs = info.durationSecs * 1000L

        if (editState.trimStartMs >= sourceDurationMs) {
            return failPreflight("Trim start is outside the source duration")
        }

        val safeTrimStartMs = editState.trimStartMs.coerceIn(0L, sourceDurationMs)
        val safeTrimEndMs: Long? = editState.trimEndMs?.let {
            it.coerceIn(safeTrimStartMs + 1L, sourceDurationMs)
        }

        val globalSpeed = speed?.takeIf { it.isFinite() && it > 0.01f } ?: 1f

        val effectiveDurationMs = if (plan.isNotEmpty()) {
            plan.sumOf { segment ->
                if (segment.isFreeze) {
                    segment.freezeHoldMs.coerceAtLeast(0L)
                } else {
                    val segStart = segment.startMs.coerceIn(0L, sourceDurationMs)
                    val segEnd = segment.endMs.coerceIn(segStart, sourceDurationMs)
                    ((segEnd - segStart) / globalSpeed).roundToLong()
                }
            }.coerceAtLeast(1L)
        } else {
            val endMs = safeTrimEndMs ?: sourceDurationMs
            ((endMs - safeTrimStartMs) / globalSpeed).roundToLong().coerceAtLeast(1L)
        }

        val effectiveDurSecs = (effectiveDurationMs / 1000L).coerceAtLeast(1L)

        val crop = editState.cropRect ?: CropRect.FULL
        val cropPxW = (info.width * crop.width).roundToInt().coerceAtLeast(1)
        val cropPxH = (info.height * crop.height).roundToInt().coerceAtLeast(1)

        // --- Pre-flight: the budget must yield valid encoder params/dimensions (Req 2.9). ---
        // BitrateBudget.compute returns a value instead of throwing, so invalid dimensions
        // become a routed failure rather than a crash.
        val budget: VideoBudget = when (
            val budgetResult = BitrateBudget.compute(
                targetSizeMb = targetSizeMb,
                durationSecs = effectiveDurSecs,
                srcBitrateKbps = info.bitrateKbps,
                srcWidth = cropPxW,
                srcHeight = cropPxH,
                frameRate = info.frameRate,
                codecChoice = codecChoice,
                removeAudio = removeAudio,
            )
        ) {
            is BudgetResult.Valid -> budgetResult.budget
            is BudgetResult.Invalid -> return failPreflight(
                "Cannot compute a valid output budget: ${budgetResult.reason}"
            )
        }

        // ── SIZE-DERIVED RATE BUDGET (single-pass size guarantee) ──────────────────
        //
        // Anchored to target size + effective (post trim/speed/plan) duration, NOT to
        // the upstream VideoBudget bitrate, so the encoder can never be asked for an
        // average bitrate the target cannot hold. VBR still varies instantaneously
        // around this average — and SizeCalibration pre-biases targetSizeMb upstream
        // to absorb that overshoot — but it cannot overshoot an average it was never
        // given. This is the fix for exports returning at/above source size.
        //
        // headroom is deliberately 1.0 here: VBR-overshoot compensation lives in
        // SizeCalibration (applied to targetSizeMb before this function is called).
        // This clamp is a pure correctness guard, so the two mechanisms do not
        // double-dip.
        val targetBits = targetSizeMb.toDouble() * BYTES_PER_MB.toDouble() * 8.0
        val totalBudgetBps = targetBits / effectiveDurSecs.toDouble()

        val rawVideoBitrateBps = (budget.videoBitrateBps + if (removeAudio) budget.audioBitrateBps else 0)
            .toInt()
            .coerceAtLeast(MIN_VIDEO_BITRATE_BPS)

        val safeAudioBitrateBps = when {
            removeAudio || budget.audioBitrateBps <= 0L -> 0
            else -> {
                // Don't let audio starve video on small targets: cap at ~20% of the
                // total size budget (and within the encoder's supported range).
                val audioCeiling = (totalBudgetBps * 0.20).toInt()
                    .coerceAtLeast(MIN_AUDIO_BITRATE_BPS)
                budget.audioBitrateBps.toInt()
                    .coerceIn(MIN_AUDIO_BITRATE_BPS, 192_000)
                    .coerceAtMost(audioCeiling)
            }
        }

        val videoCeilingBps = (totalBudgetBps - safeAudioBitrateBps).toInt()
        val safeVideoBitrateBps = rawVideoBitrateBps
            .coerceAtMost(videoCeilingBps)
            .coerceAtLeast(MIN_VIDEO_BITRATE_BPS)

        if (rawVideoBitrateBps > videoCeilingBps) {
            Log.w(
                TAG,
                "Bitrate budget (${rawVideoBitrateBps}bps) exceeded the size-derived ceiling " +
                        "(${videoCeilingBps}bps) for ${targetSizeMb}MB/${effectiveDurSecs}s; clamping. " +
                        "Verify BitrateBudget.compute is keyed off targetSizeMb, not source bitrate."
            )
        }
        // ── END SIZE-DERIVED RATE BUDGET ───────────────────────────────────────────

        // Low-bitrate quality protection.
        //
        // If the target bitrate is too small for the planned resolution/fps, reduce the
        // output resolution first (and fps for very low bitrate) so the encoder gets enough
        // bits per pixel. This is the main fix for dust/noise at tiny targets while keeping
        // a single fast encode.
        var outWidth = budget.outputWidth
        var outHeight = budget.outputHeight
        var outFps = budget.outputFps.toFloat()

        fun even(value: Int): Int {
            val v = value.coerceAtLeast(2)
            return if (v % 2 == 0) v else v + 1
        }

        // Never upscale: upscaling wastes bitrate and time.
        val (srcDisplayW, srcDisplayH) = RotationGeometry.displayedSize(cropPxW, cropPxH, rotationDegrees)
        if (srcDisplayW > 0 && srcDisplayH > 0 && outWidth > 0 && outHeight > 0) {
            if (outWidth > srcDisplayW || outHeight > srcDisplayH) {
                val scale = minOf(srcDisplayW.toFloat() / outWidth, srcDisplayH.toFloat() / outHeight)
                outWidth = even((outWidth * scale).roundToInt()).coerceAtLeast(MIN_OUTPUT_DIMENSION)
                outHeight = even((outHeight * scale).roundToInt()).coerceAtLeast(MIN_OUTPUT_DIMENSION)
            }
        }

        val codecIsHevc = codecChoice.mime == "video/hevc"
        val minBpp = if (codecIsHevc) 0.070f else 0.105f

        if (outWidth > 0 && outHeight > 0 && outFps > 0f && safeVideoBitrateBps > 0) {
            // For very low bitrate exports, prefer 24 fps: more bits per frame, less dust.
            if (safeVideoBitrateBps < 1_500_000 && outFps > MIN_OUTPUT_FPS.toFloat()) {
                outFps = MIN_OUTPUT_FPS.toFloat()
            } else if (safeVideoBitrateBps < 2_500_000 && outFps > 30f) {
                outFps = 30f
            }

            val currentPixels = outWidth.toLong() * outHeight.toLong()
            val maxPixels = (safeVideoBitrateBps.toDouble() / (outFps.toDouble() * minBpp.toDouble())).toLong()
            if (maxPixels > 0L && currentPixels > maxPixels) {
                val scale = Math.sqrt(maxPixels.toDouble() / currentPixels.toDouble())
                outWidth = even((outWidth * scale).roundToInt()).coerceAtLeast(MIN_OUTPUT_DIMENSION)
                outHeight = even((outHeight * scale).roundToInt()).coerceAtLeast(MIN_OUTPUT_DIMENSION)
            }

            // If still under the minimum bits-per-pixel budget after hitting the dimension floor,
            // reduce fps one more step (but never below 24).
            if (outFps > MIN_OUTPUT_FPS.toFloat()) {
                val bppNow = safeVideoBitrateBps.toDouble() /
                        (outWidth.toDouble() * outHeight.toDouble() * outFps.toDouble())
                if (bppNow < minBpp.toDouble() * 0.85) {
                    outFps = MIN_OUTPUT_FPS.toFloat()
                }
            }
        }

        // Media3 Effects. [effectiveSpeed] is the SpeedChangeEffect factor to apply for the
        // item being built: the caller's playback `speed` for normal clips, or a stretch
        // factor for a held-frame freeze clip (see createComposition). null appends no speed.
        fun buildEffects(effectiveSpeed: Float?): Effects {
            val effectsList = mutableListOf<Effect>()

            // Req 4.3: crop-vs-rotate ordering matches the live preview
            // (Media3EffectAdapter.toVideoEffects), which emits the Crop *before* the
            // ScaleAndRotateTransformation. editState.cropRect is already mapped into
            // source space by MediaExporter via CropSpaceMapping, so cutting it first
            // and letting the caller-supplied rotation/flip passes operate on the
            // cropped source realizes the photo renderer's rotate/mirror-then-crop
            // semantics. The crop is therefore added ahead of the videoEffects below.
            if (editState.cropRect != null) {
                val r = editState.cropRect
                effectsList.add(
                    Crop(
                        (-1f + 2f * r.left),
                        (-1f + 2f * r.right),
                        (1f - (2f * r.bottom)),
                        (1f - (2f * r.top)),
                    )
                )
            }

            // Caller-supplied visual passes: rotation/flip, then the color/parametric
            // grade. These run after the source-space crop above, matching preview.
            effectsList.addAll(videoEffects)

            // Req 3.4: the caller-supplied rotation/flip pass above rotates the
            // frame, so on a 90/270 rotation the presented frame's width and height
            // are swapped relative to the source-space budget dimensions. Derive the
            // Presentation output size from RotationGeometry.displayedSize so the
            // exported rotation matches the previewed frame's aspect (no letterboxing
            // or non-uniform scaling). For 0/180 this returns the budget dimensions
            // unchanged, preserving existing behavior.
            val (presentW, presentH) = RotationGeometry.displayedSize(
                outWidth,
                outHeight,
                rotationDegrees,
            )
            effectsList.add(
                Presentation.createForWidthAndHeight(
                    presentW,
                    presentH,
                    Presentation.LAYOUT_SCALE_TO_FIT
                )
            )

            val sourceFps = if (info.frameRate > 1f) info.frameRate else outFps
            if (outFps > 1f && sourceFps > outFps + 0.5f) {
                effectsList.add(
                    FrameDropEffect.createSimpleFrameDropEffect(
                        sourceFps,
                        outFps
                    )
                )
            }

            // Playback speed is a timing effect; ordering among the GL passes above is
            // not visually significant, so it is appended last when requested.
            effectiveSpeed?.let { effectsList.add(SpeedChangeEffect(it)) }

            return Effects(audioProcessors, effectsList)
        }

        // Build a single clipped, effected item for the [start, end] source-ms window.
        fun clipItem(
            start: Long,
            end: Long,
            itemEffects: Effects,
            forceRemoveAudio: Boolean = false,
        ): EditedMediaItem {
            val maxStart = (sourceDurationMs - 1L).coerceAtLeast(0L)
            val boundedStart = start.coerceIn(0L, maxStart)
            val boundedEnd = if (end == C.TIME_END_OF_SOURCE) {
                C.TIME_END_OF_SOURCE
            } else {
                end.coerceIn(boundedStart + 1L, sourceDurationMs)
            }
            val mediaItem = MediaItem.Builder()
                .setUri(inputUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(boundedStart)
                        .setEndPositionMs(boundedEnd)
                        .build()
                )
                .build()
            return EditedMediaItem.Builder(mediaItem)
                .setEffects(itemEffects)
                .setRemoveAudio(removeAudio || forceRemoveAudio)
                .build()
        }

        fun createComposition(): Composition {
            val baseEffects = buildEffects(speed)
            val sequenceBuilder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
            var addedItems = 0

            when {
                plan.isNotEmpty() -> {
                    val sourceFpsForFreeze = if (info.frameRate > 1f) info.frameRate else 30f
                    val frameMs = (1000f / sourceFpsForFreeze)
                        .roundToInt()
                        .toLong()
                        .coerceAtLeast(1L)

                    plan.forEach { segment ->
                        if (segment.isFreeze) {
                            val holdMs = segment.freezeHoldMs
                            if (holdMs <= 0L) return@forEach

                            val maxStart = (sourceDurationMs - 1L).coerceAtLeast(0L)
                            var freezeStart = segment.startMs.coerceIn(0L, maxStart)
                            var freezeEnd = (freezeStart + frameMs).coerceAtMost(sourceDurationMs)
                            if (freezeEnd <= freezeStart) {
                                freezeStart = (sourceDurationMs - frameMs).coerceAtLeast(0L)
                                freezeEnd = sourceDurationMs
                            }
                            if (freezeEnd <= freezeStart) return@forEach

                            val freezeSpeed = ((freezeEnd - freezeStart).toFloat() / holdMs.toFloat())
                                .coerceIn(0.001f, 1f)

                            sequenceBuilder.addItem(
                                clipItem(
                                    freezeStart,
                                    freezeEnd,
                                    buildEffects(freezeSpeed),
                                    forceRemoveAudio = true,
                                )
                            )
                            addedItems++
                        } else {
                            if (segment.reversed) {
                                Log.w(
                                    TAG,
                                    "Reverse requested for [${segment.startMs}, ${segment.endMs}) " +
                                            "but Media3 Transformer cannot reverse frames; exporting forward."
                                )
                            }
                            val maxStart = (sourceDurationMs - 1L).coerceAtLeast(0L)
                            val start = segment.startMs.coerceIn(0L, maxStart)
                            val end = segment.endMs.coerceIn(start + 1L, sourceDurationMs)
                            if (end <= start) return@forEach
                            sequenceBuilder.addItem(clipItem(start, end, baseEffects))
                            addedItems++
                        }
                    }
                }

                editState.splits.isEmpty() -> {
                    sequenceBuilder.addItem(
                        clipItem(
                            safeTrimStartMs,
                            safeTrimEndMs ?: C.TIME_END_OF_SOURCE,
                            baseEffects,
                        )
                    )
                    addedItems++
                }

                else -> {
                    val endLimit = safeTrimEndMs ?: sourceDurationMs
                    val boundaries = buildList {
                        add(safeTrimStartMs)
                        editState.splits.asSequence()
                            .filter { it > safeTrimStartMs && it < endLimit }
                            .distinct()
                            .sorted()
                            .forEach { add(it) }
                        add(endLimit)
                    }
                    boundaries.zipWithNext { start, end ->
                        if (end > start) {
                            sequenceBuilder.addItem(clipItem(start, end, baseEffects))
                            addedItems++
                        }
                    }
                }
            }

            if (addedItems == 0) {
                sequenceBuilder.addItem(
                    clipItem(
                        safeTrimStartMs,
                        safeTrimEndMs ?: C.TIME_END_OF_SOURCE,
                        baseEffects,
                    )
                )
            }

            return Composition.Builder(sequenceBuilder.build())
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()
        }

        // allowCbr=false is used on the one-shot retry after a failed CBR attempt. See
        // the try/start block below for why this exists: CBR is not universally
        // supported and has a documented failure mode (androidx/media#2273) where the
        // export simply never completes on some hardware/OS combos, as opposed to
        // failing fast with a catchable error. We can't distinguish "still working" from
        // "silently wedged" from the progress callback alone, so the retry is triggered
        // by onError specifically, not by a timeout — see the note on
        // PROGRESS_STALL_TIMEOUT_MS below for why a stall timeout is a *safety net* on
        // top of this, not a substitute for it.
        fun buildVideoEncoderSettings(allowCbr: Boolean): VideoEncoderSettings {
            val builder = VideoEncoderSettings.Builder()
                .setBitrate(safeVideoBitrateBps)
                .setiFrameIntervalSeconds(2f)

            // Prefer constrained VBR for better perceived quality.
            // CBR is kept only as a compatibility/fallback retry path.
            builder.setBitrateMode(
                if (allowCbr) {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                } else {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                }
            )

            // H.264 High profile is usually more efficient than Baseline/Main.
            // Fallback remains enabled below, so unsupported devices can still recover.
            when (codecChoice.mime) {
                MediaFormat.MIMETYPE_VIDEO_AVC -> {
                    runCatching<Unit> {
                        builder.setEncodingProfileLevel(
                            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                            VideoEncoderSettings.NO_VALUE
                        )
                    }
                }
                MediaFormat.MIMETYPE_VIDEO_HEVC -> {
                    runCatching<Unit> {
                        builder.setEncodingProfileLevel(
                            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                            VideoEncoderSettings.NO_VALUE
                        )
                    }
                }
            }

            return builder.build()
        }

        // Applies the reserved audio bitrate from the budget (budget.audioBitrateBps) to
        // the encoder. Without this, Media3 uses the platform/track default (commonly a
        // flat ~128kbps AAC), which is unaccounted for in the video-bitrate math and can
        // push small-target outputs over budget. AudioEncoderSettings was added in Media3
        // 1.5.0 and attaches to DefaultEncoderFactory.Builder via
        // setRequestedAudioEncoderSettings (this project uses Media3 1.10.1).
        fun buildAudioEncoderSettings(): AudioEncoderSettings {
            val builder = AudioEncoderSettings.Builder()
            if (safeAudioBitrateBps > 0) {
                builder.setBitrate(safeAudioBitrateBps)
            }
            return builder.build()
        }

        // `jobRef` is set immediately after the first buildTransformer() call below.
        // buildTransformer's onError listener (which reads jobRef) only ever runs later,
        // asynchronously, once transformer.start() is underway — by which point jobRef is
        // guaranteed non-null. It's a separate nullable var (rather than declaring `job`
        // above buildTransformer and capturing that directly) so the dependency is explicit
        // instead of relying on Kotlin's forward-reference-in-closure behavior being obvious
        // to the next reader.
        var jobRef: CompressionJob? = null

        fun buildTransformer(allowCbr: Boolean): Transformer {
            val transformerBuilder = Transformer.Builder(context)
                .setVideoMimeType(codecChoice.mime)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(buildVideoEncoderSettings(allowCbr))
                        .setRequestedAudioEncoderSettings(buildAudioEncoderSettings())
                        // Lets the library itself fall back to a supported bitrate /
                        // bitrate-mode / profile-level combination when the requested one
                        // isn't available on this device's encoder, instead of failing
                        // outright. This is a real, separate mechanism from our own
                        // app-level CBR retry below: this one handles "encoder rejects
                        // this exact format," ours handles "encoder accepts the format but
                        // then the export hangs/dies mid-run," which fallback alone does
                        // not catch.
                        .setEnableFallback(true)
                        .build()
                )
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cancelFlag.get()) return
                            // Terminal guard (Req 17.3): only route success if no other path
                            // (e.g. a stall-timeout failure) has already reported terminally.
                            if (terminal.tryClaim()) {
                                Handler(context.mainLooper).post { onSuccess() }
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            if (cancelFlag.get()) {
                                cleanupPartialOutput()
                                return
                            }
                            // Terminal guard (Req 17.3): if another path already reported a
                            // terminal outcome for this invocation — notably the stall-timeout
                            // poller, which cancels the transformer and reports onFailure
                            // WITHOUT setting cancelFlag — swallow this onError. Skipping it
                            // here is what prevents the CBR-retry branch below from leaking a
                            // second transformer that keeps running after failure was reported.
                            if (terminal.isClaimed) {
                                cleanupPartialOutput()
                                return
                            }

                            Log.e(TAG, "Export error", exportException)
                            cleanupPartialOutput()

                            if (!allowCbr) {
                                // One retry, CBR this time. The primary path uses VBR for better
                                // perceived quality; CBR is the compatibility fallback.
                                Log.w(TAG, "Retrying export with forced CBR after failure")

                                // This transformer's own poller must stop reporting now —
                                // the retry's poller is about to take over.
                                currentAttemptSuperseded.set(true)

                                val retryTransformer = buildTransformer(allowCbr = true)
                                jobRef?.rebind(retryTransformer)

                                val retrySupersededFlag = AtomicBoolean(false)
                                currentAttemptSuperseded = retrySupersededFlag

                                try {
                                    retryTransformer.start(createComposition(), outputPath)
                                    startProgressPolling(
                                        context = context,
                                        transformer = retryTransformer,
                                        cancelFlag = cancelFlag,
                                        supersededFlag = retrySupersededFlag,
                                        terminal = terminal,
                                        onProgress = onProgress,
                                        onCancelled = onCancelled,
                                        onFailure = onFailure,
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "Retry-with-CBR also failed to start", e)
                                    jobRef?.rebind(null)
                                    if (terminal.tryClaim()) {
                                        Handler(context.mainLooper).post { onFailure(e) }
                                    }
                                }
                            } else {
                                // Terminal guard (Req 17.3): route the final failure only if we
                                // win the single-outcome race.
                                if (terminal.tryClaim()) {
                                    Handler(context.mainLooper).post { onFailure(exportException) }
                                }
                            }
                        }
                    }
                )

            if (!removeAudio) {
                runCatching<Unit> {
                    transformerBuilder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                }
            }

            return transformerBuilder.build()
        }

        val transformer = buildTransformer(allowCbr = false)
        val job = CompressionJob(cancelFlag, transformer)
        jobRef = job

        try {
            File(outputPath).parentFile?.mkdirs()
            transformer.start(createComposition(), outputPath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start transformer", e)
            cleanupPartialOutput()
            job.rebind(null)
            if (terminal.tryClaim()) {
                Handler(context.mainLooper).post { onFailure(e) }
            }
            return job
        }

        startProgressPolling(
            context = context,
            transformer = transformer,
            cancelFlag = cancelFlag,
            supersededFlag = currentAttemptSuperseded,
            terminal = terminal,
            onProgress = onProgress,
            onCancelled = onCancelled,
            onFailure = onFailure,
        )

        return job
    }

    /**
     * @param supersededFlag set to true by the caller once [transformer] is no longer the
     *   authoritative one being watched (e.g. a retry replaced it after onError). The poller
     *   checks this every tick and stops silently — without calling onFailure/onCancelled —
     *   because in that case a *different* poller/listener pair has already taken over
     *   reporting for this compression job. Without this, a poller left running against a
     *   superseded transformer can fire a stale stall-timeout failure well after a retry has
     *   already succeeded or separately failed.
     */
    private fun startProgressPolling(
        context: Context,
        transformer: Transformer,
        cancelFlag: AtomicBoolean,
        supersededFlag: AtomicBoolean,
        // Shared single-terminal-outcome guard for the whole compress(...) invocation (Req
        // 17.3). reportOnceOnMain routes through it so a stall/cancel report here and a
        // listener callback (onCompleted/onError) can never both reach the caller: the first
        // to compareAndSet wins, and it also blocks the listener's CBR-retry branch.
        terminal: TerminalGuard,
        onProgress: (Float) -> Unit,
        onCancelled: () -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val mainHandler = Handler(context.mainLooper)
        var lastAdvancingProgressTime = System.currentTimeMillis()
        var lastSeenProgress = -1f

        // Local to this poller, distinct from the shared cancelFlag. cancelFlag means "the
        // *caller* asked to cancel" and is also read by the Transformer.Listener to decide
        // whether an onError/onCompleted callback is expected or should be swallowed. If a
        // stall timeout here also set cancelFlag, a listener callback racing in after
        // transformer.cancel() would see cancelFlag=true and silently swallow itself via
        // cleanupPartialOutput()+return — even though the caller never asked to cancel and
        // is instead waiting on the onFailure we're about to post. Keeping this separate
        // means a stall is reported as a failure even if the listener also fires afterward
        // (the second callback is a no-op via hasReportedTerminalState below, not a silent
        // swallow).
        var stalledLocally = false

        // Routes at most one terminal callback for the entire compress(...) invocation, using
        // the shared [terminal] guard (Req 17.3) rather than a poller-local flag. This means a
        // stall/cancel report here and the Transformer.Listener callbacks coordinate through
        // one flag: whichever fires first wins the compareAndSet, and — critically — a
        // stall-timeout onFailure sets [terminal] so a later onError swallows itself instead of
        // launching a leaked CBR-retry transformer.
        fun reportOnceOnMain(action: () -> Unit) {
            if (!terminal.tryClaim()) return
            Handler(context.mainLooper).post(action)
        }

        val progressRunnable = object : Runnable {
            override fun run() {
                if (supersededFlag.get()) {
                    // A retry (or other replacement) has taken over reporting for this job.
                    // Stop silently: the new poller/listener pair owns onSuccess/onFailure/
                    // onCancelled from here, and firing anything from this side would either
                    // duplicate or race a report that's no longer about the active attempt.
                    return
                }

                if (terminal.isClaimed) {
                    return
                }

                if (cancelFlag.get()) {
                    reportOnceOnMain(onCancelled)
                    return
                }

                if (stalledLocally) {
                    // Already reported as stalled on a previous tick; nothing left to poll.
                    return
                }

                val progressHolder = ProgressHolder()
                val state = transformer.getProgress(progressHolder)
                val now = System.currentTimeMillis()

                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    val progress = progressHolder.progress / 100f
                    onProgress(progress)
                    if (progress > lastSeenProgress) {
                        lastSeenProgress = progress
                        lastAdvancingProgressTime = now
                    }
                }

                val stillPolling = state == Transformer.PROGRESS_STATE_NOT_STARTED ||
                        state == Transformer.PROGRESS_STATE_AVAILABLE ||
                        state == Transformer.PROGRESS_STATE_UNAVAILABLE ||
                        state == Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY

                if (!stillPolling) {
                    // Terminal state reached by some other path (e.g. onCompleted/onError
                    // already fired) — nothing left for the poller to do.
                    return
                }

                val stalledFor = now - lastAdvancingProgressTime
                val stallLimit = when {
                    state == Transformer.PROGRESS_STATE_NOT_STARTED && lastSeenProgress < 0f -> 20_000L
                    lastSeenProgress > 0f -> ACTIVE_PROGRESS_STALL_TIMEOUT_MS
                    else -> PROGRESS_STALL_TIMEOUT_MS
                }

                if (stalledFor > stallLimit) {
                    // Belt-and-suspenders for the case documented in androidx/media#2273:
                    // an export can sit in PROGRESS_STATE_UNAVAILABLE indefinitely without
                    // ever calling onError, particularly under forced CBR. We don't know
                    // for certain the job is dead — this is a heuristic, not a guarantee —
                    // so we surface it as a failure rather than silently giving up. We still
                    // cancel the underlying transformer so it isn't left running in the
                    // background after we've stopped watching it, but we do NOT set the
                    // shared cancelFlag for that (see comment above stalledLocally).
                    val reason = if (state == Transformer.PROGRESS_STATE_NOT_STARTED && lastSeenProgress < 0f) {
                        "Export never started (no progress after ${stalledFor}ms)"
                    } else {
                        "Export appears stalled (no progress for ${stalledFor}ms)"
                    }
                    Log.w(TAG, "$reason in state=$state; treating as stalled export")
                    stalledLocally = true
                    transformer.cancel()
                    reportOnceOnMain {
                        onFailure(IllegalStateException(reason))
                    }
                    return
                }

                mainHandler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
            }
        }

        mainHandler.post(progressRunnable)
    }

    /**
     * Best-effort source size in bytes for the input [uri], used only for the target-size
     * pre-flight (Req 2.8). Returns null when the size cannot be determined, in which case
     * the caller validates only that the target is positive. Never throws.
     */
    private fun querySourceBytes(context: Context, uri: Uri): Long? {
        // Prefer the provider's declared SIZE column (works for content:// picker Uris).
        try {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0 && !cursor.isNull(idx)) {
                            val size = cursor.getLong(idx)
                            if (size > 0L) return size
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query source size via OpenableColumns for $uri", e)
        }

        // Fall back to the descriptor length (covers file:// and providers without SIZE).
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val len = afd.length
                if (len >= 0L) len else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not open source descriptor for $uri", e)
            null
        }
    }
}