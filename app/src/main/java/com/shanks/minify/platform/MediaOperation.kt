package com.shanks.minify.platform

/**
 * The media-access operations the app performs. Used by [PermissionPolicy] to
 * decide which runtime permissions must be granted for a given operation on a
 * given API level.
 */
enum class MediaOperation {
    SAVE_VIDEO,
    SAVE_IMAGE,
    READ_VIDEO,
    READ_IMAGE,
}
