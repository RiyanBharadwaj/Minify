package com.shanks.minify.ui.editor.model

/**
 * The set of continuous color/tone controls exposed by the unified editor's
 * Adjustment_Panel. Each control carries its fixed [neutral] (no-op) value and
 * its inclusive `[min, max]` bounded range.
 *
 * These bounds and neutral values are pure data so every clamping and reset
 * decision can be property-tested on the JVM. The same set and ranges apply to
 * both `Photo` and `Video` media types (Req 4.7).
 *
 * @param neutral the value at which the adjustment has no visible effect.
 * @param min     the inclusive lower bound of the adjustment's range.
 * @param max     the inclusive upper bound of the adjustment's range.
 */
enum class AdjustmentKind(val neutral: Float, val min: Float, val max: Float) {
    BRIGHTNESS(0f, -1f, 1f),
    CONTRAST(0f, -1f, 1f),
    EXPOSURE(0f, -1f, 1f),
    HIGHLIGHTS(0f, -1f, 1f),
    SHADOWS(0f, -1f, 1f),
    TEMPERATURE(0f, -1f, 1f),
    TINT(0f, -1f, 1f),
    SHARPNESS(0f, 0f, 1f),
    BLUR(0f, 0f, 1f),
    VIBRANCE(0f, -1f, 1f),
    SATURATION(0f, -1f, 1f);

    /** Clamp [raw] into this adjustment's inclusive `[min, max]` range. */
    fun clamp(raw: Float): Float = raw.coerceIn(min, max)
}

/**
 * The pure, Android-independent record of every [AdjustmentKind]'s current value
 * for the media being edited.
 *
 * Editing is non-destructive: [with] and [reset] return new [Adjustments] values
 * rather than mutating in place. Every stored value is guaranteed to lie within
 * its kind's `[min, max]` range (Req 4.2), and [reset] restores a kind's fixed
 * [AdjustmentKind.neutral] (Req 4.6).
 *
 * @param values the per-kind adjustment values; defaults to the neutral map so a
 *        fresh instance renders unchanged (Req 4.3).
 */
data class Adjustments(val values: Map<AdjustmentKind, Float> = neutralMap()) {

    /** The clamped value currently recorded for [kind]. */
    operator fun get(kind: AdjustmentKind): Float =
        values[kind] ?: kind.neutral

    /**
     * Record [raw] for [kind], clamped to the kind's `[min, max]` range (Req 4.2).
     */
    fun with(kind: AdjustmentKind, raw: Float): Adjustments =
        copy(values = values + (kind to kind.clamp(raw)))

    /** Reset [kind] to its fixed [AdjustmentKind.neutral] value (Req 4.6). */
    fun reset(kind: AdjustmentKind): Adjustments =
        copy(values = values + (kind to kind.neutral))

    /** True when every adjustment is at its neutral value, so nothing is applied. */
    val isNeutral: Boolean
        get() = AdjustmentKind.entries.all { get(it) == it.neutral }

    companion object {
        /** A fully neutral [Adjustments] value that renders the media unchanged. */
        val NEUTRAL: Adjustments = Adjustments(neutralMap())

        /** A map assigning every [AdjustmentKind] its neutral value. */
        internal fun neutralMap(): Map<AdjustmentKind, Float> =
            AdjustmentKind.entries.associateWith { it.neutral }
    }
}
