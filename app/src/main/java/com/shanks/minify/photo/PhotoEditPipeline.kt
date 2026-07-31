package com.shanks.minify.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Longest edge (px) the Photo Editor preview is down-sampled toward, so that a
 * large source does not exhaust the heap while editing. Sources whose longest
 * edge does not exceed this are shown unscaled (Req 9.1).
 */
const val PREVIEW_MAX_EDGE = 4096

/**
 * Maximum decoded pixels before a source bitmap is down-sampled (~16.7 MP).
 *
 * Single source of truth for the memory-safe pixel budget shared by
 * [PhotoCompressor] and the Photo Editor host ([decodeUprightCapped]) so both
 * cap decode memory identically (design: "matching PhotoCompressor's
 * MAX_DECODE_PIXELS").
 */
const val MAX_DECODE_PIXELS = 4096 * 4096L

/**
 * Decode cap for the on-screen Photo Editor surface (~4.2 MP).
 *
 * The editor hosts the source in a `PhotoEditorView` and composites overlays via
 * `saveAsBitmap`, which captures at the on-screen view size — so a source decoded
 * far above screen resolution buys no visible quality but doubles peak memory
 * (a full-size decode plus a full-size EXIF-rotation copy). Capping the editor
 * decode well below [MAX_DECODE_PIXELS] keeps large phone photos from failing to
 * allocate (which previously surfaced as "Couldn't open the selected image")
 * while leaving the full-resolution compression path ([PhotoCompressor], which
 * keeps using [MAX_DECODE_PIXELS]) untouched.
 */
const val EDITOR_MAX_DECODE_PIXELS = 2048 * 2048L

/**
 * Upper bound on the power-of-two `inSampleSize` used when a decode has to be
 * retried at a smaller size after running out of memory. Prevents an unbounded
 * retry loop on a pathological source.
 */
private const val MAX_SAMPLE_SIZE = 32

/**
 * Pure helper that computes the `BitmapFactory.inSampleSize` for the Photo
 * Editor preview so the decoded bitmap's longest edge is at most
 * [PREVIEW_MAX_EDGE] when the source exceeds it, and unscaled (sample size 1)
 * otherwise.
 *
 * Follows the power-of-two `inSampleSize` convention used by `loadPreviewBitmap`
 * in `PhotoTab.kt`: the sample size is doubled until the source's longest edge,
 * divided by the sample, no longer exceeds [PREVIEW_MAX_EDGE]. Non-positive
 * dimensions yield a sample size of 1.
 *
 * @param width the source image width in pixels.
 * @param height the source image height in pixels.
 * @return the `inSampleSize` (a power of two, at least 1).
 */
fun previewSampleSize(width: Int, height: Int): Int {
    val longest = maxOf(width, height)
    var sample = 1
    while (longest > 0 && longest / sample > PREVIEW_MAX_EDGE) {
        sample *= 2
    }
    return sample
}

/**
 * Pure helper that computes the pixel dimensions of the confirmed edited image
 * from the full-resolution source dimensions and the applied rotation.
 *
 * A 90° or 270° clockwise rotation swaps width and height; 0° or 180° leaves
 * them as-is. The result is always derived from the supplied full-resolution
 * `(width, height)` rather than any downsampled preview, so the confirmed
 * output tracks the source (Req 5.3, 9.2).
 *
 * @param rotationDegrees clockwise rotation, expected to be one of
 *        `{0, 90, 180, 270}`; other multiples of 90 are normalized.
 * @param width the full-resolution source width in pixels.
 * @param height the full-resolution source height in pixels.
 * @return the output `(width, height)` as a [Pair].
 */
fun outputDimensions(rotationDegrees: Int, width: Int, height: Int): Pair<Int, Int> {
    val normalized = ((rotationDegrees % 360) + 360) % 360
    return if (normalized == 90 || normalized == 270) {
        height to width
    } else {
        width to height
    }
}

