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
import org.junit.Assert.assertFalse
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Bug-condition **exploration** test for the before/after compare overlay
 * (Property 2 in the design: "Videos loop in perfect synchronization").
 *
 * These assertions encode the **expected (fixed)** behavior for the `C_loop`
 * sub-condition:
 *  - when a compared video plays to the end, both players SHALL loop (via
 *    `repeatMode = Player.REPEAT_MODE_ALL`) rather than pause/freeze on the last
 *    frame, so synchronized playback continues indefinitely.
 *
 * The [VideoComparator] owns its two [androidx.media3.exoplayer.ExoPlayer]
 * instances internally, so the "no repeat mode is set" defect is observed through
 * its only user-visible surface: the shared Play/Pause control. On the **unfixed**
 * code each player's listener calls `pause()` on `STATE_ENDED` and never sets a
 * repeat mode, so once the short clip reaches its end the control flips back to
 * "▶ Play" (both players frozen). The fix sets `repeatMode = REPEAT_MODE_ALL` and
 * removes the `STATE_ENDED → pause()` handling, so the control stays on "⏸ Pause"
 * because playback loops.
 *
 * Because the current comparator pauses at the end instead of looping, this test
 * is **expected to FAIL on the unfixed code** — the failure (the control returning
 * to "▶ Play" after the clip ends) is the counterexample that confirms `C_loop`.
 * It will pass once the fix from task 7 lands (task 7.6 re-runs this exact test).
 *
 * Follows the existing [VideoComparatorTest] pattern (`createAndroidComposeRule`,
 * `Assume`-guarded on encode support for the synthesized clips).
 *
 * Validates Requirement 1.3 (the reported defect) and, once fixed, Requirement 2.3.
 */
@RunWith(AndroidJUnit4::class)
class VideoLoopExplorationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var beforeVideo: File? = null
    private var afterVideo: File? = null

    @After
    fun tearDown() {
        beforeVideo?.delete()
        afterVideo?.delete()
        beforeVideo = null
        afterVideo = null
    }

    /**
     * Play a compared video to its end and assert playback keeps going (loops).
     *
     * On the unfixed code, both players `pause()` on `STATE_ENDED` and no repeat
     * mode is set, so once the ~2s clip finishes the shared control flips back to
     * "▶ Play" — this test detects that flip and FAILS, yielding the `C_loop`
     * counterexample ("both players paused at STATE_ENDED; no repeat mode set").
     */
    @Test
    fun compareVideo_atEnd_loopsInsteadOfPausing() {
        val (before, after) = requireVideos()

        composeRule.setContent {
            VideoComparator(
                before = Uri.fromFile(before),
                after = Uri.fromFile(after),
            )
        }

        // Start playback: the control must flip from "▶ Play" to "⏸ Pause".
        composeRule.onNodeWithText("▶", substring = true).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("⏸", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // The synthesized clip is ~2s long. Watch a window comfortably longer than
        // that: on the fixed (looping) code the control stays on "⏸ Pause"; on the
        // unfixed code it flips back to "▶ Play" the moment the clip ends.
        val windowMs = 7_000L
        val deadline = System.currentTimeMillis() + windowMs
        var pausedAfterReachingEnd = false
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            val flippedToPlay = composeRule
                .onAllNodesWithText("▶", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (flippedToPlay) {
                pausedAfterReachingEnd = true
                break
            }
            Thread.sleep(150)
        }

        assertFalse(
            "C_loop: the compared video paused/froze at its end instead of looping — the " +
                "shared control returned to '▶ Play' (both players call pause() on STATE_ENDED " +
                "and neither sets repeatMode = REPEAT_MODE_ALL)",
            pausedAfterReachingEnd,
        )
        // And the control must still show it is playing (looping) after the clip length.
        composeRule.onNodeWithText("⏸", substring = true).assertIsDisplayed()
    }

    private fun requireVideos(): Pair<File, File> {
        if (beforeVideo == null || afterVideo == null) {
            try {
                beforeVideo = TestLoopClipFactory.createVideo(context.cacheDir)
                afterVideo = TestLoopClipFactory.createVideo(context.cacheDir)
            } catch (t: Throwable) {
                beforeVideo = null
                afterVideo = null
            }
        }
        val b = beforeVideo
        val a = afterVideo
        Assume.assumeTrue("device could not synthesize test videos; skipping", b != null && a != null)
        return b!! to a!!
    }
}

/**
 * Synthesizes a short H.264 MP4 with solid-color frames using [MediaCodec] and [MediaMuxer]
 * (mirrors the factory in [VideoComparatorTest]; duplicated because that one is file-private).
 */
private object TestLoopClipFactory {

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val WIDTH = 320
    private const val HEIGHT = 240
    private const val FRAME_RATE = 15
    private const val FRAME_COUNT = 30 // ~2 seconds
    private const val BIT_RATE = 2_000_000
    private const val TIMEOUT_US = 10_000L

    fun createVideo(dir: File): File {
        val outFile = File(dir, "compare_loop_clip_${System.nanoTime()}.mp4")

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

    private fun fillImage(image: Image, frameIndex: Int) {
        val width = image.width
        val height = image.height
        val planes = image.planes

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
