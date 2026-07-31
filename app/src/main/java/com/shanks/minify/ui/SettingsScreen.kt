package com.shanks.minify.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.graphics.toColorInt
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanks.minify.ui.theme.*
import kotlin.math.roundToInt

private val TextPrim = Color(0xFFFFFFFF)
private val TextSec  = Color(0xFF8E8E93)

// ── HSV helpers ───────────────────────────────────────────────────────────────

private fun Color.toHsv(): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    return hsv
}

private fun hsvToColor(h: Float, s: Float, v: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))

private fun colorToHex(c: Color): String {
    val argb = c.toArgb()
    return "%06X".format(argb and 0xFFFFFF)
}

private fun hexToColor(hex: String): Color? = try {
    val clean = hex.trimStart('#').padStart(6, '0').take(6)
    Color("#$clean".toColorInt())
} catch (_: Exception) { null }

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(
    currentAccent: AppAccent,
    currentAccentColor: Color,
    onAccentChange: (AppAccent, Color) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    val accent = MaterialTheme.colorScheme.primary

    // Local selection — updates immediately on tap, no prop round-trip lag
    var localAccent by remember(currentAccent) { mutableStateOf(currentAccent) }

    // Local picker state
    val initHsv    = remember(currentAccentColor) { currentAccentColor.toHsv() }
    var hue        by remember(currentAccentColor) { mutableFloatStateOf(initHsv[0]) }
    var saturation by remember(currentAccentColor) { mutableFloatStateOf(initHsv[1]) }
    var value      by remember(currentAccentColor) { mutableFloatStateOf(initHsv[2]) }
    var hexInput   by remember(currentAccentColor) { mutableStateOf(colorToHex(currentAccentColor)) }
    var hexError   by remember { mutableStateOf(value = false) }

    val pickedColor by remember(hue, saturation, value) { derivedStateOf { hsvToColor(hue, saturation, value) } }

    Box(
        modifier = Modifier.fillMaxSize().background(BgDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface1)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onBack) {
                    Text("← Back", color = accent, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                }
                Text("Settings", color = TextPrim, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(80.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {

                // ── Preset chips ──────────────────────────────────────────────
                SettingsSection(title = "App Colour") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                        // Preset grid (exclude CUSTOM — it's set by picker below)
                        val presets = AppAccent.entries.filter { it != AppAccent.CUSTOM }
                        presets.chunked(3).forEach { row ->
                            Row(
                                modifier              = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.forEach { option ->
                                    val selected = localAccent == option
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Surface2)
                                            .border(
                                                width = if (selected) 2.dp else 0.dp,
                                                color = if (selected) option.color else Color.Transparent,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .clickable {
                                                val hsv    = option.color.toHsv()
                                                hue        = hsv[0]
                                                saturation = hsv[1]
                                                value      = hsv[2]
                                                hexInput   = colorToHex(option.color)
                                                localAccent = option
                                                onAccentChange(option, option.color)
                                            }
                                            .padding(10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(18.dp)
                                                    .clip(CircleShape)
                                                    .background(option.color)
                                            )
                                            Text(
                                                option.label,
                                                color      = if (selected) option.color else TextPrim,
                                                fontSize   = 12.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                                // pad last row
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }

                        HorizontalDivider(color = Surface2, modifier = Modifier.padding(vertical = 4.dp))

                        // ── Custom colour picker ──────────────────────────────
                        Text(
                            "Custom colour",
                            color      = TextPrim,
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        // Live preview
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(pickedColor)
                        )

                        // SV square (saturation horizontal, value vertical)
                        SatValSquare(
                            hue        = hue,
                            saturation = saturation,
                            value      = value,
                            onChanged  = { s, v ->
                                saturation = s; value = v
                                hexInput   = colorToHex(hsvToColor(hue, s, v))
                            },
                            modifier   = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f)
                                .clip(RoundedCornerShape(10.dp))
                        )

                        // Hue slider
                        HueSlider(
                            hue       = hue,
                            onChanged = { h ->
                                hue      = h
                                hexInput = colorToHex(hsvToColor(h, saturation, value))
                            },
                            modifier  = Modifier.fillMaxWidth()
                        )

                        // Hex input
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value         = hexInput,
                                onValueChange = { raw ->
                                    hexInput = raw.filter { it.isLetterOrDigit() }.take(6).uppercase()
                                    val parsed = hexToColor(hexInput)
                                    hexError = (hexInput.length == 6 && parsed == null)
                                    if (hexInput.length == 6 && parsed != null) {
                                        val hsv = parsed.toHsv()
                                        hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
                                    }
                                },
                                label    = { Text("#  Hex") },
                                isError  = hexError,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                                colors   = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor   = accent,
                                    focusedLabelColor    = accent,
                                    cursorColor          = accent,
                                    unfocusedBorderColor = TextSec.copy(alpha = 0.4f),
                                    unfocusedLabelColor  = TextSec,
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick  = {
                                    localAccent = AppAccent.CUSTOM
                                    onAccentChange(AppAccent.CUSTOM, pickedColor)
                                    hexInput = colorToHex(pickedColor)
                                },
                                shape    = RoundedCornerShape(10.dp),
                                colors   = ButtonDefaults.buttonColors(
                                    containerColor = pickedColor,
                                    contentColor   = Color.White
                                )
                            ) {
                                Text("Apply", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // ── About ─────────────────────────────────────────────────────
                SettingsSection(title = "About") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoRow("App version",      "Minify V7-Experimental")
                        InfoRow("Developer",   "Riyan Bharadwaj")
                        InfoRow("Official page", "github.com/riyanbharadwaj/minify")
                    }
                }
            }
        }
    }
}

// ── Saturation / Value square ─────────────────────────────────────────────────

@Composable
private fun SatValSquare(
    hue: Float,
    saturation: Float,
    value: Float,
    onChanged: (sat: Float, value: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val latestOnChanged by rememberUpdatedState(onChanged)

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                fun handle(offset: Offset) {
                    if (size == IntSize.Zero) return
                    val s = (offset.x / size.width).coerceIn(0f, 1f)
                    val v = (1f - (offset.y / size.height)).coerceIn(0f, 1f)
                    latestOnChanged(s, v)
                }
                detectTapGestures { handle(it) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset: Offset ->
                        if (size == IntSize.Zero) return@detectDragGestures
                        val s = (offset.x / size.width).coerceIn(0f, 1f)
                        val v = (1f - (offset.y / size.height)).coerceIn(0f, 1f)
                        latestOnChanged(s, v)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (size == IntSize.Zero) return@detectDragGestures
                        val s = (change.position.x / size.width).coerceIn(0f, 1f)
                        val v = (1f - (change.position.y / size.height)).coerceIn(0f, 1f)
                        latestOnChanged(s, v)
                    }
                )
            }
    ) {
        // Saturation gradient (white → hue colour)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.White, hsvToColor(hue, 1f, 1f))
                    )
                )
        )
        // Value gradient (transparent → black)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                )
        )
        // Thumb drawn via Canvas so we stay in px with no dp conversion needed
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = saturation * this.size.width
            val cy = (1f - value) * this.size.height
            drawCircle(color = Color.White, radius = 12f, center = Offset(cx, cy))
            drawCircle(color = hsvToColor(hue, saturation, value), radius = 9f, center = Offset(cx, cy))
        }
    }
}

