package com.shanks.minify.platform

/**
 * How the gallery save is performed for a given API level.
 *
 * - [LEGACY_WRITE_EXTERNAL]: write into the public media directory using
 *   `WRITE_EXTERNAL_STORAGE` (API 28).
 * - [SCOPED_MEDIASTORE]: scoped-storage MediaStore `IS_PENDING` insert/update
 *   (API 29+).
 */
enum class GalleryStrategy {
    LEGACY_WRITE_EXTERNAL,
    SCOPED_MEDIASTORE,
}

/**
 * Pure, total selector mapping an API level to the [GalleryStrategy] to use
 * (Requirement 1.4/1.5):
 * - Below API 29: [GalleryStrategy.LEGACY_WRITE_EXTERNAL].
 * - API 29 and above: [GalleryStrategy.SCOPED_MEDIASTORE].
 *
 * This function is total and never throws for any [apiLevel].
 */
object GalleryStrategySelector {

    fun select(apiLevel: Int): GalleryStrategy =
        if (apiLevel >= 29) GalleryStrategy.SCOPED_MEDIASTORE else GalleryStrategy.LEGACY_WRITE_EXTERNAL
}
