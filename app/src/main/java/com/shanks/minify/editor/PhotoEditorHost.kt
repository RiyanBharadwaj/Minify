package com.shanks.minify.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import com.shanks.minify.photo.EDITOR_MAX_DECODE_PIXELS
import com.shanks.minify.photo.decodeUprightCapped
import com.shanks.minify.ui.AspectRatioChips
import com.shanks.minify.ui.AspectRatioPreset
import com.shanks.minify.ui.CropOverlay
import com.shanks.minify.ui.CropRect
import com.shanks.minify.ui.compare.CompareWipeOverlay
import com.shanks.minify.ui.compare.DividerOps
import ja.burhanrashid52.photoeditor.OnPhotoEditorListener
import ja.burhanrashid52.photoeditor.PhotoEditor
import ja.burhanrashid52.photoeditor.PhotoEditorView
import ja.burhanrashid52.photoeditor.PhotoFilter
import ja.burhanrashid52.photoeditor.ViewType
import ja.burhanrashid52.photoeditor.shape.ShapeBuilder
import ja.burhanrashid52.photoeditor.shape.ShapeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

private const val TAG = "PhotoEditorHost"

/** How long a single, stationary finger must stay down before the
 *  "press-and-hold to view original" peek engages. Long enough to feel
 *  deliberate and to stay out of the way of tap-select / drag / pinch. */
private const val HOLD_TO_COMPARE_DELAY_MS = 450L

/** Which expandable tool panel is currently open. */
internal enum class ToolPanel { NONE, BRUSH, ERASER, FILTER, EMOJI }

// ============================================================================
// Pure error-edge model (Android-free) — tested by PhotoEditorHostErrorEdgesTest.
// ============================================================================

/** The distinct failure edges of the photo host lifecycle. */
internal enum class PhotoHostFailure { DECODE, HOSTING, APPLY }

/** Descriptive, user-facing messages for each photo host failure edge. */
internal object PhotoHostMessages {
    /** Req 1.5: the selected image cannot be loaded. */
    const val DECODE = "Couldn't open the selected image."
    /** Req 10.4: the PhotoEditorView cannot be hosted in Compose. */
    const val HOSTING = "Couldn't open the photo editor."
    /** Req 3.5: applying the pending edits to the full-resolution source failed. */
    const val APPLY = "Couldn't apply your edits. Please try again."
}

/**
 * The host's reaction to a failure edge, independent of Compose/Android.
 */
internal data class PhotoHostReaction(
    val message: String,
    val dismiss: Boolean,
    val launchesActivity: Boolean,
    val producesEditedOutput: Boolean,
)

/**
 * The pure decision behind the photo host's error handling.
 */
internal fun photoHostReaction(failure: PhotoHostFailure): PhotoHostReaction = when (failure) {
    PhotoHostFailure.DECODE -> PhotoHostReaction(
        message = PhotoHostMessages.DECODE,
        dismiss = true,
        launchesActivity = false,
        producesEditedOutput = false,
    )
    PhotoHostFailure.HOSTING -> PhotoHostReaction(
        message = PhotoHostMessages.HOSTING,
        dismiss = false,
        launchesActivity = false,
        producesEditedOutput = false,
    )
    PhotoHostFailure.APPLY -> PhotoHostReaction(
        message = PhotoHostMessages.APPLY,
        dismiss = false,
        launchesActivity = false,
        producesEditedOutput = false,
    )
}

/**
 * Applies the host's [PhotoEditor.Builder] configuration.
 */
internal fun configureHostPhotoEditorBuilder(
    builder: PhotoEditor.Builder,
    deleteView: View,
): PhotoEditor.Builder =
    builder
        .setDeleteView(deleteView)
        .setPinchTextScalable(false)

/**
 * Compose seam that embeds the View-based PhotoEditor library
 * ([PhotoEditorView] + [PhotoEditor]) inside Minify's Compose UI.
 */
