package de.glas.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Photos from Reddit communities such as r/EarthPorn (landscape photos, despite the name):
 * the best posts of the week, in landscape format and large enough for a TV. They are
 * downloaded by the TV when shown; the app itself contains none of them.
 */
object Reddit {
    class Post(val id: String, val title: String, val author: String, val url: String)

    /** Communities to choose from; any other can be typed in. */
    val suggested = listOf("EarthPorn", "SkyPorn", "MostBeautiful", "LandscapePhotography", "wallpapers", "CityPorn")

    private const val AGENT = "android:de.glas.launcher:v2 (Glas Launcher for Android TV)"
    private const val MAX_AGE = 6 * 60 * 60_000L

    private fun cache(ctx: Context, sub: String) = File(ctx.filesDir, "reddit-${sub.lowercase()}.json")

    /** Posts of the week, from the stored list if it is recent. Blocking; empty without connection and list. */
    fun posts(ctx: Context, sub: String): List<Post> {
        val file = cache(ctx, sub)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE
        val text = if (fresh) file.readText() else runCatching { download(sub) }.onSuccess { runCatching { file.writeText(it) } }.getOrNull()
            ?: file.takeIf { it.exists() }?.readText()
            ?: return emptyList()
        return runCatching { parse(text) }.getOrDefault(emptyList())
    }

    private fun download(sub: String): String {
        val conn = URL("https://www.reddit.com/r/$sub/top.json?t=week&limit=60&raw_json=1").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", AGENT)
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(text: String): List<Post> {
        val children: JSONArray = JSONObject(text).getJSONObject("data").getJSONArray("children")
        return (0 until children.length()).mapNotNull { i ->
            val d = children.getJSONObject(i).optJSONObject("data") ?: return@mapNotNull null
            if (d.optBoolean("over_18") || d.optBoolean("spoiler") || d.optBoolean("is_video")) return@mapNotNull null
            val source = d.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("source")
                ?: return@mapNotNull null
            val w = source.optInt("width")
            val h = source.optInt("height")
            // Landscape format, sharp enough for a TV
            if (w < 1600 || w < h * 1.25) return@mapNotNull null
            val direct = d.optString("url")
            val url = if (Regex("""\.(jpe?g|png)$""", RegexOption.IGNORE_CASE).containsMatchIn(direct)) direct else source.optString("url")
            if (url.isBlank()) return@mapNotNull null
            Post(d.optString("id"), clean(d.optString("title")), d.optString("author"), url)
        }
    }

    /** Titles often end in camera and size details like "[OC] [4000x3000]"; those go. */
    private fun clean(title: String) = title
        .replace(Regex("""[\[(]\s*(OC|oc|\d{3,5}\s*[x×X]\s*\d{3,5})[^\])]*[\])]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', ',', '.', '-', '|')
        .ifEmpty { title }
}

/** Background with photos from a Reddit community: browsable, with favourites, changing by itself. */
class RedditBackdrop private constructor(context: Context, private val state: State) :
    SceneBackdrop(context, PhotoScene(context, withFx = false, key = { _, _ -> state.key() }) { _, _ -> state.load(context) }) {

    constructor(context: Context) : this(context, State(Prefs(context)))

    private class State(val prefs: Prefs) {
        val sub = prefs.redditSub
        var posts = emptyList<Reddit.Post>()
        var index = -1
        var pinned = prefs.redditPost != null
        val post get() = posts.getOrNull(index)
        fun key() = post?.id ?: "none"
        fun load(ctx: Context): PhotoScene.Photo? {
            val p = post ?: return null
            val local = MediaCache.fetchBlocking(ctx, p.url) ?: return null
            return PhotoLoader.fromUri(ctx, local)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var sky = Sky.PARTLY
    private var day = true

    private val change = object : Runnable {
        override fun run() {
            if (!state.pinned && state.posts.size > 1) show(step(state.index, state.posts.size, forward = true))
            main.postDelayed(this, state.prefs.pluginMinutes * 60_000L)
        }
    }

    init {
        Thread {
            val list = Reddit.posts(context, state.sub)
            main.post {
                if (list.isEmpty()) {
                    context.toast(tr("Von Reddit kamen keine Bilder (r/{0})", state.sub))
                    return@post
                }
                state.posts = list
                val chosen = list.indexOfFirst { it.id == state.prefs.redditPost }
                state.pinned = chosen >= 0
                // Start with a random photo, or a favourite when only those should change
                val favs = list.indices.filter { uriAt(it) in state.prefs.favorites }
                val start = if (chosen >= 0) chosen else if (state.prefs.favoritesOnly && favs.isNotEmpty()) favs.random() else list.indices.random()
                show(start)
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

    /** "Title · u/author", so the photographer is named. */
    override val titles get() = state.posts.map { it.title + "  ·  u/" + it.author }
    override val current get() = state.index
    override val pinned get() = state.pinned
    override fun uriAt(index: Int) = state.posts.getOrNull(index)?.let { "reddit:" + it.id }

    override fun select(index: Int, pin: Boolean) {
        if (index !in state.posts.indices) return
        state.pinned = pin
        if (index != state.index) show(index) else onChange?.invoke()
    }

    override fun unpin() {
        state.pinned = false
        onChange?.invoke()
    }

    override fun next() { if (state.posts.size > 1) show(step(state.index, state.posts.size, forward = true)) }
    override fun previous() { if (state.posts.size > 1) show(step(state.index, state.posts.size, forward = false)) }

    override fun release() {
        main.removeCallbacksAndMessages(null)
        super.release()
    }
}
