package com.shanks.minify.media3

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.shanks.minify.MainActivity
import com.shanks.minify.platform.ForegroundServicePolicy
import com.shanks.minify.ui.CodecChoice
import com.shanks.minify.ui.EditState
import com.shanks.minify.ui.editor.model.PlanSegment
import com.shanks.minify.ui.editor.model.TokenKeyedHandoff
import com.shanks.minify.utils.SaveKind
import com.shanks.minify.utils.saveToGallery
import kotlinx.coroutines.*
import java.io.File

@OptIn(UnstableApi::class)
class CompressionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isForeground = false

    private var lastNotifiedPercent = -1
    private var lastNotificationAt = 0L

    companion object {
        private const val CHANNEL_ID = "compression_channel"
        private const val NOTIF_ID = 1001

        /**
         * The unified-media-editor visual/audio/speed passes for pending exports,
         * keyed by the export token they belong to.
         *
         * Media3 [Effect]s and [AudioProcessor]s are not [android.os.Parcelable],
         * so they cannot ride the launch [Intent] like [EditState] does. Instead
         * [start] stashes them here under the export's token and [onStartCommand]
         * consumes them once (take-and-remove) by the `exportToken` intent extra,
         * forwarding them into [VideoCompressor.compress]. A
         * [TokenKeyedHandoff] (rather than a single slot) keeps two quick,
         * back-to-back exports from clobbering each other's passes. The key is
         * the same token that [start] predicts and puts in the intent extra, so
         * correctness does not depend on [CompressionMonitor.onStart]'s internal
         * increment timing.
         */
        private val pendingEffectsByToken = TokenKeyedHandoff<PendingVideoEffects>()

        fun start(
            context: Context,
            inputUri: Uri,
            outputPath: String,
            codec: CodecChoice,
            targetSizeMb: Float,
            editState: EditState,
            beforeSize: Long,
            // Optional unified-media-editor passes. Defaulting to empty keeps the
            // existing trim + crop callers (e.g. MainScreen) byte-for-byte the same.
            videoEffects: List<Effect> = emptyList(),
            audioProcessors: List<AudioProcessor> = emptyList(),
            speed: Float? = null,
            removeAudio: Boolean = false,
            // Optional clockwise rotation recorded on the media-editor geometry
            // (Req 3.4), normalized to {0, 90, 180, 270}. Forwarded to
            // VideoCompressor so it can derive the Presentation output dimensions
            // (swapped on 90/270). Defaults to 0 (no swap) for the plain callers.
            rotationDegrees: Int = 0,
            // Optional unified-media-editor playback plan (reverse/freeze, Req 7.3).
            // Defaulting to empty keeps the plain trim + crop callers unchanged.
            plan: List<PlanSegment> = emptyList(),
        ) {
            // Predict the token CompressionMonitor.onStart will assign to THIS
            // export. Exports are serialized through onStart, which increments the
            // token by 1 each time, so the next export's token is current + 1. This
            // matches the convention already used by MediaEditorScreen
            // (routedToken = CompressionMonitor.token.value + 1).
            val token = CompressionMonitor.token.value + 1L
            val pending = if (
                videoEffects.isEmpty() && audioProcessors.isEmpty() &&
                speed == null && !removeAudio && plan.isEmpty()
            ) {
                null
            } else {
                PendingVideoEffects(videoEffects, audioProcessors, speed, removeAudio, plan)
            }
            // Only stash when there are non-Parcelable passes to carry. Plain
            // trim + crop callers leave the map untouched, so onStartCommand's
            // remove returns null and falls back to the empty defaults.
            if (pending != null) {
                pendingEffectsByToken.put(token, pending)
            }
            val intent = Intent(context, CompressionService::class.java).apply {
                putExtra("inputUri", inputUri.toString())
                putExtra("outputPath", outputPath)
                putExtra("codec", codec.name)
                putExtra("targetSizeMb", targetSizeMb)
                putExtra("editState", editState)
                putExtra("beforeSize", beforeSize)
                putExtra("exportToken", token)
                // Primitive geometry rotation (Req 3.4) rides the intent directly.
                putExtra("rotationDegrees", rotationDegrees)
            }
            // Guard the caller-side start: if Android rejects the (foreground)
            // service start we must not throw back into the UI. onStart has not
            // run yet on this side, so route a failure signal to the monitor
            // (harmless no-op-ish when isCompressing was never set true) and drop
            // any pending effects stashed for this token to avoid a leak
            // (Req 15.2, 15.3).
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                pendingEffectsByToken.takeAndRemove(token)
                CompressionMonitor.onFailure("Could not start export: ${e.localizedMessage}")
            }
        }

        /** Non-Parcelable export passes handed from [start] to [onStartCommand]. */
        private data class PendingVideoEffects(
            val videoEffects: List<Effect>,
            val audioProcessors: List<AudioProcessor>,
            val speed: Float?,
            val removeAudio: Boolean,
            val plan: List<PlanSegment>,
        )

        fun stop(context: Context) {
            context.stopService(Intent(context, CompressionService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Extract the raw extras as nullables and defer all validation to the
        // pure CompressionRequest.fromExtras validator (Req 16.1, 16.2). Reading
        // the EditState here only establishes presence for validation; the
        // actual object is used below once the request is confirmed Valid.
        val rawInputUri = intent.getStringExtra("inputUri")
        val rawOutputPath = intent.getStringExtra("outputPath")
        val rawCodec = intent.getStringExtra("codec")
        val editState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("editState", EditState::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("editState")
        }
        val targetSizeMb = intent.getFloatExtra("targetSizeMb", 8f)
        val beforeSize = intent.getLongExtra("beforeSize", 0L)
        val exportToken = intent.getLongExtra("exportToken", -1L)

        val request = CompressionRequest.fromExtras(
            inputUri = rawInputUri,
            outputPath = rawOutputPath,
            codecName = rawCodec,
            editStatePresent = editState != null,
            targetSizeMb = targetSizeMb,
            beforeSize = beforeSize,
        )
        // A malformed request must never leave a dangling foreground service, so
        // short-circuit BEFORE promoting to the foreground (Req 16.3).
        if (request is CompressionRequest.Invalid) {
            if (exportToken != -1L) {
                pendingEffectsByToken.takeAndRemove(exportToken)
            }
            CompressionMonitor.onFailure("Invalid export request: ${request.reason}")
            stopSelf()
            return START_NOT_STICKY
        }
        val valid = request as CompressionRequest.Valid

        // Consume the one-shot effect passes stashed by start() under this
        // export's token (null for the plain trim + crop callers), removing them
        // so the entry does not linger for future exports.
        val passes = pendingEffectsByToken.takeAndRemove(exportToken)

        // editState is guaranteed non-null here because Valid requires
        // editStatePresent == true above.
        val validEditState = editState!!
        val inputUri = Uri.parse(valid.inputUri)

        createNotificationChannel()

        // Increment the export token (via onStart) BEFORE promoting to the
        // foreground so that a foreground-start failure below is routed under the
        // correct token (Req 15.1). The token-keyed effect handoff keys off the
        // intent's exportToken extra, so this reorder is handoff-safe.
        CompressionMonitor.onStart(valid.beforeSize)

        lastNotifiedPercent = -1
        lastNotificationAt = 0L

        // Promote to the foreground under try/catch: if Android rejects the
        // foreground start (e.g. background-start restrictions), route the failure
        // to the monitor and stop rather than proceeding to the transformer
        // (Req 15.2, 15.3).
        try {
            startForegroundService(buildNotification(0f))
            isForeground = true
        } catch (e: Exception) {
            android.util.Log.e("CompressionService", "Failed to start foreground service", e)
            CompressionMonitor.onFailure("Could not start export: ${e.localizedMessage}")
            stopSelf()
            return START_NOT_STICKY
        }

        // Clockwise geometry rotation (Req 3.4); 0 for the plain trim + crop callers.
        val rotationDegrees = intent.getIntExtra("rotationDegrees", 0)

        val removeAudio = passes?.removeAudio ?: false
        val speed = passes?.speed

        // Adaptive one-pass size targeting.
        //
        // Instead of doing a corrective second encode, learn from previous exports on this
        // device/codec/target bucket and apply a correction factor up front.
        val calibratedTargetMb = SizeCalibration.adjustedTargetMb(
            context = this,
            codec = valid.codec,
            targetSizeMb = valid.targetSizeMb,
            beforeSizeBytes = valid.beforeSize,
            removeAudio = removeAudio,
            speed = speed,
        )

        android.util.Log.d(
            "CompressionService",
            "Target calibration: user=${valid.targetSizeMb}MB, calibrated=${calibratedTargetMb}MB"
        )

        val job = VideoCompressor.compress(
            context = this,
            inputUri = inputUri,
            outputPath = valid.outputPath,
            codecChoice = valid.codec,
            targetSizeMb = calibratedTargetMb,
            editState = validEditState,
            videoEffects = passes?.videoEffects ?: emptyList(),
            audioProcessors = passes?.audioProcessors ?: emptyList(),
            speed = speed,
            removeAudio = removeAudio,
            rotationDegrees = rotationDegrees,
            plan = passes?.plan ?: emptyList(),
            onProgress = { p ->
                CompressionMonitor.onProgress(p)
                updateNotification(p)
            },
            onSuccess = {
                serviceScope.launch {
                    val outputFile = File(valid.outputPath)
                    val afterSize = outputFile.length()

                    if (!outputFile.exists() || afterSize <= 0L) {
                        CompressionMonitor.onFailure("Export produced an empty output file")
                        stopSelf()
                        return@launch
                    }

                    // Feed the result back so the next export in the same bucket is more accurate.
                    SizeCalibration.record(
                        context = this@CompressionService,
                        codec = valid.codec,
                        userTargetSizeMb = valid.targetSizeMb,
                        actualSizeBytes = afterSize,
                        removeAudio = removeAudio,
                        speed = speed,
                    )

                    try {
                        // Retain the gallery content Uri as the "after" reference so
                        // the before/after Comparison screen can play the compressed
                        // output even though the temp output file is deleted below.
                        val savedUri = withContext(Dispatchers.IO) {
                            saveToGallery(this@CompressionService, outputFile, SaveKind.VIDEO)
                        }
                        CompressionMonitor.onComplete(afterSize, savedUri)
                    } catch (e: Exception) {
                        CompressionMonitor.onFailure("Save failed: ${e.localizedMessage}")
                    } finally {
                        outputFile.delete()
                        stopSelf()
                    }
                }
            },
            onCancelled = {
                File(valid.outputPath).delete()
                CompressionMonitor.onCancel()
                stopSelf()
            },
            onFailure = { e ->
                File(valid.outputPath).delete()
                CompressionMonitor.onFailure(e.localizedMessage ?: "Unknown error")
                stopSelf()
            }
        )
        CompressionMonitor.activeJob = job

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CompressionMonitor.activeJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Compression",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of video compression"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(progress: Float): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Compressing Video")
            .setContentText("${(progress * 100).toInt()}% complete")
            .setSmallIcon(android.R.drawable.stat_sys_download) // Standard download icon
            .setOngoing(true)
            .setProgress(100, (progress * 100).toInt(), false)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(progress: Float) {
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        val now = android.os.SystemClock.elapsedRealtime()

        val force = percent == 0 || percent == 100

        if (!force) {
            if (percent == lastNotifiedPercent) return
            if (now - lastNotificationAt < 500L) return
        }

        lastNotifiedPercent = percent
        lastNotificationAt = now

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(progress))
    }

    private fun startForegroundService(notification: Notification) {
        val serviceType = ForegroundServicePolicy.serviceType(Build.VERSION.SDK_INT)
        if (serviceType != null) {
            startForeground(NOTIF_ID, notification, serviceType)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
