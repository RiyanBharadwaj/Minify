package com.shanks.minify

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.shanks.minify.ui.AppSettings
import com.shanks.minify.ui.MainScreen
import com.shanks.minify.ui.theme.AppAccent
import com.shanks.minify.ui.theme.MinifyTheme
import com.shanks.minify.utils.cleanupMinifyTrashOnStartup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.media3.common.util.UnstableApi
class MainActivity : ComponentActivity() {

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            cleanupMinifyTrashOnStartup(applicationContext)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestManageStorageIfNeeded()

        setContent {
            // ── Load saved accent once on first composition ───────────────────
            // We use a produceState that reads DataStore exactly once at startup
            // (cold start restoration), then all subsequent changes go through
            // the mutableStateOf below — no async race conditions.
            val loadedAccent by produceState<AppAccent?>(initialValue = null) {
                value = withContext(Dispatchers.IO) {
                    AppSettings.accentFlow(applicationContext).first()
                }
            }
            val loadedHex by produceState<String?>(initialValue = null) {
                value = withContext(Dispatchers.IO) {
                    AppSettings.customHexFlow(applicationContext).first()
                }
            }

            // Don't render until we have the persisted values (avoids purple flash)
            if (loadedAccent == null) return@setContent

            // ── In-memory state — source of truth for the running session ─────
            // Updated immediately on user change (optimistic), persisted async.
            var accent by remember(loadedAccent) { mutableStateOf(loadedAccent!!) }
            var accentColor by remember(loadedAccent, loadedHex) {
                mutableStateOf(resolveColor(loadedAccent!!, loadedHex))
            }

            MinifyTheme(accentColor = accentColor, accent = accent) {
                MainScreen(
                    currentAccent      = accent,
                    currentAccentColor = accentColor,
                    onAccentChange     = { newAccent, newColor ->
                        // Update in-memory state immediately — UI reacts in same frame
                        accent      = newAccent
                        accentColor = newColor
                        // Persist asynchronously
                        CoroutineScope(Dispatchers.IO).launch {
                            if (newAccent == AppAccent.CUSTOM) {
                                val hex = "%06X".format(newColor.toArgb() and 0xFFFFFF)
                                AppSettings.setCustomHex(applicationContext, hex)
                            } else {
                                AppSettings.setAccent(applicationContext, newAccent)
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()) {
            CoroutineScope(Dispatchers.IO).launch {
                cleanupMinifyTrashOnStartup(applicationContext)
            }
        }
    }

    private fun resolveColor(accent: AppAccent, customHex: String?): Color {
        return if (accent == AppAccent.CUSTOM && customHex != null) {
            try { Color(android.graphics.Color.parseColor("#$customHex")) }
            catch (_: Exception) { AppAccent.PURPLE.color }
        } else {
            accent.color
        }
    }

    private fun requestManageStorageIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            CoroutineScope(Dispatchers.IO).launch { cleanupMinifyTrashOnStartup(applicationContext) }
            return
        }
        if (Environment.isExternalStorageManager()) {
            CoroutineScope(Dispatchers.IO).launch { cleanupMinifyTrashOnStartup(applicationContext) }
        } else {
            val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .apply { data = Uri.parse("package:$packageName") }
            val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            val intent = when {
                specific.resolveActivity(packageManager) != null -> specific
                fallback.resolveActivity(packageManager) != null -> fallback
                else -> null
            }
            intent?.let {
                try { manageStorageLauncher.launch(it) }
                catch (e: Exception) { android.util.Log.w("Minify", "Storage settings: ${e.message}") }
            }
        }
    }
}