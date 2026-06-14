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
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.shanks.minify.ui.CodecChoice
import com.shanks.minify.ui.CropRect
import com.shanks.minify.ui.EditState
import com.shanks.minify.utils.getVideoInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
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
    private const val ABS_FLOOR_BPS = 50_000
    private const val ABS_CEILING_BPS = 25_000_000

    // ── Vertex shader ─────────────────────────────────────────────────────────
    private const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    // ── Pass A: Box pre-filter (OES → FBO-A, source size) ────────────────────
    private const val FRAGMENT_SHADER_BOX = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    uniform samplerExternalOES uTexture;
    uniform mat4  uTexMatrix;
    uniform vec2  uStepSrc;
    uniform float uTapX;
    uniform float uTapY;
    uniform vec2  uCropOrigin;   // left, top  – in UI space (0..1)
    uniform vec2  uCropSize;     // width, height (positive)
    varying vec2 vTexCoord;

    void main() {
        vec4  sum   = vec4(0.0);
        float count = 0.0;
        float startX = -(uTapX - 1.0) * 0.5;
        float startY = -(uTapY - 1.0) * 0.5;

        for (float x = 0.0; x < 8.0; x++) {
            if (x >= uTapX) break;
            for (float y = 0.0; y < 8.0; y++) {
                if (y >= uTapY) break;

                // 1. Map the fragment's V coordinate (bottom‑up) to UI Y (top‑down)
                vec2 uiCoord = vec2(
                    uCropOrigin.x + vTexCoord.x * uCropSize.x,
                    uCropOrigin.y + (1.0 - vTexCoord.y) * uCropSize.y
                );

                // 2. Add box‑filter offset (still in UI space)
                uiCoord += vec2(startX + x, startY + y) * uStepSrc;

                // 3. Clamp to the crop rectangle (UI space)
                vec2 cropMax = uCropOrigin + uCropSize;
                uiCoord = clamp(uiCoord, uCropOrigin, cropMax);

                // 4. **Convert to OpenGL texture space** (flip Y)
                vec2 glCoord = vec2(uiCoord.x, 1.0 - uiCoord.y);

                // 5. Apply the OES texture matrix (expects GL coords)
                vec2 texCoord = (uTexMatrix * vec4(glCoord, 0.0, 1.0)).xy;

                sum   += texture2D(uTexture, texCoord);
                count += 1.0;
            }
        }
        gl_FragColor = sum / count;
    }