// ── Hue slider ────────────────────────────────────────────────────────────────

@Composable
private fun HueSlider(
    hue: Float,
    onChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnChanged by rememberUpdatedState(onChanged)
    var width by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .height(28.dp)
            .onSizeChanged { width = it.width }
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    (0..12).map { hsvToColor(it * 30f, 1f, 1f) }
                )
            )
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (width > 0) latestOnChanged(((offset.x / width) * 360f).coerceIn(0f, 360f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset: Offset ->
                        if (width > 0) latestOnChanged(((offset.x / width) * 360f).coerceIn(0f, 360f))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (width > 0) latestOnChanged(((change.position.x / width) * 360f).coerceIn(0f, 360f))
                    }
                )
            }
    ) {
        // Thumb line
        if (width > 0) {
            val thumbX = ((hue / 360f) * width).roundToInt().coerceIn(0, width)
            Box(
                modifier = Modifier
                    .offset(
                        x = with(androidx.compose.ui.platform.LocalDensity.current) {
                            (thumbX - 10).toDp()
                        }
                    )
                    .size(width = 4.dp, height = 28.dp)
                    .background(Color.White, RoundedCornerShape(2.dp))
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title.uppercase(),
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = TextSec, letterSpacing = 1.sp,
        )
        Card(
            shape  = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSec,  fontSize = 13.sp)
        Text(value, color = TextPrim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}