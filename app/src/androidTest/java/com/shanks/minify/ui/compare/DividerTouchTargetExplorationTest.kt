package com.shanks.minify.ui.compare

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Bug-condition **exploration** test for the before/after compare **divider touch
 * target** (Property 4 in the design: "Large invisible divider touch target with a
 * thin visible line").
 *
 * These assertions encode the **expected (fixed)** behavior:
 *  - the divider exposes a touch target *substantially larger* than the visible line,
 *    so a horizontal drag that starts a few dp **off** the thin (~3dp) visible line
 *    still grabs and moves the divider (C_touch).
 *
 * On the unfixed code the *only* draggable region is the 3dp-wide white line
 * (`handleWidthDp = 3.dp`), so a press ~12dp off the line misses the divider entirely
 * and `dividerFraction` never changes. This test is therefore **expected to FAIL on
 * the unfixed code** — the failure is the counterexample that confirms the hit area
 * equals the visible line. It will pass once the fix from task 7 lands (task 7.8
 * re-runs this exact test).
 *
 * We observe `dividerFraction` indirectly through the rendered crop boundary. The
 * color-to-side mapping below reflects the **corrected layer order** (task 7.2): in the
 * shared `CompareWipeOverlay` the ORIGINAL ("before", RED) fills the whole region as the
 * bottom layer and the EDITED ("after", BLUE) is drawn on top, cropped to the leading
 * region `[0, dividerFraction × width]`. So the LEADING (left) region shows the EDITED
 * (blue) top layer and the TRAILING (right) region reveals the ORIGINAL (red) beneath;
 * the position where blue meets red is the divider. Moving the divider to the right grows
 * the edited (blue) leading region, turning a point that was red (right of the divider)
 * into blue. Gesture injection mirrors the `VideoComparatorTest` Compose-instrumentation
 * style.
 *
 * Validates Requirement 1.5 (the reported defect) and, once fixed, Requirement 2.5.
 */
@RunWith(AndroidJUnit4::class)
class DividerTouchTargetExplorationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ORIGINAL ("before") is RED, EDITED ("after") is BLUE. On the FIXED shared
    // CompareWipeOverlay the ORIGINAL fills the whole region as the bottom layer and the
    // EDITED is drawn on top, cropped to the leading region [0, fraction × width]. So the
    // leading (left) region shows the EDITED (blue) top layer and the trailing (right)
    // region reveals the ORIGINAL (red) beneath — the blue/red boundary marks the divider.
    private val originalColor = android.graphics.Color.RED
    private val editedColor = android.graphics.Color.BLUE

    private var beforeImage: File? = null
    private var afterImage: File? = null

    @After
    fun tearDown() {
        listOf(beforeImage, afterImage).forEach { it?.delete() }
        beforeImage = null
        afterImage = null
    }

    // ---------------------------------------------------------------------------------------------
    // C_touch: a drag that starts ~12dp OFF the visible 3dp line must still move the divider.
    // ---------------------------------------------------------------------------------------------

    /**
     * Two contrasting drags on a single render:
     *
     *  1. **Reference (on the line):** a horizontal drag that starts *exactly on* the
     *     visible divider line moves the divider — the edited (blue) leading region grows,
     *     turning a point that was red (original) into blue (edited). This proves both that
     *     the gesture injection works and that a press on the line grabs the divider.
     *  2. **C_touch counterexample (off the line):** a horizontal drag that starts ~12dp
     *     to the side of the (now shifted) divider line — well outside the ~2dp visible
     *     line but well within the large invisible touch target — must also move the
     *     divider under the expected (fixed) behavior.
     *
     * Were the hit area only the thin (~2dp) visible line, the off-line drag would start
     * off the draggable `Box`, the divider would never be grabbed, `dividerFraction` would
     * be unchanged, and the sample point would stay RED — the second assertion would FAIL
     * (the C_touch counterexample "drag 12dp off the line leaves dividerFraction
     * unchanged"). That is exactly what keeps this test able to detect a thin-only touch
     * target. At `scale == 1` the outer pinch/pan gesture is a no-op (`clampPan` pins pan
     * to 0), so the off-line drag cannot move the imagery by any other path — the only way
     * the sample point can turn blue is the large invisible divider target grabbing the
     * drag.
     */
    @Test
    fun dragOffTheVisibleLine_stillMovesTheDivider() {
        beforeImage = createSolidImage(originalColor, "touch_before_original.png")
        afterImage = createSolidImage(editedColor, "touch_after_edited.png")

        composeRule.setContent {
            ImageComparator(
                before = Uri.fromFile(beforeImage),
                after = Uri.fromFile(afterImage),
            )
        }

        val before = waitForRender()
        assertNotNull("the comparator images never rendered (still on the loading spinner)", before)
        val shot = before!!
        val widthPx = shot.width
        val heightPx = shot.height
        val y = 0.5f * heightPx
        val density = composeRule.density
        val offLineOffsetPx = with(density) { 12.dp.toPx() }

        // The divider opens centered (0.5). With the corrected layer order the leading (left)
        // region shows the EDITED (blue) top layer and the trailing (right) region reveals the
        // ORIGINAL (red) beneath, so a point right of center starts on the ORIGINAL (red) side.
        val referencePointX = 0.62f
        assertTrue(
            "precondition: point right of the centered divider should start on the ORIGINAL (red) side, " +
                "but was ${shot.colorAt(referencePointX, 0.5f)}",
            shot.colorAt(referencePointX, 0.5f).isApprox(originalColor),
        )

        // (1) Reference drag ON the visible line: from the centered divider (0.5) rightward to
        // ~0.70. The edited (blue) leading region grows to ~0.70, so the 0.62 reference point —
        // now inside the leading region — becomes BLUE. This proves gesture injection works and
        // that a press on the line grabs the divider.
        dragHorizontally(fromX = 0.5f * widthPx, toX = 0.70f * widthPx, y = y)
        val afterOnLine = waitForRender()
        assertNotNull("failed to capture the comparator after the on-line reference drag", afterOnLine)
        assertTrue(
            "reference: a drag ON the visible line must move the divider, so the point at " +
                "$referencePointX should become the EDITED (blue), but was " +
                "${afterOnLine!!.colorAt(referencePointX, 0.5f)} (gesture injection or on-line hit is broken)",
            afterOnLine.colorAt(referencePointX, 0.5f).isApprox(editedColor),
        )

        // The divider now sits at ~0.70; a point at 0.80 is right of it, in the trailing region,
        // so it reveals the ORIGINAL (red) beneath.
        val offLinePointX = 0.80f
        assertTrue(
            "precondition: point right of the shifted divider should be on the ORIGINAL (red) side, " +
                "but was ${afterOnLine.colorAt(offLinePointX, 0.5f)}",
            afterOnLine.colorAt(offLinePointX, 0.5f).isApprox(originalColor),
        )

        // (2) C_touch counterexample: drag starting ~12dp to the RIGHT of the divider line
        // (off the ~2dp visible line, but within the large invisible target) rightward past the
        // 0.80 point. Under the fixed behavior the large invisible target grabs the divider, the
        // edited (blue) leading region grows past 0.80, and 0.80 becomes BLUE. If the touch
        // target were only the thin line this drag would miss it and 0.80 would stay RED.
        dragHorizontally(fromX = 0.70f * widthPx + offLineOffsetPx, toX = 0.97f * widthPx, y = y)
        val afterOffLine = waitForRender()
        assertNotNull("failed to capture the comparator after the off-line drag", afterOffLine)
        val moved = afterOffLine!!.colorAt(offLinePointX, 0.5f)

        assertTrue(
            "C_touch: a drag starting ~12dp off the ~2dp visible line must still move the divider, " +
                "so the point at $offLinePointX should become the EDITED (blue) after the drag, but was $moved — " +
                "the divider did not move (the touch target equals the thin visible line)",
            moved.isApprox(editedColor),
        )
    }

    /**
     * Injects a horizontal drag from [fromX] to [toX] at height [y], clearing touch slop.
     *
     * Events are spaced in time via [advanceEventTime] so the gesture detector processes
     * each incremental move (rather than collapsing them into a single jump), which is what
     * lets `detectHorizontalDragGestures` recognize the drag and win the pointer over the
     * comparator's outer transform (pinch/pan) detector.
     */
    private fun dragHorizontally(fromX: Float, toX: Float, y: Float) {
        composeRule.onRoot().performTouchInput {
            down(Offset(fromX, y))
            val steps = 16
            val dx = (toX - fromX) / steps
            for (i in 1..steps) {
                advanceEventTime(16L)
                moveTo(Offset(fromX + dx * i, y))
            }
            advanceEventTime(16L)
            up()
        }
        composeRule.waitForIdle()
    }

    /**
     * Reference (documenting the hit area): the *same* gesture applied **on** the thin
     * (~2dp) visible line moves the divider. This is verified up-front inside
     * [dragOffTheVisibleLine_stillMovesTheDivider] (the on-line drag moves the crop boundary
     * before the off-line drag is attempted), so the two contrasting behaviors — "on the
     * line works, and ~12dp off it also works once the large invisible target is present" —
     * are captured in one reliable render. If the touch target were only the thin visible
     * line, the off-line drag would leave the divider unchanged, so the test still detects a
     * thin-only touch target.
     */

    // ---------------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------------

    /**
     * Polls the rendered root until the comparator images are decoded and drawn (past the
     * loading spinner), detected by a saturated red/blue appearing at the sample points.
     */
    private fun waitForRender(): ImageBitmap? {
        composeRule.waitForIdle()
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            val candidate = composeRule.onRoot().captureToImage()
            if (candidate.colorAt(0.35f, 0.5f).isSaturated() || candidate.colorAt(0.62f, 0.5f).isSaturated()) {
                return candidate
            }
            Thread.sleep(150)
        }
        return null
    }

    /** Creates a solid-color, very wide (4:1) still image so a ContentScale.Fit render is width-limited. */
    private fun createSolidImage(color: Int, name: String): File {
        val bmp = Bitmap.createBitmap(1200, 300, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        val file = File(context.cacheDir, name)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    private fun ImageBitmap.colorAt(xFrac: Float, yFrac: Float): Color {
        val map = toPixelMap()
        val x = (xFrac * width).toInt().coerceIn(0, width - 1)
        val y = (yFrac * height).toInt().coerceIn(0, height - 1)
        return map[x, y]
    }

    /** Approximate color match, tolerant of PNG/decoder rounding, ignoring alpha. */
    private fun Color.isApprox(argb: Int, tol: Float = 0.25f): Boolean {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return kotlin.math.abs(red - r) <= tol &&
            kotlin.math.abs(green - g) <= tol &&
            kotlin.math.abs(blue - b) <= tol
    }

    /** True for a saturated red or blue — i.e. one of the comparator images has been drawn here. */
    private fun Color.isSaturated(): Boolean {
        val isRed = red > 0.5f && green < 0.35f && blue < 0.35f
        val isBlue = blue > 0.5f && red < 0.35f && green < 0.35f
        return isRed || isBlue
    }
}

/**
 * Test-only overload adapting the legacy `(before, after)` call style used by this
 * instrumentation test onto the current [ImageComparator] `source` API.
 */
@androidx.compose.runtime.Composable
private fun ImageComparator(before: Uri, after: Uri) =
    ImageComparator(com.shanks.minify.ui.ComparisonSource.Images(before = before, after = after))
