package com.shanks.minify.media3

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation test for the foreground-start failure routing contract
 * (Req 15.2, 15.3).
 *
 * `CompressionService` (task 17.3) promotes itself to a foreground service to
 * run a compression. On some devices/OS states `startForeground(...)` can throw
 * (e.g. `ForegroundServiceStartNotAllowedException`). The fix guards that call
 * and, on failure, routes through `CompressionMonitor.onFailure(...)` and stops
 * the service. Because deterministically forcing a real foreground-start
 * failure on a device is impractical, this test validates the *observable
 * outcome* the service guarantees through [CompressionMonitor] — the single
 * routing point the UI observes.
 *
 * It asserts that, once an export has started (busy state raised), invoking the
 * exact failure path the service's catch block runs (`onFailure(...)`):
 *   - clears the busy flag so the UI is not left spinning (Req 15.3), and
 *   - publishes a failure status so the UI can surface the error (Req 15.2).
 *
 * No real Transformer, service, or foreground promotion is launched, keeping
 * the test deterministic.
 */
@RunWith(AndroidJUnit4::class)
class CompressionMonitorFailureRoutingTest {

    @After
    fun tearDown() {
        // Keep the shared singleton clean for other tests.
        CompressionMonitor.resetStatus()
    }

    @Test
    fun foregroundStartFailure_clearsBusyState_andRoutesFailure() {
        // 1. Starting an export raises the busy flag (mirrors the service calling
        //    onStart before promoting to foreground).
        CompressionMonitor.onStart(beforeSize = 123L)
        assertTrue(
            "onStart should mark the monitor as compressing",
            CompressionMonitor.isCompressing.value,
        )

        // 2. Simulate the foreground-start failure path exactly as the service's
        //    catch block does.
        val message = "Could not start export: test"
        CompressionMonitor.onFailure(message)

        assertFalse(
            "A foreground-start failure must clear isCompressing so the UI stops " +
                "showing a busy state (Req 15.3)",
            CompressionMonitor.isCompressing.value,
        )
        val status = CompressionMonitor.status.value
        assertTrue(
            "A foreground-start failure must route a failure status (Req 15.2), " +
                "but status was '$status'",
            status.startsWith("error:"),
        )
        assertTrue(
            "The failure status should carry the failure message, but status was " +
                "'$status'",
            status.contains(message),
        )

        // 3. Reset so the test leaves no residual state.
        CompressionMonitor.resetStatus()
        assertEquals("", CompressionMonitor.status.value)
    }
}
