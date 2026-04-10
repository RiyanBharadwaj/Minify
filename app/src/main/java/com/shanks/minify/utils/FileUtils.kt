package com.shanks.minify.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

fun copyUriToFile(context: Context, uri: Uri): File {
    // Fixed: preserve original extension instead of always using .mp4
    val mimeType = context.contentResolver.getType(uri)
    val extension = when {
        mimeType?.contains("mkv") == true -> "mkv"
        else -> "mp4"
    }

    // Fixed: use a stable name so repeated calls overwrite instead of accumulating files
    val file = File(context.cacheDir, "input_video.$extension")

    context.contentResolver.openInputStream(uri)?.use { input ->
        file.outputStream().use { output ->
            input.copyTo(output)
        }
    } ?: throw Exception("Failed to read file")

    return file
}

fun saveVideoToGallery(context: Context, file: File): Uri {
    val resolver = context.contentResolver

    // Fixed: derive MIME type and display name from actual file extension
    val extension = file.extension.lowercase()
    val mimeType = if (extension == "mkv") "video/x-matroska" else "video/mp4"

    val contentValues = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "Minify_${System.currentTimeMillis()}.$extension")
        put(MediaStore.Video.Media.MIME_TYPE, mimeType)
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Minify")
        // Fixed: mark as pending while writing so other apps don't see a partial file
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }

    // Fixed: throw instead of silently returning null if insert fails
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        ?: throw Exception("Failed to create MediaStore entry")

    try {
        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: throw Exception("Failed to open output stream")

        // Fixed: clear IS_PENDING so the file becomes visible to other apps
        val updateValues = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        resolver.update(uri, updateValues, null, null)
    } catch (e: Exception) {
        // Clean up the broken MediaStore entry if write fails
        resolver.delete(uri, null, null)
        throw e
    }

    return uri
}