package com.shanks.minify.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen() {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("No file") }

    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Idle") }

    val context = LocalContext.current
    // Added scroll state to handle small screens
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .verticalScroll(scrollState) // This makes the UI scrollable
            .padding(12.dp)
    ) {
        FileSection(
            onFileSelected = { (uri, name) ->
                selectedUri = uri
                fileName = name
            },
            fileName = fileName,
            selectedUri = selectedUri
        )

        Spacer(modifier = Modifier.height(12.dp))

        FunctionSection(
            selectedUri = selectedUri,
            onProgress = {
                progress = it
                status = "Compressing..."
            },
            onSuccess = {
                status = "Done ✅"
                try {
                    context.cacheDir.deleteRecursively()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            onFailure = {
                progress = 0f
                status = "Failed ❌"
            },
            onClear = {
                selectedUri = null
                fileName = "No file"
                progress = 0f
                status = "Idle"
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        ProgressSection(progress, status)

        // Extra spacer at the bottom helps with padding when scrolled all the way down
        Spacer(modifier = Modifier.height(24.dp))
    }
}