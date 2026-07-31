package com.shanks.minify.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanks.minify.photo.PhotoCompressionMonitor
import com.shanks.minify.photo.PhotoCompressor
import com.shanks.minify.photo.PhotoFailure
import com.shanks.minify.photo.PhotoResult
import com.shanks.minify.editor.PhotoEditorHost
import com.shanks.minify.platform.MediaOperation
import com.shanks.minify.ui.nav.PhotoTabState
import com.shanks.minify.ui.NativeAdView
import com.shanks.minify.utils.SaveKind
import com.shanks.minify.utils.saveToGallery
import com.shanks.minify.ui.theme.BgDark
import com.shanks.minify.ui.theme.ErrorRed
import com.shanks.minify.ui.theme.GreenOk
import com.shanks.minify.ui.theme.Surface1
import com.shanks.minify.ui.theme.Surface2
import com.shanks.minify.ui.theme.TextPrim
import com.shanks.minify.ui.theme.TextSec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 1 MB = 1,048,576 bytes, per Req 8.2/8.6. */
private const val BYTES_PER_MB = 1_048_576.0

/** Longest edge (px) to which the preview bitmap is down-sampled to avoid OOM. */
private const val PREVIEW_MAX_EDGE = 1280

/** Render [bytes] as a megabyte string, e.g. "2.34 MB" (Req 8.1, 8.6). */
private fun formatMb(bytes: Long): String = "%.2f MB".format(bytes / BYTES_PER_MB)

/**
 * The Photo tab: pick an image, choose a target size in MB, run
 * [PhotoCompressor.compress], and show the before/after sizes (Requirement 8).
 *
 * All user-entered values (selected image, target size, last result) live in
 * the hoisted [PhotoTabState] so they survive tab switches within a session
 * (Req 3.6). The preview bitmap and source size are derived from
 * [PhotoTabState.selectedUri] and re-loaded whenever it changes, so they need
 * not be hoisted themselves.
 */
