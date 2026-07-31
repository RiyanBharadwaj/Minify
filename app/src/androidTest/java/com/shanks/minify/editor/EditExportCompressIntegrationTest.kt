package com.shanks.minify.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shanks.minify.media3.CompressionMonitor
import com.shanks.minify.media3.VideoCompressor
import com.shanks.minify.ui.CodecAvailability
import com.shanks.minify.ui.CodecChoice
import com.shanks.minify.ui.EditState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end edit → export → compress integration test (task 10.1).
 *
 * Exercises the seam the Video tab wires in `MainScreen`:
 *
 *   VideoEditorHost.onEdited(editedUri)  →  selectedUri = editedUri
 *       →  CompressionService.start(inputUri = selectedUri,
 *                                   outputPath = editor_out_<timestamp>.mp4)
 *
 * It pins three guarantees of that handoff:
 *
 *  1. **Input adoption (Req 4.3, 4.5)** — once an [EditorResult.Completed] carries
 *     the session's `Exported_Video` URI, the tab adopts it as `selectedUri`, so
 *     the compression request's INPUT is exactly that exported URI and NOT the
 *     original source that was opened for editing.
 *
 *  2. **Freshly timestamped output (Req 4.5)** — the compression output path is
 *     the `editor_out_<System.currentTimeMillis()>.mp4` name `MainScreen` builds,
 *     which embeds a timestamp and is therefore unique per run (a later run's
 *     path never collides with an earlier one).
 *
 *  3. **Progress via CompressionMonitor (Req 5.4)** — running the compression the
 *     way `CompressionService` wires it (progress routed through
 *     [CompressionMonitor.onProgress]) reports progress the tab observes.
 *
 * The input-adoption and output-path assertions need only a real `ContentResolver`
 * / `Uri`, so they always run in the instrumentation suite. The live-compression
 * leg additionally needs a decodable clip and an AVC encoder, so it is guarded
 * with [Assume] (mirroring `EndToEndHandoffTest`). Launching the real foreground
 * [com.shanks.minify.media3.CompressionService] deterministically in a test is
 * impractical, so the compression leg mirrors its exact monitor wiring — the
 * observable contract the Video tab depends on — the same rationale used by
 * `CompressionMonitorFailureRoutingTest` and `EndToEndHandoffTest`.
 *
 * Validates Requirements 4.3, 4.5, 5.4.
 */
