package com.shanks.minify.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.shanks.minify.utils.SaveKind
import com.shanks.minify.utils.saveToGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Compresses a single image to a target byte budget while preserving
 * orientation, then saves the result to the device gallery (Requirement 8).
 *
 * --- Memory safety ---
 * Before decoding the full bitmap, the source dimensions are read and a
 * power-of-two [BitmapFactory.Options.inSampleSize] is computed so the loaded
 * image never exceeds a safe pixel count (~16.7 MP). This prevents
 * OutOfMemoryErrors on high-resolution sources.
 *
 * --- I/O efficiency ---
 * A [ParcelFileDescriptor] is opened once (API 19+). The same descriptor is
 * passed to both the bitmap decoder and the EXIF reader (API 24+), halving the
 * number of stream opens. Older devices fall back to two separate opens.
 *
 * --- Fast path ---
 * If the original file is already a JPEG under the target size and requires no
 * orientation correction, it is copied directly to the gallery without
 * re-encoding, preserving original quality and speed.
 *
 * --- Probe optimization ---
 * The quality/scale search uses a binary search over the domination chain
 * produced by [PhotoBudget]. Candidate count drops from ~30 to ~5–6 encodes.
 * The final encoded output is written directly to a temporary file to avoid
 * holding a large byte array in memory.
 */
object PhotoCompressor {

    private const val TAG = "PhotoCompressor"

    /** 1 MB = 1,048,576 bytes, per Req 8.2. */
    private const val BYTES_PER_MB = 1_048_576.0

    // Memory-safe decode cap (~16.7 MP) is defined once as the top-level
    // [com.shanks.minify.photo.MAX_DECODE_PIXELS] and shared with the Photo
    // Editor host so both cap decode memory identically.

    // ---- Public API ----------------------------------------------------------