@Composable
fun PhotoTab(
    photoState: PhotoTabState,
    modifier: Modifier = Modifier,
    onFullscreenChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Live compression state from the shared monitor (sizes surfaced in MB).
    val isMonitorCompressing by PhotoCompressionMonitor.isCompressing.collectAsState()
    val monitorBeforeBytes by PhotoCompressionMonitor.beforeSizeBytes.collectAsState()

    // The running job drives the start/cancel affordance and is robust to
    // cancellation (which never reaches the monitor's onComplete/onFailure).
    var job by remember { mutableStateOf<Job?>(null) }
    var isRunning by remember { mutableStateOf(false) }

    // Full-screen Photo Editor overlay toggle (Req 3.1). The editor is layered
    // over the tab body like VideoEditorScreen is over the Video tab.
    var showEditor by remember { mutableStateOf(false) }

    // Tell the navigator to hide the tab bar while the editor is open full-screen.
    val editorFullscreen = showEditor && photoState.selectedUri != null
    LaunchedEffect(editorFullscreen) { onFullscreenChange(editorFullscreen) }
    DisposableEffect(Unit) { onDispose { onFullscreenChange(false) } }

    // Full-screen before/after Comparison overlay toggle (Req 10.1). Opened from
    // the success result card once a compressed output is available.
    var showComparison by remember { mutableStateOf(false) }

    // Preview bitmap + source size derived from the selected URI.
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var originalBytes by remember { mutableStateOf(0L) }

    LaunchedEffect(photoState.selectedUri) {
        val uri = photoState.selectedUri
        if (uri == null) {
            preview = null
            originalBytes = 0L
        } else {
            val loaded = withContext(Dispatchers.IO) {
                readSourceSize(context, uri) to loadPreviewBitmap(context, uri)
            }
            originalBytes = loaded.first
            preview = loaded.second?.asImageBitmap()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            photoState.selectedUri = uri
            // A new selection invalidates any previously shown result.
            photoState.lastResult = null
            PhotoCompressionMonitor.resetStatus()
        }
    }

    // Request the READ_IMAGE permission set (per API level) before opening the
    // picker; SAVE permissions are handled inside PhotoCompressor (Req 1.7/1.8).
    val runPermissioned = rememberMediaPermissionRunner(onDenied = { name ->
        Toast.makeText(
            context,
            "$name is required to select an image. Operation cancelled.",
            Toast.LENGTH_LONG,
        ).show()
    })

    val targetMb: Float? = photoState.targetSizeMb
    // Enabled whenever an image is selected: a target size compresses to that
    // size, and no target simply saves the image as-is (Req 4.1 / save-only).
    val canStart = !isRunning && photoState.selectedUri != null
    val willCompress = targetMb != null && targetMb > 0f

    Box(modifier = modifier.fillMaxSize().background(BgDark)) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Header ─────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Photo",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrim,
            )
            Text(
                "Compress and edit your images",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSec,
            )
        }

        // ── Image selection + preview ──────────────────────────────────────
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Image",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSec,
                )

                val bmp = preview
                val editUri = photoState.selectedUri
                if (bmp != null && editUri != null) {
                    // Static preview of the selected image. Editing (filters,
                    // geometry, etc.) is done in the full-screen PhotoEditorHost,
                    // so the tab only needs to show the current source.
                    Image(
                        bitmap = bmp,
                        contentDescription = "Selected image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f))
                            .heightIn(max = 260.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    // Current file size in MB (Req 8.1), shown as a subtle pill.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "Current size · ${formatMb(originalBytes)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Surface2)
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(16.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🖼", style = MaterialTheme.typography.headlineMedium)
                            Text(
                                "No image selected",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSec,
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        runPermissioned(MediaOperation.READ_IMAGE) {
                            imagePicker.launch("image/*")
                        }
                    },
                    enabled = !isRunning,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (preview == null) "Select image" else "Change image")
                }

                // Open the Photo Editor for the selected image (Req 3.1). Enabled
                // only once an image is selected and no compression is running.
                if (photoState.selectedUri != null) {
                    Button(
                        onClick = { if (!isRunning) showEditor = true },
                        enabled = !isRunning,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Edit", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // ── Target size picker ─────────────────────────────────────────────
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Target Size",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSec,
                )
                Text(
                    "Leave empty to save without compressing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSec.copy(alpha = 0.8f),
                )

                var targetText by remember {
                    mutableStateOf(photoState.targetSizeMb?.let { formatTarget(it) } ?: "")
                }
                val accent = MaterialTheme.colorScheme.primary
                val parsed = targetText.toFloatOrNull()
                val targetError = targetText.isNotBlank() && (parsed == null || parsed <= 0f)

                OutlinedTextField(
                    value = targetText,
                    onValueChange = { raw ->
                        targetText = raw
                        val value = raw.toFloatOrNull()
                        photoState.targetSizeMb = if (value != null && value > 0f) value else null
                    },
                    label = { Text("Target size (MB)") },
                    isError = targetError,
                    supportingText = if (targetError) {
                        { Text("Enter a size greater than 0") }
                    } else null,
                    singleLine = true,
                    enabled = !isRunning,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        focusedLabelColor = accent,
                        cursorColor = accent,
                        unfocusedBorderColor = TextSec.copy(alpha = 0.4f),
                        unfocusedLabelColor = TextSec,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── Start / cancel control ─────────────────────────────────────────
        if (isRunning) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Compressing ${formatMb(monitorBeforeBytes)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSec,
                )
                Button(
                    onClick = {
                        job?.cancel()
                        job = null
                        isRunning = false
                        PhotoCompressionMonitor.resetStatus()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ErrorRed,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Cancel", modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        } else {
            Button(
                onClick = {
                    val uri = photoState.selectedUri ?: return@Button
                    val target = photoState.targetSizeMb
                    if (target != null && target > 0f) {
                        // Compress the selected/edited image to the target size;
                        // PhotoCompressor saves the result to the gallery (Req 4.1).
                        isRunning = true
                        photoState.lastResult = null
                        job = scope.launch {
                            try {
                                photoState.lastResult =
                                    PhotoCompressor.compress(context, uri, target)
                            } finally {
                                isRunning = false
                                job = null
                            }
                        }
                    } else {
                        // No target size: save the selected/edited image as-is,
                        // without compressing (permission-gated on API <= 28).
                        runPermissioned(MediaOperation.SAVE_IMAGE) {
                            isRunning = true
                            photoState.lastResult = null
                            job = scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val file = resolveToImageFile(context, uri)
                                        saveToGallery(context, file, SaveKind.IMAGE)
                                    }
                                    Toast.makeText(context, "Saved to gallery", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        "Couldn't save the image: ${e.message}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } finally {
                                    isRunning = false
                                    job = null
                                }
                            }
                        }
                    }
                },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(16.dp),
                // Transparent container; the gradient is drawn on the inner Box so
                // the button reads as a single vivid accent action.
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                ),
            ) {
                val gradientStart = MaterialTheme.colorScheme.primary
                val gradientEnd = MaterialTheme.colorScheme.secondary
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .background(
                            brush = if (canStart) {
                                Brush.horizontalGradient(listOf(gradientStart, gradientEnd))
                            } else {
                                Brush.horizontalGradient(
                                    listOf(
                                        gradientStart.copy(alpha = 0.35f),
                                        gradientEnd.copy(alpha = 0.35f),
                                    )
                                )
                            },
                            shape = RoundedCornerShape(16.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (willCompress) "Compress" else "Save",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = if (canStart) 1f else 0.7f),
                    )
                }
            }
        }

        // ── Result / failure ───────────────────────────────────────────────
        when (val result = photoState.lastResult) {
            is PhotoResult.Success -> ResultCard(
                originalBytes = result.originalBytes,
                compressedBytes = result.compressedBytes,
                // A Compare affordance is offered only when we still have the
                // original selection and the saved compressed output (Req 10.1).
                canCompare = photoState.selectedUri != null,
                onCompare = { showComparison = true },
            )
            is PhotoResult.Failure -> FailureCard(reason = result.reason)
            null -> Unit
        }

        NativeAdView()
    }

    // ── Full-screen Photo_Editor overlay (Req 1.1, 1.2) ─────────────────────
    // PhotoEditorHost embeds the PhotoEditor library in Compose and is now the
    // SOLE entry point for editing the selected image — the legacy MediaEditorHost
    // path has been removed with no switch-back-to-legacy option (Req 1.2). On
    // On Done it hands the edited image file back via onDone; the tab adopts it as
    // the new working image (shown in the display). Compressing or saving is then a
    // separate, user-triggered step via the Compress/Save button.
    AnimatedVisibility(
        visible = showEditor && photoState.selectedUri != null,
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it },
        modifier = Modifier.fillMaxSize(),
    ) {
        val editUri = photoState.selectedUri
        if (editUri != null) {
            PhotoEditorHost(
                uri = editUri,
                onDone = { editedFile ->
                    // Adopt the edited image as the tab's working source so it
                    // loads in the Photo tab display. The user then compresses it
                    // (if a target size is set) or saves it as-is from the tab.
                    photoState.selectedUri = Uri.fromFile(editedFile)
                    photoState.lastResult = null
                    PhotoCompressionMonitor.resetStatus()
                    showEditor = false
                    Toast.makeText(context, "Edits applied", Toast.LENGTH_SHORT).show()
                },
                onError = { message ->
                    // Decode / host / render failure: surface the message; the host
                    // returns control to the Photo tab (Req 1.5, 3.5, 10.4).
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                },
                onDismiss = { showEditor = false },
            )
        }
    }

    // ── Full-screen before/after Comparison overlay (Image mode) ────────────
    // "before" is the originally selected image; "after" is the saved compressed
    // output carried by PhotoResult.Success.outputFile (Req 10.1).
    val successResult = photoState.lastResult as? PhotoResult.Success
    val beforeUri = photoState.selectedUri
    AnimatedVisibility(
        visible = showComparison && beforeUri != null && successResult != null,
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it },
        modifier = Modifier.fillMaxSize(),
    ) {
        if (beforeUri != null && successResult != null) {
            ComparisonScreen(
                source = ComparisonSource.Images(
                    before = beforeUri,
                    after = Uri.fromFile(successResult.outputFile),
                ),
                onClose = { showComparison = false },
            )
        }
    }
    } // end root Box
}

