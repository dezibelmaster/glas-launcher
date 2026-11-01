package de.glas.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout

/** Full-screen background behind the home screen. */
abstract class Backdrop(context: Context) : FrameLayout(context) {
    init { setWillNotDraw(false) }

    open fun setWeather(sky: Sky, day: Boolean) {}

    /** Paints the current frame into [c], which is scaled down for the glass blur. */
    abstract fun drawBackdrop(c: Canvas)

    open fun pause() {}
    open fun resume() {}
    open fun release() {}

    // Backgrounds that switch between several pictures or videos can be browsed by hand

    /** Titles to choose from; empty if this background shows only one thing. */
    open val titles: List<String> get() = emptyList()

    /** Index into [titles] of what is showing now, or -1. */
    open val current: Int get() = -1

    /** Whether it stays on one item instead of switching by itself. */
    open val pinned: Boolean get() = false

    /** Shows item [index]; with [pin] it stays there, otherwise it is only a preview. */
    open fun select(index: Int, pin: Boolean) {}

    /** Goes back to switching by itself. */
    open fun unpin() {}

    open fun next() {}
    open fun previous() {}

    /** Address of item [index], for favourites and remembering a choice. */
    open fun uriAt(index: Int): String? = null

    /**
     * The item after (or before) [from] when switching by itself: only favourites when
     * "Nur Favoriten wechseln" is on and this background has any, otherwise all items.
     */
    protected fun step(from: Int, count: Int, forward: Boolean): Int {
        if (count < 2) return from
        val prefs = Prefs(context)
        val favs = prefs.favorites
        val pool = if (prefs.favoritesOnly) (0 until count).filter { uriAt(it) in favs } else emptyList()
        if (pool.isEmpty()) return if (forward) (from + 1) % count else (from - 1 + count) % count
        val pos = pool.indexOf(from)
        return when {
            pos < 0 -> if (forward) pool.firstOrNull { it > from } ?: pool.first() else pool.lastOrNull { it < from } ?: pool.last()
            forward -> pool[(pos + 1) % pool.size]
            else -> pool[(pos - 1 + pool.size) % pool.size]
        }
    }

    /** Called on the main thread whenever [titles] or [current] change. */
    var onChange: (() -> Unit)? = null
}

/** Something drawn on a canvas every frame: the weather styles and still photos. */
interface Scene {
    fun resize(w: Float, h: Float)
    fun setWeather(sky: Sky, day: Boolean)
    fun step(now: Long, dt: Float)

    /** [lowRes] is set when drawing the small copy for the glass. */
    fun draw(c: Canvas, now: Long, lowRes: Boolean)
    fun release() {}
}

class SceneBackdrop(context: Context, private val scene: Scene) : Backdrop(context) {
    private var last = 0L
    private var now = 0L

    override fun setWeather(sky: Sky, day: Boolean) {
        scene.setWeather(sky, day)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = scene.resize(w.toFloat(), h.toFloat())

    override fun onDraw(canvas: Canvas) {
        val t = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 1f else ((t - last) / 16.67f).coerceIn(0f, 4f)
        last = t
        now = t
        scene.step(t, dt)
        scene.draw(canvas, t, false)
        postInvalidateOnAnimation()
    }

    override fun drawBackdrop(c: Canvas) = scene.draw(c, now, true)

    override fun release() = scene.release()
}

object Backdrops {
    /** Changes whenever the background has to be rebuilt. */
    fun key(p: Prefs) = listOf(p.background, p.weatherStyle, p.imageUri, p.videoUri, p.extAuthority, p.extUri, p.extType, p.plugin).joinToString("|")

    fun create(ctx: Context, p: Prefs): Backdrop {
        val backdrop = when (p.background) {
            "image" -> {
                val uri = p.imageUri
                SceneBackdrop(ctx, PhotoScene(ctx, withFx = false, key = { _, _ -> "image" }) { _, _ -> PhotoLoader.fromUri(ctx, uri) })
            }
            "video" -> ownVideos(ctx, p.videoUri)
            "extension" -> extension(ctx, p)
            "plugin" -> {
                val plugin = ProjectivyPlugins.find(ctx, p.plugin)
                if (plugin == null) SceneBackdrop(ctx, ModernScene())
                else PluginBackdrop(ctx, plugin.component, plugin.cacheMillis)
            }
            else -> SceneBackdrop(ctx, when (p.weatherStyle) {
                "modern" -> ModernScene()
                "abstract" -> AbstractScene()
                "lifelike" -> RealisticScene()
                else -> PhotoScene(ctx, withFx = true, key = { s, d -> "$s/$d" }) { s, d -> PhotoLoader.weather(ctx, s, d) }
            })
        }
        val w = p.cachedWeather(p.mainPlace)
        if (w != null) backdrop.setWeather(WeatherApi.skyFor(w.code), w.isDay) else backdrop.setWeather(Sky.PARTLY, true)
        return backdrop
    }

