package com.shanks.minify.editor

import android.content.Context
import android.view.View
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ja.burhanrashid52.photoeditor.PhotoEditor
import ja.burhanrashid52.photoeditor.PhotoEditorView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation **smoke test** for the host's [PhotoEditor] builder wiring
 * ([configureHostPhotoEditorBuilder]).
 *
 * Pinch-to-scale (Req 2.7) and delete-a-selected-view (Req 2.10) are configured
 * once when the host builds the controller, not through toolbar buttons, so they
 * are verified here against the exact seam the host uses in its `AndroidView`
 * factory:
 *  - `setPinchTextScalable(true)` is applied (pinch scale/rotate — Req 2.7).
 *  - a delete view is wired so a selected text/emoji/sticker view can be removed
 *    (Req 2.10).
 *
 * Both settings are readable on the [PhotoEditor.Builder] before `build()`, and a
 * final `build()` confirms the configured builder produces a controller (smoke).
 *
 * Validates: Requirements 2.7, 2.10.
 */
@RunWith(AndroidJUnit4::class)
class PhotoEditorHostBuilderSmokeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun configureHostPhotoEditorBuilder_enablesPinchAndWiresDeleteView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            val editorView = PhotoEditorView(context)
            val deleteView: View = ImageView(context)

            val builder = configureHostPhotoEditorBuilder(
                PhotoEditor.Builder(context, editorView),
                deleteView,
            )

            // Req 2.7: pinch-to-scale/rotate is enabled for added views.
            assertTrue("pinch-to-scale should be enabled", builder.isTextPinchScalable)
            // Req 2.10: the delete target is wired so a selected view can be removed.
            assertSame(deleteView, builder.deleteView)

            // Smoke: the configured builder yields a controller without throwing.
            val editor = builder.build()
            assertNotNull(editor)
        }
    }
}