/** Success card showing the original and compressed sizes in MB (Req 8.6). */
@Composable
private fun ResultCard(
    originalBytes: Long,
    compressedBytes: Long,
    canCompare: Boolean,
    onCompare: () -> Unit,
) {
    val reduction = if (originalBytes > 0 && compressedBytes in 1 until originalBytes) {
        ((originalBytes - compressedBytes).toFloat() / originalBytes * 100f).toInt()
    } else null
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = BorderStroke(1.dp, GreenOk.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Compression complete",
                    style = MaterialTheme.typography.titleSmall,
                    color = GreenOk,
                    fontWeight = FontWeight.SemiBold,
                )
                if (reduction != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(GreenOk.copy(alpha = 0.16f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "−$reduction%",
                            style = MaterialTheme.typography.labelMedium,
                            color = GreenOk,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            SizeRow(label = "Original", value = formatMb(originalBytes))
            SizeRow(label = "Compressed", value = formatMb(compressedBytes))

            // Open the before/after Comparison screen in Image mode (Req 10.1).
            if (canCompare) {
                OutlinedButton(
                    onClick = onCompare,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                ) {
                    Text("Compare")
                }
            }
        }
    }
}

@Composable
private fun SizeRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSec)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Failure card that names the reason without touching the source (Req 8.4/8.5/8.8). */
@Composable
private fun FailureCard(reason: PhotoFailure) {
    val message = when (reason) {
        PhotoFailure.UNSUPPORTED_FORMAT -> "Unsupported image format. Please pick a JPEG, PNG, or WebP image."
        PhotoFailure.UNACHIEVABLE_TARGET -> "Target size is too small to achieve for this image."
        PhotoFailure.ENCODE_ERROR -> "Could not process this image. The original was left unchanged."
        PhotoFailure.SAVE_ERROR -> "Could not save the compressed image. The original was left unchanged."
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Compression failed",
                style = MaterialTheme.typography.titleSmall,
                color = ErrorRed,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Render a target value for the text field, dropping the decimal when integral. */
private fun formatTarget(mb: Float): String =
    if (mb == mb.toInt().toFloat()) mb.toInt().toString() else "%.1f".format(mb)

/**
 * Best-effort read of the source size in bytes for the current-size display
 * (Req 8.1), falling back to streaming when no size column is exposed.
 */
private fun readSourceSize(context: Context, uri: Uri): Long {
    runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.SIZE), null, null, null,
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                return cursor.getLong(idx)
            }
        }
    }
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            var total = 0L
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        } ?: 0L
    }.getOrDefault(0L)
}

