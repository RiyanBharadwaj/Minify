package com.shanks.minify.ui.nav

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.shanks.minify.photo.PhotoResult
import com.shanks.minify.ui.CodecChoice
import com.shanks.minify.ui.EditState

/**
 * The three top-level tabs, rendered in this fixed order (Req 3.1).
 */
enum class MinifyTab(val label: String) {
    VIDEO("Video"),
    PHOTO("Photo"),
}

/**
 * User-entered state for the Video tab.
 *
 * These are the values [com.shanks.minify.ui.MainScreen] currently keeps in
 * `rememberSaveable`. Hoisting them to the navigator level lets them survive
 * tab switches within a session, not just configuration changes (Req 3.6).
 *
 * @param initialCodec the codec to select initially; the caller typically
 *   computes this from the device's codec availability.
 */
class VideoTabState(initialCodec: CodecChoice = CodecChoice.H265) {
    var selectedUri: Uri? by mutableStateOf(null)
    var sizePresetIdx: Int by mutableIntStateOf(2)
    var customSizeMb: Float? by mutableStateOf(null)
    var codecChoice: CodecChoice by mutableStateOf(initialCodec)
    var editState: EditState by mutableStateOf(EditState())

    companion object {
        /**
         * Persists the Video tab's user-entered inputs across Activity recreation
         * (configuration change or process death — the latter is common while the
         * LibreCuts editor is exporting a large video). The transient [editState]
         * is intentionally not saved: it is reset to neutral whenever the source
         * changes, so a fresh [EditState] on restore is correct.
         */
        val Saver: Saver<VideoTabState, Any> = listSaver(
            save = { state ->
                listOf(
                    state.selectedUri?.toString(),
                    state.sizePresetIdx,
                    state.customSizeMb,
                    state.codecChoice.name,
                )
            },
            restore = { saved ->
                VideoTabState(initialCodec = CodecChoice.valueOf(saved[3] as String)).apply {
                    selectedUri = (saved[0] as String?)?.let(Uri::parse)
                    sizePresetIdx = saved[1] as Int
                    customSizeMb = saved[2] as Float?
                }
            },
        )
    }
}

/**
 * User-entered state for the Photo tab, hoisted to the navigator so it survives
 * tab switches within a session (Req 3.6).
 */
class PhotoTabState {
    var selectedUri: Uri? by mutableStateOf(null)
    var targetSizeMb: Float? by mutableStateOf(null)
    var lastResult: PhotoResult? by mutableStateOf(null)

    companion object {
        /**
         * Persists the Photo tab's selection and target size across Activity
         * recreation. [lastResult] is transient (a just-finished compression
         * result) and is not saved.
         */
        val Saver: Saver<PhotoTabState, Any> = listSaver(
            save = { state ->
                listOf(state.selectedUri?.toString(), state.targetSizeMb)
            },
            restore = { saved ->
                PhotoTabState().apply {
                    selectedUri = (saved[0] as String?)?.let(Uri::parse)
                    targetSizeMb = saved[1] as Float?
                }
            },
        )
    }
}

/**
 * Hosts the three tabs (Video, Photo, Tools) with a single persistent
 * selected-state indicator (Req 3.1, 3.3) and preserves each tab's transient
 * composition state across switches via a [SaveableStateHolder] (Req 3.6).
 *
 * The per-tab **user-entered** state ([VideoTabState]/[PhotoTabState]) is
 * hoisted by the caller and passed in, so it lives across the whole session.
 * Tab content is supplied as composable slots; the concrete Video/Photo/Tools
 * wiring is handled by later tasks.
 */
@Composable
fun TabNavigator(
    videoContent: @Composable () -> Unit,
    photoContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    showTabBar: Boolean = true,
) {
    var selectedTab by remember { mutableStateOf(MinifyTab.VIDEO) }
    val stateHolder: SaveableStateHolder = rememberSaveableStateHolder()

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // The tab row is hidden while a full-screen editor is open (e.g. the
            // Photo editor) so the editor uses the whole screen.
            if (showTabBar) {
                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    MinifyTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.label) },
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                stateHolder.SaveableStateProvider(key = selectedTab.name) {
                    when (selectedTab) {
                        MinifyTab.VIDEO -> videoContent()
                        MinifyTab.PHOTO -> photoContent()
                    }
                }
            }
        }
    }
}
