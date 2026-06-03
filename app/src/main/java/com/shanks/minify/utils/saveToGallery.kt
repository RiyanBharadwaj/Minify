package com.shanks.minify.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

fun saveToGallery(context: Context, file: File): Uri {
    val resolver   = context.contentResolver
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val minifyPath = Environment.DIRECTORY_MOVIES + "/Minify/"

    // ── Cleanup: delete any orphaned IS_PENDING rows left by previous sessions ──
    // These appear as hidden/trash files in gallery apps. They accumulate when
    // the app crashed mid-write or the old delete-before-insert pattern left
    // abandoned MediaStore rows. IS_PENDING = 1 rows are our own incomplete
    // writes — safe to remove unconditionally at the start of a new save.
    resolver.delete(
        collection,
        "${MediaStore.Video.Media.RELATIVE_PATH} = ? AND ${MediaStore.Video.Media.IS_PENDING} = 1",
        arrayOf(minifyPath)
    )

    // ── Insert as pending so the row is invisible during the write ──────────────
    // IS_PENDING = 1 hides the entry from gallery apps and file managers until
    // we explicitly publish it. If the write fails or the app crashes, the OS
    // cleans up pending rows automatically — no more trash files.
    val displayName = "Minify_${System.currentTimeMillis()}.mp4"
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME,   displayName)
        put(MediaStore.Video.Media.MIME_TYPE,      "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH,  minifyPath)
        put(MediaStore.Video.Media.IS_PENDING,     1)
    }

    val uri = resolver.insert(collection, values)
        ?: throw IllegalStateException(
            "MediaStore insert failed — storage permission may be missing")

    try {
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }

        // ── Publish: make the file visible in the gallery ────────────────────
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }, null, null)

    } catch (e: Exception) {
        // Write failed — delete the pending row so it never becomes a trash file.
        resolver.delete(uri, null, null)
        throw e
    }

    return uri
}