package com.shanks.minify.ui.compare

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Bug-condition **exploration** test for the before/after compare overlay
 * (Property 1 in the design: "Edited layer on top, cropped in real time to reveal
 * the original").
 *
 * These assertions encode the **expected (fixed)** behavior:
 *  - the full-region **bottom** layer is the **original ("before")** media and the
 *    clipped **top** layer is the **edited ("after")** media, so moving the divider
 *    crops only the edited layer and reveals the original beneath (C_layer);
 *  - the overlay reads as a single aligned overlay with **no** "Original"/"Compressed"
 *    side captions (C_panel);
 *  - image and video modes share the same caption-free overlay strategy (C_parity).
 *
 * Because the current comparators invert the layers and frame the video overlay as
 * two captioned panels, this test is **expected to FAIL on the unfixed code** — the
 * failure is the counterexample that confirms the bug. It will pass once the fix
 * from task 7 lands (task 7.5 re-runs this exact test).
 *
 * The Compose-UI checks that need a real device follow the existing
 * [VideoComparatorTest] pattern (`createAndroidComposeRule`, `Assume`-guarded on
 * encode support for the video clips).
 *
 * Validates Requirements 1.1, 1.2, 1.6, 1.7 (the reported defects) and, once fixed,
 * Requirements 2.1, 2.2, 2.6, 2.7.
 */
@RunWith(AndroidJUnit4::class)
class EditorCompareOverlayExplorationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    // Solid-color still images: the ORIGINAL ("before") is RED, the EDITED ("after")
    // is BLUE, so which color appears on the leading (clipped) side reveals which
    // layer is cropped on top.
    private val originalColor = android.graphics.Color.RED
    private val editedColor = android.graphics.Color.BLUE

    private var beforeImage: File? = null
    private var afterImage: File? = null
    private var beforeVideo: File? = null
    private var afterVideo: File? = null

    @After
    fun tearDown() {
        listOf(beforeImage, afterImage, beforeVideo, afterVideo).forEach { it?.delete() }
        beforeImage = null
        afterImage = null
        beforeVideo = null
        afterVideo = null
    }

    // ---------------------------------------------------------------------------------------------
    // C_layer (image): the cropped TOP layer must be the EDITED image, not the ORIGINAL.
    // ---------------------------------------------------------------------------------------------

    /**
     * With the divider centered, the leading (clipped) region should show the **edited**
     * media on top while the trailing region reveals the **original** beneath. On the
     * unfixed code the layers are inverted (the edited image fills and the original is the
     * clipped top layer), so the leading region shows the ORIGINAL — this assertion FAILS
     * and yields the C_layer counterexample.
     */
    @Test
    fun imageComparator_croppedTopLayerIsEditedNotOriginal() {
        beforeImage = createSolidImage(originalColor, "compare_before_original.png")
        afterImage = createSolidImage(editedColor, "compare_after_edited.png")

        composeRule.setContent {
            ImageComparator(
                before = Uri.fromFile(beforeImage),
                after = Uri.fromFile(afterImage),
            )
        }

        // Poll the rendered pixels until the images are decoded and drawn (past the loading
        // spinner) — detected by a saturated red/blue appearing at the sample points. This
        // depends only on what is actually drawn, not on semantics.
        composeRule.waitForIdle()
        var shot: ImageBitmap? = null
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            val candidate = composeRule.onRoot().captureToImage()
            if (candidate.colorAt(0.35f, 0.5f).isSaturated() || candidate.colorAt(0.65f, 0.5f).isSaturated()) {
                shot = candidate
                break
            }
            Thread.sleep(200)
        }
        org.junit.Assert.assertNotNull("the comparator images never rendered (still on the loading spinner)", shot)

        // Sample the vertical center, on the leading (left of divider) and trailing (right)
        // sides — away from the divider line at 0.5 and away from the edges.
        val leading = shot!!.colorAt(0.35f, 0.5f)
        val trailing = shot.colorAt(0.65f, 0.5f)

        assertTrue(
            "C_layer: leading (clipped/top) region must show the EDITED (blue) image, " +
                "but was $leading — the original is cropped on top instead of the edited version",
            leading.isApprox(editedColor),
        )
        assertTrue(
            "C_layer: trailing region must reveal the ORIGINAL (red) image beneath, " +
                "but was $trailing — the edited version fills the background instead of the original",
            trailing.isApprox(originalColor),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // C_panel + C_parity (video): the video overlay must be a single aligned overlay with no
    // side captions, matching the caption-free image comparator.
    // ---------------------------------------------------------------------------------------------

    /**
     * The fixed overlay reads as one aligned overlay, so it must NOT present "Original"/
     * "Compressed" side captions. On the unfixed code the video comparator renders both
     * captions (framing the overlay as two separate panels), so this assertion FAILS and
     * yields the C_panel / C_parity counterexample.
     */
    @Test
    fun videoComparator_presentsSingleOverlayWithoutSidePanelCaptions() {
        val (before, after) = requireVideos()

        composeRule.setContent {
            VideoComparator(
                before = Uri.fromFile(before),
                after = Uri.fromFile(after),
            )
        }
        composeRule.waitForIdle()

        val originalCaptions =
            composeRule.onAllNodesWithText("Original", substring = false).fetchSemanticsNodes()
        val compressedCaptions =
            composeRule.onAllNodesWithText("Compressed", substring = false).fetchSemanticsNodes()

        assertTrue(
            "C_panel: video overlay must be a single aligned overlay with no 'Original' side " +
                "caption, but ${originalCaptions.size} were found (reads as two panels)",
            originalCaptions.isEmpty(),
        )
        assertTrue(
            "C_panel/C_parity: video overlay must not present a 'Compressed' side caption " +
                "(the image comparator shows none), but ${compressedCaptions.size} were found",
            compressedCaptions.isEmpty(),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Supporting property (deterministic): the crop-width relationship over dividerFraction ∈ [0,1].
    // The reveal-width math itself is correct; the defect is which LAYER the leading region holds.
    // This scoped property documents that the leading region width equals fraction × width for the
    // sampled fractions, so the C_layer failures above are about layer ORDER, not the width math.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun cropWidthRelationship_leadingRegionEqualsFractionTimesWidth() {
        val width = 1000f
        val fractions = generateSequence(0f) { it + 0.05f }.takeWhile { it <= 1f }
        for (f in fractions) {
            val regions = DividerOps.revealRegions(f, width)
            assertEquals(
                "leading (clipped) region width must equal fraction × width for f=$f",
                (f * width).toDouble(),
                regions.beforeWidth.toDouble(),
                0.001,
            )
            assertEquals(
                "leading + trailing must sum to width for f=$f",
                width.toDouble(),
                (regions.beforeWidth + regions.afterWidth).toDouble(),
                0.001,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------------

    private fun requireVideos(): Pair<File, File> {
        if (beforeVideo == null || afterVideo == null) {
            try {
                beforeVideo = TestClipFactory.createVideo(context.cacheDir)
                afterVideo = TestClipFactory.createVideo(context.cacheDir)
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

    /** Creates a solid-color, very wide (4:1) still image so a ContentScale.Fit render is width-limited. */
    private fun createSolidImage(color: Int, name: String): File {
        val bmp = Bitmap.createBitmap(1200, 300, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        val file = File(context.cacheDir, name)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    private fun ImageBitmap.colorAt(xFrac: Float, yFrac: Float): Color {
        val map = toPixelMap()
        val x = (xFrac * width).toInt().coerceIn(0, width - 1)
        val y = (yFrac * height).toInt().coerceIn(0, height - 1)
        return map[x, y]
    }

    /** Approximate color match, tolerant of PNG/decoder rounding, ignoring alpha. */
    private fun Color.isApprox(argb: Int, tol: Float = 0.25f): Boolean {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return kotlin.math.abs(red - r) <= tol &&
            kotlin.math.abs(green - g) <= tol &&
            kotlin.math.abs(blue - b) <= tol
    }

    /** True for a saturated red or blue — i.e. one of the comparator images has been drawn here. */
    private fun Color.isSaturated(): Boolean {
        val isRed = red > 0.5f && green < 0.35f && blue < 0.35f
        val isBlue = blue > 0.5f && red < 0.35f && green < 0.35f
        return isRed || isBlue
    }
}

/**
 * Synthesizes a short H.264 MP4 with solid-color frames using [MediaCodec] and [MediaMuxer]
 * (mirrors the factory in [VideoComparatorTest]; duplicated because that one is file-private).
 */
private object TestClipFactory {

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val WIDTH = 320
    private const val HEIGHT = 240
    private const val FRAME_RATE = 15
    private const val FRAME_COUNT = 30
    private const val BIT_RATE = 2_000_000
    private const val TIMEOUT_US = 10_000L

    fun createVideo(dir: File): File {
        val outFile = File(dir, "compare_explore_clip_${System.nanoTime()}.mp4")

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
 * Test-only overloads adapting the legacy `(before, after)` call style used by this
 * instrumentation test onto the current comparator `source` APIs.
 */
@androidx.compose.runtime.Composable
private fun ImageComparator(before: Uri, after: Uri) =
    ImageComparator(com.shanks.minify.ui.ComparisonSource.Images(before = before, after = after))

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@androidx.compose.runtime.Composable
private fun VideoComparator(before: Uri, after: Uri) =
    VideoComparator(com.shanks.minify.ui.ComparisonSource.Videos(before = before, after = after))
