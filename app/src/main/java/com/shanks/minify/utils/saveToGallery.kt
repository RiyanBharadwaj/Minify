package com.shanks.minify.utils

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import com.shanks.minify.platform.GalleryStrategy
import com.shanks.minify.platform.GalleryStrategySelector
import java.io.File

private const val TAG = "MinifyTrash"

/** What kind of media is being persisted to the gallery. */
enum class SaveKind { VIDEO, IMAGE }

/**
 * Persists [file] into the device gallery, selecting the correct strategy for
 * the running API level via [GalleryStrategySelector].
 *
 * - [GalleryStrategy.SCOPED_MEDIASTORE] (API 29+): MediaStore `IS_PENDING`
 *   insert/update flow, targeting `MediaStore.Video` or `MediaStore.Images`
 *   per [kind].
 * - [GalleryStrategy.LEGACY_WRITE_EXTERNAL] (API 28): write into the public
 *   `Movies/Minify` (video) or `Pictures/Minify` (image) directory and register
 *   the file with MediaStore / trigger a media scan so it appears in the gallery.
 *
 * Returns the content [Uri] of the saved media on success. Throws on failure
 * (after deleting any partial row/file) so the caller can surface an error and
 * retain the source (Req 1.4, 1.5, 1.6).
 */
fun saveToGallery(context: Context, file: File, kind: SaveKind): Uri {
    val uri = when (GalleryStrategySelector.select(Build.VERSION.SDK_INT)) {
        GalleryStrategy.SCOPED_MEDIASTORE -> saveScoped(context, file, kind)
        GalleryStrategy.LEGACY_WRITE_EXTERNAL -> saveLegacy(context, file, kind)
    }
    cleanupMinifyTrash(context)
    return uri
}

/** Per-[SaveKind] MediaStore + directory + file-naming details. */
private data class KindSpec(
    val collection: Uri,
    val relativePath: String,
    val publicDir: File,
    val mimeType: String,
    val extension: String,
)

private fun specFor(kind: SaveKind): KindSpec = when (kind) {
    SaveKind.VIDEO -> KindSpec(
        collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        relativePath = Environment.DIRECTORY_MOVIES + "/Minify/",
        publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "Minify",
        ),
        mimeType = "video/mp4",
        extension = "mp4",
    )

    SaveKind.IMAGE -> KindSpec(
        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        relativePath = Environment.DIRECTORY_PICTURES + "/Minify/",
        publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Minify",
        ),
        mimeType = "image/jpeg",
        extension = "jpg",
    )
}

private fun displayNameFor(spec: KindSpec): String =
    "Minify_${System.currentTimeMillis()}_${(1000..9999).random()}.${spec.extension}"

/**
 * Scoped-storage save (API 29+). Uses the `IS_PENDING` insert/update flow so the
 * media is not visible to other apps until the copy completes.
 */
private fun saveScoped(context: Context, file: File, kind: SaveKind): Uri {
    val spec = specFor(kind)
    val resolver = context.contentResolver

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayNameFor(spec))
        put(MediaStore.MediaColumns.MIME_TYPE, spec.mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, spec.relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    val uri = resolver.insert(spec.collection, values)
        ?: throw IllegalStateException(
            "MediaStore insert failed — storage permission may be missing",
        )

    try {
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("Could not open output stream for $uri")

        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }

    return uri
}

/**
 * Legacy save (API 28). Writes the file into the public Minify directory and
 * registers it with MediaStore so it appears in the gallery. `IS_PENDING` is
 * unsupported pre-29, so it is not used.
 */
private fun saveLegacy(context: Context, file: File, kind: SaveKind): Uri {
    val spec = specFor(kind)

    if (!spec.publicDir.exists() && !spec.publicDir.mkdirs()) {
        throw IllegalStateException("Could not create ${spec.publicDir.absolutePath}")
    }

    val target = File(spec.publicDir, displayNameFor(spec))

    try {
        file.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    } catch (e: Exception) {
        target.delete()
        throw e
    }

    // Register with MediaStore via a synchronous scan so the gallery sees it.
    val scanned = scanFile(context, target, spec.mimeType)
    if (scanned != null) return scanned

    // Fallback: insert a MediaStore row pointing at the file path.
    return try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, target.name)
            put(MediaStore.MediaColumns.MIME_TYPE, spec.mimeType)
            @Suppress("DEPRECATION")
            put(MediaStore.MediaColumns.DATA, target.absolutePath)
        }
        context.contentResolver.insert(spec.collection, values)
            ?: target.toUri()
    } catch (e: Exception) {
        target.delete()
        throw e
    }
}