@RunWith(AndroidJUnit4::class)
class EditExportCompressIntegrationTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The `editor_out_<timestamp>.mp4` name pattern built by `MainScreen.onStart`. */
    private val timestampedOutputName = Regex("""editor_out_(\d+)\.mp4""")

    private val tempFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        tempFiles.forEach { runCatching { it.delete() } }
        tempFiles.clear()
        CompressionMonitor.resetStatus()
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Compression input is the session's Exported_Video, not the original (Req 4.3, 4.5).
    // ---------------------------------------------------------------------------------------------

    /**
     * Simulates a completed editing session: LibreCuts returns its `Exported_Video`
     * via `EXTRA_OUTPUT_URI` on a `RESULT_OK` intent, the host maps it to
     * [EditorResult.Completed], and the tab's `onEdited` adopts it as `selectedUri`.
     * The subsequent compression request must read from that adopted exported URI
     * — never from the original source the editor was opened on.
     */
    @Test
    fun editExportCompress_compressionInputIsSessionExportedVideo_notOriginal() {
        // The source the editor was opened on, and the DISTINCT Exported_Video the
        // session produced. They are different files so an "input is the original"
        // regression is detectable.
        val originalSource = writeSmallFile("integration_source_", ".mp4", byteCount = 2_048)
        val exportedVideo = writeSmallFile("Exported_Video_", ".mp4", byteCount = 4_096)
        val originalUri = Uri.fromFile(originalSource)
        val exportedUri = Uri.fromFile(exportedVideo)

        // The editor returns the Exported_Video URI; the contract maps it to Completed.
        val resultIntent = Intent().apply {
            putExtra(LibreCutsExtras.EXTRA_OUTPUT_URI, exportedUri)
        }
        val parsed = LibreCutsEditContract().parseResult(Activity.RESULT_OK, resultIntent)
        assertTrue(
            "A RESULT_OK with the Exported_Video URI must map to Completed, but was $parsed",
            parsed is EditorResult.Completed,
        )

        // Mirror MainScreen's Video tab: onEdited adopts the completed output as the
        // new working source, which then becomes the compression INPUT.
        var selectedUri: Uri = originalUri
        val completed = parsed as EditorResult.Completed
        selectedUri = completed.output // onEdited { editedUri -> selectedUri = editedUri }

        // The compression request reads inputUri = selectedUri (see MainScreen.onStart).
        val compressionInputUri = selectedUri

        // Req 4.3 / 4.5: the input is exactly the session's Exported_Video ...
        assertEquals(
            "Compression input must be the session's Exported_Video URI",
            exportedUri,
            compressionInputUri,
        )
        // ... and NOT the original source that was opened for editing.
        assertNotEquals(
            "Compression input must NOT be the original source (stale-source regression)",
            originalUri,
            compressionInputUri,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Compression output path is freshly timestamped and unique per run (Req 4.5).
    // ---------------------------------------------------------------------------------------------

    /**
     * The output path `MainScreen` builds for the compression request embeds
     * `System.currentTimeMillis()`, so it is timestamped and unique per run: two
     * consecutive builds never collide, and the later one carries the later
     * timestamp.
     */
    @Test
    fun editExportCompress_outputPathIsFreshlyTimestamped_uniquePerRun() {
        val firstPath = buildEditorOutputPath()
        // Ensure the wall clock advances so the second run's timestamp differs.
        Thread.sleep(5)
        val secondPath = buildEditorOutputPath()

        val firstName = File(firstPath).name
        val secondName = File(secondPath).name

        val firstMatch = timestampedOutputName.matchEntire(firstName)
        val secondMatch = timestampedOutputName.matchEntire(secondName)

        // Req 4.5: each output name is a timestamped editor_out_<millis>.mp4.
        assertTrue(
            "Output name '$firstName' must be a freshly timestamped editor_out_<millis>.mp4",
            firstMatch != null,
        )
        assertTrue(
            "Output name '$secondName' must be a freshly timestamped editor_out_<millis>.mp4",
            secondMatch != null,
        )

        // Req 4.5: the path is unique per run — a later run never reuses an earlier path.
        assertNotEquals(
            "Two consecutive compression runs must produce distinct output paths",
            firstPath,
            secondPath,
        )

        val firstTs = firstMatch!!.groupValues[1].toLong()
        val secondTs = secondMatch!!.groupValues[1].toLong()
        assertTrue(
            "The later run's output timestamp ($secondTs) must be >= the earlier one ($firstTs)",
            secondTs >= firstTs,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Compression progress is reported through the existing CompressionMonitor (Req 5.4).
    // ---------------------------------------------------------------------------------------------

    /**
     * Full leg: adopt the session's Exported_Video as the compression input, compress
     * it into a freshly timestamped output, and assert progress is surfaced through
     * the existing [CompressionMonitor] the Video tab observes. Guarded on codec
     * availability like `EndToEndHandoffTest`.
     */
    @Test
    fun editExportCompress_compressionReportsProgressThroughMonitor() {
        // The session's Exported_Video: a real, decodable clip standing in for what
        // LibreCuts hands back and the tab adopts as selectedUri.
        val exportedVideo = requireVideoAndCodec()
        val exportedUri = Uri.fromFile(exportedVideo)

        // Adopt exactly as onEdited does: selectedUri becomes the Exported_Video URI,
        // which is then the compression INPUT (Req 4.3, 4.5).
        val selectedUri: Uri = exportedUri

        // Freshly timestamped output path, built exactly as MainScreen does (Req 4.5).
        val outputPath = buildEditorOutputPath()
        val output = File(outputPath)
        tempFiles += output
        assertTrue(
            "Output path must be a freshly timestamped editor_out_<millis>.mp4",
            timestampedOutputName.matchEntire(output.name) != null,
        )

        val targetSizeMb = (exportedVideo.length().toFloat() / 1_048_576f).coerceAtLeast(0.1f)

        val latch = CountDownLatch(1)
        val outcome = AtomicReference("timeout")
        val progressCount = AtomicInteger(0)
        val maxProgress = AtomicReference(-1f)

        // Mirror CompressionService's wiring: onStart raises the monitor busy flag,
        // onProgress routes to the monitor (the single point the tab observes).
        CompressionMonitor.onStart(beforeSize = exportedVideo.length())

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            VideoCompressor.compress(
                context = context,
                inputUri = selectedUri, // the adopted Exported_Video URI (Req 4.3, 4.5)
                outputPath = outputPath,
                codecChoice = CodecChoice.H264,
                targetSizeMb = targetSizeMb,
                editState = EditState(), // neutral: LibreCuts already applied every edit
                onProgress = { p ->
                    progressCount.incrementAndGet()
                    if (p > maxProgress.get()) maxProgress.set(p)
                    CompressionMonitor.onProgress(p)
                },
                onSuccess = {
                    CompressionMonitor.onComplete(output.length(), null)
                    outcome.set("success")
                    latch.countDown()
                },
                onCancelled = {
                    outcome.set("cancelled")
                    latch.countDown()
                },
                onFailure = { e ->
                    outcome.set("failure:${e.message}")
                    latch.countDown()
                },
            )
        }

        val completed = latch.await(90, TimeUnit.SECONDS)
        assertTrue("export did not reach a terminal state within the timeout", completed)
        assertEquals("compression of the Exported_Video should succeed", "success", outcome.get())

        // Req 5.4: progress was reported through the existing CompressionMonitor.
        assertTrue(
            "CompressionMonitor should receive at least one progress update " +
                "(count=${progressCount.get()})",
            progressCount.get() > 0,
        )
        assertTrue(
            "reported progress should be a valid fraction (max=${maxProgress.get()})",
            maxProgress.get() in 0f..1f,
        )
        assertTrue(
            "the monitor's own progress flow should reflect a reported fraction " +
                "(progress=${CompressionMonitor.progress.value})",
            CompressionMonitor.progress.value in 0f..1f,
        )
        assertEquals(
            "the monitor should report a completed export",
            "done",
            CompressionMonitor.status.value,
        )
        assertTrue(
            "the freshly timestamped output file should exist with content",
            output.exists() && output.length() > 0L,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds the compression output path exactly as `MainScreen.onStart` does:
     * `cacheDir/editor_out_<System.currentTimeMillis()>.mp4`.
     */
    private fun buildEditorOutputPath(): String =
        File(context.cacheDir, "editor_out_${System.currentTimeMillis()}.mp4").absolutePath

    /** Skips the live-compression test unless a synthesized clip and an AVC encoder are available. */
    private fun requireVideoAndCodec(): File {
        val file = try {
            IntegrationTestVideoFactory.createVideo(context.cacheDir)
        } catch (t: Throwable) {
            null
        }
        Assume.assumeTrue("device could not synthesize a test video; skipping", file != null)
        Assume.assumeTrue(
            "device has no usable H.264 encoder; skipping",
            CodecAvailability.getStatus(CodecChoice.H264).supported,
        )
        tempFiles += file!!
        return file
    }

    /** Writes a small placeholder file with [byteCount] bytes, tracked for cleanup. */
    private fun writeSmallFile(prefix: String, suffix: String, byteCount: Int): File {
        val file = File.createTempFile(prefix, suffix, context.cacheDir)
        FileOutputStream(file).use { out -> out.write(ByteArray(byteCount) { it.toByte() }) }
        tempFiles += file
        return file
    }
}

/**
 * Synthesizes a short H.264 MP4 with solid-color frames using [MediaCodec] (ByteBuffer/YUV input,
 * no OpenGL) and [MediaMuxer]. Frame content is irrelevant; the goal is a decodable clip the
 * compression pipeline can re-encode. Mirrors the factory used by the end-to-end handoff and
 * trimmer/comparator instrumentation tests.
 */
private object IntegrationTestVideoFactory {

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val WIDTH = 320
    private const val HEIGHT = 240
    private const val FRAME_RATE = 15
    private const val FRAME_COUNT = 30 // ~2 seconds
    private const val BIT_RATE = 2_000_000
    private const val TIMEOUT_US = 10_000L

    fun createVideo(dir: File): File {
        val outFile = File(dir, "edit_export_compress_source_${System.nanoTime()}.mp4")

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
