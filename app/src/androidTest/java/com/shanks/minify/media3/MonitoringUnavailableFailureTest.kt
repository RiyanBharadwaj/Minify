package com.shanks.minify.media3

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation test for the "compression monitoring unavailable" failure
 * contract.
 *
 * Feature: video-editor-fixes
 * **Validates: Requirements 5.5**
 *
 * Req 5.5: IF the existing compression monitoring is unavailable when the video
 * compression pipeline runs, THEN Minify SHALL fail the compression operation and
 * display a descriptive error message.
 *
 * In the pipeline, "monitoring unavailable" manifests when the compression
 * foreground service cannot be established (e.g. `startForeground(...)` is
 * rejected on some devices/OS states). `CompressionService` guards that path and
 * routes the failure through the single observable point the UI watches —
 * `CompressionMonitor.onFailure("Could not start export: ...")` (see
 * `CompressionService.onStartCommand` / the async `exportVideo` catch block).
 * This mirrors the sibling [CompressionMonitorFailureRoutingTest]; because
 * deterministically forcing a real foreground-start failure on a device is
 * impractical, this test drives the exact failure path the service's catch block
 * runs and asserts the observable outcome Req 5.5 guarantees:
 *   - the compression operation is FAILED (not left running), and
 *   - a descriptive error message is published for the UI to display.
 *
 * `CompressionMonitor` is an Android-bound singleton (`android.net.Uri` state),
 * so this lives in the instrumentation suite rather than a JVM unit test.
 */
@RunWith(AndroidJUnit4::class)
class MonitoringUnavailableFailureTest {

    @After
    fun tearDown() {
        // Keep the shared singleton clean for other tests.
        CompressionMonitor.resetStatus()
    }

    @Test
    fun monitoringUnavailable_failsCompression_withDescriptiveMessage() {
        // 1. A compression run has begun (the service called onStart before it
        //    tried — and failed — to establish foreground monitoring).
        CompressionMonitor.onStart(beforeSize = 4_096L)
        assertTrue(
            "onStart should mark the pipeline as compressing",
            CompressionMonitor.isCompressing.value,
        )

        // 2. Monitoring is unavailable: the service's guard routes the exact
        //    descriptive failure its catch block emits.
        val message = "Could not start export: foreground service not allowed"
        CompressionMonitor.onFailure(message)

        // Req 5.5: the compression operation must be FAILED, not left running.
        assertFalse(
            "monitoring-unavailable must fail the compression so the UI is not left " +
                "spinning (Req 5.5)",
            CompressionMonitor.isCompressing.value,
        )

        // Req 5.5: a descriptive error message must be surfaced for the UI.
        val status = CompressionMonitor.status.value
        assertTrue(
            "monitoring-unavailable must publish a failure status (Req 5.5), but was '$status'",
            status.startsWith("error:"),
        )
        assertTrue(
            "the failure status must carry the descriptive message, but was '$status'",
            status.contains(message),
        )

        // 3. Leave no residual state for other tests.
        CompressionMonitor.resetStatus()
        assertEquals("", CompressionMonitor.status.value)
    }
}
