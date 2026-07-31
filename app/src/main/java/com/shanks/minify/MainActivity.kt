package com.shanks.minify

import com.shanks.minify.ui.SetupScreen
import android.os.Build
import android.os.Bundle
import android.os.Environment
import com.google.android.gms.ads.MobileAds
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.toColorInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shanks.minify.logic.CodecDefault
import com.shanks.minify.ui.AppSettings
import com.shanks.minify.ui.CodecAvailability
import com.shanks.minify.ui.PhotoTab
import com.shanks.minify.ui.VideoTab
import com.shanks.minify.ads.AdManager
import com.shanks.minify.media3.CompressionMonitor
import com.shanks.minify.ui.nav.PhotoTabState
import com.shanks.minify.ui.nav.TabNavigator
import com.shanks.minify.ui.nav.VideoTabState
import com.shanks.minify.ui.theme.AppAccent
import com.shanks.minify.ui.theme.MinifyTheme
import com.shanks.minify.utils.cleanupMinifyTrashOnStartup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.media3.common.util.UnstableApi
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        /*
        val testDeviceIds = listOf("3229A0AAC99A59BCB210E710DD77FACA")
        val configuration = com.google.android.gms.ads.RequestConfiguration.Builder()
            .setTestDeviceIds(testDeviceIds)
            .build()
        MobileAds.setRequestConfiguration(configuration)
        */

        MobileAds.initialize(this) {}
        AdManager.initialize(applicationContext)

        setContent {
            val setupDone by AppSettings.setupDoneFlow(applicationContext).collectAsStateWithLifecycle(initialValue = null)
            val isCompressing by CompressionMonitor.isCompressing.collectAsStateWithLifecycle()
            val loadedAccent by produceState<AppAccent?>(null) {
                value = withContext(Dispatchers.IO) { AppSettings.accentFlow(applicationContext).first() }
            }
            val loadedHex by produceState<String?>(null) {
                value = withContext(Dispatchers.IO) { AppSettings.customHexFlow(applicationContext).first() }
            }

            if (setupDone == null || loadedAccent == null) return@setContent

            // Recurring Interstitial Ads during video compression (Approved Plan).
            // Shows an ad shortly after start and then every 45s if still running.
            LaunchedEffect(isCompressing) {
                if (isCompressing) {
                    while (CompressionMonitor.isCompressing.value) {
                        AdManager.showInterstitial(this@MainActivity) {
                            // On dismissed, the loop continues and will hit the delay
                        }
                        delay(45_000L)
                    }
                }
            }

            var accent by remember(loadedAccent) { mutableStateOf(loadedAccent!!) }
            var accentColor by remember(loadedAccent, loadedHex) { mutableStateOf(resolveColor(loadedAccent!!, loadedHex)) }

            MinifyTheme(accentColor = accentColor, accent = accent) {
                if (setupDone == false) {
                    SetupScreen(onComplete = {
                        CoroutineScope(Dispatchers.IO).launch {
                            AppSettings.setSetupDone(applicationContext, true)
                        }
                    })
                } else {
                    // Session-scoped tab state: hoisted here so each tab's
                    // user-entered inputs survive tab switches for the whole
                    // session (Req 3.6). VideoTabState seeds its codec the same
                    // way MainScreen's wrapper used to.
                    // rememberSaveable so a selected video/photo and the chosen
                    // size/codec survive Activity recreation — including process
                    // death while the LibreCuts editor is exporting a large video,
                    // which previously made the app appear to reset.
                    val videoState = rememberSaveable(saver = VideoTabState.Saver) {
                        VideoTabState(
                            initialCodec = CodecDefault.initialChoice { CodecAvailability.isSupported(it) }
                        )
                    }
                    val photoState = rememberSaveable(saver = PhotoTabState.Saver) { PhotoTabState() }

                    // True while the Photo editor overlay is open full-screen, so the
                    // tab bar can be hidden.
                    var photoEditorFullscreen by remember { mutableStateOf(false) }

                    val onAccentChange: (AppAccent, Color) -> Unit = { newAccent, newColor ->
                        accent = newAccent; accentColor = newColor
                        CoroutineScope(Dispatchers.IO).launch {
                            if (newAccent == AppAccent.CUSTOM) {
                                AppSettings.setCustomHex(applicationContext, "%06X".format(newColor.toArgb() and 0xFFFFFF))
                            } else AppSettings.setAccent(applicationContext, newAccent)
                        }
                    }

                    // Video is selected initially (Req 3.2); TabNavigator
                    // defaults its selected tab to Video.
                    TabNavigator(
                        videoContent = { VideoTab(videoState, accent, accentColor, onAccentChange) },
                        photoContent = {
                            PhotoTab(
                                photoState = photoState,
                                onFullscreenChange = { photoEditorFullscreen = it },
                            )
                        },
                        showTabBar = !photoEditorFullscreen,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if ((Build.VERSION.SDK_INT >= 30) && Environment.isExternalStorageManager()) {
            CoroutineScope(Dispatchers.IO).launch { cleanupMinifyTrashOnStartup(applicationContext) }
        }
    }

    private fun resolveColor(accent: AppAccent, customHex: String?) =
        if ((accent == AppAccent.CUSTOM) && (customHex != null)) try { Color("#$customHex".toColorInt()) } catch (_: Exception) { AppAccent.PURPLE.color }
        else accent.color
}
