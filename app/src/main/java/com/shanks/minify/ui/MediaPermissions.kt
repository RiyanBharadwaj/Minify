package com.shanks.minify.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.shanks.minify.platform.MediaOperation
import com.shanks.minify.platform.PermissionPolicy

/**
 * Returns a runner that guarantees the runtime permissions required for a
 * [MediaOperation] (per [PermissionPolicy.requiredPermissions] evaluated for the
 * running API level) are granted before executing the supplied operation.
 *
 * Behavior (Requirements 1.7, 1.8):
 * - The exact permission set for the operation on `Build.VERSION.SDK_INT` is
 *   requested; already-granted permissions are not re-requested.
 * - When every required permission is granted, the operation runs.
 * - When any required permission is denied, the operation is NOT run (it is
 *   halted before any media is touched) and [onDenied] is invoked with a
 *   human-readable name of the first missing permission so the caller can show
 *   a message identifying it.
 *
 * Because the operation only runs after all permissions are granted, a denial
 * leaves media files unchanged.
 */
@Composable
fun rememberMediaPermissionRunner(
    onDenied: (String) -> Unit,
): (MediaOperation, () -> Unit) -> Unit {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingPermissions by remember { mutableStateOf<List<String>>(emptyList()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = pendingPermissions.firstOrNull { grants[it] != true }
        val action = pendingAction
        pendingAction = null
        pendingPermissions = emptyList()
        if (denied == null) action?.invoke() else onDenied(permissionDisplayName(denied))
    }

    return { operation, onGranted ->
        val required = PermissionPolicy.requiredPermissions(Build.VERSION.SDK_INT, operation)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onGranted()
        } else {
            pendingAction = onGranted
            pendingPermissions = missing
            launcher.launch(missing.toTypedArray())
        }
    }
}

/** Maps a raw Android permission string to a user-facing name for denial messages. */
fun permissionDisplayName(permission: String): String = when (permission) {
    Manifest.permission.WRITE_EXTERNAL_STORAGE -> "Storage write permission (WRITE_EXTERNAL_STORAGE)"
    Manifest.permission.READ_MEDIA_VIDEO -> "Video access permission (READ_MEDIA_VIDEO)"
    Manifest.permission.READ_MEDIA_IMAGES -> "Image access permission (READ_MEDIA_IMAGES)"
    else -> permission.substringAfterLast('.')
}
