package de.glas.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper

/**
 * Background from a Projectivy plugin: asks the plugin for its wallpapers, shows a random
 * one and switches to another every few minutes, unless one was chosen by hand. The list is
 * kept for as long as the plugin asks (its itemsCacheDurationMillis) before asking again.
 */
class PluginBackdrop(
    context: Context,
    private val plugin: ComponentName,
    private val cacheMillis: Long,
) : Backdrop(context) {
    private val prefs = Prefs(context)
    private val main = Handler(Looper.getMainLooper())
    private var child: Backdrop? = null
    private var items = emptyList<Wallpaper>()
    private var fetchedAt = 0L
    private var index = -1
    private var released = false
    private var paused = false

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, prefs.pluginMinutes * 60_000L)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
    }

    override val titles get() = items.mapIndexed { i, w -> label(w, i) }
    override val current get() = index
    override val pinned get() = prefs.pluginPin != null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        released = false
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** Loads the list if it has expired, then moves on unless a wallpaper was chosen by hand. */
    private fun refresh() {
        val fresh = items.isNotEmpty() && System.currentTimeMillis() - fetchedAt < cacheMillis
        if (fresh) {
            if (!pinned || index < 0) showPinnedOrRandom()
            return
        }
        Thread {
            val list = ProjectivyPlugins.wallpapers(context, plugin).filter { ProjectivyPlugins.isSupported(it) }
            main.post {
                if (released) return@post
                if (list.isEmpty()) {
                    if (items.isEmpty()) {
                        // Often the network is not back yet right after switching on: ask again shortly
                        main.postDelayed({ if (!released && items.isEmpty()) refresh() }, 20_000)
                        if (Network.isOnline(context)) context.toast("Das Plugin liefert gerade keine Hintergründe. Prüfe seine Einstellungen.")
                    }
                } else {
                    items = list
                    fetchedAt = System.currentTimeMillis()
                    index = -1
                }
                // While another app is open, show it only when the launcher comes back
                if (items.isNotEmpty() && !paused) showPinnedOrRandom()
            }
        }.start()
    }

    private fun showPinnedOrRandom() {
        val pinnedAt = items.indexOfFirst { it.uri == prefs.pluginPin }
        if (pinnedAt >= 0) return show(pinnedAt)
        // Random pick, among the favourites when only those should come up
        val favs = prefs.favorites
        val pool = if (prefs.favoritesOnly) items.indices.filter { items[it].uri in favs }.ifEmpty { items.indices.toList() }
        else items.indices.toList()
        val choices = pool.filter { it != index }.ifEmpty { pool }
        show(choices.random())
    }

    override fun select(index: Int, pin: Boolean) {
        if (index !in items.indices) return
        if (pin) prefs.pluginPin = items[index].uri
        if (index != this.index) show(index) else onChange?.invoke()
    }

    override fun unpin() {
        prefs.pluginPin = null
        onChange?.invoke()
    }

    override fun uriAt(index: Int): String? = items.getOrNull(index)?.uri

    override fun next() {
        if (items.size > 1) show(step(index, items.size, forward = true))
    }

    override fun previous() {
        if (items.size > 1) show(step(index, items.size, forward = false))
    }

    private fun label(w: Wallpaper, i: Int) =
        listOfNotNull(w.title, w.author).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Hintergrund ${i + 1}" }

    private fun show(i: Int) {
        index = i
        onChange?.invoke()
        val pick = items[i]
        val ctx = context
        val uri = pick.uri
        val next: Backdrop = when (pick.type) {
            // Plugin videos are often large aerial films, so they stream instead of being stored
            Wallpaper.VIDEO -> VideoBackdrop(ctx, cache = false) { listOf(MediaItem(uri, label(pick, i))) }
            Wallpaper.COLOR -> ColorBackdrop(ctx, runCatching { Color.parseColor(uri) }.getOrDefault(Color.BLACK))
            else -> SceneBackdrop(ctx, PhotoScene(ctx, withFx = false, key = { _, _ -> uri }) { _, _ ->
                (MediaCache.fetchBlocking(ctx, uri) ?: uri).let { PhotoLoader.fromUri(ctx, it) }
            }).also { it.setWeather(Sky.PARTLY, true) }
        }
        val old = child
        child = next
        next.alpha = 0f
        addView(next, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        next.animate().alpha(1f).setDuration(900).withEndAction {
            if (old != null) {
                old.release()
                removeView(old)
            }
        }.start()
    }

    override fun drawBackdrop(c: Canvas) {
        val cur = child
        if (cur == null) c.drawColor(Color.BLACK) else cur.drawBackdrop(c)
    }

    /** Another app is in front: no switching, and the video player lets go of the decoder. */
    override fun pause() {
        paused = true
        main.removeCallbacks(tick)
        child?.pause()
    }

    override fun resume() {
        if (!paused) return
        paused = false
        val cur = child
        if (cur == null) {
            main.post(tick)
        } else {
            cur.resume()
            main.postDelayed(tick, prefs.pluginMinutes * 60_000L)
        }
    }

    override fun release() {
        released = true
        main.removeCallbacks(tick)
        child?.release()
    }
}

/** Plain colour background, used for colour wallpapers from plugins. */
class ColorBackdrop(context: Context, private val color: Int) : Backdrop(context) {
    init {
        setBackgroundColor(color)
    }

    override fun drawBackdrop(c: Canvas) = c.drawColor(color)
}
