package com.shanks.minify.ui.editor.model

import androidx.compose.runtime.saveable.Saver
import com.shanks.minify.photo.ImageEditModel
import com.shanks.minify.ui.CropRect
import com.shanks.minify.ui.trim.TrimRange

/**
 * The non-destructive, Android-independent composite edit model for the unified
 * media editor (Req 2.5).
 *
 * A single [MediaEditState] records every pending edit for one Media_Item as
 * plain data, composing the reused [ImageEditModel] geometry (rotate/mirror/crop)
 * with the pure [ColorGrade] and exactly one of the type-specific sub-models:
 * a [VideoTimeline] for [MediaType.VIDEO], or [PhotoSettings] for
 * [MediaType.PHOTO]. Editing is non-destructive: every helper returns a new
 * value rather than mutating in place, mirroring [ImageEditModel] and
 * [VideoTimeline].
 *
 * Because it carries no Android dependency (only the pure geometry/color/timeline
 * models), every invariant — neutrality of [initial], crop preservation across
 * trim/split, and [Saver] round-trip — can be property-tested on the JVM.
 *
 * @param mediaType the classification of the edited item; fixes which
 *        type-specific sub-model is present (Req 1.2–1.5).
 * @param geometry  the reused rotate/mirror/crop geometry; defaults to identity.
 * @param color     the pure color/tone grade; defaults to neutral.
 * @param timeline  the video timeline edits; non-null only for [MediaType.VIDEO].
 * @param photo     the photo export settings; non-null only for [MediaType.PHOTO].
 */
