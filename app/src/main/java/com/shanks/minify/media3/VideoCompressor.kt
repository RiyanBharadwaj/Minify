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
    // Vertex shader passes through position and applies the SurfaceTexture
    // transform matrix so texture coordinates match the actual decoder layout.
    // Not applying this matrix causes mis-sampling on some devices/content.
    private const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """

    // Two-pass Lanczos-2 approximation using 4-tap weighted sum.
    // Single bilinear (GL_LINEAR) when downscaling 3x+ produces aliasing and
    // moire. A weighted multi-tap filter preserves fine detail and sharpness.
    // The shader samples 4 neighbours in whichever axis is specified by uStep,
    // so it is run once horizontally then once vertically (ping-pong FBO).
    private const val FRAGMENT_SHADER_PASS1 = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uTexture;
        uniform vec2 uStep;
        varying vec2 vTexCoord;

        float lanczos2(float x) {
            if (x == 0.0) return 1.0;
            if (abs(x) >= 2.0) return 0.0;
            float px = 3.14159265 * x;
            float px2 = px * 0.5;
            return (sin(px) / px) * (sin(px2) / px2);
        }

        void main() {
            vec4 color = vec4(0.0);
            float weightSum = 0.0;
            for (int i = -1; i <= 2; i++) {
                float offset = float(i) - 0.5;
                float w = lanczos2(offset);
                color += texture2D(uTexture, vTexCoord + uStep * offset) * w;
                weightSum += w;
            }
            gl_FragColor = color / weightSum;
        }
    """

    // Second pass samples the intermediate RGBA FBO texture (regular 2D).
    private const val FRAGMENT_SHADER_PASS2 = """
        precision mediump float;
        uniform sampler2D uTexture2D;
        uniform vec2 uStep;
        varying vec2 vTexCoord;

        float lanczos2(float x) {
            if (x == 0.0) return 1.0;
            if (abs(x) >= 2.0) return 0.0;
            float px = 3.14159265 * x;
            float px2 = px * 0.5;
            return (sin(px) / px) * (sin(px2) / px2);
        }

        void main() {
            vec4 color = vec4(0.0);
            float weightSum = 0.0;
            for (int i = -1; i <= 2; i++) {
                float offset = float(i) - 0.5;
                float w = lanczos2(offset);
                color += texture2D(uTexture2D, vTexCoord + uStep * offset) * w;
                weightSum += w;
            }
            gl_FragColor = color / weightSum;
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

    private fun createProgram(vertSrc: String, fragSrc: String): Int {
        val vertex   = compileShader(GLES20.GL_VERTEX_SHADER,   vertSrc)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
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
        useH265: Boolean,
        // Fraction of the target budget to actually use. Caller sets this after
        // querying real encoder capabilities so headroom matches actual behaviour.
        headroom: Float = 0.88f
    ): Pair<Int, Int> {
        val fps = frameRate.coerceIn(1f, 120f)

        val audioBudgetBits = if (durationSecs > 0) (AUDIO_KBPS * 1000L * durationSecs) else 0L
        // 1 MB = 8 × 1,048,576 bits. headroom is set by the caller based on
        // whether the encoder supports true CBR or falls back to VBR.
        val targetBits      = (targetSizeMb * 8_388_608f * headroom).toLong()
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
        // Never downscale below 480p — blurry 480p beats sharp 160p at the
        // same bitrate. The encoder uses fewer bits per frame when tight.
        val minHeight    = minOf(480, srcHeight)
        val rawHeight    = idealHeight.roundToInt().coerceIn(minHeight, srcHeight)
        // Align height to 16 then clamp back to srcHeight so we never request
        // a resolution larger than the source (e.g. source 2340x1080: aligned
        // width 2352 exceeds source width 2340 and the encoder rejects it).
        val outputHeight = alignTo16(rawHeight).coerceIn(16, srcHeight)

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
        // info.width/height are display-space (rotation-corrected) since getVideoInfo
        // now applies the rotation swap. computeParams gets the correct aspect ratio.
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
        val codedWidth  = videoFormat!!.getInteger(MediaFormat.KEY_WIDTH)
        val codedHeight = videoFormat!!.getInteger(MediaFormat.KEY_HEIGHT)

        // MediaFormat KEY_WIDTH/KEY_HEIGHT are always *coded* (bitstream) dimensions
        // and do not account for rotation. We must swap them into display space here
        // for the encoder resolution and aspect ratio math.
        // Note: getVideoInfo already returns display-space dims in info.width/height,
        // so computeParams always gets the correct aspect ratio. This local swap is
        // still needed for encFinalWidth/encFinalHeight passed to the encoder.
        val displaySwapped = rotation == 90 || rotation == 270
        val srcWidth  = if (displaySwapped) codedHeight else codedWidth
        val srcHeight = if (displaySwapped) codedWidth  else codedHeight

        val targetWidth = (targetHeight.toFloat() * srcWidth / srcHeight).roundToInt()
        // Align then clamp to srcWidth/srcHeight so non-16-aligned source widths
        // (e.g. 2340) don't produce aligned values that exceed the source dimensions.
        val finalWidth  = alignTo16(targetWidth).coerceAtMost(srcWidth)
        val finalHeight = targetHeight.coerceAtMost(srcHeight)
        val encoderMime       = if (useH265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else         MediaFormat.MIMETYPE_VIDEO_AVC
        val frameRateInt      = info.frameRate.toInt().coerceIn(1, 120)

        // ---- Encoder capability resolution ─────────────────────────────────────
        // findEncoderForFormat(mime + dimensions) returns null on many devices when
        // the requested dimensions exceed the encoder's *advertised* max, even though
        // the encoder works fine at those dimensions in practice (OMX.MTK.VIDEO.ENCODER.HEVC
        // won't advertise 2340x1080 but encodes it without issue).
        // Strategy: find by mime type only, then use capability info to clamp
        // dimensions and bitrate — never reject based on declared caps alone.
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

        // Find encoder by mime type only (no dimension filter)
        val encoderInfo = codecList.codecInfos
            .firstOrNull { it.isEncoder && it.supportedTypes.contains(encoderMime) }

        if (encoderInfo == null) {
            // Codec genuinely absent from device
            if (useH265) {
                Log.w(TAG, "No H.265 encoder on device, falling back to H.264")
                return runCompression(
                    context, inputUri, outputPath, false, targetSizeMb,
                    cancelFlag, onProgress, onSuccess, onCancelled, onFailure
                )
            } else {
                throw IllegalStateException("No H.264 encoder found on device")
            }
        }

        val encoderCaps   = encoderInfo.getCapabilitiesForType(encoderMime)
        val videoCaps     = encoderCaps.videoCapabilities

        // Clamp requested dimensions to what the encoder actually declares.
        // If the encoder under-reports (common on MTK), we pass the original
        // dimensions and let the hardware decide — it almost always works.
        val encMaxW = videoCaps.supportedWidths.upper
        val encMaxH = videoCaps.supportedHeights.upper
        val encFinalWidth  = if (finalWidth  > encMaxW) {
            Log.w(TAG, "Width $finalWidth exceeds declared max $encMaxW — attempting anyway")
            finalWidth   // attempt at requested size; hardware usually accepts it
        } else finalWidth
        val encFinalHeight = if (finalHeight > encMaxH) {
            Log.w(TAG, "Height $finalHeight exceeds declared max $encMaxH — attempting anyway")
            finalHeight
        } else finalHeight

        // CBR support and headroom — calibrated from real OMX.MTK device measurements:
        //   CBR H.265: overshoots ~4–8%  → headroom 0.92
        //   VBR H.264: overshoots ~3–5%  → headroom 0.93
        val cbrSupported = encoderCaps.encoderCapabilities.isBitrateModeSupported(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        val headroom = if (cbrSupported) 0.92f else 0.93f

        val (rawAdjustedBitrate, _) = computeParams(
            targetSizeMb, info.durationSecs, info.bitrateKbps,
            srcWidth, srcHeight, info.frameRate, useH265, headroom
        )
        // Clamp to declared ceiling — never reject or fall back on bitrate alone.
        val encoderBitrateCeiling = videoCaps.bitrateRange.upper
        val adjustedBitrate = rawAdjustedBitrate.coerceAtMost(encoderBitrateCeiling)
        Log.d(TAG, "Bitrate: raw=${rawAdjustedBitrate/1000}kbps  ceiling=${encoderBitrateCeiling/1000}kbps  final=${adjustedBitrate/1000}kbps")

        // ---- Encoder ------------------------------------------------------------
        val encoder = MediaCodec.createEncoderByType(encoderMime)
        val encoderFormat = MediaFormat.createVideoFormat(encoderMime, encFinalWidth, encFinalHeight).apply {
            setInteger(MediaFormat.KEY_BIT_RATE,    adjustedBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE,  frameRateInt)
            // Only set CBR mode when the encoder actually supports it. Setting an
            // unsupported bitrate mode causes some encoders to behave erratically.
            if (cbrSupported) {
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            // 4-second keyframe interval. 1s was forcing ~600 large keyframes
            // for a 10-min video, each 5–20× bigger than a P-frame, bloating
            // the output. 4s keeps seeking reasonable while cutting overhead.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 4)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // Non-realtime priority: tells the encoder this is an offline
            // transcode, not live capture. Enables slower, higher-quality
            // encoding paths on hardware encoders that support two modes.
            // Silently ignored by encoders that don't distinguish modes.
            try { setInteger(MediaFormat.KEY_PRIORITY, 1) }
            catch (_: Exception) {}
            // B-frames: enabled for H.265 only.
            // H.264 on MTK hardware (OMX.MTK.VIDEO.ENCODER.AVC) accepts
            // KEY_MAX_B_FRAMES = 2 without error but doesn't correctly signal
            // B-frame reordering in the output bitstream — the muxer writes
            // frames in encode order but the decoder renders them out of display
            // order, producing glitchy / corrupted playback.
            // H.265 (OMX.MTK.VIDEO.ENCODER.HEVC) handles B-frames correctly.
            if (encoderMime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            } else {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            // Encoder complexity hint (0–10). Higher = more CPU time on motion
            // estimation = better quality per bit. Silently ignored if unsupported.
            try { setInteger(MediaFormat.KEY_COMPLEXITY, 10) }
            catch (_: Exception) {}
            // Profile hints — use Main for both codecs on MTK hardware.
            // AVCProfileHigh causes silent failures on OMX.MTK.VIDEO.ENCODER.AVC;
            // AVCProfileMain still enables CABAC and is reliably supported.
            // HEVCProfileMain is the correct baseline for H.265.
            if (encoderMime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                try {
                    setInteger(MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
                    setInteger(MediaFormat.KEY_LEVEL,
                        MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                } catch (_: Exception) {}
            } else if (encoderMime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                try {
                    setInteger(MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                    setInteger(MediaFormat.KEY_LEVEL,
                        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41)
                } catch (_: Exception) {}
            }
        }
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface = encoder.createInputSurface()
        encoder.start()

        // ---- Diagnostic logging — visible in Logcat filtered by "VideoCompressor" ----
        Log.i(TAG, "=== Compression params ===")
        Log.i(TAG, "  Codec        : $encoderMime  (${encoder.name})")
        Log.i(TAG, "  Resolution   : ${encFinalWidth}x${encFinalHeight}  (source: ${srcWidth}x${srcHeight})")
        Log.i(TAG, "  Bitrate      : ${adjustedBitrate / 1000} kbps  (headroom=${headroom})")
        Log.i(TAG, "  Frame rate   : $frameRateInt fps  |  Duration: ${info.durationSecs}s")
        Log.i(TAG, "  Target size  : $targetSizeMb MB")
        Log.i(TAG, "  CBR support  : $cbrSupported  |  Mode applied: ${if (cbrSupported) "CBR" else "VBR (fallback — size accuracy is best-effort)"}")
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

        // ---- OpenGL programs + geometry -----------------------------------------
        // Pass 1: OES external texture → intermediate FBO (horizontal Lanczos)
        val progPass1    = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_PASS1)
        // Pass 2: intermediate FBO → encoder surface (vertical Lanczos)
        val progPass2    = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_PASS2)

        // Geometry: full-screen quad. V is NOT flipped here — the SurfaceTexture
        // transform matrix (uTexMatrix) handles the Y-axis correctly per-frame.
        val vertices = floatArrayOf(
            -1f, -1f,  0f, 0f,
            1f, -1f,  1f, 0f,
            -1f,  1f,  0f, 1f,
            1f,  1f,  1f, 1f
        )
        val vertBuffer = ByteBuffer
            .allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(vertices).position(0) }

        // Wire geometry into both programs
        for (prog in intArrayOf(progPass1, progPass2)) {
            GLES20.glUseProgram(prog)
            val pLoc = GLES20.glGetAttribLocation(prog, "aPosition")
            val tLoc = GLES20.glGetAttribLocation(prog, "aTexCoord")
            vertBuffer.position(0)
            GLES20.glVertexAttribPointer(pLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, vertBuffer)
            GLES20.glEnableVertexAttribArray(pLoc)
            vertBuffer.position(2)
            GLES20.glVertexAttribPointer(tLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, vertBuffer)
            GLES20.glEnableVertexAttribArray(tLoc)
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // OES texture — decoder writes frames here via SurfaceTexture
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

        // Intermediate FBO + RGBA texture for the horizontal pass output.
        // Size = output resolution so pass 2 reads at 1:1.
        val fboId   = IntArray(1)
        val fboTexId = IntArray(1)
        GLES20.glGenFramebuffers(1, fboId, 0)
        GLES20.glGenTextures(1, fboTexId, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            encFinalWidth, encFinalHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTexId[0], 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // Transform matrix storage — updated each frame from SurfaceTexture
        val texMatrix = FloatArray(16)

        // Cache all uniform locations once here — calling glGetUniformLocation
        // inside the per-frame render loop causes a driver roundtrip every frame.
        // For a 756-frame video that's ~6000 redundant GL calls eliminated.
        val uTexMatrixLoc1 = GLES20.glGetUniformLocation(progPass1, "uTexMatrix")
        val uStepLoc1      = GLES20.glGetUniformLocation(progPass1, "uStep")
        val uSamplerLoc1   = GLES20.glGetUniformLocation(progPass1, "uTexture")
        val uTexMatrixLoc2 = GLES20.glGetUniformLocation(progPass2, "uTexMatrix")
        val uStepLoc2      = GLES20.glGetUniformLocation(progPass2, "uStep")
        val uSamplerLoc2   = GLES20.glGetUniformLocation(progPass2, "uTexture")
        // Identity matrix for pass 2 (regular 2D texture — no OES transform needed)
        val identityMatrix = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
        // Precompute step values — these only change if resolution changes (it doesn't)
        val stepH = 1f / srcWidth    // horizontal step for pass 1
        val stepV = 1f / srcHeight   // vertical step for pass 2

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
        // Latch timeout = 3× the frame interval, min 50ms, max 200ms.
        // 100ms flat was fine at 30fps but at 63fps frames arrive every 16ms —
        // a timed-out latch stalls the pipeline for 100ms per frame (6+ seconds
        // of unnecessary waiting for a 756-frame video).
        val frameLatchTimeoutMs = (3000L / frameRateInt.toLong()).coerceIn(50L, 200L)

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
                    val arrived = frameLatch.await(frameLatchTimeoutMs, TimeUnit.MILLISECONDS)
                    if (!arrived) Log.w(TAG, "Frame latch timed out — skipping render")
                    frameLatch = CountDownLatch(1)

                    surfaceTexture.updateTexImage()
                    surfaceTexture.getTransformMatrix(texMatrix)

                    // ── Pass 1: OES texture → FBO (horizontal Lanczos) ───────────
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[0])
                    GLES20.glViewport(0, 0, encFinalWidth, encFinalHeight)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glUseProgram(progPass1)
                    GLES20.glUniformMatrix4fv(uTexMatrixLoc1, 1, false, texMatrix, 0)
                    GLES20.glUniform2f(uStepLoc1, stepH, 0f)
                    GLES20.glUniform1i(uSamplerLoc1, 0)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId[0])
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                    // ── Pass 2: FBO → encoder surface (vertical Lanczos) ─────────
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glViewport(0, 0, encFinalWidth, encFinalHeight)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glUseProgram(progPass2)
                    GLES20.glUniformMatrix4fv(uTexMatrixLoc2, 1, false, identityMatrix, 0)
                    GLES20.glUniform2f(uStepLoc2, 0f, stepV)
                    val sampLoc2 = GLES20.glGetUniformLocation(progPass2, "uTexture2D")
                    GLES20.glUniform1i(sampLoc2, 0)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId[0])
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
        GLES20.glDeleteProgram(progPass1)
        GLES20.glDeleteProgram(progPass2)
        GLES20.glDeleteTextures(1, textureId, 0)
        GLES20.glDeleteTextures(1, fboTexId, 0)
        GLES20.glDeleteFramebuffers(1, fboId, 0)

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

    private fun isCbrSupported(mime: String, encoderName: String): Boolean {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (ci in codecList.codecInfos) {
            if (ci.name != encoderName || !ci.isEncoder) continue
            val ec = try { ci.getCapabilitiesForType(mime).encoderCapabilities }
            catch (e: Exception) { return false }
            return ec.isBitrateModeSupported(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        return false
    }

    private fun alignTo16(value: Int): Int = (value + 15) / 16 * 16
}