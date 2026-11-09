package de.glas.launcher

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URLEncoder

/**
 * Shared parts of the Glas Browser: favourites, turning typed text into an address,
 * the parental lock, and the open browser window that the phone ([BrowserRemote]) talks to.
 */
object Browser {
    class Favorite(val title: String, val url: String)

    /** Key of the browser in the parental lock and the time windows, like an app's package. */
    const val LOCK_KEY = "de.glas.launcher"
    const val HOME = "glas:start"

    private val defaults = listOf(
        Favorite("YouTube", "https://m.youtube.com"),
        Favorite("ARD Mediathek", "https://www.ardmediathek.de"),
        Favorite("ZDF Mediathek", "https://www.zdf.de"),
        Favorite("arte", "https://www.arte.tv"),
        Favorite("3sat", "https://www.3sat.de/mediathek"),
        Favorite("Vimeo", "https://vimeo.com"),
        Favorite("Dailymotion", "https://www.dailymotion.com"),
        Favorite("Twitch", "https://m.twitch.tv"),
    )

    /** The browser window on screen, if any; commands from the phone go there. */
    @Volatile var window: WeakReference<BrowserActivity>? = null
    val open: BrowserActivity? get() = window?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    /** Set by the launcher after it checked the PIN, so the browser doesn't ask a second time. */
    @Volatile var unlockedUntil = 0L

    private fun sp(ctx: Context) = ctx.getSharedPreferences("glas", Context.MODE_PRIVATE)

    fun favorites(ctx: Context): List<Favorite> {
        val raw = sp(ctx).getString("browserFavorites", null) ?: return defaults
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Favorite(o.getString("title"), o.getString("url")) } }
        }.getOrDefault(defaults)
    }

    fun setFavorites(ctx: Context, list: List<Favorite>) {
        val arr = JSONArray()
        for (f in list) arr.put(JSONObject().put("title", f.title).put("url", f.url))
        sp(ctx).edit().putString("browserFavorites", arr.toString()).apply()
    }

    fun addFavorite(ctx: Context, title: String, url: String) {
        if (!url.startsWith("http")) return
        val list = favorites(ctx).filter { it.url != url }
        setFavorites(ctx, list + Favorite(title.ifBlank { Uri.parse(url).host ?: url }.take(40), url))
    }

    fun removeFavorite(ctx: Context, url: String) = setFavorites(ctx, favorites(ctx).filter { it.url != url })

    fun resetFavorites(ctx: Context) = sp(ctx).edit().remove("browserFavorites").apply()

    /** Page as on a computer instead of the phone/tablet layout. */
    fun desktop(ctx: Context) = sp(ctx).getBoolean("browserDesktop", false)
    fun setDesktop(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("browserDesktop", on).apply()

    /** Last page, so the browser opens where it was left. */
    fun lastUrl(ctx: Context): String? = sp(ctx).getString("browserLast", null)
    fun setLastUrl(ctx: Context, url: String) = sp(ctx).edit().putString("browserLast", url).apply()

    /** Whether the browser needs the PIN right now (locked, or outside its time window). */
    fun restricted(ctx: Context): String? {
        val prefs = Prefs(ctx)
        if (prefs.pinHash == null) return null
        if (LOCK_KEY in prefs.locked) return tr("{0} ist gesperrt", tr("Browser"))
        val window = prefs.timeWindows[LOCK_KEY]
        if (window != null && !TimeLimits.allowedNow(window)) return tr("{0} ist nur {1} erlaubt", tr("Browser"), TimeLimits.label(window))
        return null
    }

    /** Typed text as an address: a web address as it is, anything else as a search. */
    fun toUrl(input: String): String {
        val s = input.trim()
        if (s.isEmpty() || s == HOME) return HOME
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s)) return s
        val looksLikeHost = !s.contains(' ') && (
            Regex("^[^/\\s]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$").matches(s) ||
                Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$").matches(s) ||
                s.startsWith("localhost")
            )
        return if (looksLikeHost) "https://$s" else searchUrl(s)
    }

    private fun searchUrl(q: String) = "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
    fun youtubeSearchUrl(q: String) = "https://m.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")
}