"""

    // ── Pass B: Horizontal Lanczos-2 (FBO-A → FBO-B, output size) ────────────
    private const val FRAGMENT_SHADER_LANCZOS_H = """
        precision mediump float;
        uniform sampler2D uTexture2D;
        uniform mat4  uTexMatrix; // Unused but kept for program consistency if needed
        uniform vec2 uStep;
        varying vec2 vTexCoord;

        float lanczos2(float x) {
            if (x == 0.0) return 1.0;
            if (abs(x) >= 2.0) return 0.0;
            float px  = 3.14159265 * x;
            float px2 = px * 0.5;
            return (sin(px) / px) * (sin(px2) / px2);
        }

        void main() {
            vec4  color = vec4(0.0); float ws = 0.0;
            for (int i = -1; i <= 2; i++) {
                float o = float(i) - 0.5;
                float w = lanczos2(o);
                color += texture2D(uTexture2D, vTexCoord + uStep * o) * w;
                ws    += w;
            }
            gl_FragColor = color / ws;
        }
    """

    // ── Pass C: Vertical Lanczos-2 (FBO-B → encoder surface) ─────────────────
    private const val FRAGMENT_SHADER_LANCZOS_V = """
        precision mediump float;
        uniform sampler2D uTexture2D;
        uniform mat4  uTexMatrix; // Unused but kept for program consistency if needed
        uniform vec2 uStep;
        varying vec2 vTexCoord;

        float lanczos2(float x) {
            if (x == 0.0) return 1.0;
            if (abs(x) >= 2.0) return 0.0;
            float px  = 3.14159265 * x;
            float px2 = px * 0.5;
            return (sin(px) / px) * (sin(px2) / px2);
        }

        void main() {
            vec4  color = vec4(0.0); float ws = 0.0;
            for (int i = -1; i <= 2; i++) {
                float o = float(i) - 0.5;
                float w = lanczos2(o);
                color += texture2D(uTexture2D, vTexCoord + uStep * o) * w;
                ws    += w;
            }
            gl_FragColor = color / ws;
        }
    """

    // ── Shader helpers ────────────────────────────────────────────────────────

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { Log.e(TAG, "Shader error: ${GLES20.glGetShaderInfoLog(shader)}"); GLES20.glDeleteShader(shader); return 0 }
        return shader
    }

    private fun createProgram(v: String, f: String): Int {
        val vert = compileShader(GLES20.GL_VERTEX_SHADER, v)
        val frag = compileShader(GLES20.GL_FRAGMENT_SHADER, f)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vert); GLES20.glAttachShader(prog, frag)
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { Log.e(TAG, "Link error: ${GLES20.glGetProgramInfoLog(prog)}"); GLES20.glDeleteProgram(prog); return 0 }
        GLES20.glDeleteShader(vert); GLES20.glDeleteShader(frag)
        return prog
    }

    private fun makeFbo(w: Int, h: Int): Pair<Int, Int> {
        val fbo = IntArray(1); val tex = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0); GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return fbo[0] to tex[0]
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun computeParams(
        targetSizeMb: Float,
        durationSecs: Long,
        srcBitrateKbps: Int,
        srcWidth: Int,
        srcHeight: Int,
        frameRate: Float,
        codecChoice: CodecChoice,
        headroom: Float = 0.88f
    ): Pair<Int, Int> {
        val fps             = frameRate.coerceIn(1f, 120f)
        val audioBudget     = if (durationSecs > 0) AUDIO_KBPS * 1000L * durationSecs else 0L
        val targetBits      = (targetSizeMb * 8_388_608f * headroom).toLong()
        val videoBudget     = (targetBits - audioBudget).coerceAtLeast(targetBits / 2)
        val rawBps          = if (durationSecs > 0) (videoBudget / durationSecs).toInt()
        else (srcBitrateKbps * 1000 * 0.5f).roundToInt()
        val videoTargetBps  = rawBps.coerceIn(ABS_FLOOR_BPS, ABS_CEILING_BPS)

        // Codec-aware BPP: AV1 (0.09) < H.265 (0.13) < H.264 (0.20)
        // High efficiency codecs can target higher resolutions at the same bitrate.
        val targetBpp = when (codecChoice) {
            CodecChoice.AV1  -> 0.09f
            CodecChoice.H265 -> 0.13f
            CodecChoice.H264 -> 0.20f
        }

        val ar          = srcWidth.toFloat() / srcHeight
        val totalPixels = videoTargetBps.toFloat() / (targetBpp * fps)
        val idealH      = sqrt(totalPixels / ar)
        val minH        = minOf(480, srcHeight)
        val rawH        = idealH.roundToInt().coerceIn(minH, srcHeight)
        val outH        = alignTo16(rawH).coerceIn(16, srcHeight)

        Log.d(TAG, "computeParams -> codec=${codecChoice.label} bitrate=${videoTargetBps/1000}kbps height=$outH BPP=$targetBpp")
        return videoTargetBps to outH
    }

    fun compress(
        context: Context,
        inputUri: Uri,
        outputPath: String,
        codecChoice: CodecChoice,
        targetSizeMb: Float,
        editState: EditState = EditState(),
        onProgress: (Float) -> Unit,
        onSuccess: () -> Unit,
        onCancelled: () -> Unit,
        onFailure: (Exception) -> Unit
    ): CompressionJob {
        val cancelFlag = AtomicBoolean(false)
        val thread     = HandlerThread("CompressThread").apply { start() }
        val handler    = Handler(thread.looper)
        handler.post {
            try {
                runCompression(context, inputUri, outputPath, codecChoice,
                    targetSizeMb, editState, cancelFlag, onProgress, onSuccess, onCancelled, onFailure)
            } catch (e: Exception) {
                if (!cancelFlag.get()) { Log.e(TAG, "Compression error", e); Handler(context.mainLooper).post { onFailure(e) } }
                thread.quitSafely()
            }
        }
        return CompressionJob(cancelFlag, thread, handler)
    }

    // ── Core pipeline ─────────────────────────────────────────────────────────

    private fun runCompression(
        context: Context,
        inputUri: Uri,
        outputPath: String,
        codecChoice: CodecChoice,
        targetSizeMb: Float,
        editState: EditState,
        cancelFlag: AtomicBoolean,
        onProgress: (Float) -> Unit,
        onSuccess: () -> Unit,
        onCancelled: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val info = getVideoInfo(context, inputUri)

        // Effective duration for bitrate budget: trim if set
        val trimStartUs = editState.trimStartMs * 1000L
        val trimEndUs   = editState.trimEndMs?.let { it * 1000L }
        val effectiveDurSecs = if (trimEndUs != null)
            ((trimEndUs - trimStartUs) / 1_000_000L).coerceAtLeast(1L)
        else
            info.durationSecs

        // ── Track discovery ───────────────────────────────────────────────────
        val extractor = MediaExtractor().apply { setDataSource(context, inputUri, null) }
        var videoIdx = -1; var audioIdx = -1
        var videoFmt: MediaFormat? = null; var audioFmt: MediaFormat? = null
        var rotation = 0
        for (i in 0 until extractor.trackCount) {
            val fmt  = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME)
            when {
                mime?.startsWith("video/") == true && videoIdx == -1 -> {
                    videoIdx = i; videoFmt = fmt
                    rotation = try {
                        if (Build.VERSION.SDK_INT >= 29) {
                            fmt.getInteger(MediaFormat.KEY_ROTATION, 0)
                        } else {
                            if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
                        }
                    } catch (_: Exception) { 0 }
                }
                mime?.startsWith("audio/") == true && audioIdx == -1 -> { audioIdx = i; audioFmt = fmt }
            }
        }
        if (videoIdx == -1) { extractor.release(); throw IllegalStateException("No video track found") }

        // ── Dimensions ───────────────────────────────────────────────────────
        val codedW = videoFmt!!.getInteger(MediaFormat.KEY_WIDTH)
        val codedH = videoFmt!!.getInteger(MediaFormat.KEY_HEIGHT)
        val swapped = rotation == 90 || rotation == 270
        val srcW = if (swapped) codedH else codedW
        val srcH = if (swapped) codedW  else codedH

        // Crop dimensions affect the output resolution calculation
        val crop        = editState.cropRect ?: CropRect.FULL
        val cropPxW     = (srcW * crop.width).roundToInt().coerceAtLeast(1)
        val cropPxH     = (srcH * crop.height).roundToInt().coerceAtLeast(1)

        val (_, targetHeight) = computeParams(
            targetSizeMb, effectiveDurSecs, info.bitrateKbps,
            cropPxW, cropPxH,
            info.frameRate, codecChoice
        )

        val targetW   = (targetHeight.toFloat() * cropPxW / cropPxH).roundToInt()
        val finalW    = alignTo16(targetW).coerceAtMost(cropPxW)
        val finalH    = targetHeight.coerceAtMost(cropPxH)
        val encMime   = codecChoice.mime
        val fpsInt    = info.frameRate.toInt().coerceIn(1, 120)

        // Box taps based on crop region size, not full frame size
        val tapX = ceil(cropPxW.toFloat() / finalW).toInt().coerceIn(1, 8)
        val tapY = ceil(cropPxH.toFloat() / finalH).toInt().coerceIn(1, 8)
        Log.d(TAG, "Box taps: ${tapX}x${tapY}  crop: ${cropPxW}x${cropPxH} -> ${finalW}x${finalH}")

        // ── Encoder ───────────────────────────────────────────────────────────
        val codecList   = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val encInfo     = codecList.codecInfos.firstOrNull { it.isEncoder && it.supportedTypes.contains(encMime) }
        if (encInfo == null) {
            val fallback = when (codecChoice) { CodecChoice.AV1 -> CodecChoice.H265; CodecChoice.H265 -> CodecChoice.H264; CodecChoice.H264 -> null }
            if (fallback != null) {
                Log.w(TAG, "No encoder for ${codecChoice.label}, falling back to ${fallback.label}")
                return runCompression(context, inputUri, outputPath, fallback, targetSizeMb, editState, cancelFlag, onProgress, onSuccess, onCancelled, onFailure)
            } else throw IllegalStateException("No H.264 encoder found on device")
        }

        val encCaps  = encInfo.getCapabilitiesForType(encMime)
        val vidCaps  = encCaps.videoCapabilities ?: throw IllegalStateException("No video capabilities for $encMime")
        val encMaxW  = vidCaps.supportedWidths.upper
        val encMaxH  = vidCaps.supportedHeights.upper
        val encFW    = if (finalW > encMaxW) { Log.w(TAG, "Width $finalW > $encMaxW — attempting"); finalW } else finalW
        val encFH    = if (finalH > encMaxH) { Log.w(TAG, "Height $finalH > $encMaxH — attempting"); finalH } else finalH

        val encoderCaps = encCaps.encoderCapabilities
        val vbrOk    = encoderCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true
        val headroom = if (vbrOk) 0.90f else 0.92f
        val (rawBr, _) = computeParams(targetSizeMb, effectiveDurSecs, info.bitrateKbps, cropPxW, cropPxH, info.frameRate, codecChoice, headroom)
        val bitrate  = rawBr.coerceAtMost(vidCaps.bitrateRange.upper)

        val encoder  = MediaCodec.createEncoderByType(encMime)
        val encFmt   = MediaFormat.createVideoFormat(encMime, encFW, encFH).apply {
            setInteger(MediaFormat.KEY_BIT_RATE,   bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fpsInt)
            if (vbrOk) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            else setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)

            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3) // More efficient for offline storage
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            try { setInteger(MediaFormat.KEY_PRIORITY, 1) } catch (_: Exception) {}
            when (codecChoice) {
                CodecChoice.H264 -> { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0); try { setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain); setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41) } catch (_: Exception) {} }
                CodecChoice.H265 -> { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0); try { setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain); setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41) } catch (_: Exception) {} }
                CodecChoice.AV1  -> { try { setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8) } catch (_: Exception) {} }
            }
            try { setInteger(MediaFormat.KEY_COMPLEXITY, 10) } catch (_: Exception) {}
        }
        encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encSurface = encoder.createInputSurface()
        encoder.start()

        Log.i(TAG, "=== Compression params ===")
        Log.i(TAG, "  Codec      : $encMime  (${encoder.name})")
        Log.i(TAG, "  Resolution : ${encFW}x${encFH}  (crop src: ${cropPxW}x${cropPxH})")
        Log.i(TAG, "  Bitrate    : ${bitrate/1000} kbps  headroom=$headroom")
        Log.i(TAG, "  Duration   : ${effectiveDurSecs}s  (trim: ${editState.trimStartMs}ms – ${editState.trimEndMs ?: "end"}ms)")
        Log.i(TAG, "  Crop       : left=${crop.left} top=${crop.top} right=${crop.right} bottom=${crop.bottom}")
        Log.i(TAG, "  VBR        : $vbrOk")
        Log.i(TAG, "==========================")

        // ── EGL ───────────────────────────────────────────────────────────────
        val eglDisp = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisp, IntArray(2), 0, IntArray(2), 1)
        val cfgAttr = intArrayOf(EGL14.EGL_RED_SIZE,8, EGL14.EGL_GREEN_SIZE,8, EGL14.EGL_BLUE_SIZE,8, EGL14.EGL_ALPHA_SIZE,8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1); val nCfg = IntArray(1)
        EGL14.eglChooseConfig(eglDisp, cfgAttr, 0, cfgs, 0, 1, nCfg, 0)
        val eglCtx  = EGL14.eglCreateContext(eglDisp, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val eglSurf = EGL14.eglCreateWindowSurface(eglDisp, cfgs[0], encSurface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(eglDisp, eglSurf, eglSurf, eglCtx)

        // ── GL programs ───────────────────────────────────────────────────────
        val progBox  = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_BOX)
        val progLanH = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_LANCZOS_H)
        val progLanV = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_LANCZOS_V)

        val verts = floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)
        val vBuf  = ByteBuffer.allocateDirect(verts.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { it.put(verts).position(0) }

        val aPos1=GLES20.glGetAttribLocation(progBox,"aPosition");  val aTex1=GLES20.glGetAttribLocation(progBox,"aTexCoord")
        val aPos2=GLES20.glGetAttribLocation(progLanH,"aPosition"); val aTex2=GLES20.glGetAttribLocation(progLanH,"aTexCoord")
        val aPos3=GLES20.glGetAttribLocation(progLanV,"aPosition"); val aTex3=GLES20.glGetAttribLocation(progLanV,"aTexCoord")

        GLES20.glClearColor(0f,0f,0f,1f)

        val oesId = IntArray(1)
        GLES20.glGenTextures(1, oesId, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesId[0])
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // FBO-A: box output at CROP size; FBO-B: lanczos-H output at ENCODE size
        val (fboAId, fboATex) = makeFbo(cropPxW, cropPxH)
        val (fboBId, fboBTex) = makeFbo(encFW, encFH)

        // Uniform locations
        val uTMBox=GLES20.glGetUniformLocation(progBox,"uTexMatrix"); val uStepBox=GLES20.glGetUniformLocation(progBox,"uStepSrc")
        val uTapXB=GLES20.glGetUniformLocation(progBox,"uTapX");      val uTapYB=GLES20.glGetUniformLocation(progBox,"uTapY")
        val uSmpBox=GLES20.glGetUniformLocation(progBox,"uTexture");   val uCropOri=GLES20.glGetUniformLocation(progBox,"uCropOrigin")
        val uCropSz=GLES20.glGetUniformLocation(progBox,"uCropSize")
        val uTMLH=GLES20.glGetUniformLocation(progLanH,"uTexMatrix"); val uStepLH=GLES20.glGetUniformLocation(progLanH,"uStep"); val uSmpLH=GLES20.glGetUniformLocation(progLanH,"uTexture2D")
        val uTMLV=GLES20.glGetUniformLocation(progLanV,"uTexMatrix"); val uStepLV=GLES20.glGetUniformLocation(progLanV,"uStep"); val uSmpLV=GLES20.glGetUniformLocation(progLanV,"uTexture2D")

        val identity = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)

        // Lanczos steps in crop-region UV space
        val stepCropX = 1f / cropPxW
        val stepCropY = 1f / cropPxH

        val surfTex  = SurfaceTexture(oesId[0])
        val decSurf  = Surface(surfTex)

        var latch = CountDownLatch(1)
        val cbThread = HandlerThread("FrameCallback").apply { start() }
        surfTex.setOnFrameAvailableListener({ latch.countDown() }, Handler(cbThread.looper))
        val latchTimeout = (3000L / fpsInt.toLong()).coerceIn(50L, 200L)

        // ── Decoder ───────────────────────────────────────────────────────────
        val decMime = videoFmt!!.getString(MediaFormat.KEY_MIME)!!
        val decoder = MediaCodec.createDecoderByType(decMime)
        decoder.configure(videoFmt, decSurf, null, 0)
        decoder.start()

        // ── Seek to trim start ─────────────────────────────────────────────────
        if (editState.trimStartMs > 0L) {
            extractor.selectTrack(videoIdx)
            extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        } else {
            extractor.selectTrack(videoIdx)
        }

        // ── Muxer ─────────────────────────────────────────────────────────────
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        // Set rotation to 0 because we've already applied it in the shader via uTexMatrix.
        muxer.setOrientationHint(0)

        var vidMuxIdx = -1; var audMuxIdx = -1; var muxStarted = false
        if (audioIdx != -1 && audioFmt != null) audMuxIdx = muxer.addTrack(audioFmt!!)

        // ── Audio extractor ───────────────────────────────────────────────────
        var audioExt: MediaExtractor? = null
        if (audioIdx != -1) {
            audioExt = MediaExtractor().apply {
                setDataSource(context, inputUri, null)
                selectTrack(audioIdx)
                if (editState.trimStartMs > 0L) seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
        }

        data class AudioSample(val data: ByteArray, val pts: Long, val flags: Int)
        val pendingAudio = ArrayDeque<AudioSample>()
        val audioBuf  = ByteBuffer.allocate(1_048_576)
        var audioEos  = (audioIdx == -1)
        var lastAudPts = 0L

        // PTS offset: subtract trim start so output starts at 0
        val ptsOffsetUs = trimStartUs

        val totalDurUs  = effectiveDurSecs * 1_000_000L
        var lastProg    = 0f
        var lastEncPts  = 0L
        val progHandler = Handler(context.mainLooper)

        var decEos  = false; var encEos = false; var sawEos = false

        // ═════════════════════════════════════════════════════════════════════
        // Main loop
        // ═════════════════════════════════════════════════════════════════════
        while (!cancelFlag.get() && (!decEos || !encEos || !audioEos)) {

            // Feed decoder
            if (!decEos) {
                val idx = decoder.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val buf  = decoder.getInputBuffer(idx)!!
                    val size = extractor.readSampleData(buf, 0)
                    val samplePts = extractor.sampleTime
                    // Stop feeding if we've passed the trim end
                    val pastTrimEnd = trimEndUs != null && samplePts > trimEndUs
                    if (size < 0 || pastTrimEnd) {
                        decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        decEos = true; Log.d(TAG, "Decoder EOS queued")
                    } else {
                        decoder.queueInputBuffer(idx, 0, size, samplePts, 0)
                        extractor.advance()
                    }
                }
            }

            // Drain decoder
            val dInfo = MediaCodec.BufferInfo()
            var dIdx  = decoder.dequeueOutputBuffer(dInfo, 10_000)
            while (dIdx >= 0 && !cancelFlag.get()) {
                val render = dInfo.size > 0
                decoder.releaseOutputBuffer(dIdx, render)
                if (render) {
                    val arrived = latch.await(latchTimeout, TimeUnit.MILLISECONDS)
                    if (!arrived) Log.w(TAG, "Frame latch timeout")
                    latch = CountDownLatch(1)

                    surfTex.updateTexImage()
                    val texMtx = FloatArray(16); surfTex.getTransformMatrix(texMtx)

                    // ── Pass A: Box (OES → FBO-A, crop region) ────────────────
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboAId)
                    GLES20.glViewport(0, 0, cropPxW, cropPxH)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glUseProgram(progBox)
                    vBuf.position(0); GLES20.glVertexAttribPointer(aPos1,2,GLES20.GL_FLOAT,false,4*4,vBuf); GLES20.glEnableVertexAttribArray(aPos1)
                    vBuf.position(2); GLES20.glVertexAttribPointer(aTex1,2,GLES20.GL_FLOAT,false,4*4,vBuf); GLES20.glEnableVertexAttribArray(aTex1)
                    GLES20.glUniformMatrix4fv(uTMBox, 1, false, texMtx, 0)
                    // Step in normalized display-space
                    GLES20.glUniform2f(uStepBox, 1f / srcW, 1f / srcH)
                    GLES20.glUniform1f(uTapXB, tapX.toFloat()); GLES20.glUniform1f(uTapYB, tapY.toFloat())
                    GLES20.glUniform1i(uSmpBox, 0)
                    // Pass the crop rectangle exactly as defined in UI (top‑left, positive size)
                    GLES20.glUniform2f(uCropOri, crop.left, crop.top)
                    GLES20.glUniform2f(uCropSz,  crop.width, crop.height)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesId[0])
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                    // ── Pass B: H-Lanczos (FBO-A → FBO-B) ────────────────────
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboBId)
                    GLES20.glViewport(0, 0, encFW, encFH)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glUseProgram(progLanH)
                    vBuf.position(0); GLES20.glVertexAttribPointer(aPos2,2,GLES20.GL_FLOAT,false,4*4,vBuf); GLES20.glEnableVertexAttribArray(aPos2)
                    vBuf.position(2); GLES20.glVertexAttribPointer(aTex2,2,GLES20.GL_FLOAT,false,4*4,vBuf); GLES20.glEnableVertexAttribArray(aTex2)
                    GLES20.glUniformMatrix4fv(uTMLH, 1, false, identity, 0)
                    GLES20.glUniform2f(uStepLH, stepCropX, 0f)
                    GLES20.glUniform1i(uSmpLH, 0)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboATex)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                    // ── Pass C: V-Lanczos (FBO-B → encoder) ──────────────────
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    
                    // Clear the entire encoder frame first (to avoid garbage in bars)
                    GLES20.glViewport(0, 0, encFW, encFH)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                    // Maintenance exact aspect ratio within the 16-aligned encoder frame to avoid deformation.
                    val cropAr = cropPxW.toFloat() / cropPxH
                    val encAr  = encFW.toFloat() / encFH
                    var vpW = encFW; var vpH = encFH
                    var vpx = 0; var vpy = 0
                    if (cropAr > encAr) {
                        vpH = (encFW / cropAr).roundToInt()
                        vpy = (encFH - vpH) / 2
                    } else {
                        vpW = (encFH * cropAr).roundToInt()
                        vpx = (encFW - vpW) / 2
                    }
                    GLES20.glViewport(vpx, vpy, vpW, vpH)
                    // We don't clear again here because we already cleared the whole surface
                    GLES20.glUseProgram(progLanV)
                    vBuf.position(0); GLES20.glVertexAttribPointer(aPos3,2,GLES20.GL_FLOAT,false,4*4,vBuf); GLES20.glEnableVertexAttribArray(aPos3)
                    vBuf.position(2); GLES20.glVertexAttribPointer(aTex3,2,GLES20.GL_FLOAT,false,4*4,vBuf); GLES20.glEnableVertexAttribArray(aTex3)
                    GLES20.glUniformMatrix4fv(uTMLV, 1, false, identity, 0)
                    GLES20.glUniform2f(uStepLV, 0f, stepCropY)
                    GLES20.glUniform1i(uSmpLV, 0)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboBTex)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                    EGLExt.eglPresentationTimeANDROID(eglDisp, eglSurf, surfTex.timestamp)
                    EGL14.eglSwapBuffers(eglDisp, eglSurf)
                }
                dIdx = decoder.dequeueOutputBuffer(dInfo, 0)
            }

            if (decEos && !sawEos) { encoder.signalEndOfInputStream(); sawEos = true; Log.d(TAG, "Encoder EOS signalled") }

            // Drain encoder
            val eInfo = MediaCodec.BufferInfo()
            var eIdx  = encoder.dequeueOutputBuffer(eInfo, 10_000)
            while (eIdx != MediaCodec.INFO_TRY_AGAIN_LATER && !cancelFlag.get()) {
                when {
                    eIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxStarted) {
                            vidMuxIdx = muxer.addTrack(encoder.outputFormat)
                            muxer.start(); muxStarted = true; Log.d(TAG, "Muxer started")
                            if (audMuxIdx != -1) {
                                for (s in pendingAudio) muxer.writeSampleData(audMuxIdx, ByteBuffer.wrap(s.data), MediaCodec.BufferInfo().apply { size=s.data.size; presentationTimeUs=s.pts; flags=s.flags; offset=0 })
                                pendingAudio.clear()
                            }
                        }
                    }
                    eIdx >= 0 -> {
                        if (eInfo.size > 0 && muxStarted) {
                            // Re-zero PTS relative to trim start
                            val adjustedPts = (eInfo.presentationTimeUs - ptsOffsetUs).coerceAtLeast(0L)
                            val adjustedInfo = MediaCodec.BufferInfo().apply { size=eInfo.size; presentationTimeUs=adjustedPts; flags=eInfo.flags; offset=eInfo.offset }
                            muxer.writeSampleData(vidMuxIdx, encoder.getOutputBuffer(eIdx)!!, adjustedInfo)
                        }
                        encoder.releaseOutputBuffer(eIdx, false)
                        if (eInfo.presentationTimeUs > 0) lastEncPts = eInfo.presentationTimeUs - ptsOffsetUs
                        if (eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encEos = true; Log.d(TAG, "Encoder EOS received") }
                    }
                }
                eIdx = encoder.dequeueOutputBuffer(eInfo, 0)
            }

            // Progress
            if (totalDurUs > 0 && lastEncPts > 0) {
                val prog = (lastEncPts.toFloat() / totalDurUs).coerceIn(0f, 1f)
                if (prog - lastProg > 0.01f) { lastProg = prog; progHandler.post { onProgress(prog) } }
            }

            // Audio passthrough with trim + PTS offset
            audioExt?.let { ae ->
                while (!audioEos && !cancelFlag.get()) {
                    audioBuf.clear()
                    val sz = ae.readSampleData(audioBuf, 0)
                    val samplePts = ae.sampleTime
                    val pastEnd = trimEndUs != null && samplePts > trimEndUs
                    if (sz < 0 || pastEnd) { audioEos = true; Log.d(TAG, "Audio EOS"); break }
                    val adjPts = (samplePts - ptsOffsetUs).coerceAtLeast(0L)
                    val ef = ae.sampleFlags
                    val flags = (if (ef and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) or
                            (if (ef and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) MediaCodec.BUFFER_FLAG_PARTIAL_FRAME else 0)
                    ae.advance()
                    audioBuf.limit(sz).position(0)
                    val bi = MediaCodec.BufferInfo().apply { size=sz; presentationTimeUs=adjPts; this.flags=flags; offset=0 }
                    if (muxStarted && audMuxIdx != -1) muxer.writeSampleData(audMuxIdx, audioBuf, bi)
                    else if (audMuxIdx != -1) { val bytes=ByteArray(sz).also { audioBuf.get(it) }; pendingAudio.addLast(AudioSample(bytes, adjPts, flags)) }
                }
            }
        }

        // ── Cleanup ───────────────────────────────────────────────────────────
        cbThread.quitSafely()
        decoder.stop(); decoder.release()
        encoder.stop(); encoder.release()
        decSurf.release(); surfTex.release()
        extractor.release(); audioExt?.release()
        if (muxStarted) try { muxer.stop() } catch (e: Exception) { Log.w(TAG, "muxer.stop: ${e.message}") }
        muxer.release()

        EGL14.eglMakeCurrent(eglDisp, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisp, eglSurf)
        EGL14.eglDestroyContext(eglDisp, eglCtx)
        EGL14.eglTerminate(eglDisp)

        GLES20.glDeleteProgram(progBox); GLES20.glDeleteProgram(progLanH); GLES20.glDeleteProgram(progLanV)
        GLES20.glDeleteTextures(1, oesId, 0)
        GLES20.glDeleteTextures(1, intArrayOf(fboATex), 0); GLES20.glDeleteTextures(1, intArrayOf(fboBTex), 0)
        GLES20.glDeleteFramebuffers(1, intArrayOf(fboAId), 0); GLES20.glDeleteFramebuffers(1, intArrayOf(fboBId), 0)

        if (cancelFlag.get()) { File(outputPath).delete(); progHandler.post { onCancelled() } }
        else progHandler.post { onProgress(1f); onSuccess() }
    }

    private fun alignTo16(v: Int) = (v + 15) / 16 * 16
}