/**
 * Decode [source] **upright** (EXIF orientation applied) and memory-capped so
 * the decoded pixel count never exceeds [MAX_DECODE_PIXELS] (~16.7 MP).
 *
 * This is the shared decode used by the Photo Editor host to load the source
 * into a `PhotoEditorView`: it mirrors [PhotoCompressor]'s decode/EXIF handling
 * (a power-of-two `inSampleSize` bounds pass, then a real decode, then an
 * [ExifOrientation.normalize] correction matrix) but exposes the result as an
 * in-memory [Bitmap] rather than re-encoding it. The capped, upright bitmap is
 * the operative "full-resolution source" for the editor surface.
 *
 * @param maxPixels the decode pixel budget; the source is down-sampled so the
 *   decoded bitmap never exceeds it. Defaults to [MAX_DECODE_PIXELS]; the Photo
 *   Editor host passes the smaller [EDITOR_MAX_DECODE_PIXELS].
 * @return the upright [Bitmap], or `null` if the pixels cannot be decoded (the
 *   host treats `null` as a decode failure and returns to the Photo tab).
 */
fun decodeUprightCapped(
    context: Context,
    source: Uri,
    maxPixels: Long = MAX_DECODE_PIXELS,
): Bitmap? {
    // Preferred path (API 28+): ImageDecoder is the most robust system decoder.
    // It handles formats BitmapFactory can silently fail on (notably HEIC/HEIF,
    // which many phones use for camera photos), applies EXIF orientation
    // automatically, and lets us cap the decoded size. A SOFTWARE allocation is
    // required because the editor draws/composites the bitmap (a hardware bitmap
    // cannot be read back or drawn to a Canvas).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
            val src = ImageDecoder.createSource(context.contentResolver, source)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val w = info.size.width
                val h = info.size.height
                if (w > 0 && h > 0) {
                    var sample = 1
                    while (w / sample.toLong() * (h / sample) > maxPixels) sample *= 2
                    if (sample > 1) decoder.setTargetSampleSize(sample)
                }
            }
        } catch (t: Throwable) {
            // Includes decode errors and OutOfMemoryError. Fall back to the
            // BitmapFactory path below, which also retries at smaller sizes.
            Log.w(PIPELINE_TAG, "ImageDecoder failed for $source; falling back to BitmapFactory", t)
        }
    }
    return decodeUprightCappedLegacy(context, source, maxPixels)
}

/**
 * BitmapFactory-based decode used as a fallback for [decodeUprightCapped] (and on
 * pre-P devices). Applies EXIF orientation manually and retries at a smaller
 * sample size if it runs out of memory. Returns `null` if the source cannot be
 * opened or decoded.
 */
private fun decodeUprightCappedLegacy(
    context: Context,
    source: Uri,
    maxPixels: Long,
): Bitmap? {
    // Pass 1: bounds only, so a sample size can be chosen before allocating.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(source)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    } ?: return null

    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null

    // Initial power-of-two sample size so (w/sample)*(h/sample) <= maxPixels.
    var sample = 1
    while (width / sample.toLong() * (height / sample) > maxPixels) {
        sample *= 2
    }

    // Read EXIF orientation once (independent of sample size) so the bitmap opens
    // upright (Req 3.7 groundwork).
    val orientation = try {
        context.contentResolver.openInputStream(source)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        Log.w(PIPELINE_TAG, "Could not read EXIF orientation; assuming normal", e)
        ExifInterface.ORIENTATION_NORMAL
    }
    val transform = ExifOrientation.normalize(orientation)

    // Decode + orient, retrying at half resolution if memory runs out. A large
    // source can exhaust the heap during either the raw decode or the full-size
    // rotation copy; rather than returning null outright (which the host reports
    // as "Couldn't open the selected image"), progressively down-sample until it
    // fits or the retry ceiling is hit.
    while (true) {
        val decoded: Bitmap? = try {
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        } catch (e: OutOfMemoryError) {
            Log.w(PIPELINE_TAG, "Out of memory decoding at sample=$sample; retrying smaller", e)
            null
        }

        if (decoded == null) {
            if (sample >= MAX_SAMPLE_SIZE) return null
            sample *= 2
            continue
        }

        if (transform.isIdentity) return decoded

        val matrix = Matrix()
        val sx = if (transform.flipHorizontal) -1f else 1f
        val sy = if (transform.flipVertical) -1f else 1f
        if (sx != 1f || sy != 1f) matrix.postScale(sx, sy)
        if (transform.rotationDegrees != 0) matrix.postRotate(transform.rotationDegrees.toFloat())

        val oriented: Bitmap? = try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            Log.w(PIPELINE_TAG, "Out of memory rotating at sample=$sample; retrying smaller", e)
            null
        } catch (e: Exception) {
            Log.w(PIPELINE_TAG, "EXIF orientation transform failed; using un-rotated bitmap", e)
            decoded
        }

        if (oriented == null) {
            // Ran out of memory applying the rotation: free the source and retry
            // the whole decode at a smaller size.
            decoded.recycle()
            if (sample >= MAX_SAMPLE_SIZE) return null
            sample *= 2
            continue
        }

        if (oriented !== decoded) decoded.recycle()
        return oriented
    }
}

