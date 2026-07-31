package com.shanks.minify.media3

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Instrumentation test that pins the cancellation contract closing the async gap
 * between requesting a video export and the [CompressionService] publishing its
 * live [CompressionMonitor.activeJob] handle (Req 17.2).
 *
 * `MediaExporter.exportVideo` starts the foreground service asynchronously and hands
 * the caller a **service-backed** [CompressionJob]: a handle whose internal
 * `transformer` is `null` (the real Transformer lives inside the not-yet-started
 * service) but whose `onCancel` hook stops the service. Task 18.5 made
 * `MediaEditorScreen` retain that handle and call `exportJob?.cancel()` alongside
 * `CompressionMonitor.activeJob?.cancel()`, so a user who cancels before the service
 * publishes `activeJob` still stops the export.
 *
 * This test exercises that handle directly, constructed exactly the way
 * `MediaExporter.exportVideo` builds it — `CompressionJob(AtomicBoolean(false), null,
 * onCancel = { ... })` — to assert:
 *  1. A service-backed handle (null transformer, non-null onCancel) is NOT dead: it
 *     still controls the export via its onCancel hook.
 *  2. Cancelling that retained handle runs the onCancel hook (stopping the service
 *     path) and sets the shared cancel flag, without ever needing `activeJob`.
 *  3. A genuinely dead handle (null transformer, null onCancel) reports `isDead`,
 *     pinning the distinction that makes the service-backed handle useful.
 *
 * Validates Requirements 17.2.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExportJobCancelTest {

    @Test
    fun serviceBackedHandle_isNotDead_evenWithNullTransformer() {
        // Req 17.2: the handle exportVideo returns before the service publishes its
        // activeJob has no in-process transformer, yet it controls the export through
        // its onCancel hook, so it must not be considered dead.
        val serviceStopped = AtomicBoolean(false)
        val handle = CompressionJob(
            AtomicBoolean(false),
            null,
            onCancel = { serviceStopped.set(true) },
        )

        assertFalse(
            "A service-backed handle (null transformer, non-null onCancel) must not be dead",
            handle.isDead,
        )
    }

    @Test
    fun cancellingRetainedHandle_stopsExport_withoutActiveJob() {
        // Req 17.2: cancelling the retained handle before activeJob is published still
        // stops the export path. This models MediaEditorScreen calling exportJob.cancel()
        // in the async gap where CompressionMonitor.activeJob is still null.
        val cancelFlag = AtomicBoolean(false)
        val serviceStopped = AtomicBoolean(false)
        val handle = CompressionJob(
            cancelFlag,
            null,
            onCancel = { serviceStopped.set(true) },
        )

        assertFalse("onCancel must not have run before cancel()", serviceStopped.get())
        assertFalse("cancel flag must be clear before cancel()", cancelFlag.get())

        handle.cancel()

        assertTrue(
            "Cancelling the retained handle must run onCancel to stop the service",
            serviceStopped.get(),
        )
        assertTrue(
            "Cancelling the retained handle must set the shared cancel flag",
            cancelFlag.get(),
        )
    }

    @Test
    fun fullyDeadHandle_reportsDead() {
        // Pin the distinction: a handle with neither a transformer nor an onCancel hook
        // controls nothing and is dead. Only the non-null onCancel above rescues the
        // service-backed handle from this state.
        val deadHandle = CompressionJob(AtomicBoolean(false), null)

        assertTrue(
            "A handle with null transformer and null onCancel controls nothing and is dead",
            deadHandle.isDead,
        )
    }
}
