@file:OptIn(androidx.annotation.OptIn::class)
package com.shanks.minify.media3

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.shanks.minify.utils.getVideoInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.math.sqrt

class CompressionJob(
    internal val cancelFlag: AtomicBoolean,
    private val thread: HandlerThread,
    private val handler: Handler
) {
    fun cancel() {
        cancelFlag.set(true)
        thread.quitSafely()
    }
}

object VideoCompressor {
    private const val TAG = "VideoCompressor"
    private const val AUDIO_KBPS = 128
    private const val TARGET_BPP = 0.15f
    private const val ABS_FLOOR_BPS = 50_000
    private const val ABS_CEILING_BPS = 25_000_000

    // --- Shaders -----------------------------------------------------------------

    private const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    private const val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uTexture;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """

    // --- Shader helpers ----------------------------------------------------------

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun createProgram(): Int {
        val vertex   = compileShader(GLES20.GL_VERTEX_SHADER,   VERTEX_SHADER)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program  = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e(TAG, "Program link error: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }

    // --- Public API --------------------------------------------------------------

    fun computeParams(
        targetSizeMb: Float,
        durationSecs: Long,
        srcBitrateKbps: Int,
        srcWidth: Int,
        srcHeight: Int,
        frameRate: Float,
        useH265: Boolean
    ): Pair<Int, Int> {
        val fps = frameRate.coerceIn(1f, 120f)

        val audioBudgetBits = if (durationSecs > 0) (AUDIO_KBPS * 1000L * durationSecs) else 0L
        // 1 MB = 8 × 1,048,576 bits. Reserve 12% headroom: ~4% for MP4
        // container overhead and ~8% for CBR encoder burst tolerance (Android
        // hardware encoders routinely produce 8-15% above the configured
        // bitrate even in CBR mode). Goal is to land at or under target.
        val targetBits      = (targetSizeMb * 8_388_608f * 0.88f).toLong()
        val videoBudgetBits = (targetBits - audioBudgetBits).coerceAtLeast(targetBits / 2)
        val rawBitrateBps   = if (durationSecs > 0)
            (videoBudgetBits / durationSecs).toInt()
        else
            (srcBitrateKbps * 1000 * 0.5f).roundToInt()

        // Do NOT apply a codec factor here. The budget math already produces the
        // bitrate that fills the target file size exactly. The codec choice (H.264
        // vs H.265) affects quality at that bitrate, not the number of bytes written.
        // Applying 0.5× for H.265 was halving the bitrate a second time, causing
        // the output to be ~55–60% of the requested target size.
        val videoTargetBps = rawBitrateBps.coerceIn(ABS_FLOOR_BPS, ABS_CEILING_BPS)

        val aspectRatio   = srcWidth.toFloat() / srcHeight.toFloat()
        val totalPixels   = videoTargetBps.toFloat() / (TARGET_BPP * fps)
        val idealHeight   = sqrt(totalPixels / aspectRatio)
        val clampedHeight = idealHeight.roundToInt().coerceIn(144, srcHeight)
        val outputHeight  = alignTo16(clampedHeight).coerceIn(16, srcHeight)

        Log.d(TAG, "computeParams -> bitrate=${videoTargetBps / 1000}kbps height=$outputHeight")
        return Pair(videoTargetBps, outputHeight)
    }

    fun compress(
        context: Context,
        inputUri: Uri,
        outputPath: String,
        useH265: Boolean,
        targetSizeMb: Float,
        onProgress: (Float) -> Unit,
        onSuccess: () -> Unit,
        onCancelled: () -> Unit,
        onFailure: (Exception) -> Unit
    ): CompressionJob {
        val cancelFlag  = AtomicBoolean(false)
        val workThread  = HandlerThread("CompressThread").apply { start() }
        val handler     = Handler(workThread.looper)

        handler.post {
            try {
                runCompression(
                    context, inputUri, outputPath, useH265,
                    targetSizeMb, cancelFlag, onProgress, onSuccess, onCancelled, onFailure
                )
            } catch (e: Exception) {
                if (!cancelFlag.get()) {
                    Log.e(TAG, "Compression error", e)
                    Handler(context.mainLooper).post { onFailure(e) }
                }
                workThread.quitSafely()
            }
        }

        return CompressionJob(cancelFlag, workThread, handler)
    }

    // --- Core pipeline -----------------------------------------------------------

    private fun runCompression(
        context: Context,
        inputUri: Uri,
        outputPath: String,
        useH265: Boolean,
        targetSizeMb: Float,
        cancelFlag: AtomicBoolean,
        onProgress: (Float) -> Unit,
        onSuccess: () -> Unit,
        onCancelled: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val info = getVideoInfo(context, inputUri)
        val (targetBitrate, targetHeight) = computeParams(
            targetSizeMb, info.durationSecs, info.bitrateKbps,
            info.width, info.height, info.frameRate, useH265
        )

        // ---- Track discovery ----------------------------------------------------
        val extractor = MediaExtractor().apply { setDataSource(context, inputUri, null) }

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null
        var rotation = 0

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime   = format.getString(MediaFormat.KEY_MIME)
            when {
                mime?.startsWith("video/") == true && videoTrackIndex == -1 -> {
                    videoTrackIndex = i
                    videoFormat     = format
                    rotation        = format.getInteger(MediaFormat.KEY_ROTATION, 0)
                }
                mime?.startsWith("audio/") == true && audioTrackIndex == -1 -> {
                    audioTrackIndex = i
                    audioFormat     = format
                }
            }
        }

        if (videoTrackIndex == -1) {
            extractor.release()
            throw IllegalStateException("No video track found")
        }

        // ---- Output dimensions --------------------------------------------------
        val srcWidth          = videoFormat!!.getInteger(MediaFormat.KEY_WIDTH)
        val srcHeight         = videoFormat!!.getInteger(MediaFormat.KEY_HEIGHT)
        val targetWidth       = (targetHeight.toFloat() * srcWidth / srcHeight).roundToInt()
        val finalWidth        = alignTo16(targetWidth).coerceIn(16, srcWidth)
        val finalHeight       = targetHeight.coerceIn(16, srcHeight)
        val encoderMime       = if (useH265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else         MediaFormat.MIMETYPE_VIDEO_AVC
        val frameRateInt      = info.frameRate.toInt().coerceIn(1, 120)

        if (!isEncoderSupported(encoderMime, finalWidth, finalHeight, targetBitrate, frameRateInt)) {
            if (useH265) {
                Log.w(TAG, "H.265 not supported, falling back to H.264")
                return runCompression(
                    context, inputUri, outputPath, false, targetSizeMb,
                    cancelFlag, onProgress, onSuccess, onCancelled, onFailure
                )
            } else {
                throw IllegalStateException(
                    "No encoder supports ${finalWidth}x${finalHeight} @ ${targetBitrate}bps"
                )
            }
        }

        // ---- Encoder ------------------------------------------------------------
        val encoder = MediaCodec.createEncoderByType(encoderMime)
        val encoderFormat = MediaFormat.createVideoFormat(encoderMime, finalWidth, finalHeight).apply {
            setInteger(MediaFormat.KEY_BIT_RATE,        targetBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE,      frameRateInt)
            // CBR: encoder must hit the bitrate target every second, not just
            // on average. Without this Android defaults to VBR which bursts
            // freely on complex scenes and overshoots the target file size.
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            // 4-second keyframe interval. 1s was forcing ~600 large keyframes
            // for a 10-min video, each 5–20× bigger than a P-frame, bloating
            // the output. 4s keeps seeking reasonable while cutting overhead.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 4)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface = encoder.createInputSurface()
        encoder.start()

        // ---- Diagnostic logging — visible in Logcat filtered by "VideoCompressor" ----
        Log.i(TAG, "=== Compression params ===")
        Log.i(TAG, "  Codec        : $encoderMime")
        Log.i(TAG, "  Encoder name : ${encoder.name}")
        Log.i(TAG, "  Resolution   : ${finalWidth}x${finalHeight}  (source: ${srcWidth}x${srcHeight})")
        Log.i(TAG, "  Bitrate      : ${targetBitrate / 1000} kbps")
        Log.i(TAG, "  Frame rate   : $frameRateInt fps")
        Log.i(TAG, "  Duration     : ${info.durationSecs}s")
        Log.i(TAG, "  Target size  : $targetSizeMb MB")
        Log.i(TAG, "  Bitrate mode : CBR requested")
        // Check whether the hardware encoder actually supports CBR
        run {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (ci in codecList.codecInfos) {
                if (ci.name != encoder.name || !ci.isEncoder) continue
                val ec = try { ci.getCapabilitiesForType(encoderMime).encoderCapabilities }
                catch (e: Exception) { null } ?: break
                val cbrOk = ec.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                val vbrOk = ec.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                val cq    = ec.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
                Log.i(TAG, "  CBR supported: $cbrOk  VBR: $vbrOk  CQ: $cq")
                break
            }
        }
        Log.i(TAG, "==========================")

        // ---- EGL — all GL work runs on CompressThread (this thread) -------------
        // FIX (Bug 1): eglMakeCurrent AND all subsequent GL calls stay on the same
        // thread. We no longer spawn a separate RenderThread for GL work.
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version    = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE,        8,
            EGL14.EGL_GREEN_SIZE,      8,
            EGL14.EGL_BLUE_SIZE,       8,
            EGL14.EGL_ALPHA_SIZE,      8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs    = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)

        val eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        val eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], encoderInputSurface,
            intArrayOf(EGL14.EGL_NONE), 0
        )
        // Make current on THIS thread — all GL calls below must stay here.
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        // ---- OpenGL program + geometry ------------------------------------------
        val program    = createProgram()
        GLES20.glUseProgram(program)
        val posLoc     = GLES20.glGetAttribLocation(program,  "aPosition")
        val texLoc     = GLES20.glGetAttribLocation(program,  "aTexCoord")
        val samplerLoc = GLES20.glGetUniformLocation(program, "uTexture")

        val vertices = floatArrayOf(
            -1f, -1f,  0f, 1f,
            1f, -1f,  1f, 1f,
            -1f,  1f,  0f, 0f,
            1f,  1f,  1f, 0f
        )
        val vertBuffer = ByteBuffer
            .allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(vertices).position(0) }

        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, vertBuffer)
        GLES20.glEnableVertexAttribArray(posLoc)
        vertBuffer.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, vertBuffer)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glUniform1i(samplerLoc, 0)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        val textureId = IntArray(1)
        GLES20.glGenTextures(1, textureId, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId[0])
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // FIX (Bug 5): keep references so we can release them in the cleanup block.
        val surfaceTexture       = SurfaceTexture(textureId[0])
        val decoderOutputSurface = Surface(surfaceTexture)

        // FIX (Bug 2): use a CountDownLatch instead of an AtomicBoolean + polling.
        // Each time a frame arrives the latch is counted down to 1 on a dedicated
        // callback thread, and the main loop awaits it with a timeout.
        var frameLatch = CountDownLatch(1)
        val frameCallbackThread = HandlerThread("FrameCallback").apply { start() }
        surfaceTexture.setOnFrameAvailableListener(
            { frameLatch.countDown() },
            Handler(frameCallbackThread.looper)
        )

        // ---- Decoder ------------------------------------------------------------
        val decoderMime = videoFormat!!.getString(MediaFormat.KEY_MIME)!!
        val decoder     = MediaCodec.createDecoderByType(decoderMime)
        decoder.configure(videoFormat, decoderOutputSurface, null, 0)
        decoder.start()

        // ---- Muxer (tracks added, then started after first encoded frame) -------
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // FIX (Bug 8): record rotation now so we can apply it before muxer.start()
        // regardless of which track is added first.
        if (rotation != 0) muxer.setOrientationHint(rotation)

        var videoTrackMuxerIndex = -1
        var audioTrackMuxerIndex = -1
        var muxerStarted         = false

        if (audioTrackIndex != -1 && audioFormat != null) {
            audioTrackMuxerIndex = muxer.addTrack(audioFormat!!)
        }

        // ---- Audio extractor ----------------------------------------------------
        var audioExtractor: MediaExtractor? = null
        if (audioTrackIndex != -1) {
            audioExtractor = MediaExtractor().apply {
                setDataSource(context, inputUri, null)
                selectTrack(audioTrackIndex)
            }
        }

        // Pending audio samples buffered until the muxer is started by the first
        // encoded video frame (FIX Bug 6: no audio is silently dropped).
        data class AudioSample(val data: ByteArray, val pts: Long, val flags: Int)
        val pendingAudio = ArrayDeque<AudioSample>()

        // FIX (Bug D): 1 MB covers the largest AAC super-frames seen in the wild.
        val audioBuffer  = ByteBuffer.allocate(1_048_576)
        // FIX (Bug 7): initialise audioEos to true when there is no audio track so
        // the main while-loop condition is satisfied without an audio extractor.
        var audioEos     = (audioTrackIndex == -1)
        var lastAudioPts = 0L

        val totalDurationUs  = info.durationSecs * 1_000_000L
        var lastProgress     = 0f
        var lastEncodedPts   = 0L   // tracks the most recent real encoder PTS
        val progressHandler  = Handler(context.mainLooper)

        extractor.selectTrack(videoTrackIndex)
        var decoderEos   = false
        var encoderEos   = false
        var sawInputEos  = false

        // =========================================================================
        // Main encode loop — everything runs on CompressThread
        // =========================================================================
        while (!cancelFlag.get() && (!decoderEos || !encoderEos || !audioEos)) {

            // ---- Feed decoder ---------------------------------------------------
            if (!decoderEos) {
                val inputIndex = decoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                    val sampleSize  = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        decoderEos = true
                        Log.d(TAG, "Decoder EOS queued")
                    } else {
                        decoder.queueInputBuffer(
                            inputIndex, 0, sampleSize, extractor.sampleTime, 0
                        )
                        extractor.advance()
                    }
                }
            }

            // ---- Drain decoder — render frames to SurfaceTexture ----------------
            val decoderOutInfo = MediaCodec.BufferInfo()
            var decoderOutIdx  = decoder.dequeueOutputBuffer(decoderOutInfo, 10_000)
            while (decoderOutIdx >= 0 && !cancelFlag.get()) {
                val render = decoderOutInfo.size > 0
                decoder.releaseOutputBuffer(decoderOutIdx, render)

                if (render) {
                    // FIX (Bug 1 + Bug 2): wait for the frame on THIS thread with
                    // a latch, then call updateTexImage here — not on RenderThread.
                    val arrived = frameLatch.await(100, TimeUnit.MILLISECONDS)
                    if (!arrived) Log.w(TAG, "Frame latch timed out — skipping render")
                    frameLatch = CountDownLatch(1)    // arm for the next frame

                    surfaceTexture.updateTexImage()   // must be on EGL-current thread
                    GLES20.glViewport(0, 0, finalWidth, finalHeight)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    EGLExt.eglPresentationTimeANDROID(
                        eglDisplay, eglSurface, surfaceTexture.timestamp
                    )
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                }

                decoderOutIdx = decoder.dequeueOutputBuffer(decoderOutInfo, 0)
            }

            // ---- Signal encoder EOS after decoder finishes ----------------------
            if (decoderEos && !sawInputEos) {
                encoder.signalEndOfInputStream()
                sawInputEos = true
                Log.d(TAG, "Encoder EOS signalled")
            }

            // ---- Drain encoder --------------------------------------------------
            val encoderOutInfo = MediaCodec.BufferInfo()
            var encoderOutIdx  = encoder.dequeueOutputBuffer(encoderOutInfo, 10_000)
            while (encoderOutIdx != MediaCodec.INFO_TRY_AGAIN_LATER && !cancelFlag.get()) {
                when {
                    // INFO_OUTPUT_FORMAT_CHANGED: the correct moment to read the
                    // negotiated output format and start the muxer. Starting on a
                    // data buffer (old approach) could use a stale format on some
                    // encoders and would miss this event entirely on others.
                    encoderOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            val negotiated = encoder.outputFormat
                            Log.i(TAG, "Encoder output format negotiated: $negotiated")
                            videoTrackMuxerIndex = muxer.addTrack(negotiated)
                            muxer.start()
                            muxerStarted = true
                            Log.d(TAG, "Muxer started on FORMAT_CHANGED")

                            // Flush audio samples buffered before muxer was ready
                            if (audioTrackMuxerIndex != -1) {
                                for (sample in pendingAudio) {
                                    val buf  = ByteBuffer.wrap(sample.data)
                                    val info = MediaCodec.BufferInfo().apply {
                                        size               = sample.data.size
                                        presentationTimeUs = sample.pts
                                        flags              = sample.flags
                                        offset             = 0
                                    }
                                    muxer.writeSampleData(audioTrackMuxerIndex, buf, info)
                                }
                                pendingAudio.clear()
                            }
                        }
                    }
                    // Real output buffer
                    encoderOutIdx >= 0 -> {
                        if (encoderOutInfo.size > 0 && muxerStarted) {
                            val outputBuffer = encoder.getOutputBuffer(encoderOutIdx)!!
                            muxer.writeSampleData(videoTrackMuxerIndex, outputBuffer, encoderOutInfo)
                        }
                        encoder.releaseOutputBuffer(encoderOutIdx, false)

                        if (encoderOutInfo.presentationTimeUs > 0) {
                            lastEncodedPts = encoderOutInfo.presentationTimeUs
                        }
                        if (encoderOutInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderEos = true
                            Log.d(TAG, "Encoder EOS received")
                        }
                    }
                }
                encoderOutIdx = encoder.dequeueOutputBuffer(encoderOutInfo, 0)
            }

            // ---- Progress -------------------------------------------------------
            // Use lastEncodedPts (updated inside the drain loop) rather than
            // encoderOutInfo.presentationTimeUs, which is 0 on most iterations
            // because the encoder dequeue timed out. The old approach caused
            // artificially uniform 2%-per-second increments.
            if (totalDurationUs > 0 && lastEncodedPts > 0) {
                val prog = (lastEncodedPts.toFloat() / totalDurationUs).coerceIn(0f, 1f)
                if (prog - lastProgress > 0.01f) {
                    lastProgress = prog
                    progressHandler.post { onProgress(prog) }
                }
            }

            // ---- Audio passthrough ----------------------------------------------
            // FIX (Bug A): drain ALL available audio samples each iteration, not
            // just one. Without this the audio extractor falls far behind on any
            // video with dense audio, causing the while-loop to spin thousands of
            // extra iterations after encoderEos is set, and audio may be truncated.
            audioExtractor?.let { ae ->
                while (!audioEos && !cancelFlag.get()) {
                    // FIX (Bug B): always reset to pristine write-mode before each
                    // readSampleData call so position/limit are never stale.
                    audioBuffer.clear()
                    val sampleSize = ae.readSampleData(audioBuffer, 0)
                    if (sampleSize < 0) {
                        // FIX (Bug 4): never write a 0-byte sentinel — MediaMuxer
                        // throws IllegalArgumentException on size=0 writes.
                        audioEos = true
                        Log.d(TAG, "Audio EOS reached")
                        break
                    }

                    lastAudioPts = ae.sampleTime
                    // Translate MediaExtractor sample flags → MediaCodec buffer flags.
                    // These are different constants and cannot be passed interchangeably
                    // to MediaMuxer.writeSampleData(), which expects BUFFER_FLAG_* values.
                    val extractorFlags = ae.sampleFlags
                    val flags = (if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) or
                            (if (extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0)
                                MediaCodec.BUFFER_FLAG_PARTIAL_FRAME else 0)
                    ae.advance()

                    if (muxerStarted && audioTrackMuxerIndex != -1) {
                        // Muxer is live — write directly. Set limit exactly so the
                        // muxer reads only sampleSize bytes from position 0.
                        audioBuffer.limit(sampleSize).position(0)
                        val bi = MediaCodec.BufferInfo().apply {
                            size               = sampleSize
                            presentationTimeUs = lastAudioPts
                            this.flags         = flags
                            offset             = 0
                        }
                        muxer.writeSampleData(audioTrackMuxerIndex, audioBuffer, bi)
                    } else if (audioTrackMuxerIndex != -1) {
                        // FIX (Bug C): muxer not started yet — copy into a heap
                        // array so we own the bytes independently of audioBuffer,
                        // then flush the whole queue once the muxer starts (before
                        // writing the first video frame) so PTS order is preserved.
                        audioBuffer.limit(sampleSize).position(0)
                        val bytes = ByteArray(sampleSize).also { audioBuffer.get(it) }
                        pendingAudio.addLast(AudioSample(bytes, lastAudioPts, flags))
                    }
                }
            }
        } // end while

        // ---- Cleanup ------------------------------------------------------------
        frameCallbackThread.quitSafely()

        decoder.stop()
        decoder.release()
        encoder.stop()
        encoder.release()

        // FIX (Bug 5): release the SurfaceTexture and its wrapper Surface.
        decoderOutputSurface.release()
        surfaceTexture.release()

        extractor.release()
        audioExtractor?.release()

        // FIX (Bug 3): always stop before release; guard both on muxerStarted.
        if (muxerStarted) {
            try { muxer.stop() } catch (e: Exception) {
                Log.w(TAG, "muxer.stop() threw: ${e.message}")
            }
        }
        muxer.release()

        EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteTextures(1, textureId, 0)

        if (cancelFlag.get()) {
            File(outputPath).delete()
            progressHandler.post { onCancelled() }
        } else {
            progressHandler.post {
                onProgress(1f)
                onSuccess()
            }
        }
    }

    // --- Helpers -----------------------------------------------------------------

    private fun isEncoderSupported(
        mime: String,
        width: Int,
        height: Int,
        bitrateBps: Int,
        frameRate: Int
    ): Boolean {
        val codecList   = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val encoderName = codecList.findEncoderForFormat(
            MediaFormat.createVideoFormat(mime, width, height)
        ) ?: return false

        for (codecInfo in codecList.codecInfos) {
            if (codecInfo.name != encoderName || !codecInfo.isEncoder) continue
            val caps = try {
                codecInfo.getCapabilitiesForType(mime)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot get capabilities for $mime: ${e.message}")
                return false
            }
            val vc = caps.videoCapabilities
            if (!vc.isSizeSupported(width, height)) {
                Log.w(TAG, "Size ${width}x${height} not supported by $encoderName")
                return false
            }
            val br = vc.bitrateRange
            if (bitrateBps < br.lower || bitrateBps > br.upper) {
                Log.w(TAG, "Bitrate $bitrateBps not in [${br.lower}, ${br.upper}]")
                return false
            }
            if (!vc.areSizeAndRateSupported(width, height, frameRate.toDouble())) {
                Log.w(TAG, "${width}x${height}@${frameRate}fps not supported")
                return false
            }
            return true
        }
        return false
    }

    private fun alignTo16(value: Int): Int = (value + 15) / 16 * 16
}