    /**
     * Compress [input] so the output is no larger than [targetSizeMb] megabytes,
     * saving the result to the device gallery on success.
     *
     * @return [PhotoResult.Success] with the saved output file and the original
     *   and compressed byte sizes, or [PhotoResult.Failure] with the reason.
     *   The source [input] is never modified.
     */
    suspend fun compress(context: Context, input: Uri, targetSizeMb: Float): PhotoResult {
        val originalBytes = readSize(context, input)
        PhotoCompressionMonitor.onStart(originalBytes)

        // 1. Validate format up front — unsupported inputs never touch the source.
        val format = resolveFormat(context, input)
            ?: return fail(PhotoFailure.UNSUPPORTED_FORMAT)

        val targetBytes = (targetSizeMb.toDouble() * BYTES_PER_MB).toLong().coerceAtLeast(1L)

        // 2. Fast path: if original is already a JPEG under target size and upright, copy directly.
        if (format == ImageFormat.JPEG && originalBytes in 1..targetBytes) {
            val pfd = runCatching {
                context.contentResolver.openFileDescriptor(input, "r")
            }.getOrNull()
            val orientation = try {
                readExifOrientation(context, input, pfd)
            } finally {
                pfd?.close()
            }
            if (orientation == ExifInterface.ORIENTATION_NORMAL) {
                return copyToGallery(context, input, originalBytes)
            }
        }

        // 3. Decode upright bitmap (CPU-bound).
        val encodedOutcome: EncodeOutcome = withContext(Dispatchers.Default) {
            val base = try {
                decodeUpright(context, input)
            } catch (e: Exception) {
                Log.e(TAG, "Decode failed", e)
                null
            } ?: return@withContext EncodeOutcome.Error

            try {
                // 4. Choose best params via binary search over the domination chain.
                val params = selectParamsBinary(
                    candidateList = PhotoBudget.candidateParams(format),
                    targetBytes = targetBytes,
                    baseBitmap = base,
                )
                if (params == null) {
                    // No candidate fits.
                    return@withContext EncodeOutcome.Unachievable
                }

                // 5. Encode final output directly to a temporary file (no large byte[] in memory).
                val tmpFile = encodeToTempFile(context, base, params)
                    ?: return@withContext EncodeOutcome.Error

                EncodeOutcome.Ok(tmpFile, params.format)
            } finally {
                base.recycle()
            }
        }

        when (encodedOutcome) {
            EncodeOutcome.Error -> return fail(PhotoFailure.ENCODE_ERROR)
            EncodeOutcome.Unachievable -> return fail(PhotoFailure.UNACHIEVABLE_TARGET)
            is EncodeOutcome.Ok -> Unit
        }
        val ok = encodedOutcome as EncodeOutcome.Ok

        // 6. Save to gallery (the temp file is already the compressed result).
        val outputFile: File = try {
            withContext(Dispatchers.IO) {
                try {
                    saveToGallery(context, ok.tempFile, SaveKind.IMAGE)
                    ok.tempFile   // return the temp file; gallery has its own copy
                } catch (e: Exception) {
                    ok.tempFile.delete()   // clean up on failure
                    throw e
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save failed", e)
            return fail(PhotoFailure.SAVE_ERROR)
        }

        val compressedBytes = ok.tempFile.length()
        PhotoCompressionMonitor.onComplete(compressedBytes)
        return PhotoResult.Success(
            outputFile = outputFile,
            originalBytes = originalBytes,
            compressedBytes = compressedBytes,
        )
    }

    // ---- Internal helpers ----------------------------------------------------

    /** Report [reason] to the monitor and return the matching failure result. */
    private fun fail(reason: PhotoFailure): PhotoResult.Failure {
        PhotoCompressionMonitor.onFailure(reason)
        return PhotoResult.Failure(reason)
    }

    /** The outcome of the CPU-bound decode/select/encode stage. */
    private sealed interface EncodeOutcome {
        data class Ok(val tempFile: File, val format: ImageFormat) : EncodeOutcome
        data object Error : EncodeOutcome
        data object Unachievable : EncodeOutcome
    }

    /**
     * Decode [input] and apply its EXIF orientation correction so the returned
     * bitmap is upright (Req 8.9). Returns `null` if the pixels cannot be
     * decoded.
     *
     * The decoded bitmap is automatically down-sampled when the source exceeds
     * [MAX_DECODE_PIXELS] to avoid OutOfMemoryErrors.
     */
    private fun decodeUpright(context: Context, input: Uri): Bitmap? {
        // 1. Open the file once, using a ParcelFileDescriptor when possible.
        //    The whole body runs inside `use {}` so every exit path — including
        //    the early return below on decode failure — closes the descriptor.
        //    (Previously the early return skipped both close sites and leaked
        //    the fd on any corrupt/truncated source.)
        val pfd = runCatching {
            context.contentResolver.openFileDescriptor(input, "r")
        }.getOrNull()

        return pfd.use { safePfd ->
            // 2. Decode with a safe sample size.
            val decoded = decodeWithSafeSize(context, input, safePfd) ?: return@use null

            // 3. Read EXIF orientation from the same descriptor (or fallback stream).
            val orientation = readExifOrientation(context, input, safePfd)

            // 4. Apply orientation correction.
            val transform = ExifOrientation.normalize(orientation)
            if (transform.isIdentity) {
                return@use decoded
            }

            val matrix = Matrix()
            val sx = if (transform.flipHorizontal) -1f else 1f
            val sy = if (transform.flipVertical) -1f else 1f
            if (sx != 1f || sy != 1f) matrix.postScale(sx, sy)
            if (transform.rotationDegrees != 0) matrix.postRotate(transform.rotationDegrees.toFloat())

            val rotated = try {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            } catch (e: Exception) {
                Log.w(TAG, "Orientation transform failed; using un-rotated bitmap", e)
                decoded
            }

            if (rotated !== decoded) decoded.recycle()
            rotated
        }
    }

    /**
     * Decodes the bitmap with a power-of-two sample size so that the total pixel
     * count never exceeds [MAX_DECODE_PIXELS]. Uses the already-opened [pfd] if
     * available, otherwise falls back to a fresh stream.
     */
    private fun decodeWithSafeSize(
        context: Context,
        input: Uri,
        pfd: ParcelFileDescriptor?,
    ): Bitmap? {
        // First pass: read dimensions only.
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (pfd != null) {
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)
        } else {
            context.contentResolver.openInputStream(input)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }

        val width = opts.outWidth
        val height = opts.outHeight
        if (width <= 0 || height <= 0) return null

        // Calculate sample size.
        val sampleSize = calculateSampleSize(width, height, MAX_DECODE_PIXELS)

        // Second pass: real decode.
        opts.inJustDecodeBounds = false
        opts.inSampleSize = sampleSize
        return if (pfd != null) {
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)
        } else {
            context.contentResolver.openInputStream(input)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }
    }

