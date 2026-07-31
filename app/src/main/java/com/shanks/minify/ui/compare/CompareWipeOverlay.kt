package com.shanks.minify.ui.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The visible divider line width. The line is intentionally thin so the overlay
 * reads as one seamless image with a wipe boundary rather than two panels.
 */
private val VISIBLE_DIVIDER_WIDTH_DP: Dp = 2.dp

/**
 * The invisible draggable touch target width, centered on the visible divider
 * line. It is substantially larger than [VISIBLE_DIVIDER_WIDTH_DP] so the divider
 * can be grabbed and dragged smoothly (a finger press a few dp off the line still
 * moves the divider). Task 7.3 may further tune this value.
 */
private val TOUCH_TARGET_WIDTH_DP: Dp = 128.dp

/**
 * Shared before/after wipe overlay used identically by both the image and video
 * comparators so the two modes behave the same (design Property 1, C_parity).
 *
 * The overlay draws a single, perfectly-aligned composite of two layers within one
 * region:
 *  - [bottom] — the original ("before") media — fills the whole region.
 *  - [top] — the edited ("after") media — is drawn over [bottom] and **cropped by
 *    the divider** so only the leading region `[0, dividerFraction × width]` of the
 *    top layer is visible; the remainder reveals the original beneath.
 *
 * The crop is performed at **draw time** via [drawWithContent] + [clipRect] keyed to
 * [dividerFraction], not via a `Modifier.width(...)` layout pass. Because the top
 * slot always lays out at the full region size and only its *drawing* is clipped,
 * the top and bottom layers stay pixel-aligned and the crop updates continuously as
 * the divider moves — with no per-frame layout and no flicker (design Property 1,
 * C_flicker).
 *
 * The divider itself is a thin, non-interactive visible line
 * ([VISIBLE_DIVIDER_WIDTH_DP]) paired with a wide, transparent draggable hit area
 * ([TOUCH_TARGET_WIDTH_DP]) centered on it. Horizontal drags update the fraction via
 * [DividerOps.clampDivider], preserving the divider defaults/clamping and the
 * single-source-at-`0`/`1` behavior (design Property 5).
 *
 * At `dividerFraction == 0` the top layer is cropped to zero width so only the
 * original shows; at `dividerFraction == 1` the top layer covers the whole region so
 * only the edited version shows — exactly one source covers the whole region at each
 * extreme.
 *
 * @param dividerFraction the divider position, a fraction in `[0, 1]` (seeded from
 *   [DividerOps.DEFAULT_DIVIDER_FRACTION] and kept clamped by [DividerOps.clampDivider]).
 * @param onDividerFractionChange invoked with the new clamped fraction as the user
 *   drags the divider.
 * @param labelBefore the label for the "before" (original) source, shown when revealed.
 * @param labelAfter the label for the "after" (edited) source, shown when revealed.
 * @param bottom the original ("before") layer, drawn filling the whole region.
 * @param top the edited ("after") layer, drawn over [bottom] and cropped by the divider.
 */
@Composable
fun CompareWipeOverlay(
    dividerFraction: Float,
    onDividerFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    labelBefore: String? = null,
    labelAfter: String? = null,
    bottom: @Composable BoxScope.() -> Unit,
    top: @Composable BoxScope.() -> Unit,
) {
    // The drag handler below runs in a long-lived pointer-input coroutine keyed only on
    // `widthPx`, so it must NOT close over the `dividerFraction` parameter directly (that
    // value would be captured once and go stale, re-basing every incremental drag off the
    // initial fraction). Reading through `rememberUpdatedState` gives the coroutine the
    // latest committed fraction on each drag event so the divider accumulates and follows
    // the finger smoothly.
    val currentFraction by rememberUpdatedState(dividerFraction)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        // The pure divider math is the single source of truth: `beforeWidth` is the
        // width of the cropped TOP (edited) layer; single-source-at-extremes preserved.
        val regions = DividerOps.revealRegions(dividerFraction, widthPx)
        val fullWidthDp = maxWidth
        val dividerCenterDp = fullWidthDp * DividerOps.clampDivider(dividerFraction)

        // Bottom layer (original) fills the whole region.
        Box(modifier = Modifier.fillMaxSize()) {
            bottom()
        }

        // Top layer (edited) drawn over the bottom, laid out at the full region size
        // so it stays pixel-aligned, but cropped at draw time to the divider region.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    val clipRight = regions.beforeWidth.coerceIn(0f, size.width)
                    clipRect(right = clipRight) {
                        this@drawWithContent.drawContent()
                    }
                },
        ) {
            top()
        }

        // Thin, non-interactive visible divider line at the boundary.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = dividerCenterDp - VISIBLE_DIVIDER_WIDTH_DP / 2)
                .fillMaxHeight()
                .width(VISIBLE_DIVIDER_WIDTH_DP)
                .background(Color.White),
        )

        // Wide, transparent draggable hit area centered on the divider.
        Box(
            modifier = Modifier
                .offset(x = dividerCenterDp - TOUCH_TARGET_WIDTH_DP / 2)
                .fillMaxHeight()
                .width(TOUCH_TARGET_WIDTH_DP)
                .align(Alignment.TopStart)
                .pointerInput(widthPx) {
                    // Accumulate the divider position in a coroutine-local var seeded from
                    // the current fraction at drag start. `detectHorizontalDragGestures`
                    // delivers `dragAmount` incrementally (one small delta per pointer
                    // event), so the base must accumulate across events within the gesture
                    // rather than re-basing off a captured (and possibly not-yet-recomposed)
                    // fraction — otherwise the divider only ever reflects the last event's
                    // delta and cannot follow the finger.
                    var dragFraction = currentFraction
                    detectHorizontalDragGestures(
                        onDragStart = { dragFraction = currentFraction },
                        onHorizontalDrag = { _, dragAmount ->
                            val delta = if (widthPx > 0f) dragAmount / widthPx else 0f
                            dragFraction = DividerOps.clampDivider(dragFraction + delta)
                            onDividerFractionChange(dragFraction)
                        },
                    )
                },
        )

        // Labels for the two sides.
        if (labelAfter != null && dividerFraction > 0.05f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = labelAfter,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (labelBefore != null && dividerFraction < 0.95f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = labelBefore,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
