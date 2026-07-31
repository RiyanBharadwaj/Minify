package com.shanks.minify.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap

/**
 * Renders the application's adaptive launcher icon.
 *
 * The launcher icon (`@mipmap/ic_launcher`) is an **adaptive icon** — an XML
 * `<adaptive-icon>` with foreground/background layers — which
 * `painterResource` cannot load (it only supports VectorDrawables and raster
 * assets and throws otherwise). Instead we resolve the composite launcher icon
 * via the package manager and rasterize it to an [ImageBitmap] once, so the
 * adaptive icon's own background layer keeps it fully visible in both dark and
 * light mode without any `tint`.
 */
@Composable
fun AppIconImage(
    modifier: Modifier = Modifier,
    contentDescription: String = "Minify app icon",
) {
    val context = LocalContext.current
    val iconBitmap: ImageBitmap = remember(context) {
        context.packageManager
            .getApplicationIcon(context.packageName)
            .toBitmap()
            .asImageBitmap()
    }
    Image(
        bitmap = iconBitmap,
        contentDescription = contentDescription,
        modifier = modifier,
    )
}
