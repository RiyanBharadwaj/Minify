package com.shanks.minify.ui.editor.model

import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny, thread-safe, Android-independent holder for one-shot hand-offs of a
 * value from a producer to a consumer, keyed by a numeric token.
 *
 * The motivating use case is `CompressionService`: the unified-media-editor's
 * visual/audio/speed passes are Media3 [androidx.media3.common.Effect]s /
 * [androidx.media3.common.audio.AudioProcessor]s, which are not
 * `android.os.Parcelable` and so cannot ride the launch `Intent`. The caller
 * stashes them under the export token it also puts in the intent extra, and the
 * service consumes them exactly once via [takeAndRemove] keyed by that same
 * token. Keying by token (rather than using a single slot) prevents two quick,
 * back-to-back hand-offs from clobbering each other.
 *
 * The class is deliberately generic and free of any Android or Media3 types so
 * that its retrieve-and-remove semantics can be verified with a plain JVM
 * property test.
 *
 * All operations are backed by a [ConcurrentHashMap] and are safe to call from
 * multiple threads.
 *
 * @param T the type of value being handed off.
 */
class TokenKeyedHandoff<T> {

    private val byToken = ConcurrentHashMap<Long, T>()

    /**
     * Stash [value] under [token] for a later single retrieval via
     * [takeAndRemove]. If a value was already stashed under [token] it is
     * overwritten.
     */
    fun put(token: Long, value: T) {
        byToken[token] = value
    }

    /**
     * Retrieve-and-remove the value stashed under [token].
     *
     * Returns `null` if there is no value for [token] — either because it was
     * already taken (this is a one-shot retrieval) or because nothing was ever
     * stashed for that token. After this call the entry for [token] no longer
     * exists, so the holder self-cleans and never leaks stale entries.
     */
    fun takeAndRemove(token: Long): T? = byToken.remove(token)

    /** The number of values currently stashed and not yet taken. */
    val size: Int get() = byToken.size
}
