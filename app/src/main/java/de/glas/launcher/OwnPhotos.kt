package de.glas.launcher

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * The user's own pictures, browsable like the videos: a chosen one stays, otherwise they change
 * by themselves in random order. With only one picture (or no access to the list) it just shows that one.
 */
class OwnPhotosBackdrop private constructor(context: Context, private val state: State) :
    SceneBackdrop(context, PhotoScene(context, withFx = false, key = { _, _ -> state.key() }) { _, _ ->
        state.uri()?.let { uri -> PhotoLoader.fromUri(context, uri) ?: run { state.missing?.invoke(); null } }
    }) {

    constructor(context: Context) : this(context, State(Prefs(context)))

    private class State(val prefs: Prefs) {
        val chosen = prefs.imageUri
        @Volatile var items: List<MediaItem> = chosen?.let { listOf(MediaItem(it, title(it))) } ?: emptyList()
        @Volatile var index = if (chosen != null) 0 else -1
        var pinned = chosen != null
        fun uri() = items.getOrNull(index)?.uri
        /** Called when the picture to show can't be opened any more, e.g. because it was deleted. */
        var missing: (() -> Unit)? = null
        fun key() = uri() ?: "none"
        fun title(uri: String) = Uri.parse(uri).lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: tr("Bild")
    }

    private val main = Handler(Looper.getMainLooper())
    private var sky = Sky.PARTLY
    private var day = true

    private val change = object : Runnable {
        override fun run() {
            if (!state.pinned && state.items.size > 1) show(step(state.index, state.items.size, forward = true))
            main.postDelayed(this, state.prefs.pluginMinutes * 60_000L)
        }
    }

    init {
        state.missing = { main.post { refresh() } }
        Thread {
            val found = merged(context)
            main.post {
                if (found.isEmpty()) {
                    context.toast(tr("Keine eigenen Bilder gefunden"))
                    return@post
                }
                val keep = found.indexOfFirst { it.uri == state.chosen }
                state.items = found
                if (keep >= 0) {
                    // Already showing; only the list became longer
                    state.index = keep
                    onChange?.invoke()
                } else {
                    state.pinned = false
                    val favs = state.items.indices.filter { uriAt(it) in state.prefs.favorites }
                    show(if (state.prefs.favoritesOnly && favs.isNotEmpty()) favs.random() else state.items.indices.random())
                }
                main.postDelayed(change, state.prefs.pluginMinutes * 60_000L)
            }
        }.start()
    }

    /**
     * The pictures on the TV now. The chosen one may be listed under another address (file:// or
     * content://); once it has been deleted it is left out like any other.
     */
    private fun merged(ctx: Context): List<MediaItem> {
        val found = Media.list(ctx, video = false).map { MediaItem(it.uri, it.name.substringBeforeLast('.')) }
        val chosen = state.chosen ?: return found
        val name = state.title(chosen)
        val at = found.indexOfFirst { it.uri == chosen }.takeIf { it >= 0 } ?: found.indexOfFirst { it.title == name }
        return when {
            at >= 0 -> found.toMutableList().apply { set(at, MediaItem(chosen, found[at].title)) }
            Media.exists(ctx, chosen) -> listOf(MediaItem(chosen, name)) + found
            else -> found
        }
    }

    /** Pictures deleted or added in the meantime: the list follows, and a deleted one is no longer shown. */
    fun refresh() {
        Thread {
            val found = merged(context)
            main.post {
                if (found.map { it.uri } == state.items.map { it.uri }) return@post
                val showing = state.uri()
                val keep = found.indexOfFirst { it.uri == showing }
                state.items = found
                if (keep >= 0) {
                    state.index = keep
                    onChange?.invoke()
                } else if (found.isNotEmpty()) {
                    // The picture on screen is gone: on to another one, changing by itself again
                    if (state.pinned) state.prefs.imageUri = null
                    state.pinned = false
                    show(found.indices.random())
                } else {
                    state.index = -1
                    onChange?.invoke()
                }
            }
        }.start()
    }

    override fun setWeather(sky: Sky, day: Boolean) {
        this.sky = sky
        this.day = day
        super.setWeather(sky, day)
    }

    private fun show(i: Int) {
        state.index = i
        super.setWeather(sky, day)
        onChange?.invoke()
    }

    override val titles get() = state.items.map { it.title }
    override val current get() = state.index
    override val pinned get() = state.pinned
    override fun uriAt(index: Int) = state.items.getOrNull(index)?.uri

    override fun select(index: Int, pin: Boolean) {
        if (index !in state.items.indices) return
        state.pinned = pin
        if (index != state.index) show(index) else onChange?.invoke()
    }

    override fun unpin() {
        state.pinned = false
        onChange?.invoke()
    }

    override fun next() { if (state.items.size > 1) show(step(state.index, state.items.size, forward = true)) }
    override fun previous() { if (state.items.size > 1) show(step(state.index, state.items.size, forward = false)) }

    override fun release() {
        main.removeCallbacksAndMessages(null)
        super.release()
    }
}
