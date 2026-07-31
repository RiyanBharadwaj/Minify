package com.shanks.minify.ui.compare

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shanks.minify.ui.ComparisonScreen
import com.shanks.minify.ui.ComparisonSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Preservation instrumentation tests for Property 6 of the before/after comparator.
 *
 * Feature: editor-compare-slider-fixes, Property 6 (Preservation) — Dismiss, audio routing,
 * lifecycle release, and shared viewport. This file covers the two on-device behaviors that require
 * a live Composable/[androidx.media3.exoplayer.ExoPlayer]:
 *
 *  - **Dismiss (Req 3.3):** the [ComparisonScreen] close button and system back both return to the
 *    editor via [ComparisonScreen]'s `onClose` callback, and the screen never mutates the edit
 *    session/state it was handed (the same immutable [ComparisonSource] is preserved).
 *  - **Lifecycle release (Req 3.5):** when the screen moves to the background (`ON_STOP`) the
 *    [VideoComparator] pauses both players (observable as the Play/Pause control flipping back to
 *    Play), and when the comparator leaves composition its `onDispose` releases both players (a
 *    clean disposal with no leak/crash).
 *
 * These tests follow the observation-first methodology: they encode behavior observed on the UNFIXED
 * code so it is protected against regression. They MUST PASS on the unfixed code and must continue
 * to pass after the comparator fix.
 *
 * Devices that cannot encode AVC skip the video-dependent lifecycle tests via [Assume]; the dismiss
 * tests use still-image sources and always run.
 *
 * **Validates: Requirements 3.3, 3.5**
 */
@RunWith(AndroidJUnit4::class)
class ComparatorDismissAndLifecyclePreservationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var beforeFile: File? = null
    private var afterFile: File? = null

    @Before
    fun setUp() {
        try {
            beforeFile = LifecycleTestVideoFactory.createVideo(context.cacheDir)
            afterFile = LifecycleTestVideoFactory.createVideo(context.cacheDir)
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
    // Dismiss (Req 3.3) — close button / system back return to the editor, edit state unchanged.
    // ---------------------------------------------------------------------------------------------

    /**
     * Pressing the close affordance ("✕") dismisses the overlay via `onClose`, returning to the
     * editor with the edit session/state (the immutable [ComparisonSource]) unchanged.
     */
    @Test
    fun closeButton_returnsToEditor_withStateUnchanged() {
        var closeCount = 0
        val before = Uri.parse("content://com.shanks.minify.test/original")
        val after = Uri.parse("content://com.shanks.minify.test/edited")
        val source = ComparisonSource.Images(before = before, after = after)

        composeRule.setContent {
            ComparisonScreen(source = source, onClose = { closeCount++ })
        }

        composeRule.onNodeWithText("✕").performClick()
        composeRule.waitForIdle()

        assertEquals("close button must return to the editor exactly once", 1, closeCount)
        // The screen must not mutate the edit session/state it was handed.
        assertEquals(before, source.before)
        assertEquals(after, source.after)
    }

    /**
     * System back dismisses the overlay via the [ComparisonScreen] `BackHandler`, returning to the
     * editor with the edit session/state unchanged.
     */
    @Test
    fun systemBack_returnsToEditor_withStateUnchanged() {
        var closeCount = 0
        val before = Uri.parse("content://com.shanks.minify.test/original")
        val after = Uri.parse("content://com.shanks.minify.test/edited")
        val source = ComparisonSource.Images(before = before, after = after)

        composeRule.setContent {
            ComparisonScreen(source = source, onClose = { closeCount++ })
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        assertEquals("system back must return to the editor exactly once", 1, closeCount)
        assertEquals(before, source.before)
        assertEquals(after, source.after)
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle (Req 3.5) — ON_STOP pauses both players; onDispose releases both.
    // ---------------------------------------------------------------------------------------------

    /**
     * Moving the screen to the background (`ON_STOP`) pauses both players. Observable proxy: after
     * pressing Play (control shows Pause), driving `ON_STOP` flips the control back to Play because
     * the lifecycle observer pauses both players and clears the playing state.
     */
    @Test
    fun onStop_pausesBothPlayers() {
        val (before, after) = requireVideos()

        val lifecycleOwner = TestLifecycleOwner()
        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                VideoComparator(before = Uri.fromFile(before), after = Uri.fromFile(after))
            }
        }

        // Start playback → control shows Pause (⏸).
        composeRule.onNodeWithText("▶", substring = true).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("⏸", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Background the screen → both players pause → control flips back to Play (▶).
        composeRule.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("▶", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(
            "ON_STOP must pause both players so the control shows Play (▶) again",
            composeRule.onAllNodesWithText("▶", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /**
     * When the comparator leaves composition, its `onDispose` releases both players. Observable
     * proxy: toggling the comparator out of the composition removes its controls with no crash,
     * confirming the disposal/release path runs cleanly (no leaked players).
     */
    @Test
    fun onDispose_releasesBothPlayers_cleanly() {
        val (before, after) = requireVideos()

        var show by mutableStateOf(true)
        composeRule.setContent {
            if (show) {
                VideoComparator(before = Uri.fromFile(before), after = Uri.fromFile(after))
            }
        }

        // Comparator present: its control row (Replay ↺) is displayed.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("↺", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Leave composition → onDispose releases both players.
        composeRule.runOnUiThread { show = false }
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("↺", substring = true).fetchSemanticsNodes().isEmpty()
        }
        // Clean disposal: the comparator controls are gone and no player leaked/crashed.
        assertTrue(
            "comparator must be fully removed from composition on dispose",
            composeRule.onAllNodesWithText("↺", substring = true).fetchSemanticsNodes().isEmpty(),
        )
        assertFalse(
            "no Play control should remain after disposal",
            composeRule.onAllNodesWithText("▶", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private fun requireVideos(): Pair<File, File> {
        val b = beforeFile
        val a = afterFile
        Assume.assumeTrue("device could not synthesize test videos; skipping", b != null && a != null)
        return b!! to a!!
    }
}

/** Minimal [LifecycleOwner] backed by a [LifecycleRegistry] the test drives directly. */
private class TestLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
}

/**
 * Synthesizes a short H.264 MP4 with solid-color frames using [MediaCodec] (ByteBuffer/YUV input,
 * no OpenGL) and [MediaMuxer]. Mirrors the factory used by [VideoComparatorTest]; frame content is
 * irrelevant, the goal is a decodable clip two ExoPlayers can play.
 */
private object LifecycleTestVideoFactory {

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val WIDTH = 320
    private const val HEIGHT = 240
    private const val FRAME_RATE = 15
    private const val FRAME_COUNT = 30 // ~2 seconds
    private const val BIT_RATE = 2_000_000
    private const val TIMEOUT_US = 10_000L

    fun createVideo(dir: File): File {
        val outFile = File(dir, "comparator_lifecycle_test_${System.nanoTime()}.mp4")

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
    VideoComparator(ComparisonSource.Videos(before = before, after = after))