    /**
     * The user's own videos, so they can be browsed and marked as favourites like a plugin's:
     * [chosen] loops, or with null all of them play one after another in random order.
     */
    private fun ownVideos(ctx: Context, chosen: String?) = VideoBackdrop(ctx, start = chosen) {
        val found = Media.list(ctx, video = true).map { MediaItem(it.uri, it.name.substringBeforeLast('.')) }
        when {
            chosen == null -> found.shuffled()
            found.any { it.uri == chosen } -> found
            // Without access to the video list only the chosen file can play
            found.isEmpty() -> listOf(MediaItem(chosen, Uri.parse(chosen).lastPathSegment ?: tr("Video")))
            else -> {
                // The same file may be listed under another address (file:// or content://)
                val name = Uri.parse(chosen).lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                val i = found.indexOfFirst { it.title == name }
                // A chosen file that is gone no longer shows up; then all others play
                if (i >= 0) found.toMutableList().apply { set(i, MediaItem(chosen, found[i].title)) } else found
            }
        }
    }

    /** Wallpaper from an extension app: one picture, one video, or all its videos in random order. */
    private fun extension(ctx: Context, p: Prefs): Backdrop {
        val authority = p.extAuthority
        val uri = p.extUri
        if (uri != null && p.extType == "image") {
            return SceneBackdrop(ctx, PhotoScene(ctx, withFx = false, key = { _, _ -> uri }) { _, _ ->
                MediaCache.fetchBlocking(ctx, uri)?.let { PhotoLoader.fromUri(ctx, it) }
            })
        }
        // All videos are loaded so they can be browsed; a chosen one plays in a loop
        return VideoBackdrop(ctx, start = uri) {
            if (authority == null) emptyList()
            else Extensions.wallpapers(ctx, authority).filter { it.type == "video" }.map { MediaItem(it.uri, it.title) }.shuffled()
        }
    }
}

/**
 * Paints the frosted glass behind registered views: the backdrop is drawn a second
 * time at 1/16 size and scaled back up, which blurs it on every Android version.
 */
class GlassLayer(context: Context) : View(context) {
    class Glass(val view: View, val radius: Float, var tint: Int)

    var backdrop: Backdrop? = null
    val glass = mutableListOf<Glass>()

    private var small: Bitmap? = null
    private var smallCanvas: Canvas? = null
    private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint()
    private val full = RectF()
    private val rect = RectF()
    private val path = Path()
    private val here = IntArray(2)
    private val there = IntArray(2)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        full.set(0f, 0f, w.toFloat(), h.toFloat())
        small = Bitmap.createBitmap((w / BLUR).coerceAtLeast(1), (h / BLUR).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        smallCanvas = Canvas(small!!)
    }

    override fun onDraw(canvas: Canvas) {
        val b = backdrop
        val sc = smallCanvas
        val sb = small
        if (b != null && sc != null && sb != null && glass.any { it.view.isShown }) {
            sc.drawColor(Color.BLACK)
            sc.save()
            sc.scale(1f / BLUR, 1f / BLUR)
            b.drawBackdrop(sc)
            sc.restore()

            getLocationInWindow(here)
            for (g in glass) {
                val v = g.view
                if (!v.isShown || v.width == 0) continue
                v.getLocationInWindow(there)
                val x = (there[0] - here[0]).toFloat()
                val y = (there[1] - here[1]).toFloat()
                rect.set(x, y, x + v.width, y + v.height)
                path.reset()
                path.addRoundRect(rect, g.radius, g.radius, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(path)
                canvas.drawBitmap(sb, null, full, blurPaint)
                tintPaint.color = g.tint
                canvas.drawRect(rect, tintPaint)
                canvas.restore()
            }
        }
        postInvalidateOnAnimation()
    }

    private companion object {
        const val BLUR = 16
    }
}