data class MediaEditState(
    val mediaType: MediaType,
    val geometry: ImageEditModel = ImageEditModel(),
    val color: ColorGrade = ColorGrade(),
    val timeline: VideoTimeline? = null,
    val photo: PhotoSettings? = null,
) {
    /**
     * True when this state carries no visible edit: the [color] grade is neutral,
     * the [geometry] is the identity (rotation `0`, not mirrored, full crop), and
     * the type-specific sub-model is at its no-edit default — a [VideoTimeline]
     * whose trim starts at `0` with no split/speed/volume/mute edits (Req 2.5,
     * 6.6), or the default [PhotoSettings] for a photo.
     */
    val isInitial: Boolean
        get() {
            val identityGeometry = geometry.rotationDegrees == 0 &&
                !geometry.mirrored &&
                geometry.crop == CropRect.FULL
            val neutralTimeline = when (mediaType) {
                MediaType.VIDEO -> timeline != null &&
                    timeline.trim.startMs == 0L &&
                    timeline.isNoEdit
                MediaType.PHOTO -> true
            }
            val neutralPhoto = when (mediaType) {
                MediaType.PHOTO -> photo == null || photo == PhotoSettings()
                MediaType.VIDEO -> true
            }
            return color.isNeutral && identityGeometry && neutralTimeline && neutralPhoto
        }

    /**
     * Records a new video [trim] range while leaving every other edit — including
     * the recorded [geometry] and its crop — unchanged (Req 7.6).
     *
     * A no-op for a photo state (no [timeline] present).
     */
    fun applyTrim(trim: TrimRange): MediaEditState {
        val current = timeline ?: return this
        return copy(timeline = current.copy(trim = trim))
    }

    /**
     * Adds a split at [positionMs] via [SplitOps.addSplit] while leaving every
     * other edit — including the recorded [geometry] and its crop — unchanged
     * (Req 7.4, 7.6). The split is accepted only when it lies strictly inside the
     * kept range and is not already present; otherwise the state is unchanged.
     *
     * A no-op for a photo state (no [timeline] present).
     */
    fun addSplit(positionMs: Long): MediaEditState {
        val current = timeline ?: return this
        return copy(timeline = SplitOps.addSplit(current, positionMs))
    }

    companion object {
        /**
         * A fresh, fully neutral edit state for [type] (Req 2.5).
         *
         * The [geometry] is the identity and the [color] grade is neutral. For
         * [MediaType.VIDEO] the [timeline] spans the whole video
         * (`[0, fullDurationMs]`) with no trim/split/speed/volume/mute edits; for
         * [MediaType.PHOTO] the default [PhotoSettings] is used.
         *
         * @param type           the media classification.
         * @param fullDurationMs the video's full duration in milliseconds; used
         *        only for [MediaType.VIDEO], coerced to be non-negative.
         */
        fun initial(type: MediaType, fullDurationMs: Long = 0L): MediaEditState = when (type) {
            MediaType.VIDEO -> MediaEditState(
                mediaType = type,
                timeline = VideoTimeline(trim = TrimRange(0L, fullDurationMs.coerceAtLeast(0L))),
            )
            MediaType.PHOTO -> MediaEditState(
                mediaType = type,
                photo = PhotoSettings(),
            )
        }

        /**
         * A [Saver] that persists a [MediaEditState] across configuration changes
         * and process death via `rememberSaveable`/`SavedStateHandle` (Req 17.1).
         *
         * Serializes to a bundle-safe [ArrayList] of primitives and nested lists,
         * reusing [ImageEditModel.Saver] for the geometry and encoding the
         * [ColorGrade], [VideoTimeline], and [PhotoSettings] field-by-field so the
         * round-trip reproduces an equal state.
         */
        val Saver: Saver<MediaEditState, Any> = Saver(
            save = { state ->
                arrayListOf<Any?>(
                    state.mediaType.name,
                    with(ImageEditModel.Saver) { save(state.geometry) },
                    saveColor(state.color),
                    saveTimeline(state.timeline),
                    savePhoto(state.photo),
                )
            },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                val arr = saved as ArrayList<Any?>
                MediaEditState(
                    mediaType = MediaType.valueOf(arr[0] as String),
                    geometry = ImageEditModel.Saver.restore(arr[1] as Any)
                        ?: ImageEditModel(),
                    color = restoreColor(arr[2]),
                    timeline = restoreTimeline(arr[3]),
                    photo = restorePhoto(arr[4]),
                )
            },
        )

        // --- ColorGrade -------------------------------------------------------

        private fun saveColor(color: ColorGrade): ArrayList<Any?> {
            val adjustments = FloatArray(AdjustmentKind.entries.size) { i ->
                color.adjustments[AdjustmentKind.entries[i]]
            }
            return arrayListOf(adjustments, color.filter.name, color.vignette)
        }

        private fun restoreColor(saved: Any?): ColorGrade {
            @Suppress("UNCHECKED_CAST")
            val arr = saved as ArrayList<Any?>
            val values = arr[0] as FloatArray
            val map = AdjustmentKind.entries.associateWith { kind ->
                values[kind.ordinal]
            }
            return ColorGrade(
                adjustments = Adjustments(map),
                filter = Filter.valueOf(arr[1] as String),
                vignette = arr[2] as Float,
            )
        }

        // --- VideoTimeline ----------------------------------------------------

        private fun saveTimeline(timeline: VideoTimeline?): ArrayList<Any?>? {
            if (timeline == null) return null
            val freeze = timeline.freeze
            return arrayListOf(
                timeline.trim.startMs,
                timeline.trim.endMs,
                timeline.splits.toLongArray(),
                timeline.speed.name,
                timeline.volume,
                timeline.muted,
                timeline.reverse,
                freeze?.atMs ?: -1L,
                freeze?.holdMs ?: -1L,
                saveDeletedSections(timeline.deletedSections),
            )
        }

        private fun restoreTimeline(saved: Any?): VideoTimeline? {
            if (saved == null) return null
            @Suppress("UNCHECKED_CAST")
            val arr = saved as ArrayList<Any?>
            val freezeAt = arr[7] as Long
            val freezeHold = arr[8] as Long
            return VideoTimeline(
                trim = TrimRange(arr[0] as Long, arr[1] as Long),
                splits = (arr[2] as LongArray).toList(),
                speed = PlaybackSpeed.valueOf(arr[3] as String),
                volume = arr[4] as Float,
                muted = arr[5] as Boolean,
                reverse = arr[6] as Boolean,
                freeze = if (freezeAt < 0L) null else FreezeFrame(freezeAt, freezeHold),
                deletedSections = restoreDeletedSections(arr.getOrNull(9)),
            )
        }

        /**
         * Encodes [deletedSections] as an interleaved [LongArray]
         * `[start0, end0, start1, end1, ...]`, mirroring the primitive-array
         * encoding style used for [VideoTimeline.splits].
         */
        private fun saveDeletedSections(deletedSections: List<Segment>): LongArray {
            val encoded = LongArray(deletedSections.size * 2)
            deletedSections.forEachIndexed { index, segment ->
                encoded[index * 2] = segment.startMs
                encoded[index * 2 + 1] = segment.endMs
            }
            return encoded
        }

        /**
         * Restores the interleaved [LongArray] `[start0, end0, ...]` written by
         * [saveDeletedSections] back into a `List<Segment>`. Tolerates a missing
         * value (older persisted payloads without the field) by returning an empty
         * list.
         */
        private fun restoreDeletedSections(saved: Any?): List<Segment> {
            val encoded = saved as? LongArray ?: return emptyList()
            return (0 until encoded.size / 2).map { i ->
                Segment(startMs = encoded[i * 2], endMs = encoded[i * 2 + 1])
            }
        }

        // --- PhotoSettings ----------------------------------------------------

        private fun savePhoto(photo: PhotoSettings?): ArrayList<Any?>? {
            if (photo == null) return null
            val resize = photo.resize
            return arrayListOf(
                photo.quality.name,
                resize?.width ?: -1,
                resize?.height ?: -1,
            )
        }

        private fun restorePhoto(saved: Any?): PhotoSettings? {
            if (saved == null) return null
            @Suppress("UNCHECKED_CAST")
            val arr = saved as ArrayList<Any?>
            val width = arr[1] as Int
            val height = arr[2] as Int
            return PhotoSettings(
                quality = ExportQuality.valueOf(arr[0] as String),
                resize = if (width <= 0 || height <= 0) null else OutputSize(width, height),
            )
        }
    }
}