/**
 * Runs a synchronous media scan for [file] and returns the resulting content
 * [Uri], or null if the scan produced none within the timeout.
 */
private fun scanFile(context: Context, file: File, mimeType: String): Uri? {
    val result = arrayOfNulls<Uri>(1)
    val latch = java.util.concurrent.CountDownLatch(1)
    MediaScannerConnection.scanFile(
        context,
        arrayOf(file.absolutePath),
        arrayOf(mimeType),
    ) { _, uri ->
        result[0] = uri
        latch.countDown()
    }
    return try {
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        result[0]
    } catch (_: InterruptedException) {
        null
    }
}

/**
 * Deletes .trashed-* Minify files from Movies/Minify.
 *
 * Requires MANAGE_EXTERNAL_STORAGE (Android 11+) for File.listFiles() to see
 * files created by other apps (the system trash mechanism). Without it, scoped
 * storage hides those files from the filesystem scan entirely. The permission
 * is requested in MainActivity on first launch.
 *
 * Also sweeps MediaStore for IS_PENDING and IS_TRASHED rows as a fallback.
 */
fun cleanupMinifyTrash(context: Context) {
    val minifyDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
        "Minify"
    )

    // ── 1. Filesystem scan (requires MANAGE_EXTERNAL_STORAGE on API 30+) ─────
    val hasFullAccess = (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) ||
            Environment.isExternalStorageManager()

    if (hasFullAccess && minifyDir.exists() && minifyDir.isDirectory) {
        val allFiles = minifyDir.listFiles()
        if (allFiles != null) {
            Log.d(TAG, "Scanning ${allFiles.size} files in Movies/Minify")
            for (f in allFiles) {
                if (!f.isFile || !isTrashFile(f.name)) continue
                Log.d(TAG, "Deleting trash file: ${f.name}")
                val deleted = try { f.delete() } catch (_: Exception) { false }
                Log.d(TAG, "  deleted=$deleted  canWrite=${f.canWrite()}")
                if (deleted) {
                    // Remove stale MediaStore row
                    try {
                        context.contentResolver.delete(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            "${MediaStore.Video.Media.DATA} = ?",
                            arrayOf(f.absolutePath)
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    } else if (!hasFullAccess) {
        Log.w(TAG, "MANAGE_EXTERNAL_STORAGE not granted — filesystem scan skipped. " +
                "Trash files won't be visible until permission is granted.")
    }

    // ── 2. MediaStore IS_PENDING sweep ────────────────────────────────────────
    try {
        val rows = context.contentResolver.delete(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Video.Media.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.Video.Media.IS_PENDING} = 1",
            arrayOf(Environment.DIRECTORY_MOVIES + "/Minify/")
        )
        if (rows > 0) Log.d(TAG, "IS_PENDING sweep deleted $rows rows")
    } catch (_: Exception) {}

    // ── 3. MediaStore IS_TRASHED sweep ────────────────────────────────────────
    try {
        val rows = context.contentResolver.delete(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Video.Media.IS_TRASHED} = 1 AND " +
                    "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("%Minify_%")
        )
        if (rows > 0) Log.d(TAG, "IS_TRASHED sweep deleted $rows rows")
    } catch (_: Exception) {}
}

fun cleanupMinifyTrashOnStartup(context: Context) = cleanupMinifyTrash(context)

private fun isTrashFile(name: String): Boolean =
    (name.startsWith(".trashed-") && name.endsWith(".mp4"))
            || (name.startsWith(".trash") && name.endsWith(".mp4"))
            || (name.startsWith(".") && name.endsWith(".mp4"))
