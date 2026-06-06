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
import com.shanks.minify.ui.MainScreen
import com.shanks.minify.ui.theme.MinifyTheme
import com.shanks.minify.utils.cleanupMinifyTrashOnStartup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@androidx.media3.common.util.UnstableApi
class MainActivity : ComponentActivity() {

    // Launcher for the MANAGE_EXTERNAL_STORAGE settings screen.
    // When the user returns from settings we re-run cleanup — now with full access.
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // User returned from the settings screen — attempt cleanup regardless
        // of whether they granted it (they may have, and we'll now find the files).
        CoroutineScope(Dispatchers.IO).launch {
            cleanupMinifyTrashOnStartup(applicationContext)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request MANAGE_EXTERNAL_STORAGE if not already granted.
        // This is the only permission that lets File.listFiles() see files created
        // by other apps (including system-trashed files) in shared storage.
        // Without it, scoped storage hides everything our app didn't create.
        requestManageStorageIfNeeded()

        setContent {
            MinifyTheme {
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Also run cleanup on every resume in case permission was granted
        // between sessions (e.g. user granted it from system settings manually).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()) {
            CoroutineScope(Dispatchers.IO).launch {
                cleanupMinifyTrashOnStartup(applicationContext)
            }
        }
    }

    private fun requestManageStorageIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Below Android 11 — regular READ/WRITE_EXTERNAL_STORAGE is enough,
            // and File.listFiles() already sees all files. Just run cleanup.
            CoroutineScope(Dispatchers.IO).launch {
                cleanupMinifyTrashOnStartup(applicationContext)
            }
            return
        }

        if (Environment.isExternalStorageManager()) {
            // Already granted — run cleanup immediately.
            CoroutineScope(Dispatchers.IO).launch {
                cleanupMinifyTrashOnStartup(applicationContext)
            }
        } else {
            // Not granted — send user to the all-files-access settings screen.
            // ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION was added in API 30 but
            // some early Android 11 OEM builds don't resolve it — the Intent
            // constructor doesn't throw, the failure happens at launch time.
            // We check resolveActivity first, then fall back to the general
            // storage management screen, then silently skip if neither works.
            val specificIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
            ).apply { data = Uri.parse("package:$packageName") }

            val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

            val intentToLaunch = when {
                specificIntent.resolveActivity(packageManager) != null -> specificIntent
                fallbackIntent.resolveActivity(packageManager) != null -> fallbackIntent
                else -> null  // Neither resolves — device doesn't support the flow
            }

            if (intentToLaunch != null) {
                try {
                    manageStorageLauncher.launch(intentToLaunch)
                } catch (e: Exception) {
                    // Last-resort catch — if launch throws on a broken OEM build,
                    // silently skip. Cleanup will just not see the trash files.
                    android.util.Log.w("Minify", "Could not open storage settings: ${e.message}")
                }
            }
        }
    }
}