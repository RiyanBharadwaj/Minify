package com.shanks.minify.platform

import android.Manifest

/**
 * Pure, total selector mapping an API level and a [MediaOperation] to the set of
 * runtime permissions that must be granted for that operation.
 *
 * Version matrix (Requirement 1.7):
 * - API 28: `WRITE_EXTERNAL_STORAGE` for save operations; read operations need
 *   no runtime media permission.
 * - API 29–32: scoped storage — no storage runtime permission for save; no
 *   media-read permission for the picker flow.
 * - API 33+: `READ_MEDIA_VIDEO` for video read, `READ_MEDIA_IMAGES` for image
 *   read; save operations need no runtime permission.
 *
 * This function is total and never throws for any [apiLevel] or [operation].
 */
object PermissionPolicy {

    fun requiredPermissions(apiLevel: Int, operation: MediaOperation): Set<String> = when (operation) {
        MediaOperation.SAVE_VIDEO,
        MediaOperation.SAVE_IMAGE ->
            if (apiLevel <= 28) setOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptySet()

        MediaOperation.READ_VIDEO ->
            if (apiLevel >= 33) setOf(Manifest.permission.READ_MEDIA_VIDEO) else emptySet()

        MediaOperation.READ_IMAGE ->
            if (apiLevel >= 33) setOf(Manifest.permission.READ_MEDIA_IMAGES) else emptySet()
    }
}
