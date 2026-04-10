package com.shanks.minify.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ProgressSection(progress: Float, status: String) {

    val statusColor = when {
        status.contains("Done") -> Color(0xFF4CAF50)
        status.contains("Failed") -> Color(0xFFF44336)
        status.contains("Compressing") -> Color(0xFFFFAB00)
        else -> Color.Gray
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text("Progress", color = Color.White)

        Spacer(modifier = Modifier.height(4.dp))

        Text(status, color = statusColor)

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text("${(progress * 100).toInt()}%", color = statusColor)
    }
}