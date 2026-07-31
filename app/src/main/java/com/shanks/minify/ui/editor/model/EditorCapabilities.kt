package com.shanks.minify.ui.editor.model

/**
 * Feature flags for the optional video tools (A3).
 *
 * Reverse and Freeze_Frame are modeled as configurable capabilities so they can
 * ship behind a flag without blocking the core editor. When a capability is
 * disabled, the editor omits its control entirely (Req 9.1, 9.3, 9.5, 9.6).
 *
 * Both default to `false`, so the optional tools are hidden unless explicitly
 * enabled by the host.
 */
data class EditorCapabilities(
    /** WHERE enabled, the Reverse control is presented for Video items (Req 9.1, 9.5). */
    val reverseEnabled: Boolean = false,
    /** WHERE enabled, the Freeze_Frame control is presented for Video items (Req 9.3, 9.6). */
    val freezeFrameEnabled: Boolean = false,
)