private const val PIPELINE_TAG = "PhotoEditPipeline"

/**
 * Apply the pending [edits] to the full-resolution [source] image and persist
 * the result to an app-owned temporary file, ready for the existing
 * [PhotoCompressor] pipeline.
 *
 * Pipeline stages, mirroring [PhotoCompressor]'s decode/encode style and bitmap
 * recycling:
 *  1. Decode the **full-resolution** source (no preview downsampling) and apply
 *     its EXIF orientation correction via [ExifOrientation.normalize] so the
 *     bitmap opens upright — the same correction [PhotoCompressor.decodeUpright]
 *     performs (Req 3.2).
 *  2. Compose the [ImageEditModel] rotation (90 clockwise steps) and horizontal
 *     mirror into the same [Matrix] and apply it to the oriented bitmap, so the
 *     transformed bitmap matches what the editor previewed (Req 5.3, 6.3).
 *  3. Crop to [ImageEditModel.crop] via [CropGeometry.toPixelRect], which
 *     intersects the crop with the transformed bounds (Req 4.4).
 *  4. Write the result to a temp file in [Context.getCacheDir] (Req 8.1, 8.3).
 *     Encoding through [Bitmap.compress] writes no EXIF orientation tag, so the
 *     temp file carries a normalized (identity) orientation and
 *     [PhotoCompressor] — which re-applies EXIF — cannot double-rotate it.
 *
 * Bitmap work runs on [Dispatchers.Default] and the file write on
 * [Dispatchers.IO]. On any decode, transform, or write failure the function
 * returns [Result.failure] carrying the reason so the caller can surface an
 * error and skip compression, leaving the source unchanged (Req 8.5). Overlay
 * compositing and any filter/color effects are baked into the [source] bitmap
 * by the Photo Editor before this call (via `saveAsBitmap`); this pipeline
 * applies only the pending geometry (crop/rotate/mirror).
 *
 * @return [Result.success] with the temp [File] on success, or [Result.failure]
 *         carrying the failure reason.
 */
suspend fun applyEditsAndPersist(
    context: Context,
    source: Uri,
    edits: ImageEditModel,
): Result<File> {
    // 1..3. Decode upright, transform, and crop — all CPU-bound.
    val edited: Bitmap = withContext(Dispatchers.Default) {
        val upright = try {
            decodeFullResUpright(context, source)
        } catch (e: Exception) {
            Log.e(PIPELINE_TAG, "Full-resolution decode failed", e)
            null
        } ?: return@withContext null

        try {
            applyEdits(upright, edits)
        } catch (e: Exception) {
            Log.e(PIPELINE_TAG, "Applying edits failed", e)
            if (!upright.isRecycled) upright.recycle()
            null
        }
    } ?: return Result.failure(IllegalStateException("Could not decode the selected image"))

    // Choose the output format from the source so the intermediate keeps the
    // source's compression characteristics; fall back to lossless PNG when the
    // source type is unknown so no quality is lost before compression.
    val format = ImageFormat.fromMimeType(context.contentResolver.getType(source)) ?: ImageFormat.PNG

    // 4. Persist to a cache-dir temp file on the IO dispatcher.
    return try {
        withContext(Dispatchers.IO) {
            val temp = File.createTempFile(
                "minify_edit_",
                ".${extensionForEdit(format)}",
                context.cacheDir,
            )
            FileOutputStream(temp).use { out ->
                val ok = edited.compress(compressFormatForEdit(format), 100, out)
                check(ok) { "Bitmap.compress returned false for $format" }
            }
            Result.success(temp)
        }
    } catch (e: Exception) {
        Log.e(PIPELINE_TAG, "Writing edited temp file failed", e)
        Result.failure(e)
    } finally {
        if (!edited.isRecycled) edited.recycle()
    }
}

