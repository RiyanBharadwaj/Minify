package com.shanks.minify.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FunctionSection(
    selectedUri: Uri?,
    quality: Int,
    isCompressing: Boolean,       // NEW: disables button while compression is running
    onQuality: (Int) -> Unit,
    onStart: (Uri) -> Unit
) {
    val qualityLabels = listOf("Poor", "Okay", "Normal", "Good", "Excellent", "Max")

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Quality",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = qualityLabels[quality],
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Slider(
                value = quality.toFloat(),
                onValueChange = { onQuality(it.toInt().coerceIn(0, 5)) },
                valueRange = 0f..5f,
                steps = 4,
                enabled = !isCompressing,   // Also lock slider during compression
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                qualityLabels.forEach { label ->
                    Text(
                        text = label.first().toString(),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { selectedUri?.let(onStart) },
                // Disabled if no video selected OR compression already running
                enabled = selectedUri != null && !isCompressing,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (isCompressing) "Compressing…" else "Start Compression",
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}