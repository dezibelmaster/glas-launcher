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
        state.uri()?.let { PhotoLoader.fromUri(context, it) }
    }) {

    constructor(context: Context) : this(context, State(Prefs(context)))

    private class State(val prefs: Prefs) {
        val chosen = prefs.imageUri
        @Volatile var items: List<MediaItem> = chosen?.let { listOf(MediaItem(it, title(it))) } ?: emptyList()
        @Volatile var index = if (chosen != null) 0 else -1
        var pinned = chosen != null
        fun uri() = items.getOrNull(index)?.uri
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
        Thread {
            val found = Media.list(context, video = false).map { MediaItem(it.uri, it.name.substringBeforeLast('.')) }
            main.post {
                if (found.isEmpty()) {
                    if (state.items.isEmpty()) context.toast(tr("Keine eigenen Bilder gefunden"))
                    return@post
                }
                val chosen = state.chosen
                // The chosen file may be listed under another address (file:// or content://)
                val name = chosen?.let { state.title(it) }
                val at = found.indexOfFirst { it.uri == chosen }.takeIf { it >= 0 } ?: found.indexOfFirst { it.title == name }
                state.items = if (at >= 0 && chosen != null) found.toMutableList().apply { set(at, MediaItem(chosen, found[at].title)) }
                    else if (chosen != null) listOf(MediaItem(chosen, state.title(chosen))) + found else found
                if (chosen != null) {
                    // Already showing; only the list became longer
                    state.index = if (at >= 0) at else 0
                    onChange?.invoke()
                } else {
                    val favs = state.items.indices.filter { uriAt(it) in state.prefs.favorites }
                    show(if (state.prefs.favoritesOnly && favs.isNotEmpty()) favs.random() else state.items.indices.random())
                }
                main.postDelayed(change, state.prefs.pluginMinutes * 60_000L)
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