/**
 * Decode a down-sampled preview bitmap for [uri] whose longest edge is roughly
 * [PREVIEW_MAX_EDGE] px, so large photos do not exhaust memory. Returns `null`
 * if the pixels cannot be decoded.
 */
/**
 * Resolve [uri] to a real [File] for saving. A `file://` URI (e.g. the edited
 * output produced by the Photo Editor) is used directly; any other URI (e.g. a
 * freshly picked `content://` image) is copied to a temp file in the cache with
 * an extension matching its MIME type. Throws if the source cannot be opened.
 */
private fun resolveToImageFile(context: Context, uri: Uri): File {
    if (uri.scheme == "file") {
        uri.path?.let { path ->
            val f = File(path)
            if (f.exists()) return f
        }
    }
    val ext = when (context.contentResolver.getType(uri)) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
    val tmp = File.createTempFile("minify_save_", ".$ext", context.cacheDir)
    context.contentResolver.openInputStream(uri)?.use { input ->
        tmp.outputStream().use { output -> input.copyTo(output) }
    } ?: throw IllegalStateException("Cannot open the selected image")
    return tmp
}

private fun loadPreviewBitmap(context: Context, uri: Uri): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
    }
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longest > 0 && longest / sample > PREVIEW_MAX_EDGE) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()
}