/**
 * Decode [source] at full resolution and apply its EXIF orientation correction,
 * returning an upright bitmap. Mirrors [PhotoCompressor.decodeUpright] but
 * performs no preview downsampling. Returns `null` if the pixels cannot be
 * decoded.
 */
private fun decodeFullResUpright(context: Context, source: Uri): Bitmap? {
    val decoded = context.contentResolver.openInputStream(source)?.use {
        BitmapFactory.decodeStream(it)
    } ?: return null

    val orientation = try {
        context.contentResolver.openInputStream(source)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        Log.w(PIPELINE_TAG, "Could not read EXIF orientation; assuming normal", e)
        ExifInterface.ORIENTATION_NORMAL
    }

    val transform = ExifOrientation.normalize(orientation)
    if (transform.isIdentity) return decoded

    // Correction matrix built as Rotation * Flip. Android's post* ops
    // pre-multiply the current matrix (postX => M' = X * M), so applying the
    // flip first and the rotation second yields Rotation * Flip, matching
    // OrientationTransform.toMatrix() and PhotoCompressor.decodeUpright.
    val matrix = Matrix()
    val sx = if (transform.flipHorizontal) -1f else 1f
    val sy = if (transform.flipVertical) -1f else 1f
    if (sx != 1f || sy != 1f) matrix.postScale(sx, sy)
    if (transform.rotationDegrees != 0) matrix.postRotate(transform.rotationDegrees.toFloat())

    return try {
        val rotated = Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, matrix, true,
        )
        if (rotated !== decoded) decoded.recycle()
        rotated
    } catch (e: Exception) {
        Log.w(PIPELINE_TAG, "EXIF orientation transform failed; using un-rotated bitmap", e)
        decoded
    }
}

/**
 * Apply the [edits]' rotation + mirror to [upright], then crop to
 * [ImageEditModel.crop]. The returned bitmap is the confirmed edited image; any
 * intermediate bitmaps (and [upright] itself when superseded) are recycled.
 */
private fun applyEdits(upright: Bitmap, edits: ImageEditModel): Bitmap {
    // Compose rotation + mirror. Following the post* pre-multiply convention,
    // the mirror (flip) is applied first and the rotation second, producing
    // Rotation * FlipH — matching ImageEditModel.orientationMatrix().
    val transformed: Bitmap = if (edits.rotationDegrees == 0 && !edits.mirrored) {
        upright
    } else {
        val matrix = Matrix()
        if (edits.mirrored) matrix.postScale(-1f, 1f)
        if (edits.rotationDegrees != 0) matrix.postRotate(edits.rotationDegrees.toFloat())
        val out = Bitmap.createBitmap(
            upright, 0, 0, upright.width, upright.height, matrix, true,
        )
        if (out !== upright) upright.recycle()
        out
    }

    // Crop against the transformed (displayed) bounds, guarding against a
    // degenerate rectangle so createBitmap always receives a valid subset.
    val rect = CropGeometry.toPixelRect(edits.crop, transformed.width, transformed.height)
    val cw = rect.width.coerceIn(1, transformed.width)
    val ch = rect.height.coerceIn(1, transformed.height)
    val left = rect.left.coerceIn(0, transformed.width - cw)
    val top = rect.top.coerceIn(0, transformed.height - ch)

    if (left == 0 && top == 0 && cw == transformed.width && ch == transformed.height) {
        return transformed
    }

    val cropped = Bitmap.createBitmap(transformed, left, top, cw, ch)
    if (cropped !== transformed) transformed.recycle()
    return cropped
}

@Suppress("DEPRECATION")
private fun compressFormatForEdit(format: ImageFormat): Bitmap.CompressFormat = when (format) {
    ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
    ImageFormat.PNG -> Bitmap.CompressFormat.PNG
    ImageFormat.WEBP ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS
        else Bitmap.CompressFormat.WEBP
}

private fun extensionForEdit(format: ImageFormat): String = when (format) {
    ImageFormat.JPEG -> "jpg"
    ImageFormat.PNG -> "png"
    ImageFormat.WEBP -> "webp"
}
