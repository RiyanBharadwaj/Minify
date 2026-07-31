package com.shanks.minify.media3

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object CompressionMonitor {
    private val _isCompressing = MutableStateFlow(false)
    val isCompressing = _isCompressing.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()

    private val _beforeSizeBytes = MutableStateFlow(0L)
    val beforeSizeBytes = _beforeSizeBytes.asStateFlow()

    private val _afterSizeBytes = MutableStateFlow(0L)
    val afterSizeBytes = _afterSizeBytes.asStateFlow()

    // Content Uri of the saved compressed output, retained so the before/after
    // Comparison screen can play the "after" video. The temporary output file is
    // deleted right after it is saved to the gallery, so the gallery Uri is the
    // only durable reference to the compressed result (Req 11.1).
    private val _afterUri = MutableStateFlow<Uri?>(null)
    val afterUri = _afterUri.asStateFlow()

    // Monotonically increasing export token used to correlate an export request
    // with its completion/cancellation routing. Because CompressionMonitor is a
    // global singleton, repeated exports can race; the token lets consumers
    // (e.g. MediaEditorScreen) remember the token of the export they triggered
    // and ignore stale completion/cancel/failure events from superseded exports
    // (Req 11.3, 11.4). It is incremented on onStart and echoed (retained) across
    // onComplete/onCancel/onFailure so the completing token is observable.
    private val _token = MutableStateFlow(0L)
    val token = _token.asStateFlow()

    var activeJob: CompressionJob? = null

    fun onStart(beforeSize: Long) {
        _token.value += 1L
        _isCompressing.value = true
        _progress.value = 0f
        _status.value = ""
        _beforeSizeBytes.value = beforeSize
        _afterSizeBytes.value = 0L
        _afterUri.value = null
    }

    fun onProgress(p: Float) {
        val clamped = p.coerceIn(0f, 1f)
        if (clamped == 1f || kotlin.math.abs(clamped - _progress.value) >= 0.002f) {
            _progress.value = clamped
        }
    }

    fun onComplete(afterSize: Long, afterUri: Uri? = null) {
        _progress.value = 1f
        _afterSizeBytes.value = afterSize
        _afterUri.value = afterUri
        _status.value = "done"
        _isCompressing.value = false
        activeJob = null
    }

    fun onCancel() {
        _status.value = "cancelled"
        _isCompressing.value = false
        _progress.value = 0f
        _afterUri.value = null
        activeJob = null
    }

    fun onFailure(error: String) {
        _status.value = "error:$error"
        _isCompressing.value = false
        _progress.value = 0f
        _afterUri.value = null
        activeJob = null
    }

    fun resetStatus() {
        _status.value = ""
        _progress.value = 0f
    }
}
