package com.shanks.minify.ui.editor.model

/**
 * Pure, Android-independent routing predicate that correlates a video export request with its
 * completion/cancellation events (Req 11.3, 11.4).
 *
 * `CompressionMonitor` is a global singleton, so repeated exports can race: a superseded export
 * can still emit a completion, cancellation, or failure after a newer export has started. Each
 * export request is stamped with a monotonically increasing token (see
 * `CompressionMonitor.token`), and the screen remembers the token of the export it triggered.
 * [routes] is the single decision used to route an incoming event only to the request that owns
 * it, ignoring stale events from superseded exports.
 */
object ExportToken {

    /**
     * Whether a completion/cancellation event carrying [eventToken] should be routed to the
     * handler that remembered [rememberedToken].
     *
     * The event is routed if and only if its token equals the remembered token; any non-matching
     * (stale) token is ignored.
     */
    fun routes(eventToken: Long, rememberedToken: Long): Boolean = eventToken == rememberedToken
}
