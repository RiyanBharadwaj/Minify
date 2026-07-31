package com.shanks.minify.ui.compare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.shanks.minify.ui.ComparisonSource

/** Longest edge (px) to which each comparator bitmap is down-sampled to bound memory. */
private const val COMPARE_MAX_EDGE = 2048

/**
 * Before/after still-image comparator (Requirement 10).
 *
 * Routes through the shared [CompareWipeOverlay]: the original image is the full-region bottom
 * layer and the edited image is the top layer cropped by the draggable divider, so moving the
 * divider crops only the edited layer and reveals the original beneath — identically to the video
 * comparator (Req 10.1, 10.2). The divider position is a fraction in `[0,1]` clamped by
 * [DividerOps.clampDivider] (Req 10.3).
 *
 * A single shared [ComparisonViewport] (scale + pan) is applied identically to both images via
 * [graphicsLayer] with a top-left [TransformOrigin], so a pinch zooms both by the same factor
 * about the gesture focal point (Req 10.4) and a pan moves both together (Req 10.5), keeping the
 * two images aligned to the same pixel coordinates at all times (Req 10.6). The focal-point zoom
 * and pan clamping are computed by the pure [ComparisonViewport] functions.
 */
@Composable
fun ImageComparator(
    source: ComparisonSource,
) {
    val context = LocalContext.current

    // Decode both the original ("before") and compressed ("after") stills.
    var beforeBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var afterBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(source) {
        beforeBitmap = null
        afterBitmap = null
        withContext(Dispatchers.IO) {
            when (source) {
                is ComparisonSource.Images -> {
                    beforeBitmap = loadComparatorBitmap(context, source.before)?.asImageBitmap()
                    afterBitmap = loadComparatorBitmap(context, source.after)?.asImageBitmap()
                }
                else -> {}
            }
        }
    }

    // Shared transform applied identically to both images, plus the divider.
    var viewport by remember { mutableStateOf(ComparisonViewport(scale = 1f, panX = 0f, panY = 0f)) }
    var dividerFraction by remember { mutableFloatStateOf(DividerOps.DEFAULT_DIVIDER_FRACTION) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(top = 48.dp)) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val bounds = Size(widthPx, heightPx)

        if (beforeBitmap == null || afterBitmap == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@BoxWithConstraints
        }

        // The transform lambda shared by both images so they stay pixel-aligned.
        val applyViewport: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {
            scaleX = viewport.scale
            scaleY = viewport.scale
            translationX = viewport.panX
            translationY = viewport.panY
            transformOrigin = TransformOrigin(0f, 0f)
        }

        // Shared overlay: original ("before") is the bottom layer filling the
        // region; edited ("after") is the top layer cropped by the divider,
        // revealing the original beneath. Both carry the same viewport transform
        // so the revealed pixels line up exactly.
        CompareWipeOverlay(
            dividerFraction = dividerFraction,
            onDividerFractionChange = { dividerFraction = it },
            labelBefore = "Original",
            labelAfter = "Compressed",
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bounds) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        var next = viewport
                        if (zoom != 1f) {
                            next = next.zoomAround(centroid, zoom, bounds)
                        }
                        next = next.copy(panX = next.panX + pan.x, panY = next.panY + pan.y)
                        viewport = next.clampPan(bounds)
                    }
                },
            bottom = {
                Image(
                    bitmap = beforeBitmap!!,
                    contentDescription = "Original image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(applyViewport),
                )
            },
            top = {
                Image(
                    bitmap = afterBitmap!!,
                    contentDescription = "Compressed image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(applyViewport),
                )
            },
        )
    }
}

/**
 * Decode a down-sampled bitmap for [uri] whose longest edge is roughly
 * [COMPARE_MAX_EDGE] px, so large images do not exhaust memory. Returns `null`
 * when the pixels cannot be decoded.
 */
private fun loadComparatorBitmap(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
    }
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longest > 0 && longest / sample > COMPARE_MAX_EDGE) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()
}
