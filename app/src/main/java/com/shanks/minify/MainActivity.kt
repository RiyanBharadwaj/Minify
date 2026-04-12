package com.shanks.minify

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.shanks.minify.ui.MainScreen
import com.shanks.minify.ui.theme.MinifyTheme
@androidx.media3.common.util.UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MinifyTheme {
                MainScreen()
            }
        }
    }
}