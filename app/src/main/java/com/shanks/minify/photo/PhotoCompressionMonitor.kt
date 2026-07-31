package com.shanks.minify.photo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable state for an in-progress (or just-completed) photo compression.
 *
 * Mirrors the existing [com.shanks.minify.media3.CompressionMonitor] pattern so
 * the Photo tab can drive the same kind of UI. Photo compression is fast and
 * runs in-process (no foreground service), so this monitor is intentionally
 * lighter than the video one: there is no incremental progress fraction, just
 * the running flag, a status string, and the original/compressed byte sizes
 * that the UI renders in MB (Req 8.6).
 */
object PhotoCompressionMonitor {
    private val _isCompressing = MutableStateFlow(false)
    val isCompressing = _isCompressing.asStateFlow()

    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()

    private val _beforeSizeBytes = MutableStateFlow(0L)
    val beforeSizeBytes = _beforeSizeBytes.asStateFlow()

    private val _afterSizeBytes = MutableStateFlow(0L)
    val afterSizeBytes = _afterSizeBytes.asStateFlow()

    fun onStart(beforeSize: Long) {
        _isCompressing.value = true
        _status.value = ""
        _beforeSizeBytes.value = beforeSize
        _afterSizeBytes.value = 0L
    }

    fun onComplete(afterSize: Long) {
        _afterSizeBytes.value = afterSize
        _status.value = "done"
        _isCompressing.value = false
    }

    fun onFailure(reason: PhotoFailure) {
        _status.value = "error:$reason"
        _isCompressing.value = false
    }

    fun resetStatus() {
        _status.value = ""
    }
}
