package com.shanks.minify.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanks.minify.platform.MediaOperation

@Composable
fun FileSection(
    selectedUri: Uri?,
    enabled: Boolean = true,             // ← new
    onSelect: (Uri) -> Unit
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(onSelect) }

    // Request the READ_VIDEO permission set (per API level) before opening the
    // picker; on denial, name the permission and halt without selecting a video.
    val runPermissioned = rememberMediaPermissionRunner(onDenied = { name ->
        Toast.makeText(
            context,
            "$name is required to select a video. Operation cancelled.",
            Toast.LENGTH_LONG
        ).show()
    })

    OutlinedCard(
        onClick = { if (enabled) runPermissioned(MediaOperation.READ_VIDEO) { launcher.launch("video/*") } },  // ← guard
        enabled = enabled,                                        // ← greyed out visually
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        border = CardDefaults.outlinedCardBorder(enabled = enabled)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "🎬",
                fontSize = 28.sp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (selectedUri == null) "No video selected" else "Video selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                        .copy(alpha = if (enabled) 1f else 0.38f)
                )
                Text(
                    text = if (selectedUri == null) "Tap to pick a video" else "Tap to change",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                        .copy(alpha = if (enabled) 0.5f else 0.28f)
                )
            }
            if (selectedUri != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary
                        .copy(alpha = if (enabled) 0.12f else 0.06f)
                ) {
                    Text(
                        "✓",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.primary
                            .copy(alpha = if (enabled) 1f else 0.38f)
                    )
                }
            }
        }
    }
}