@Composable
fun PhotoEditorHost(
    uri: Uri,
    onDone: (editedFile: File) -> Unit,
    onError: (message: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val currentOnError by rememberUpdatedState(onError)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnDone by rememberUpdatedState(onDone)

    val scope = rememberCoroutineScope()

    var processing by remember(uri) { mutableStateOf(false) }
    var doneError by remember(uri) { mutableStateOf<String?>(null) }
    var showDiscardConfirm by remember(uri) { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(doneError) {
        doneError?.let { message ->
            snackbarHostState.showSnackbar(message)
            doneError = null
        }
    }

    var displayBitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var originalBitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var cropBaseBitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var committedCrop by remember(uri) { mutableStateOf(CropRect.FULL) }
    var currentRotation by remember(uri) { mutableIntStateOf(0) }
    var currentMirrored by remember(uri) { mutableStateOf(false) }

    var compareMode by remember(uri) { mutableStateOf(false) }
    var compareEdited by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var compareDividerFraction by remember(uri) { mutableFloatStateOf(DividerOps.DEFAULT_DIVIDER_FRACTION) }

    var isHoldingToCompare by remember(uri) { mutableStateOf(false) }
    var activePanel by remember(uri) { mutableStateOf(ToolPanel.NONE) }
    var decodeSettled by remember(uri) { mutableStateOf(false) }
    var photoEditor by remember(uri) { mutableStateOf<PhotoEditor?>(null) }
    var photoEditorView by remember(uri) { mutableStateOf<PhotoEditorView?>(null) }
    var currentFilter by remember(uri) { mutableStateOf<PhotoFilter?>(null) }

    var cropMode by remember(uri) { mutableStateOf(false) }
    var arPreset by remember(uri) { mutableStateOf(AspectRatioPreset.FREE) }
    var pendingCrop by remember(uri) { mutableStateOf(CropRect.FULL) }

    var adjustMode by remember(uri) { mutableStateOf(false) }
    var adjustValues by remember(uri) { mutableStateOf(AdjustValues()) }
    var adjustLiveValues by remember(uri) { mutableStateOf(AdjustValues()) }
    var adjustBaseline by remember(uri) { mutableStateOf<Bitmap?>(null) }

    val undoStack = remember(uri) { mutableStateListOf<PhotoEditOp>() }
    val redoStack = remember(uri) { mutableStateListOf<PhotoEditOp>() }
    val suppressOverlayLog = remember(uri) { booleanArrayOf(false) }

    val undoAvailable = undoStack.isNotEmpty()
    val redoAvailable = redoStack.isNotEmpty()

    val sourceBaseLp = remember(uri) { arrayOfNulls<android.view.ViewGroup.LayoutParams>(1) }
    val sourceBaseSize = remember(uri) { intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE) }

    fun reassertSourceScaling() {
        val iv = photoEditorView?.source ?: return
        iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
        sourceBaseLp[0]?.let { base ->
            base.width = sourceBaseSize[0]
            base.height = sourceBaseSize[1]
            iv.layoutParams = base
        }
        iv.requestLayout()
    }

    fun showInSource(bmp: Bitmap?) {
        val v = photoEditorView ?: return
        bmp?.let { v.source.setImageBitmap(it) }
        currentFilter?.let {
            photoEditor?.setFilterEffect(it)
            reassertSourceScaling()
        }
    }

    fun withAdjustCrop(cb: Bitmap, av: AdjustValues, cc: CropRect): Bitmap {
        val adjusted = if (av.isIdentity) cb else applyPhotoAdjust(cb, av)
        return cropBitmap(adjusted, cc)
    }

    fun dumpSource(tag: String, bmp: Bitmap?) {
        val iv = photoEditorView?.source ?: return
        iv.post {
            Log.d(TAG, "$tag measured=${iv.measuredWidth}x${iv.measuredHeight} " +
                    "lp=${iv.layoutParams.width}x${iv.layoutParams.height} " +
                    "scale=${iv.scaleType} bmp=${bmp?.width}x${bmp?.height} dens=${bmp?.density}")
        }
    }

    val loadBase: (PhotoBaseState) -> Unit = { s ->
        val previousFilter = currentFilter
        displayBitmap = s.bitmap
        currentFilter = s.filter
        currentRotation = s.rotation
        currentMirrored = s.mirrored
        cropBaseBitmap = s.cropBase
        committedCrop = s.crop
        adjustValues = s.adjust
        photoEditorView?.source?.setImageBitmap(s.bitmap)
        when {
            s.filter != null -> photoEditor?.setFilterEffect(s.filter)
            previousFilter != null -> photoEditor?.setFilterEffect(PhotoFilter.NONE)
        }
        reassertSourceScaling()
        dumpSource("afterEdit", s.bitmap)
    }

    val commitBase: (Bitmap, PhotoFilter?, Int, Boolean, Bitmap, CropRect, AdjustValues) -> Unit =
        { nb, nf, rot, mir, ncb, ncr, nAdjust ->
            val cur = displayBitmap
            val curCb = cropBaseBitmap
            if (cur != null && curCb != null) {
                undoStack.add(PhotoEditOp.Base(
                    PhotoBaseState(cur, currentFilter, currentRotation, currentMirrored, curCb, committedCrop, adjustValues),
                    PhotoBaseState(nb, nf, rot, mir, ncb, ncr, nAdjust)
                ))
                redoStack.clear()
                loadBase(PhotoBaseState(nb, nf, rot, mir, ncb, ncr, nAdjust))
            }
        }

    // The layer the user currently has selected (null = nothing selected / bar hidden).
    var selectedView by remember(uri) { mutableStateOf<View?>(null) }
    var selectedMeta by remember(uri) { mutableStateOf<LayerMeta?>(null) }
    var showLayerStyle by remember(uri) { mutableStateOf(false) }

    // True while the user is actively dragging the selected layer via the library's
    // MultiTouchListener. The LayerControls bar is hidden for the duration so it never
    // covers a layer being repositioned (e.g. a sticker placed at the bottom of a tall
    // image). Restored automatically when the gesture ends.
    var layerDragging by remember(uri) { mutableStateOf(false) }

    // Rich style picked in the Add-text dialog that we still have to push onto the
    // view the library creates (its addText only carries a color, nothing else).
    var pendingTextMeta by remember(uri) { mutableStateOf<LayerMeta?>(null) }

    // Rich style for a pending emoji that we still have to push onto the view the
    // library creates (mirrors pendingTextMeta for the emoji path).
    var pendingEmojiMeta by remember(uri) { mutableStateOf<LayerMeta?>(null) }

    val deselect: () -> Unit = {
        selectedView = null
        selectedMeta = null
        showLayerStyle = false
        layerDragging = false
    }

    val layerEditing = selectedMeta != null &&
            selectedView?.isAttachedToWindow != false &&
            !cropMode && !adjustMode && !compareMode &&
            activePanel == ToolPanel.NONE && !processing &&
            !layerDragging

    val styleSheetOpen = layerEditing && showLayerStyle

    fun setMeta(nm: LayerMeta) {
        selectedMeta = nm
        val v = selectedView
        if (v != null) {
            applyMeta(context, v, nm, preview = false)
            v.setTag(com.shanks.minify.R.id.layer_meta, nm)
        }
    }

    /** Push the real [meta] onto a text layer view the library created, tag it, and select it. */
    fun adoptLayerView(wrapper: View, meta: LayerMeta) {
        applyMeta(context, wrapper, meta, preview = false)
        wrapper.setTag(com.shanks.minify.R.id.layer_meta, meta)
        pendingTextMeta = null
        selectedView = wrapper
        selectedMeta = meta
    }

    /** Push [meta] onto an emoji layer view the library created, tag it, and select
     *  it so the scale/rotation bar appears immediately. Re-selection on later taps
     *  is handled by [LayerSelectingPhotoEditorView] — we deliberately do NOT touch
     *  the view's OnTouchListener, so the library keeps drag-to-move / delete. */
    fun adoptEmojiView(wrapper: View, meta: LayerMeta) {
        applyMeta(context, wrapper, meta, preview = false) // scale + rotation
        wrapper.setTag(com.shanks.minify.R.id.layer_meta, meta)
        pendingEmojiMeta = null
        selectedView = wrapper
        selectedMeta = meta
    }

    /** The newly added, not-yet-tagged layer child (top-most wins), or null if the
     *  library hasn't parented it yet. Diff-based on purpose: it does NOT inspect
     *  view type, so it works whether the library builds an emoji from a TextView
     *  or an ImageView. The [before] snapshot is captured by the caller BEFORE the
     *  add call so the same logic works synchronously and in a posted retry. */
    fun findNewLayerChild(ev: PhotoEditorView, before: Set<View>): View? {
        for (i in ev.childCount - 1 downTo 0) {
            val c = ev.getChildAt(i)
            if (c in before) continue
            if (c.getTag(com.shanks.minify.R.id.layer_meta) != null) continue
            return c
        }
        return null
    }

    /** Use the library to add text or emoji, then capture + adopt the resulting view. */
    fun addStyledLayer(text: String, meta: LayerMeta) {
        val ed = photoEditor ?: return
        val ev = photoEditorView ?: return
        activePanel = ToolPanel.NONE

        // Snapshot existing children BEFORE the add so we can diff out the new one.
        val before = HashSet<View>(ev.childCount)
        for (i in 0 until ev.childCount) before.add(ev.getChildAt(i))

        val isText = meta.kind == LayerKind.TEXT
        if (isText) {
            pendingTextMeta = meta
            ed.addText(text, meta.color)
        } else {
            pendingEmojiMeta = meta
            ed.addEmoji(text)
        }

        fun adopt(w: View) {
            if (isText) adoptLayerView(w, meta) else adoptEmojiView(w, meta)
        }

        val wrapper = findNewLayerChild(ev, before)
        if (wrapper != null) {
            adopt(wrapper)
        } else {
            // Library parented the view on a later frame — retry with the SAME diff.
            ev.post {
                val w = findNewLayerChild(ev, before) ?: return@post
                adopt(w)
            }
        }
    }

    val performUndo: () -> Unit = {
        when (val op = undoStack.removeLastOrNull()) {
            is PhotoEditOp.Base -> { redoStack.add(op); loadBase(op.prev) }
            PhotoEditOp.Overlay -> {
                redoStack.add(op); suppressOverlayLog[0] = true
                photoEditor?.undo(); suppressOverlayLog[0] = false
            }
            null -> {}
        }
    }

    val performRedo: () -> Unit = {
        when (val op = redoStack.removeLastOrNull()) {
            is PhotoEditOp.Base -> { undoStack.add(op); loadBase(op.next) }
            PhotoEditOp.Overlay -> {
                undoStack.add(op); suppressOverlayLog[0] = true
                photoEditor?.redo(); suppressOverlayLog[0] = false
            }
            null -> {}
        }
    }

    LaunchedEffect(uri) {
        val decoded = try {
            withContext(Dispatchers.IO) { decodeUprightCapped(context, uri, EDITOR_MAX_DECODE_PIXELS) }
        } catch (e: Throwable) {
            Log.e(TAG, "Source decode threw", e)
            null
        }
        if (decoded == null) {
            val reaction = photoHostReaction(PhotoHostFailure.DECODE)
            currentOnError(reaction.message)
            if (reaction.dismiss) currentOnDismiss()
        } else {
            val prepared = ensureMinEditSize(decoded)
            displayBitmap = prepared
            originalBitmap = prepared
            cropBaseBitmap = prepared
            committedCrop = CropRect.FULL
        }
        decodeSettled = true
    }

    LaunchedEffect(compareMode) {
        compareEdited = if (compareMode) {
            compareDividerFraction = DividerOps.DEFAULT_DIVIDER_FRACTION
            try {
                photoEditor?.saveAsBitmap()
            } catch (e: Exception) {
                Log.e(TAG, "Compare: saveAsBitmap failed", e)
                displayBitmap
            }
        } else {
            null
        }
    }

    BackHandler(enabled = !processing) {
        when {
            styleSheetOpen -> showLayerStyle = false
            layerEditing -> deselect()
            else -> showDiscardConfirm = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = displayBitmap

        when {
            !decodeSettled -> {
                CircularProgressIndicator()
            }

            bitmap != null -> {
                val displayAspect = bitmap.width.toFloat() / bitmap.height.toFloat()

                LaunchedEffect(displayBitmap, photoEditorView) {
                    val v = photoEditorView ?: return@LaunchedEffect
                    displayBitmap?.let { v.source.setImageBitmap(it) }
                    currentFilter?.let {
                        photoEditor?.setFilterEffect(it)
                        reassertSourceScaling()
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                val pressed = mutableSetOf<PointerId>()
                                var downPos: Offset? = null
                                var holdJob: Job? = null
                                val slop = viewConfiguration.touchSlop

                                val gatesOpen = {
                                    !cropMode && !adjustMode && !compareMode &&
                                            activePanel == ToolPanel.NONE && originalBitmap != null &&
                                            selectedView == null
                                }

                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    when (event.type) {
                                        PointerEventType.Press -> {
                                            event.changes.forEach { pressed += it.id }
                                            if (pressed.size == 1 && gatesOpen()) {
                                                downPos = event.changes.first().position
                                                holdJob?.cancel()
                                                holdJob = scope.launch {
                                                    delay(HOLD_TO_COMPARE_DELAY_MS)
                                                    if (gatesOpen()) isHoldingToCompare = true
                                                }
                                            } else {
                                                holdJob?.cancel(); holdJob = null
                                                isHoldingToCompare = false
                                            }
                                        }
                                        PointerEventType.Move -> {
                                            val anchor = downPos
                                            if (anchor != null && event.changes.isNotEmpty()) {
                                                if ((event.changes.first().position - anchor).getDistance() > slop) {
                                                    holdJob?.cancel(); holdJob = null
                                                    downPos = null
                                                    isHoldingToCompare = false
                                                }
                                            }
                                        }
                                        PointerEventType.Release, PointerEventType.Exit -> {
                                            event.changes.forEach { pressed -= it.id }
                                            if (pressed.isEmpty()) {
                                                holdJob?.cancel(); holdJob = null
                                                downPos = null
                                                isHoldingToCompare = false
                                            }
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            try {
                                val editorView = LayerSelectingPhotoEditorView(ctx).apply {
                                    layoutParams = android.view.ViewGroup.LayoutParams(
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    setPadding(0, 0, 0, 0)
                                    source.setPadding(0, 0, 0, 0)
                                    source.scaleType = ImageView.ScaleType.CENTER_INSIDE
                                    source.setImageBitmap(bitmap)
                                }

                                // Re-select a tapped layer (emoji/text) so the scale/rotate bar reappears.
                                // Gated so a tap during crop/adjust/compare/panel never hijacks selection.
                                editorView.onLayerTouched = { v, m ->
                                    if (!cropMode && !adjustMode && !compareMode &&
                                        activePanel == ToolPanel.NONE && !processing
                                    ) {
                                        selectedView = v
                                        selectedMeta = m
                                        showLayerStyle = false
                                    }
                                }

                                val srcLp = editorView.source.layoutParams
                                sourceBaseLp[0] = srcLp
                                sourceBaseSize[0] = srcLp.width
                                sourceBaseSize[1] = srcLp.height
                                Log.d(TAG, "captured source lp type=${srcLp::class.java.simpleName} " +
                                        "w=${srcLp.width} h=${srcLp.height}")

                                val deleteView: View = ImageView(ctx).apply {
                                    visibility = View.GONE
                                }
                                editorView.addView(deleteView)

                                val built = configureHostPhotoEditorBuilder(
                                    PhotoEditor.Builder(ctx, editorView),
                                    deleteView,
                                ).build()

                                built.setOnPhotoEditorListener(
                                    HistoryListener(
                                        onTouchSource = { selectedView = null; selectedMeta = null },
                                        onTextEdited = { rv, t, c ->
                                            val pending = pendingTextMeta
                                            val existing = rv.getTag(com.shanks.minify.R.id.layer_meta) as? LayerMeta
                                            val m = pending ?: existing
                                            ?: LayerMeta(kind = LayerKind.TEXT, text = t, color = c)
                                            m.text = t
                                            m.color = c
                                            applyMeta(context, rv, m, preview = false)
                                            rv.setTag(com.shanks.minify.R.id.layer_meta, m)
                                            pendingTextMeta = null
                                            selectedView = rv
                                            selectedMeta = m
                                        },
                                        onStartMove = { layerDragging = true },   // finger down on a layer → bar hides
                                        onStopMove = { layerDragging = false },  // gesture ends → bar returns
                                    ) {
                                        if (!suppressOverlayLog[0]) {
                                            undoStack.add(PhotoEditOp.Overlay)
                                            redoStack.clear()
                                        }
                                        if (selectedView?.isAttachedToWindow == false) {
                                            selectedView = null
                                            selectedMeta = null
                                        }
                                    }
                                )

                                photoEditor = built
                                photoEditorView = editorView

                                editorView.source.post {
                                    val iv = editorView.source
                                    Log.d(TAG, "open measured=${iv.measuredWidth}x${iv.measuredHeight} " +
                                            "lp=${iv.layoutParams.width}x${iv.layoutParams.height} " +
                                            "scale=${iv.scaleType} bmp=${bitmap.width}x${bitmap.height} dens=${bitmap.density}")
                                }

                                editorView
                            } catch (e: Exception) {
                                Log.e(TAG, "Hosting PhotoEditorView failed", e)
                                val reaction = photoHostReaction(PhotoHostFailure.HOSTING)
                                currentOnError(reaction.message)
                                View(ctx)
                            }
                        },
                    )

                    // ===== Quick hold-to-compare overlay =====
                    if (isHoldingToCompare && originalBitmap != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = originalBitmap!!.asImageBitmap(),
                                contentDescription = "Original",
                                contentScale = ContentScale.Inside,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 16.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Original",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // ===== Crop overlay =====
                if (cropMode) {
                    val cb = cropBaseBitmap
                    if (cb != null) {
                        CropOverlay(
                            crop = pendingCrop,
                            lockedAspect = arPreset.ratio,
                            videoAspect = cb.width.toFloat() / cb.height.toFloat(),
                            imageWidth = cb.width,
                            imageHeight = cb.height,
                            onCropChange = { pendingCrop = it },
                            modifier = Modifier.fillMaxSize(),
                            mirrored = false,
                        )
                    }
                }

                // Live adjust preview
                LaunchedEffect(adjustMode, adjustLiveValues, photoEditorView) {
                    val v = photoEditorView ?: return@LaunchedEffect
                    if (adjustMode) {
                        val base = adjustBaseline ?: cropBaseBitmap ?: displayBitmap ?: return@LaunchedEffect
                        val preview = withContext(Dispatchers.Default) {
                            val adjusted = if (adjustLiveValues.isIdentity) base else applyPhotoAdjust(base, adjustLiveValues)
                            cropBitmap(adjusted, committedCrop)
                        }
                        v.source.setImageBitmap(preview)
                    } else {
                        displayBitmap?.let { v.source.setImageBitmap(it) }
                    }
                }

                // ===== Bottom controls =====
                val editor = photoEditor
                if (editor != null) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    ) {
                        if (layerEditing) {
                            LayerControls(
                                meta = selectedMeta!!,
                                showStyle = showLayerStyle,
                                onShowStyleChange = { showLayerStyle = it },
                                onMeta = { setMeta(it) },
                                onDeselect = { deselect() }
                            )
                        }

                        if (cropMode) {
                            CropControls(
                                selected = arPreset,
                                onSelect = { preset ->
                                    arPreset = preset
                                    val cb = cropBaseBitmap
                                    val baseAspect = cb?.let { it.width.toFloat() / it.height } ?: displayAspect
                                    preset.ratio?.let { ratio ->
                                        pendingCrop = CropRect.forAspectRatio(ratio, baseAspect)
                                    }
                                },
                                onApply = {
                                    val cb = cropBaseBitmap
                                    if (cb != null) {
                                        commitBase(
                                            withAdjustCrop(cb, adjustValues, pendingCrop),
                                            currentFilter,
                                            currentRotation,
                                            currentMirrored,
                                            cb,
                                            pendingCrop,
                                            adjustValues
                                        )
                                    }
                                    pendingCrop = CropRect.FULL
                                    arPreset = AspectRatioPreset.FREE
                                    cropMode = false
                                },
                                onCancel = {
                                    pendingCrop = CropRect.FULL
                                    arPreset = AspectRatioPreset.FREE
                                    cropMode = false
                                    showInSource(displayBitmap)
                                },
                            )
                        }

                        if (adjustMode) {
                            AdjustControls(
                                values = adjustLiveValues,
                                onValuesChange = { adjustLiveValues = it },
                                onApply = {
                                    val cb = cropBaseBitmap
                                    if (cb != null && adjustLiveValues != adjustValues) {
                                        commitBase(
                                            withAdjustCrop(cb, adjustLiveValues, committedCrop),
                                            currentFilter,
                                            currentRotation,
                                            currentMirrored,
                                            cb,
                                            committedCrop,
                                            adjustLiveValues
                                        )
                                    } else {
                                        adjustValues = adjustLiveValues
                                    }
                                    adjustBaseline = null
                                    adjustMode = false
                                },
                                onCancel = {
                                    adjustBaseline = null
                                    adjustMode = false
                                },
                            )
                        }

                        if (!styleSheetOpen) {
                            PhotoEditorToolbar(
                                photoEditor = editor,
                                undoAvailable = undoAvailable,
                                redoAvailable = redoAvailable,
                                onUndo = performUndo,
                                onRedo = performRedo,
                                cropMode = cropMode,
                                activePanel = activePanel,
                                onActivePanelChange = {
                                    activePanel = it
                                    deselect()
                                },
                                enabled = !cropMode && !adjustMode,
                                onEnterCrop = {
                                    deselect()
                                    pendingCrop = committedCrop
                                    arPreset = AspectRatioPreset.FREE
                                    cropMode = true
                                    val cb = cropBaseBitmap
                                    showInSource(cb?.let { if (adjustValues.isIdentity) it else applyPhotoAdjust(it, adjustValues) })
                                },
                                onEnterAdjust = {
                                    deselect()
                                    activePanel = ToolPanel.NONE
                                    photoEditor?.setBrushDrawingMode(false)
                                    adjustBaseline = cropBaseBitmap
                                    adjustLiveValues = adjustValues
                                    adjustMode = true
                                },
                                onCompare = {
                                    deselect()
                                    compareMode = true
                                },
                                onRotate = {
                                    val cb = cropBaseBitmap
                                    if (cb != null) {
                                        val newCb = rotateBitmap90CW(cb)
                                        val newCrop = rotateCropRect90CW(committedCrop)
                                        commitBase(
                                            withAdjustCrop(newCb, adjustValues, newCrop),
                                            currentFilter,
                                            (currentRotation + 90) % 360,
                                            currentMirrored,
                                            newCb,
                                            newCrop,
                                            adjustValues
                                        )
                                    }
                                },
                                onMirror = {
                                    val cb = cropBaseBitmap
                                    if (cb != null) {
                                        val newCb = mirrorBitmapH(cb)
                                        val newCrop = mirrorCropRectH(committedCrop)
                                        commitBase(
                                            withAdjustCrop(newCb, adjustValues, newCrop),
                                            currentFilter,
                                            currentRotation,
                                            !currentMirrored,
                                            newCb,
                                            newCrop,
                                            adjustValues
                                        )
                                    }
                                },
                                onFilterSelected = { filter ->
                                    val d = displayBitmap
                                    val cb = cropBaseBitmap
                                    if (d != null && cb != null) {
                                        commitBase(d, filter, currentRotation, currentMirrored, cb, committedCrop, adjustValues)
                                    }
                                },
                                onAddText = { text, meta -> addStyledLayer(text, meta) },
                                onAddEmoji = { emoji ->
                                    addStyledLayer(emoji, LayerMeta(kind = LayerKind.EMOJI, text = emoji, sizePx = 120f))
                                },
                                currentFilter = currentFilter,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                // Close (X)
                IconButton(
                    onClick = { if (!processing && !styleSheetOpen) showDiscardConfirm = true },
                    enabled = !processing && !styleSheetOpen,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                ) {
                    if (!styleSheetOpen) {
                        Icon(
                            painter = painterResource(id = com.shanks.minify.R.drawable.ic_close_24),
                            contentDescription = "Close editor",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                if (editor != null && !styleSheetOpen) {
                    val doneEditor = editor
                    Button(
                        onClick = {
                            if (processing) return@Button
                            processing = true
                            doneError = null
                            scope.launch {
                                runDone(
                                    context = context,
                                    photoEditor = doneEditor,
                                    onDone = currentOnDone,
                                    onLocalError = { doneError = it },
                                )
                                processing = false
                            }
                        },
                        enabled = !processing && !cropMode && !adjustMode,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                    ) {
                        Icon(
                            painter = painterResource(id = com.shanks.minify.R.drawable.ic_check_24),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("Done", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }

            else -> Unit
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        if (processing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        // Compare overlay
        if (compareMode) {
            val original = originalBitmap
            val edited = compareEdited
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                if (original != null && edited != null) {
                    CompareWipeOverlay(
                        dividerFraction = compareDividerFraction,
                        onDividerFractionChange = { compareDividerFraction = it },
                        labelBefore = "Original",
                        labelAfter = "Edited",
                        modifier = Modifier.fillMaxSize(),
                        bottom = {
                            Image(
                                bitmap = original.asImageBitmap(),
                                contentDescription = "Original",
                                contentScale = ContentScale.Inside,
                                modifier = Modifier.fillMaxSize()
                            )
                        },
                        top = {
                            Image(
                                bitmap = edited.asImageBitmap(),
                                contentDescription = "Edited",
                                contentScale = ContentScale.Inside,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                IconButton(
                    onClick = { compareMode = false },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                ) {
                    Icon(
                        painterResource(id = com.shanks.minify.R.drawable.ic_close_24),
                        contentDescription = "Close compare",
                        tint = Color.White
                    )
                }
            }
        }

        // Discard-changes confirmation
        if (showDiscardConfirm) {
            AlertDialog(
                onDismissRequest = { showDiscardConfirm = false },
                title = { Text("Discard changes?") },
                text = { Text("Your edits to this image will be lost.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDiscardConfirm = false
                            currentOnDismiss()
                        },
                    ) {
                        Text("Discard", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDiscardConfirm = false }) {
                        Text("Keep editing")
                    }
                },
            )
        }
    }
}

/**
 * The Done pipeline.
 */
private suspend fun runDone(
    context: android.content.Context,
    photoEditor: PhotoEditor,
    onDone: (File) -> Unit,
    onLocalError: (String) -> Unit,
) {
    val produced = produceEditedOutput(context, photoEditor)
    val editedFile = produced.getOrElse { error ->
        Log.e(TAG, "Producing the edited output failed", error)
        onLocalError(error.localizedMessage ?: "Couldn't apply your edits.")
        return
    }
    onDone(editedFile)
}

/**
 * Composite the PhotoEditor overlays and persist to a lossless PNG temp file.
 */
private suspend fun produceEditedOutput(
    context: android.content.Context,
    photoEditor: PhotoEditor,
): Result<File> {
    val composite: Bitmap = try {
        photoEditor.saveAsBitmap()
    } catch (e: Exception) {
        Log.e(TAG, "saveAsBitmap failed", e)
        return Result.failure(e)
    }

    return try {
        withContext(Dispatchers.IO) {
            val tmp = File.createTempFile("minify_edit_", ".png", context.cacheDir)
            FileOutputStream(tmp).use { out ->
                val ok = composite.compress(Bitmap.CompressFormat.PNG, 100, out)
                check(ok) { "Compositing the edited photo failed" }
            }
            Result.success(tmp)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Writing edited output failed", e)
        Result.failure(e)
    } finally {
        if (!composite.isRecycled) composite.recycle()
    }
}

// ----- Geometry helpers -----

private const val EDITOR_MIN_EDIT_EDGE = 1080

private fun ensureMinEditSize(src: Bitmap): Bitmap {
    val longest = maxOf(src.width, src.height)
    if (longest <= 0 || longest >= EDITOR_MIN_EDIT_EDGE) return src
    val scale = EDITOR_MIN_EDIT_EDGE.toFloat() / longest
    val w = (src.width * scale).toInt().coerceAtLeast(1)
    val h = (src.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(src, w, h, true)
    if (scaled !== src) src.recycle()
    return scaled
}

private fun rotateBitmap90CW(src: Bitmap): Bitmap {
    val m = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}

private fun mirrorBitmapH(src: Bitmap): Bitmap {
    val m = Matrix().apply { postScale(-1f, 1f) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}

private fun cropBitmap(src: Bitmap, crop: CropRect): Bitmap {
    val w = src.width
    val h = src.height
    val left = (crop.left * w).toInt().coerceIn(0, w - 1)
    val top = (crop.top * h).toInt().coerceIn(0, h - 1)
    val right = (crop.right * w).toInt().coerceIn(left + 1, w)
    val bottom = (crop.bottom * h).toInt().coerceIn(top + 1, h)
    if (left == 0 && top == 0 && right == w && bottom == h) return src
    return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
}

internal fun rotateCropRect90CW(r: CropRect): CropRect =
    CropRect(left = 1f - r.bottom, top = r.left, right = 1f - r.top, bottom = r.right)

internal fun mirrorCropRectH(r: CropRect): CropRect =
    CropRect(left = 1f - r.right, top = r.top, right = 1f - r.left, bottom = r.bottom)

// ----- Base-image state + unified undo/redo timeline types -----

internal data class PhotoBaseState(
    val bitmap: Bitmap,
    val filter: PhotoFilter?,
    val rotation: Int = 0,
    val mirrored: Boolean = false,
    val cropBase: Bitmap,
    val crop: CropRect,
    val adjust: AdjustValues = AdjustValues()
)

internal sealed interface PhotoEditOp {
    data class Base(val prev: PhotoBaseState, val next: PhotoBaseState) : PhotoEditOp
    data object Overlay : PhotoEditOp
}

internal enum class LayerKind { TEXT, EMOJI }

internal data class LayerMeta(
    val kind: LayerKind,
    var text: String = "",
    var color: Int = Color.White.toArgb(),
    var family: String = "sans",
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var strikethrough: Boolean = false,
    var sizePx: Float = 56f,
    var scale: Float = 1f,
    var rotationDeg: Float = 0f,
    var alignCenter: Boolean = false,
)

private val FONT_FAMILIES = listOf(
    "sans" to "Sans", "serif" to "Serif", "mono" to "Mono",
    "sans-light" to "Light", "sans-medium" to "Medium", "sans-black" to "Black",
    "casual" to "Casual", "cursive" to "Script",
)

private val FAMILY_MAP = mapOf(
    "sans" to "sans-serif", "serif" to "serif", "mono" to "monospace",
    "sans-light" to "sans-serif-light", "sans-medium" to "sans-serif-medium",
    "sans-black" to "sans-serif-black", "casual" to "casual", "cursive" to "cursive",
)

private fun typefaceFor(ctx: Context, key: String): Typeface = try {
    Typeface.createFromAsset(ctx.assets, "fonts/$key.ttf")
} catch (_: Exception) {
    Typeface.create(FAMILY_MAP[key] ?: "sans-serif", Typeface.NORMAL)
}

private fun textViewOf(v: View): TextView? = when (v) {
    is TextView -> v
    is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { textViewOf(v.getChildAt(it)) }
    else -> null
}

/** Push a [LayerMeta] onto a layer view. Scale/rotation go on the root (the node the
 *  library moves); typography goes on the inner TextView. Idempotent. */
private fun applyMeta(ctx: Context, view: View, m: LayerMeta, preview: Boolean = false) {
    view.scaleX = m.scale; view.scaleY = m.scale; view.rotation = m.rotationDeg

    if (m.kind == LayerKind.TEXT) {
        textViewOf(view)?.let { tv ->
            val style = when {
                m.bold && m.italic -> Typeface.BOLD_ITALIC
                m.bold -> Typeface.BOLD
                m.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            val apply = {
                tv.typeface = Typeface.create(typefaceFor(ctx, m.family), style)
                tv.setTextColor(m.color)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, m.sizePx)
                tv.paintFlags = (tv.paintFlags
                        and Paint.UNDERLINE_TEXT_FLAG.inv()
                        and Paint.STRIKE_THRU_TEXT_FLAG.inv()) or
                        (if (m.underline) Paint.UNDERLINE_TEXT_FLAG else 0) or
                        (if (m.strikethrough) Paint.STRIKE_THRU_TEXT_FLAG else 0)
                tv.gravity = if (m.alignCenter) Gravity.CENTER_HORIZONTAL else Gravity.START
                tv.textAlignment = if (m.alignCenter) View.TEXT_ALIGNMENT_CENTER else View.TEXT_ALIGNMENT_VIEW_START
                tv.text = m.text
            }
            if (preview) apply() else tv.post(apply)
        }
    }

    view.requestLayout(); view.invalidate()
}

data class AdjustValues(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
) {
    val isIdentity: Boolean
        get() = brightness == 0f && contrast == 0f && saturation == 0f && warmth == 0f
}

internal fun applyPhotoAdjust(src: Bitmap, v: AdjustValues): Bitmap {
    val cm = ColorMatrix()
    cm.postConcat(ColorMatrix().apply { setSaturation(1f + v.saturation) })

    val s = 1f + v.contrast
    val t = 127.5f * (1f - s)
    cm.postConcat(
        ColorMatrix(
            floatArrayOf(
                s, 0f, 0f, 0f, t,
                0f, s, 0f, 0f, t,
                0f, 0f, s, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )

    val b = v.brightness * 100f
    cm.postConcat(
        ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, b,
                0f, 1f, 0f, 0f, b,
                0f, 0f, 1f, 0f, b,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )

    val w = v.warmth * 30f
    cm.postConcat(
        ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, w,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, -w,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )

    val out = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
    out.density = src.density
    val canvas = Canvas(out)
    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
    canvas.drawBitmap(src, 0f, 0f, paint)
    return out
}

// ============================================================================
// Toolbar and supporting UI
// ============================================================================

private val EDITOR_COLORS = listOf(
    Color.Black, Color.White, Color(0xFFE53935), Color(0xFFFB8C00),
    Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA),
)

private val EDITOR_EMOJIS = listOf(
    "\uD83D\uDE00", "\uD83D\uDE0D", "\uD83D\uDE02", "\uD83D\uDE0E",
    "\uD83D\uDE22", "\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDD25",
    "\u2B50", "\uD83C\uDF89", "\uD83D\uDE4C", "\uD83D\uDCAF",
)

@Composable
internal fun PhotoEditorToolbar(
    photoEditor: PhotoEditor,
    undoAvailable: Boolean,
    redoAvailable: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    cropMode: Boolean,
    activePanel: ToolPanel,
    onActivePanelChange: (ToolPanel) -> Unit,
    onEnterCrop: () -> Unit,
    onEnterAdjust: () -> Unit,
    onCompare: () -> Unit,
    onRotate: () -> Unit,
    onMirror: () -> Unit,
    onFilterSelected: (PhotoFilter) -> Unit,
    onAddText: (text: String, meta: LayerMeta) -> Unit,
    onAddEmoji: (emoji: String) -> Unit,
    currentFilter: PhotoFilter?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var brushColor by remember { mutableStateOf(Color(0xFFE53935)) }
    var brushSize by remember { mutableStateOf(25f) }
    var brushOpacity by remember { mutableStateOf(255f) }
    var showTextDialog by remember { mutableStateOf(false) }

    fun applyBrush() {
        photoEditor.setShape(
            ShapeBuilder()
                .withShapeType(ShapeType.Brush)
                .withShapeColor(brushColor.toArgb())
                .withShapeSize(brushSize)
                .withShapeOpacity(brushOpacity.toInt())
        )
        photoEditor.setBrushDrawingMode(true)
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {

            when (activePanel) {
                ToolPanel.BRUSH -> BrushPanel(
                    color = brushColor,
                    size = brushSize,
                    opacity = brushOpacity,
                    onColorChange = { brushColor = it; applyBrush() },
                    onSizeChange = { brushSize = it; applyBrush() },
                    onOpacityChange = { brushOpacity = it; applyBrush() },
                )
                ToolPanel.FILTER -> FilterPanel(
                    current = currentFilter,
                    onFilterSelected = onFilterSelected,
                )
                ToolPanel.EMOJI -> EmojiPanel(
                    onEmojiSelected = {
                        photoEditor.setBrushDrawingMode(false)
                        onAddEmoji(it)
                    },
                )
                ToolPanel.ERASER -> Unit
                ToolPanel.NONE -> Unit
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(
                    painter = painterResource(id = com.shanks.minify.R.drawable.ic_crop_24),
                    label = "Crop",
                    selected = cropMode,
                    enabled = enabled,
                    onClick = {
                        onActivePanelChange(ToolPanel.NONE)
                        photoEditor.setBrushDrawingMode(false)
                        onEnterCrop()
                    },
                )
                ToolButton(
                    painter = painterResource(id = com.shanks.minify.R.drawable.ic_tune_24),
                    label = "Adjust",
                    selected = false,
                    enabled = enabled,
                    onClick = {
                        onActivePanelChange(ToolPanel.NONE)
                        onEnterAdjust()
                    },
                )
                ToolButton(
                    painter = painterResource(id = com.shanks.minify.R.drawable.ic_preview_24),
                    label = "Compare",
                    selected = false,
                    enabled = enabled,
                    onClick = {
                        photoEditor.setBrushDrawingMode(false)
                        onActivePanelChange(ToolPanel.NONE)
                        onCompare()
                    },
                )
                ToolButton(
                    painter = painterResource(id = com.shanks.minify.R.drawable.ic_rotate_right_24),
                    label = "Rotate",
                    selected = false,
                    enabled = enabled,
                    onClick = {
                        photoEditor.setBrushDrawingMode(false)
                        onActivePanelChange(ToolPanel.NONE)
                        onRotate()
                    },
                )
                ToolButton(
                    painter = painterResource(id = com.shanks.minify.R.drawable.ic_swap_24),
                    label = "Mirror",
                    selected = false,
                    enabled = enabled,
                    onClick = {
                        photoEditor.setBrushDrawingMode(false)
                        onActivePanelChange(ToolPanel.NONE)
                        onMirror()
                    },
                )
                ToolButton(
                    painter = painterResource(id = com.shanks.minify.R.drawable.ic_palette_24),
                    label = "Brush",
                    selected = activePanel == ToolPanel.BRUSH,
                    enabled = enabled,
                    onClick = {
                        if (activePanel == ToolPanel.BRUSH) {
                            photoEditor.setBrushDrawingMode(false)
                            onActivePanelChange(ToolPanel.NONE)
                        } else {
                            applyBrush()
                            onActivePanelChange(ToolPanel.BRUSH)
                        }
                    },
                )
                ToolButton(
                    painter = painterResource(id = com.shanks.minify.R.drawable.ic_delete_24),
                    label = "Eraser",
                    selected = activePanel == ToolPanel.ERASER,
                    enabled = enabled,
                    onClick = {
                        if (activePanel == ToolPanel.ERASER) {
                            photoEditor.setBrushDrawingMode(false)
                            onActivePanelChange(ToolPanel.NONE)
                        } else {
                            photoEditor.brushEraser()
                            onActivePanelChange(ToolPanel.ERASER)
                        }
                    },
                )
                ToolButton(
                    painter = painterResource(id = com.shanks.minify.R.drawable.ic_text_24),
                    label = "Text",
                    selected = false,
                    enabled = enabled,
                    onClick = {
                        onActivePanelChange(ToolPanel.NONE)
                        photoEditor.setBrushDrawingMode(false)
                        showTextDialog = true
                    },
                )
                ToolButton(
                    painter = painterResource(id = com.shanks.minify.R.drawable.ic_sticker_24),
                    label = "Emoji",
                    selected = activePanel == ToolPanel.EMOJI,
                    enabled = enabled,
                    onClick = {
                        photoEditor.setBrushDrawingMode(false)
                        onActivePanelChange(
                            if (activePanel == ToolPanel.EMOJI) ToolPanel.NONE
                            else ToolPanel.EMOJI
                        )
                    },
                )
                ToolButton(
                    painter = painterResource(id = com.shanks.minify.R.drawable.ic_layers_24),
                    label = "Filter",
                    selected = activePanel == ToolPanel.FILTER,
                    enabled = enabled,
                    onClick = {
                        photoEditor.setBrushDrawingMode(false)
                        onActivePanelChange(
                            if (activePanel == ToolPanel.FILTER) ToolPanel.NONE
                            else ToolPanel.FILTER
                        )
                    },
                )
                ToolButton(
                    painter = painterResource(id = com.shanks.minify.R.drawable.ic_undo_24),
                    label = "Undo",
                    selected = false,
                    enabled = enabled && undoAvailable,
                    onClick = {
                        photoEditor.setBrushDrawingMode(false)
                        onActivePanelChange(ToolPanel.NONE)
                        onUndo()
                    },
                )
                ToolButton(
                    painter = painterResource(id = com.shanks.minify.R.drawable.ic_redo_24),
                    label = "Redo",
                    selected = false,
                    enabled = enabled && redoAvailable,
                    onClick = {
                        photoEditor.setBrushDrawingMode(false)
                        onActivePanelChange(ToolPanel.NONE)
                        onRedo()
                    },
                )
            }
        }
    }

    if (showTextDialog) {
        AddTextDialog(
            onDismiss = { showTextDialog = false },
            onConfirm = { text, meta ->
                showTextDialog = false
                if (text.isNotBlank()) onAddText(text, meta)
            },
        )
    }
}

@Composable
private fun CropControls(
    selected: AspectRatioPreset,
    onSelect: (AspectRatioPreset) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AspectRatioChips(
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Drag corners or edges to crop. Tap a preset to lock the aspect ratio.",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onApply,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(id = com.shanks.minify.R.drawable.ic_check_24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Apply crop", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun AdjustControls(
    values: AdjustValues,
    onValuesChange: (AdjustValues) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AdjustSlider("Brightness", values.brightness) { onValuesChange(values.copy(brightness = it)) }
            AdjustSlider("Contrast", values.contrast) { onValuesChange(values.copy(contrast = it)) }
            AdjustSlider("Saturation", values.saturation) { onValuesChange(values.copy(saturation = it)) }
            AdjustSlider("Warmth", values.warmth) { onValuesChange(values.copy(warmth = it)) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(onClick = onApply, modifier = Modifier.weight(1f)) {
                    Icon(
                        painter = painterResource(id = com.shanks.minify.R.drawable.ic_check_24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Apply", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    val pct = (value * 100).roundToInt()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(
                if (pct >= 0) "+$pct" else "$pct",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = -1f..1f)
    }
}

@Composable
private fun BrushPanel(
    color: Color,
    size: Float,
    opacity: Float,
    onColorChange: (Color) -> Unit,
    onSizeChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        ColorRow(selected = color, onSelect = onColorChange)
        Text("Size", style = MaterialTheme.typography.labelMedium)
        Slider(value = size, onValueChange = onSizeChange, valueRange = 2f..100f)
        Text("Opacity", style = MaterialTheme.typography.labelMedium)
        Slider(value = opacity, onValueChange = onOpacityChange, valueRange = 0f..255f)
    }
}

@Composable
private fun FilterPanel(current: PhotoFilter?, onFilterSelected: (PhotoFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhotoFilter.entries.forEach { filter ->
            TextButton(
                onClick = { onFilterSelected(filter) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (filter == current) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            ) {
                Text(filter.name.replace('_', ' '), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun EmojiPanel(onEmojiSelected: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EDITOR_EMOJIS.forEach { emoji ->
            Text(
                text = emoji,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onEmojiSelected(emoji) }
                    .padding(4.dp),
            )
        }
    }
}

@Composable
private fun ColorRow(selected: Color, onSelect: (Color) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EDITOR_COLORS.forEach { color ->
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (color == selected) 3.dp else 1.dp,
                        color = if (color == selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape,
                    )
                    .clickable { onSelect(color) },
            )
        }
    }
}

@Composable
private fun AddTextDialog(
    onDismiss: () -> Unit,
    onConfirm: (text: String, meta: LayerMeta) -> Unit,
) {
    var draft by remember {
        mutableStateOf(LayerMeta(kind = LayerKind.TEXT, text = "", color = Color.White.toArgb()))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.95f),
        title = { Text("Add text") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TextLivePreview(meta = draft)
                OutlinedTextField(
                    value = draft.text,
                    onValueChange = { draft = draft.copy(text = it) },
                    label = { Text("Text") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextStyleForm(meta = draft, onChanged = { draft = it })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(draft.text, draft) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ToolButton(
    painter: androidx.compose.ui.graphics.painter.Painter,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                painter = painter,
                contentDescription = label,
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
    }
}

@Composable
private fun ValueSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps)
    }
}

@Composable
private fun TextLivePreview(meta: LayerMeta) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val blank = meta.text.isBlank()
    val shown = meta.text.ifBlank { "Your text preview" }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp, max = 150.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF42424C))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
    ) {
        val innerPx = (constraints.maxWidth - with(density) { 24.dp.toPx() }.roundToInt())
            .coerceAtLeast(1)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { c ->
                    TextView(c).apply {
                        includeFontPadding = false
                        maxLines = 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            innerPx,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        )
                    }
                },
                update = { tv ->
                    val lp = tv.layoutParams
                    if (lp.width != innerPx) {
                        lp.width = innerPx
                        tv.layoutParams = lp
                    }
                    applyMeta(ctx, tv, meta, preview = true)
                    tv.scaleX = 1f
                    tv.scaleY = 1f
                    tv.rotation = 0f
                    tv.text = shown
                    tv.alpha = if (blank) 0.45f else 1f
                },
            )
        }
    }
}

@Composable
private fun ColorPickerControl(selected: Color, onSelect: (Color) -> Unit) {
    val hsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(selected.toArgb(), it) }
    }
    fun emit() = onSelect(Color(android.graphics.Color.HSVToColor(hsv)))

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Color", style = MaterialTheme.typography.labelMedium)
        ColorRow(selected = selected, onSelect = { c ->
            android.graphics.Color.colorToHSV(c.toArgb(), hsv); emit()
        })
        SvPad(hue = hsv[0], s = hsv[1], v = hsv[2], onChange = { ns, nv ->
            hsv[1] = ns; hsv[2] = nv; emit()
        })
        HueBar(hue = hsv[0], onChange = { nh -> hsv[0] = nh; emit() })
    }
}

@Composable
private fun SvPad(hue: Float, s: Float, v: Float, onChange: (s: Float, v: Float) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val latestSize by rememberUpdatedState(size)
    val latestOnChange by rememberUpdatedState(onChange)
    val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .background(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun apply(o: Offset) {
                            val w = latestSize.width.toFloat().coerceAtLeast(1f)
                            val h = latestSize.height.toFloat().coerceAtLeast(1f)
                            latestOnChange(
                                (o.x / w).coerceIn(0f, 1f),
                                (1f - o.y / h).coerceIn(0f, 1f),
                            )
                        }
                        apply(down.position)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { ch ->
                                if (ch.pressed) {
                                    apply(ch.position)
                                    ch.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
            },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        )
        if (size.width > 0 && size.height > 0) {
            val cx = s * size.width
            val cy = (1f - v) * size.height
            Box(
                Modifier
                    .offset { IntOffset((cx - 9.dp.toPx()).roundToInt(), (cy - 9.dp.toPx()).roundToInt()) }
                    .size(18.dp)
                    .border(2.dp, Color.White, CircleShape)
                    .background(
                        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, s, v))),
                        CircleShape,
                    )
            )
        }
    }
}

@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val latestSize by rememberUpdatedState(size)
    val latestOnChange by rememberUpdatedState(onChange)
    val rainbow = Brush.horizontalGradient(
        listOf(
            Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00), Color(0xFF00FFFF),
            Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
        )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(11.dp))
            .background(rainbow)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun apply(o: Offset) {
                            val w = latestSize.width.toFloat().coerceAtLeast(1f)
                            latestOnChange((o.x / w * 360f).coerceIn(0f, 360f))
                        }
                        apply(down.position)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { ch ->
                                if (ch.pressed) {
                                    apply(ch.position)
                                    ch.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
            },
    ) {
        if (size.width > 0) {
            val cx = hue / 360f * size.width
            Box(
                Modifier
                    .offset { IntOffset((cx - 11.dp.toPx()).roundToInt(), 0) }
                    .size(22.dp)
                    .border(2.dp, Color.White, CircleShape)
                    .background(
                        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))),
                        CircleShape,
                    )
            )
        }
    }
}

@Composable
private fun StyleToggle(label: String, on: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        label = "tg-bg",
    )
    val fg by animateColorAsState(
        if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        label = "tg-fg",
    )
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun TextStyleForm(meta: LayerMeta, onChanged: (LayerMeta) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ColorPickerControl(
            selected = Color(meta.color),
            onSelect = { onChanged(meta.copy(color = it.toArgb())) },
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FONT_FAMILIES.forEach { (key, name) ->
                FilterChip(
                    selected = meta.family == key,
                    onClick = { onChanged(meta.copy(family = key)) },
                    label = { Text(name, fontSize = 12.sp) },
                    shape = RoundedCornerShape(8.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StyleToggle("B", meta.bold) { onChanged(meta.copy(bold = !meta.bold)) }
            StyleToggle("I", meta.italic) { onChanged(meta.copy(italic = !meta.italic)) }
            StyleToggle("U", meta.underline) { onChanged(meta.copy(underline = !meta.underline)) }
            StyleToggle("S", meta.strikethrough) { onChanged(meta.copy(strikethrough = !meta.strikethrough)) }
            StyleToggle("≡", meta.alignCenter) { onChanged(meta.copy(alignCenter = !meta.alignCenter)) }
        }
        ValueSlider(
            label = "Text size",
            valueText = "${meta.sizePx.roundToInt()} px",
            value = meta.sizePx,
            onValueChange = { onChanged(meta.copy(sizePx = it)) },
            valueRange = 16f..220f,
        )
    }
}

/** The bar that appears over a selected layer: scale + rotate for ALL kinds (text AND emoji),
 *  full typography style sheet for text only. */
@Composable
private fun LayerControls(
    meta: LayerMeta,
    showStyle: Boolean,
    onShowStyleChange: (Boolean) -> Unit,
    onMeta: (LayerMeta) -> Unit,
    onDeselect: () -> Unit
) {
    BoxWithConstraints {
        val maxBarHeight = (maxHeight - 96.dp).coerceAtLeast(180.dp)

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBarHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (meta.kind == LayerKind.TEXT) "Text layer" else "Sticker layer",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (meta.kind == LayerKind.TEXT) {
                        TextButton(onClick = { onShowStyleChange(!showStyle) }) {
                            Text(if (showStyle) "Hide style" else "Style & edit")
                        }
                    }
                    TextButton(onClick = onDeselect) { Text("Close") }
                }

                // ----- Scale slider (works for BOTH text and emoji layers) -----
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(painterResource(com.shanks.minify.R.drawable.ic_sticker_24), null, Modifier.size(18.dp))
                    Slider(
                        value = meta.scale,
                        onValueChange = { onMeta(meta.copy(scale = it)) },
                        valueRange = 0.25f..4f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(meta.scale * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // ----- Rotation slider (works for BOTH text and emoji layers) -----
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(painterResource(com.shanks.minify.R.drawable.ic_rotate_right_24), null, Modifier.size(18.dp))
                    Slider(
                        value = meta.rotationDeg,
                        onValueChange = { onMeta(meta.copy(rotationDeg = it)) },
                        valueRange = -180f..180f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${meta.rotationDeg.roundToInt()}°",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                OutlinedButton(onClick = { onMeta(meta.copy(scale = 1f, rotationDeg = 0f)) }) {
                    Text("Reset size & rotation")
                }

                // ----- Text-only style sheet (hidden for emoji layers) -----
                AnimatedVisibility(
                    visible = showStyle && meta.kind == LayerKind.TEXT,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextLivePreview(meta = meta)
                        OutlinedTextField(
                            value = meta.text,
                            onValueChange = { onMeta(meta.copy(text = it)) },
                            label = { Text("Edit text") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextStyleForm(meta = meta, onChanged = onMeta)
                    }
                }
            }
        }
    }
}

/**
 * A [PhotoEditorView] that re-selects a tagged layer (text/emoji) when the user
 * taps it, WITHOUT consuming the gesture. `onInterceptTouchEvent` returns
 * `super` (false) so the library's own per-view MultiTouchListener keeps full
 * ownership of drag-to-move / tap-to-frame / delete — we only add a selection
 * side-effect on ACTION_DOWN. Hit-testing uses each child's inverse matrix so
 * rotated / scaled layers select accurately.
 */
private class LayerSelectingPhotoEditorView(
    context: Context,
) : PhotoEditorView(context) {

    /** Invoked on ACTION_DOWN when the touch lands on a tagged layer child. */
    var onLayerTouched: ((view: View, meta: LayerMeta) -> Unit)? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            findLayerChildAt(ev.x, ev.y)?.let { child ->
                val meta = child.getTag(com.shanks.minify.R.id.layer_meta) as? LayerMeta
                if (meta != null) onLayerTouched?.invoke(child, meta)
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    private fun findLayerChildAt(x: Float, y: Float): View? {
        // Top-most first so overlapping layers select the front one.
        for (i in childCount - 1 downTo 0) {
            val c = getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            if (c.getTag(com.shanks.minify.R.id.layer_meta) == null) continue
            if (isPointInChild(c, x, y)) return c
        }
        return null
    }

    /** Maps a parent-space point into the child's local space and bounds-checks it.
     *  Mirrors ViewGroup.transformPointToViewLocal: offset by the child's LAYOUT
     *  position only (left/top + parent scroll) — translationX/Y live INSIDE the
     *  matrix, so subtracting them separately would double-count and shift the
     *  hit-box away from a dragged layer. The inverse matrix then undoes
     *  translation + scale + rotation about the pivot in one step. */
    private fun isPointInChild(child: View, x: Float, y: Float): Boolean {
        val pts = floatArrayOf(x + scrollX - child.left, y + scrollY - child.top)
        val matrix = child.matrix
        if (!matrix.isIdentity) {
            val inv = Matrix()
            if (!matrix.invert(inv)) return false
            inv.mapPoints(pts)
        }
        return pts[0] >= 0f && pts[0] <= child.width &&
                pts[1] >= 0f && pts[1] <= child.height
    }
}

/**
 * Adapts the library's [OnPhotoEditorListener] to a single "history changed"
 * callback.
 */
private class HistoryListener(
    private val onTouchSource: () -> Unit = {},
    private val onTextEdited: (rootView: View, text: String, colorCode: Int) -> Unit = { _, _, _ -> },
    private val onStartMove: () -> Unit = {},
    private val onStopMove: () -> Unit = {},
    private val onChanged: () -> Unit,
) : OnPhotoEditorListener {

    override fun onEditTextChangeListener(rootView: View, text: String, colorCode: Int) =
        onTextEdited(rootView, text, colorCode)

    override fun onAddViewListener(viewType: ViewType, numberOfAddedViews: Int) = onChanged()

    override fun onRemoveViewListener(viewType: ViewType, numberOfAddedViews: Int) = onChanged()

    override fun onStartViewChangeListener(viewType: ViewType) = onStartMove()

    override fun onStopViewChangeListener(viewType: ViewType) = onStopMove()

    override fun onTouchSourceImage(event: MotionEvent) = onTouchSource()
}
