package com.shanks.minify.ui.trim

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android glue over [MediaMetadataRetriever] that decodes timeline thumbnails for the
 * Video_Trimmer filmstrip.
 *
 * All timeline geometry lives in the pure [TimelineMapping]/[TimelineSampling] modules; this
 * class only turns a list of source time positions (produced by
 * [TimelineSampling.sampleTimes], which bounds the count to keep memory in check — Req 18.2)
 * into decoded [Bitmap]s.
 *
 * Extraction is performed off the main thread on [Dispatchers.IO] and frames are emitted as
 * they decode so the timeline can fill in progressively while remaining interactive
 * (Req 18.1, 13.1, 13.3). The returned [Flow] is cooperatively cancellable: leaving the screen
 * cancels the collecting coroutine, which unwinds the flow and releases the retriever. A
 * [ComponentCallbacks2] registered for the duration of collection also aborts extraction when
 * the system reports low memory (Req 18.3, 18.4).
 *
 * The caller is responsible for calling [close] once it is finished with the extractor (for
 * example in a `DisposableEffect.onDispose`) to release the underlying [MediaMetadataRetriever].
 *
 * Validates Requirements 13.1, 18.1, 18.2, 18.3, 18.4.
 */
class ThumbnailExtractor(context: Context, private val uri: Uri) {

    /** Application context so a registered [ComponentCallbacks2] does not leak an Activity. */
    private val appContext: Context = context.applicationContext

    private val retriever = MediaMetadataRetriever()

    /** Guards [retriever] so [close] and the extraction loop never touch a released instance. */
    private val lock = Any()

    @Volatile
    private var dataSourceSet = false

    @Volatile
    private var closed = false

    /**
     * Emits `(timeMs, bitmap)` pairs for each entry in [times], in order, as the frames decode.
     *
     * Each time is interpreted as a whole-millisecond source position and converted to the
     * microsecond argument [MediaMetadataRetriever.getFrameAtTime] expects, requested with
     * [MediaMetadataRetriever.OPTION_CLOSEST_SYNC] for fast keyframe seeking. Frames that fail
     * to decode (null) are skipped.
     *
     * The work runs on [Dispatchers.IO]. The flow checks for coroutine cancellation before each
     * decode so leaving the screen stops extraction promptly, and it registers a
     * [ComponentCallbacks2] that requests cancellation when the system signals low memory.
     */
    fun frames(times: List<Long>): Flow<Pair<Long, Bitmap>> {
        val lowMemory = AtomicBoolean(false)

        return flow {
            if (times.isEmpty()) return@flow

            val callback = object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                        lowMemory.set(true)
                    }
                }

                override fun onLowMemory() {
                    lowMemory.set(true)
                }

                override fun onConfigurationChanged(newConfig: Configuration) {
                    // No-op: thumbnail extraction is orientation-independent.
                }
            }
            appContext.registerComponentCallbacks(callback)

            try {
                ensureDataSource()
                for (timeMs in times) {
                    // Cooperative cancellation: leaving the screen cancels the collector.
                    currentCoroutineContext().ensureActive()
                    if (lowMemory.get()) {
                        throw CancellationException("Thumbnail extraction cancelled: low memory")
                    }

                    val frame = decodeFrame(timeMs) ?: continue
                    emit(timeMs to frame)
                }
            } finally {
                appContext.unregisterComponentCallbacks(callback)
            }
        }
            .flowOn(Dispatchers.IO)
            .onCompletion {
                // Low-memory cancellation must eagerly free the native retriever; other
                // completion paths leave release to the caller's close().
                if (lowMemory.get()) close()
            }
    }

    private fun ensureDataSource() {
        synchronized(lock) {
            if (closed || dataSourceSet) return
            retriever.setDataSource(appContext, uri)
            dataSourceSet = true
        }
    }

    private fun decodeFrame(timeMs: Long): Bitmap? {
        synchronized(lock) {
            if (closed) return null
            val timeUs = timeMs * 1_000L

            // Extract a scaled frame (since API 27) to speed up loading and save memory.
            // A height of 200px is sufficient for the 56dp UI even on high-density screens.
            // minSdk is 28, so getScaledFrameAtTime is always available.
            try {
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) {
                    val targetHeight = 200
                    val targetWidth = (w.toFloat() * targetHeight / h).toInt().coerceAtLeast(1)
                    return retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetWidth,
                        targetHeight
                    )
                }
            } catch (_: Exception) {
                // Fall back to full-res if scaling fails.
            }

            return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    }

    /** Releases the underlying [MediaMetadataRetriever]. Safe to call more than once. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                retriever.release()
            } catch (_: Exception) {
                // Releasing an already-released retriever can throw; ignore on teardown.
            }
        }
    }
}
