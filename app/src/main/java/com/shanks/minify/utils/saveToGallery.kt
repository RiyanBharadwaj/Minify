package com.shanks.minify.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

fun saveToGallery(context: Context, file: File): Uri {
    // Remove previous Minify exports so Movies/Minify doesn't accumulate old files
    val where = "${MediaStore.Video.Media.RELATIVE_PATH} = ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
    val args = arrayOf(Environment.DIRECTORY_MOVIES + "/Minify/", "Minify_%")
    context.contentResolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, where, args)

    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "Minify_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Minify")
    }

    val uri = context.contentResolver.insert(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        values
    ) ?: throw IllegalStateException("MediaStore insert failed — storage permission may be missing")

    return try {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        uri
    } catch (e: Exception) {
        context.contentResolver.delete(uri, null, null)
        throw e
    }
}