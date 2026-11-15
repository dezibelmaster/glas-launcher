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
    // Reddit answers some devices only when they look like a browser
    private const val BROWSER = "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    /** Why the last attempt failed, shown to the user so the cause can be found. */
    @Volatile var lastError: String? = null
    private const val MAX_AGE = 6 * 60 * 60_000L

    private fun cache(ctx: Context, sub: String) = File(ctx.filesDir, "reddit-${sub.lowercase()}.json")

    /** Posts of the week, from the stored list if it is recent. Blocking; empty without connection and list. */
    fun posts(ctx: Context, sub: String): List<Post> {
        val file = cache(ctx, sub)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE
        if (fresh) runCatching { parseAny(file.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        // Several ways in, because Reddit blocks some of them depending on the device and network
        val tries = listOf(
            "https://www.reddit.com/r/$sub/top.json?t=week&limit=60&raw_json=1" to AGENT,
            "https://old.reddit.com/r/$sub/top.json?t=week&limit=60&raw_json=1" to AGENT,
            "https://www.reddit.com/r/$sub/top.json?t=week&limit=60&raw_json=1" to BROWSER,
            "https://www.reddit.com/r/$sub/top/.rss?t=week&limit=60" to BROWSER,
        )
        val errors = ArrayList<String>()
        for ((url, agent) in tries) {
            try {
                val text = download(url, agent)
                val posts = parseAny(text)
                if (posts.isNotEmpty()) {
                    runCatching { file.writeText(text) }
                    lastError = null
                    return posts
                }
                errors += "${host(url)}: " + tr("keine passenden Bilder")
            } catch (e: Exception) {
                errors += "${host(url)}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        lastError = errors.distinct().joinToString(" · ")
        // An older list is better than nothing
        return file.takeIf { it.exists() }?.let { runCatching { parseAny(it.readText()) }.getOrNull() } ?: emptyList()
    }

    private fun host(url: String) = url.substringAfter("//").substringBefore('/') + if (url.contains(".rss")) " (RSS)" else ""

    private fun download(url: String, agent: String): String {
        var u = URL(url)
        repeat(4) {
            val conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", agent)
            conn.setRequestProperty("Accept", "application/json, application/atom+xml, */*")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    u = URL(u, conn.getHeaderField("Location") ?: throw java.io.IOException("HTTP $code"))
                    return@repeat
                }
                if (code != 200) throw java.io.IOException("HTTP $code")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        throw java.io.IOException(tr("Zu viele Weiterleitungen"))
    }

    private fun parseAny(text: String): List<Post> = if (text.trimStart().startsWith("<")) parseRss(text) else parse(text)

    /**
     * The RSS feed has no picture sizes; it links the full picture as "[link]" to i.redd.it.
     * Portrait pictures are skipped later, when they are loaded.
     */
    private fun parseRss(text: String): List<Post> {
        val entries = text.split("<entry>").drop(1)
        return entries.mapNotNull { e ->
            val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(e)?.groupValues?.get(1)?.let(::unescape) ?: return@mapNotNull null
            val author = Regex("<name>/?u/(.*?)</name>").find(e)?.groupValues?.get(1).orEmpty()
            val id = Regex("<id>(?:t3_)?(.*?)</id>").find(e)?.groupValues?.get(1).orEmpty()
            val content = unescape(Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL).find(e)?.groupValues?.get(1).orEmpty())
            val url = Regex("""https://i\.redd\.it/[^"'\s<>]+\.(?:jpe?g|png)""", RegexOption.IGNORE_CASE).find(content)?.value
                ?: return@mapNotNull null
            Post(id.ifEmpty { url }, clean(title), author, url)
        }
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    private fun parse(text: String): List<Post> {
        val children: JSONArray = JSONObject(text).getJSONObject("data").getJSONArray("children")
        return (0 until children.length()).mapNotNull { i ->
            val d = children.getJSONObject(i).optJSONObject("data") ?: return@mapNotNull null
            if (d.optBoolean("over_18") || d.optBoolean("spoiler") || d.optBoolean("is_video")) return@mapNotNull null
            gallery(d)?.let { return@mapNotNull it }
            val source = d.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("source")
            val direct = d.optString("url").takeIf { Regex("""\.(jpe?g|png)$""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            // Landscape format, sharp enough for a TV (when Reddit tells the size; otherwise checked when loading)
            if (source != null) {
                val w = source.optInt("width")
                val h = source.optInt("height")
                if (w < 1200 || w < h * 1.2) return@mapNotNull null
            } else if (direct == null) return@mapNotNull null
            val url = direct ?: source?.optString("url").orEmpty()
            if (url.isBlank()) return@mapNotNull null
            Post(d.optString("id"), clean(d.optString("title")), d.optString("author"), url)
        }
    }

    /** Gallery posts (several pictures) list them under media_metadata; the first landscape one is taken. */
    private fun gallery(d: JSONObject): Post? {
        if (!d.optBoolean("is_gallery")) return null
        val meta = d.optJSONObject("media_metadata") ?: return null
        val order = d.optJSONObject("gallery_data")?.optJSONArray("items")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).optString("media_id") } }
            ?: meta.keys().asSequence().toList()
        for (id in order) {
            val s = meta.optJSONObject(id)?.optJSONObject("s") ?: continue
            val w = s.optInt("x")
            val h = s.optInt("y")
            val url = s.optString("u").ifEmpty { continue }
            if (w >= 1200 && w >= h * 1.2) return Post(d.optString("id"), clean(d.optString("title")), d.optString("author"), url)
        }
        return null
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
    SceneBackdrop(context, PhotoScene(context, withFx = false, key = { _, _ -> state.key() }) { _, _ ->
        state.load(context) { i -> Handler(Looper.getMainLooper()).post { state.index = i; state.onMoved?.invoke() } }
    }) {

    constructor(context: Context) : this(context, State(Prefs(context)))

    private class State(val prefs: Prefs) {
        val sub = prefs.redditSub
        var posts = emptyList<Reddit.Post>()
        var index = -1
        var pinned = prefs.redditPost != null
        val post get() = posts.getOrNull(index)
        var onMoved: (() -> Unit)? = null
        fun key() = post?.id ?: "none"
        /** The chosen photo; one that doesn't load or stands upright is skipped for the next. */
        fun load(ctx: Context, moved: (Int) -> Unit): PhotoScene.Photo? {
            val start = index
            for (n in 0 until minOf(6, posts.size)) {
                val i = (start + n) % posts.size
                val p = posts[i]
                val local = MediaCache.fetchBlocking(ctx, p.url) ?: continue
                val photo = PhotoLoader.fromUri(ctx, local) ?: continue
                if (photo.bitmap.width < photo.bitmap.height * 1.1f && n < 5) continue
                if (i != start) moved(i)
                return photo
            }
            return null
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
        state.onMoved = { onChange?.invoke() }
        Thread {
            val list = Reddit.posts(context, state.sub)
            main.post {
                if (list.isEmpty()) {
                    context.toast(tr("Von Reddit kamen keine Bilder (r/{0})", state.sub) + (Reddit.lastError?.let { "\n$it" } ?: ""))
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
