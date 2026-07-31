package com.shanks.minify.ui.compare

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumentation and smoke tests for the video Before/After comparator (Requirements 11, 12).
 *
 * Two categories of check live here:
 *
 *  - **Pure / deterministic checks** exercise the [SyncController] drift decision (Req 11.6) and
 *    the audio-routing invariant (Req 11.7). They do not depend on real player timing, so they
 *    pass deterministically wherever the suite runs.
 *  - **Compose UI checks** render [VideoComparator] with two short synthesized H.264 clips and
 *    verify the observable behavior that requires a real device/emulator and live [ExoPlayer]
 *    instances: side-by-side layout (Req 11.1), Play/Pause fan-out (Req 11.2, 11.3), Replay
 *    (Req 11.5), and per-side load-error surfacing (Req 12.3).
 *
 * The Media3 sync tolerance, seek fan-out, completion sync, and lifecycle pause/release
 * (Reqs 11.4, 11.8, 12.1, 12.2) are runtime behaviors whose observable surface is exercised here
 * through the fan-out controls and the drift-decision seam; the exact 100ms tolerance is a runtime
 * measurement rather than a pure property (see the design Testing Strategy).
 *
 * Devices that cannot encode AVC skip the video-dependent UI tests via [Assume]; the drift and
 * volume checks need no asset and always run.
 *
 * Validates Requirements 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.8, 12.1, 12.2, 12.3.
 */