    /**
     * Reads the EXIF orientation tag. Uses the file descriptor on API 24+,
     * otherwise opens a separate stream. The [pfd] is **not** closed by this
     * method; the caller is responsible for closing it when no longer needed.
     */
    private fun readExifOrientation(
        context: Context,
        input: Uri,
        pfd: ParcelFileDescriptor?,
    ): Int {
        return try {
            if (pfd != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ExifInterface(pfd.fileDescriptor)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } else {
                context.contentResolver.openInputStream(input)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read EXIF orientation; assuming normal", e)
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /**
     * Binary search over the [candidateList] (non-increasing quality/scale) to
     * find the highest-quality params whose encoded size ≤ [targetBytes].
     *
     * A reusable [ByteArrayOutputStream] is used for probes. If a probe encode
     * fails, that candidate is treated as too large, and the search continues.
     * Returns `null` if no candidate fits.
     */
    private fun selectParamsBinary(
        candidateList: List<PhotoParams>,
        targetBytes: Long,
        baseBitmap: Bitmap,
    ): PhotoParams? {
        if (candidateList.isEmpty()) return null

        var low = 0
        var high = candidateList.lastIndex
        var best: PhotoParams? = null

        ByteArrayOutputStream().use { probeStream ->
            while (low <= high) {
                val mid = (low + high) / 2
                val params = candidateList[mid]
                val size = probeEncodedSize(baseBitmap, params, probeStream)
                if (size <= targetBytes) {
                    best = params
                    high = mid - 1   // try to find an even higher-quality one on the left
                } else {
                    low = mid + 1
                }
            }
        }
        return best
    }

    /**
     * Probes the encoded size of [base] at [params] using the reusable [probeStream].
     * The stream is reset before each measurement. Returns [Long.MAX_VALUE] on
     * encode failure (treated as too large).
     */
    private fun probeEncodedSize(
        base: Bitmap,
        params: PhotoParams,
        probeStream: ByteArrayOutputStream,
    ): Long = try {
        probeStream.reset()
        if (encodeInto(base, params, probeStream)) probeStream.size().toLong() else Long.MAX_VALUE
    } catch (e: Exception) {
        Log.w(TAG, "Size probe failed for $params", e)
        Long.MAX_VALUE
    }

    /**
     * Encodes [base] at [params] directly to a new temporary file in the cache
     * directory. Returns the file, or `null` on failure.
     */
    private fun encodeToTempFile(
        context: Context,
        base: Bitmap,
        params: PhotoParams,
    ): File? {
        return try {
            val tmp = File.createTempFile(
                "minify_photo_",
                ".${extensionFor(params.format)}",
                context.cacheDir,
            )
            FileOutputStream(tmp).use { fos ->
                if (!encodeIntoOutputStream(base, params, fos)) {
                    tmp.delete()
                    return null
                }
            }
            tmp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode to temp file", e)
            null
        }
    }

    /**
     * Scale [base] by `params.scale` (if needed) and compress it into [out]
     * (a [ByteArrayOutputStream]) at `params.quality`/`params.format`.
     * Returns the encoder's success flag. The scaled bitmap is recycled if it
     * differs from the original.
     */
    private fun encodeInto(
        base: Bitmap,
        params: PhotoParams,
        out: ByteArrayOutputStream,
    ): Boolean {
        val scaled = scaleBitmap(base, params.scale)
        return try {
            scaled.compress(compressFormat(params.format), params.quality, out)
        } finally {
            if (scaled !== base) scaled.recycle()
        }
    }

    /**
     * Same as [encodeInto] but writes to a [FileOutputStream] for the final
     * output.
     */
    private fun encodeIntoOutputStream(
        base: Bitmap,
        params: PhotoParams,
        out: FileOutputStream,
    ): Boolean {
        val scaled = scaleBitmap(base, params.scale)
        return try {
            scaled.compress(compressFormat(params.format), params.quality, out)
        } finally {
            if (scaled !== base) scaled.recycle()
        }
    }

    /** Return [src] scaled by [scale], or [src] itself when no scaling is needed. */
    private fun scaleBitmap(src: Bitmap, scale: Float): Bitmap {
        if (scale >= 0.999f) return src
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    @Suppress("DEPRECATION")
    private fun compressFormat(format: ImageFormat): Bitmap.CompressFormat = when (format) {
        ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
        ImageFormat.PNG -> Bitmap.CompressFormat.PNG
        ImageFormat.WEBP ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
            else Bitmap.CompressFormat.WEBP
    }

    private fun extensionFor(format: ImageFormat): String = when (format) {
        ImageFormat.JPEG -> "jpg"
        ImageFormat.PNG -> "png"
        ImageFormat.WEBP -> "webp"
    }

    /**
     * Resolve the supported [ImageFormat] of [input].
     *
     * Primary probe is the content resolver's MIME type. `file://` URIs (e.g.
     * the Photo Editor's edited temp file) fall back to the URI's file extension.
     * Returns `null` only when neither probe yields a supported format.
     */
    private fun resolveFormat(context: Context, input: Uri): ImageFormat? {
        ImageFormat.fromMimeType(context.contentResolver.getType(input))?.let { return it }
        val extension = input.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotEmpty() }
        return ImageFormat.fromMimeType(extension)
    }

    /**
     * Best‑effort read of the source size in bytes for reporting (Req 8.6).
     * Falls back to streaming the content when the provider does not expose a
     * size column. Returns 0 if the size cannot be determined.
     */
    private fun readSize(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null,
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                    return cursor.getLong(idx)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Size query failed; falling back to stream count", e)
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                var total = 0L
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            } ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine source size", e)
            0L
        }
    }

    /**
     * Copies a JPEG file that already fits the target and is upright directly to
     * the gallery. Returns a Success result or Failure on error.
     */
    private suspend fun copyToGallery(
        context: Context,
        input: Uri,
        originalBytes: Long,
    ): PhotoResult {
        return try {
            val outputFile = withContext(Dispatchers.IO) {
                val tmp = File.createTempFile("minify_copy_", ".jpg", context.cacheDir)
                context.contentResolver.openInputStream(input)?.use { inputStream ->
                    FileOutputStream(tmp).use { outputStream ->
                        inputStream.copyTo(outputStream, bufferSize = 8192)
                    }
                } ?: throw IllegalStateException("Cannot open input stream")
                saveToGallery(context, tmp, SaveKind.IMAGE)
                tmp
            }
            PhotoCompressionMonitor.onComplete(originalBytes)
            PhotoResult.Success(
                outputFile = outputFile,
                originalBytes = originalBytes,
                compressedBytes = originalBytes,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Copy-to-gallery fast path failed", e)
            fail(PhotoFailure.SAVE_ERROR)
        }
    }

    // ---- Utility functions ---------------------------------------------------

    /**
     * Computes a power‑of‑two [BitmapFactory.Options.inSampleSize] such that
     * `(width / sample) * (height / sample) <= maxPixels`.
     */
    private fun calculateSampleSize(width: Int, height: Int, maxPixels: Long): Int {
        var sample = 1
        while (width / sample.toLong() * (height / sample) > maxPixels) {
            sample *= 2
        }
        return sample
    }
}