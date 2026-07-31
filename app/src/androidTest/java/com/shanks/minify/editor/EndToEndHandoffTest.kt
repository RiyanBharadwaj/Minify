package com.shanks.minify.editor

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import com.shanks.minify.photo.PhotoCompressionMonitor
import com.shanks.minify.photo.PhotoCompressor
import com.shanks.minify.photo.PhotoResult
import com.shanks.minify.ui.CodecAvailability
import com.shanks.minify.ui.CodecChoice
import com.shanks.minify.ui.EditState
import com.shanks.minify.utils.SaveKind
import com.shanks.minify.utils.saveToGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end handoff instrumentation tests (task 10.3).
 *
 * These exercise the two `Editor_Host` → compression-pipeline seams end-to-end,
 * on the real Android framework (a real `ContentResolver`, `MediaStore`, and
 * Media3 `Transformer` are required, so this lives in the instrumentation
 * suite):
 *
 *  - **Photo** — the `Edited_Output` a [com.shanks.minify.editor.PhotoEditorHost]
 *    Done produces (a full-resolution upright image temp file written by the
 *    geometry `applyEditsAndPersist` path) is handed to
 *    [PhotoCompressor.compress]. The pipeline must produce a gallery output whose
 *    size is at or under the requested target (Req 1.4, 3.4, 3.6, 4.1, 4.2, 4.3).
 *
 *  - **Video** — the `Edited_Output` LibreCuts returns via
 *    [LibreCutsEditContract] (an `EXTRA_OUTPUT_URI` on a `RESULT_OK` intent) must
 *    be app-readable (Req 9.5), and handing that URI to the video compression
 *    pipeline must report progress and save the compressed result to the gallery
 *    (Req 8.1, 8.2, 8.3, 8.4).
 *
 * The video compression leg mirrors exactly how
 * [com.shanks.minify.media3.CompressionService] wires the pipeline — progress is
 * routed through [CompressionMonitor.onProgress] and success saves to the gallery
 * then routes [CompressionMonitor.onComplete] — because deterministically
 * launching a real foreground service in an instrumentation test is impractical
 * (the same rationale used by `CompressionMonitorFailureRoutingTest`). The
 * observable contract the Video tab depends on (monitor progress + a saved
 * "after" output) is what is asserted here.
 *
 * Device/codec-dependent portions are guarded with [Assume]; the photo leg and
 * the URI-readability leg always run.
 *
 * Validates Requirements 1.4, 3.4, 3.6, 4.1, 4.2, 4.3, 8.1, 8.2, 8.3, 8.4, 9.5.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndHandoffTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val tempFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        tempFiles.forEach { runCatching { it.delete() } }
        tempFiles.clear()
        PhotoCompressionMonitor.resetStatus()
        CompressionMonitor.resetStatus()
    }

    // ---------------------------------------------------------------------------------------------
    // Photo: Edited_Output -> PhotoCompressor -> gallery output at target size (Req 4.1-4.3, 1.4).
    // ---------------------------------------------------------------------------------------------

    /**
     * Mirrors the photo Done handoff: the host writes the full-resolution edited
     * image to a temp `file://` (as `applyEditsAndPersist` does) and passes it to
     * [PhotoCompressor.compress] with the tab's target size. The pipeline must
     * succeed, produce a gallery output, and keep that output at or under the
     * requested target byte budget.
     */
    @Test
    fun photoEdit_handoffToPhotoCompressor_producesGalleryOutputAtTargetSize() {
        // A full-resolution "Edited_Output": a noisy image large enough that JPEG
        // encoding at full quality exceeds the target, so compression genuinely
        // engages (rather than trivially fitting).
        val editedOutput = writeNoisyJpeg(width = 1600, height = 1200)
        val editedUri = Uri.fromFile(editedOutput)

        val targetSizeMb = 0.5f
        val targetBytes = (targetSizeMb.toDouble() * 1_048_576.0).toLong()

        val result = runBlocking { PhotoCompressor.compress(context, editedUri, targetSizeMb) }

        // Req 4.1 / 4.3 / 1.4: the edited output reaches the pipeline and a gallery
        // output is produced (the returned file is the pipeline's compressed result;
        // the gallery holds its own saved copy).
        assertTrue(
            "Photo handoff should succeed and produce an output, but was $result",
            result is PhotoResult.Success,
        )
        val success = result as PhotoResult.Success
        tempFiles += success.outputFile
        assertTrue(
            "The pipeline output file should exist with content",
            success.outputFile.exists() && success.outputFile.length() > 0L,
        )

        // Req 4.2: the output honors the requested target size (never exceeds it).
        assertTrue(
            "Compressed output (${success.compressedBytes} B) must be at or under the " +
                "target ($targetBytes B)",
            success.compressedBytes <= targetBytes,
        )
        assertEquals(
            "compressedBytes should equal the produced output file length",
            success.outputFile.length(),
            success.compressedBytes,
        )

        // The photo monitor the tab observes reflects a completed compression.
        assertEquals("done", PhotoCompressionMonitor.status.value)
        assertTrue(
            "After a save the monitor should record the compressed size",
            PhotoCompressionMonitor.afterSizeBytes.value > 0L,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Video: returned URI app-readable (Req 9.5) + compression reports progress & saves (Req 8.1-8.4).
    // ---------------------------------------------------------------------------------------------

    /**
     * The URI LibreCuts returns (parsed by [LibreCutsEditContract] into
     * [EditorResult.Completed]) must point at an app-accessible location the app
     * can read back (Req 9.5).
     */
    @Test
    fun videoEdit_returnedOutputUri_isAppReadable() {
        // Simulate a completed export: LibreCuts writes an app-accessible output
        // file and returns its URI via EXTRA_OUTPUT_URI on a RESULT_OK intent.
        val exported = writeSmallFile("librecuts_export_", ".mp4", byteCount = 4_096)
        val outputUri = Uri.fromFile(exported)

        val resultIntent = Intent().apply {
            putExtra(LibreCutsExtras.EXTRA_OUTPUT_URI, outputUri)
        }
        val parsed = LibreCutsEditContract().parseResult(android.app.Activity.RESULT_OK, resultIntent)

        assertTrue(
            "A RESULT_OK with an output URI must map to Completed, but was $parsed",
            parsed is EditorResult.Completed,
        )
        val completedUri = (parsed as EditorResult.Completed).output

        // Req 9.5: the returned output URI is readable by the app.
        val bytesRead = context.contentResolver.openInputStream(completedUri)?.use { it.readBytes().size } ?: -1
        assertTrue(
            "The returned output URI must be app-readable with content, read=$bytesRead bytes",
            bytesRead > 0,
        )
    }

    /**
     * Handing the returned edited URI to the video compression pipeline (as
     * `VideoEditorHost` does for a neutral [EditState]) must report progress and
     * save the compressed result to the gallery — the observable contract the
     * Video tab depends on (Req 8.1, 8.2, 8.3, 8.4).
     */
    @Test
    fun videoEdit_handoffToCompression_reportsProgressAndSaves() {
        val source = requireVideoAndCodec()
        val editedUri = Uri.fromFile(source)

        // Target is positive and <= source size in MB (the compressor rejects a
        // target larger than the source), mirroring the retained export path.
        val targetSizeMb = (source.length().toFloat() / 1_048_576f).coerceAtLeast(0.1f)

        val output = File(context.cacheDir, "e2e_video_out_${System.nanoTime()}.mp4")
        tempFiles += output

        val latch = CountDownLatch(1)
        val outcome = AtomicReference("timeout")
        val progressCount = AtomicInteger(0)
        val maxProgress = AtomicReference(-1f)

        // Mirror CompressionService's wiring: onStart raises the monitor busy flag,
        // onProgress routes to the monitor, onSuccess saves to the gallery and
        // completes the monitor. This is the exact contract the Video tab observes
        // (Req 8.1, 8.4); launching the real foreground service deterministically
        // in a test is impractical.
        CompressionMonitor.onStart(beforeSize = source.length())

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            VideoCompressor.compress(
                context = context,
                inputUri = editedUri,
                outputPath = output.absolutePath,
                codecChoice = CodecChoice.H264,
                targetSizeMb = targetSizeMb,
                editState = EditState(), // neutral: LibreCuts already applied every edit
                onProgress = { p ->
                    progressCount.incrementAndGet()
                    if (p > (maxProgress.get())) maxProgress.set(p)
                    CompressionMonitor.onProgress(p)
                },
                onSuccess = {
                    // Save exactly as CompressionService.onSuccess does.
                    val savedUri = runCatching {
                        runBlocking {
                            withContext(Dispatchers.IO) { saveToGallery(context, output, SaveKind.VIDEO) }
                        }
                    }.getOrNull()
                    CompressionMonitor.onComplete(output.length(), savedUri)
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
        assertEquals("compression handoff should complete successfully", "success", outcome.get())

        // Req 8.4: progress was reported through the pipeline and surfaced to the
        // monitor the tab observes.
        assertTrue(
            "the pipeline should report at least one progress update (count=${progressCount.get()})",
            progressCount.get() > 0,
        )
        assertTrue(
            "reported progress should be a valid fraction (max=${maxProgress.get()})",
            maxProgress.get() in 0f..1f,
        )

        // Req 8.1 / 8.3: the compressed result was saved and the monitor reflects
        // a completed export.
        assertTrue("output file should exist with content", output.exists() && output.length() > 0L)
        assertEquals("monitor status should report done", "done", CompressionMonitor.status.value)
        assertTrue(
            "monitor should record the saved after-size",
            CompressionMonitor.afterSizeBytes.value > 0L,
        )
        assertNotNull(
            "a saved gallery URI should be published for the before/after comparison",
            CompressionMonitor.afterUri.value,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Skips a video export test unless a synthesized clip and an AVC encoder are both available. */
    private fun requireVideoAndCodec(): File {
        val file = try {
            TestVideoFactory.createVideo(context.cacheDir)
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

    /** Writes a noisy full-resolution JPEG to a temp `file://`, tracked for cleanup. */
    private fun writeNoisyJpeg(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val random = Random(42)
        for (i in pixels.indices) {
            pixels[i] = 0xFF000000.toInt() or (random.nextInt() and 0x00FFFFFF)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val file = File.createTempFile("minify_edit_", ".jpg", context.cacheDir)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        }
        bitmap.recycle()
        tempFiles += file
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
 * compression pipeline can re-encode. Mirrors the factory used by the trimmer/comparator/
 * reverse-freeze instrumentation tests.
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
        val outFile = File(dir, "e2e_handoff_source_${System.nanoTime()}.mp4")

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
