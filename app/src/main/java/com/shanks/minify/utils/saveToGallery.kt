package com.shanks.minify.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

private const val TAG = "MinifyTrash"

fun saveToGallery(context: Context, file: File): Uri {
    val resolver   = context.contentResolver
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val minifyPath = Environment.DIRECTORY_MOVIES + "/Minify/"

    val displayName = "Minify_${System.currentTimeMillis()}_${(1000..9999).random()}.mp4"

    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME,  displayName)
        put(MediaStore.Video.Media.MIME_TYPE,     "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, minifyPath)
        put(MediaStore.Video.Media.IS_PENDING,    1)
    }

    val uri = resolver.insert(collection, values)
        ?: throw IllegalStateException(
            "MediaStore insert failed — storage permission may be missing")

    try {
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }, null, null)
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }

    cleanupMinifyTrash(context)
    return uri
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
    val hasFullAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
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