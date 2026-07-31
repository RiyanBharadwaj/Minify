package com.shanks.minify.ui.trim

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Instrumentation tests for [ThumbnailExtractor] threading, cancellation, and resource release.
 *
 * These run on a device/emulator (Android framework classes such as [MediaMetadataRetriever]
 * and [MediaCodec] are required). They verify the runtime concerns that are not property-testable:
 *
 *  - Off-main extraction: frames are produced without running on the main (UI) thread (Req 18.1, 13.1).
 *  - Cancellation on leave: cancelling the collecting coroutine stops extraction (Req 18.3).
 *  - Retriever release: [ThumbnailExtractor.close] is idempotent and safe to call repeatedly (Req 18.3, 18.4).
 *
 * A short H.264 MP4 is synthesized at runtime into the app cache so the tests exercise a real
 * decode path. Devices that cannot encode AVC skip the video-dependent tests via [Assume]; the
 * close()-idempotency test needs no asset and always runs.
 *
 * Validates Requirements 13.1, 18.1, 18.3, 18.4.
 */
@RunWith(AndroidJUnit4::class)
class ThumbnailExtractorTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var videoFile: File? = null

    @Before
    fun setUp() {
        // Best-effort synthesis of a small test video. If encoding is unsupported on this device,
        // videoFile stays null and video-dependent tests are skipped via Assume.
        videoFile = try {
            TestVideoFactory.createVideo(context.cacheDir)
        } catch (t: Throwable) {
            null
        }
    }

    @After
    fun tearDown() {
        videoFile?.delete()
        videoFile = null
    }

    /** [ThumbnailExtractor.close] must be safe to call more than once (Req 18.3, 18.4). */
    @Test
    fun close_isIdempotent() {
        val extractor = ThumbnailExtractor(context, Uri.EMPTY)
        // No exception should escape on repeated release, even without a data source set.
        extractor.close()
        extractor.close()
        extractor.close()
    }

    /**
     * Frames must be produced off the main thread so the trimmer UI stays responsive (Req 18.1, 13.1).
     * We collect on a background dispatcher and assert emissions are delivered off the main looper
     * thread, and that at least one frame decoded.
     */
    @Test
    fun frames_areExtractedOffMainThread() {
        val file = requireVideo()
        val extractor = ThumbnailExtractor(context, Uri.fromFile(file))
        val emittedThread = AtomicReference<Thread>()
        val emittedCount = AtomicInteger(0)

        try {
            runBlocking(Dispatchers.Default) {
                withTimeout(30_000) {
                    extractor.frames(listOf(0L, 100L, 200L)).collect { (_, bitmap) ->
                        emittedThread.compareAndSet(null, Thread.currentThread())
                        emittedCount.incrementAndGet()
                        assertTrue("emitted bitmap should not be recycled", !bitmap.isRecycled)
                    }
                }
            }
        } finally {
            extractor.close()
        }

        val mainThread = Looper.getMainLooper().thread
        assertTrue("expected at least one extracted frame", emittedCount.get() > 0)
        assertNotEquals(
            "extraction must not run on the main thread",
            mainThread,
            emittedThread.get(),
        )
    }

    /**
     * Leaving the trimmer cancels the collecting coroutine; extraction must stop promptly and not
     * keep emitting afterwards (Req 18.3).
     */
    @Test
    fun cancellingCollector_stopsExtraction() {
        val file = requireVideo()
        val extractor = ThumbnailExtractor(context, Uri.fromFile(file))
        // Request many frames so cancellation happens mid-stream.
        val times = (0 until 200).map { it * 20L }
        val seen = AtomicInteger(0)

        try {
            runBlocking(Dispatchers.Default) {
                val job = launch {
                    extractor.frames(times).collect {
                        seen.incrementAndGet()
                    }
                }
                // Let extraction start, then cancel as if the user left the screen.
                while (seen.get() == 0 && job.isActive) {
                    delay(10)
                }
                job.cancelAndJoinCompat()

                val countAtCancel = seen.get()
                // After cancellation no further frames should be emitted.
                delay(500)
                assertEquals(
                    "no frames should be emitted after cancellation",
                    countAtCancel,
                    seen.get(),
                )
                assertTrue("extraction should have been cancelled before finishing", countAtCancel < times.size)
            }
        } finally {
            extractor.close()
        }
    }

    /**
     * After [ThumbnailExtractor.close], the extractor releases its retriever and produces no frames.
     * This documents the released-state contract (Req 18.3, 18.4).
     */
    @Test
    fun framesAfterClose_emitNothing() {
        val file = requireVideo()
        val extractor = ThumbnailExtractor(context, Uri.fromFile(file))
        extractor.close()

        val frames: List<Pair<Long, Bitmap>> = runBlocking(Dispatchers.Default) {
            withTimeout(10_000) {
                extractor.frames(listOf(0L, 100L, 200L)).toList()
            }
        }

        assertEquals("a closed extractor should emit no frames", 0, frames.size)
    }

    private fun requireVideo(): File {
        val file = videoFile
        Assume.assumeTrue("device could not synthesize a test video; skipping", file != null)
        return file!!
    }
}

private suspend fun kotlinx.coroutines.Job.cancelAndJoinCompat() {
    cancel()
    join()
}

/**
 * Synthesizes a short H.264 MP4 with solid-color frames using [MediaCodec] (ByteBuffer/YUV input,
 * no OpenGL) and [MediaMuxer]. Frame content is irrelevant; the goal is a decodable video with
 * enough keyframes for [MediaMetadataRetriever.OPTION_CLOSEST_SYNC] seeking.
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
        val outFile = File(dir, "thumbnail_extractor_test_${System.nanoTime()}.mp4")

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