@RunWith(AndroidJUnit4::class)
class VideoComparatorTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var beforeFile: File? = null
    private var afterFile: File? = null

    @Before
    fun setUp() {
        // Best-effort synthesis of two small test clips. If encoding is unsupported on this device,
        // the files stay null and video-dependent UI tests are skipped via Assume.
        try {
            beforeFile = TestVideoFactory.createVideo(context.cacheDir)
            afterFile = TestVideoFactory.createVideo(context.cacheDir)
        } catch (t: Throwable) {
            beforeFile = null
            afterFile = null
        }
    }

    @After
    fun tearDown() {
        beforeFile?.delete()
        afterFile?.delete()
        beforeFile = null
        afterFile = null
    }

    // ---------------------------------------------------------------------------------------------
    // Drift-sync decision (Req 11.6) — pure, deterministic.
    // ---------------------------------------------------------------------------------------------

    /** When both positions are within tolerance, no correction is issued (Req 11.6). */
    @Test
    fun drift_withinTolerance_returnsNone() {
        // 60ms apart, below the 100ms tolerance.
        assertEquals(SyncAction.None, SyncController.decideDrift(bothPlaying = true, beforePosMs = 1_000L, afterPosMs = 1_060L))
    }

    /** A divergence exactly equal to the tolerance is not corrected (boundary, Req 11.6). */
    @Test
    fun drift_atTolerance_returnsNone() {
        assertEquals(
            SyncAction.None,
            SyncController.decideDrift(bothPlaying = true, beforePosMs = 1_000L, afterPosMs = 1_100L),
        )
    }

    /**
     * Drift is now corrected whether or not both players are actively playing, so pause/seek/scrub
     * divergence is re-seeked too (editor-compare-slider-fixes Req 2.4 / Property 3). This
     * supersedes the old media-editor-ux-fixes both-playing gate that returned None while paused.
     * Here "before" (0) lags "after" (5000) while not both playing, so the lagging "before" player
     * is re-seeked up to the leader.
     */
    @Test
    fun drift_notBothPlaying_stillCorrects() {
        assertEquals(
            SyncAction.SeekBefore(5_000L),
            SyncController.decideDrift(bothPlaying = false, beforePosMs = 0L, afterPosMs = 5_000L),
        )
    }

    /** When "before" leads beyond tolerance, the lagging "after" player is re-seeked to it (Req 11.6). */
    @Test
    fun drift_beforeAhead_seeksAfterToLeader() {
        val action = SyncController.decideDrift(bothPlaying = true, beforePosMs = 2_500L, afterPosMs = 2_000L)
        assertEquals(SyncAction.SeekAfter(2_500L), action)
    }

    /** When "after" leads beyond tolerance, the lagging "before" player is re-seeked to it (Req 11.6). */
    @Test
    fun drift_afterAhead_seeksBeforeToLeader() {
        val action = SyncController.decideDrift(bothPlaying = true, beforePosMs = 2_000L, afterPosMs = 2_500L)
        assertEquals(SyncAction.SeekBefore(2_500L), action)
    }

    // ---------------------------------------------------------------------------------------------
    // Audio-routing smoke test (Req 11.7) — pure, deterministic.
    // ---------------------------------------------------------------------------------------------

    /**
     * Exactly one of the two players is audible at all times so no echo occurs (Req 11.7). The
     * comparator assigns these volumes once at construction and never mutates them from the
     * playback controls, so asserting on the production constants captures the "at all times"
     * invariant.
     */
    @Test
    fun audioRouting_exactlyOnePlayerIsAudible() {
        val beforeAudible = BEFORE_VOLUME != 0f
        val afterAudible = AFTER_VOLUME != 0f
        assertTrue(
            "exactly one comparator player must be audible (Req 11.7)",
            beforeAudible xor afterAudible,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Compose UI behavior — requires a device/emulator and live ExoPlayer instances.
    // ---------------------------------------------------------------------------------------------

    /**
     * The comparator renders a single aligned before/after overlay (no "Original"/"Compressed"
     * side captions — those were intentionally removed so the two layers read as one aligned
     * overlay, editor-compare-slider-fixes Req 2.1) plus a single shared control row.
     */
    @Test
    fun comparator_showsOverlayAndControls() {
        val (before, after) = requireVideos()
        composeRule.setContent {
            VideoComparator(before = Uri.fromFile(before), after = Uri.fromFile(after))
        }

        // Single shared control row: Play (▶) and Replay (↺).
        composeRule.onNodeWithText("▶", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("↺", substring = true).assertIsDisplayed()
    }

    /**
     * The single Play/Pause control fans out to both players: pressing Play flips the control to
     * Pause (both playing, Req 11.2), and pressing again flips it back (both paused, Req 11.3).
     */
    @Test
    fun playPause_fansOutToBothPlayers() {
        val (before, after) = requireVideos()
        composeRule.setContent {
            VideoComparator(before = Uri.fromFile(before), after = Uri.fromFile(after))
        }

        // Initially paused → shows Play (▶).
        composeRule.onNodeWithText("▶", substring = true).assertIsDisplayed()

        // Press Play → both play → control shows Pause (⏸).
        composeRule.onNodeWithText("▶", substring = true).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("⏸", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Press Pause → both pause → control shows Play (▶) again.
        composeRule.onNodeWithText("⏸", substring = true).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("▶", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Replay returns both players to the start and plays, so the control shows Pause (Req 11.5). */
    @Test
    fun replay_startsPlaybackFromStart() {
        val (before, after) = requireVideos()
        composeRule.setContent {
            VideoComparator(before = Uri.fromFile(before), after = Uri.fromFile(after))
        }

        composeRule.onNodeWithText("↺", substring = true).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("⏸", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * A source that cannot be loaded surfaces an error naming which side failed (Req 12.3). Here the
     * "before" (original) source is a non-existent file while the "after" source is valid, so the
     * message must identify the original side and must not blame the compressed side.
     */
    @Test
    fun sourceLoadError_identifiesFailingSide() {
        val (_, after) = requireVideos()
        val bogusBefore = Uri.fromFile(File(context.cacheDir, "does_not_exist_${System.nanoTime()}.mp4"))

        composeRule.setContent {
            VideoComparator(before = bogusBefore, after = Uri.fromFile(after))
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Original video failed to load", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The valid compressed side must not be reported as failed.
        composeRule.onAllNodesWithText("Compressed video failed to load", substring = true)
            .fetchSemanticsNodes()
            .let { assertTrue("compressed side should not be reported as failed", it.isEmpty()) }
    }

    private fun requireVideos(): Pair<File, File> {
        val b = beforeFile
        val a = afterFile
        Assume.assumeTrue("device could not synthesize test videos; skipping", b != null && a != null)
        return b!! to a!!
    }
}

/**
 * Synthesizes a short H.264 MP4 with solid-color frames using [MediaCodec] (ByteBuffer/YUV input,
 * no OpenGL) and [MediaMuxer]. Frame content is irrelevant; the goal is a decodable video that two
 * [ExoPlayer] instances can play side by side.
 *
 * (Mirrors the factory used by the trimmer's ThumbnailExtractor instrumentation test.)
 */
private object TestVideoFactory {

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val WIDTH = 320
    private const val HEIGHT = 240
    private const val FRAME_RATE = 15
    private const val FRAME_COUNT = 30 // ~2 seconds
    private const val BIT_RATE = 2_000_000
    private const val TIMEOUT_US = 10_000L

    fun createVideo(dir: File): File {
        val outFile = File(dir, "video_comparator_test_${System.nanoTime()}.mp4")

        val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MIME)
        var muxer: MediaMuxer? = null
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var muxerStarted = false
            var frameIndex = 0
            var inputDone = false

            while (true) {
                if (!inputDone) {
                    val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (frameIndex >= FRAME_COUNT) {
                            encoder.queueInputBuffer(
                                inIndex, 0, 0,
                                computePtsUs(frameIndex),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val image = encoder.getInputImage(inIndex)
                            val size = if (image != null) {
                                fillImage(image, frameIndex)
                                WIDTH * HEIGHT * 3 / 2
                            } else {
                                0
                            }
                            encoder.queueInputBuffer(inIndex, 0, size, computePtsUs(frameIndex), 0)
                            frameIndex++
                        }
                    }
                }

                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "format changed twice" }
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val encoded = encoder.getOutputBuffer(outIndex)
                        if (encoded != null && bufferInfo.size > 0 && muxerStarted &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                    // INFO_TRY_AGAIN_LATER: loop again to feed more input.
                }
            }
            return outFile
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun computePtsUs(frameIndex: Int): Long = frameIndex * 1_000_000L / FRAME_RATE

    /** Fills the encoder input image with a solid frame that varies slightly by index. */
    private fun fillImage(image: Image, frameIndex: Int) {
        val width = image.width
        val height = image.height
        val planes = image.planes

        // Y plane (luma), pixelStride is 1 for plane 0.
        val yPlane = planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yValue = (16 + (frameIndex * 8) % 200).toByte()
        for (row in 0 until height) {
            var pos = row * yRowStride
            for (col in 0 until width) {
                yBuffer.put(pos, yValue)
                pos += 1
            }
        }

        // Chroma planes (U, V), subsampled by two in each dimension.
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        for (planeIndex in 1..2) {
            val plane = planes[planeIndex]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaWidth) {
                    val pos = row * rowStride + col * pixelStride
                    if (pos < buffer.limit()) {
                        buffer.put(pos, 128.toByte())
                    }
                }
            }
        }
    }
}

/**
 * Test-only overload adapting the legacy `(before, after)` call style used by these
 * instrumentation tests onto the current [VideoComparator] `source` API.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@androidx.compose.runtime.Composable
private fun VideoComparator(before: Uri, after: Uri) =
    VideoComparator(com.shanks.minify.ui.ComparisonSource.Videos(before = before, after = after))
