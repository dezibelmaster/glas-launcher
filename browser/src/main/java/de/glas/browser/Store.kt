package de.glas.browser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** What the browser remembers: favourites, view, last page, language. */
object Store {
    class Favorite(val title: String, val url: String)

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

    private fun sp(ctx: Context) = ctx.getSharedPreferences("browser", Context.MODE_PRIVATE)

    fun favorites(ctx: Context): List<Favorite> {
        val raw = sp(ctx).getString("favorites", null) ?: return defaults
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Favorite(o.getString("title"), o.getString("url")) } }
        }.getOrDefault(defaults)
    }

    private fun setFavorites(ctx: Context, list: List<Favorite>) {
        val arr = JSONArray()
        for (f in list) arr.put(JSONObject().put("title", f.title).put("url", f.url))
        sp(ctx).edit().putString("favorites", arr.toString()).apply()
    }

    fun addFavorite(ctx: Context, title: String, url: String) {
        if (!url.startsWith("http")) return
        setFavorites(ctx, favorites(ctx).filter { it.url != url } + Favorite(title.ifBlank { Uri.parse(url).host ?: url }.take(40), url))
    }

    fun removeFavorite(ctx: Context, url: String) = setFavorites(ctx, favorites(ctx).filter { it.url != url })
    fun resetFavorites(ctx: Context) = sp(ctx).edit().remove("favorites").apply()
    fun isFavorite(ctx: Context, url: String) = favorites(ctx).any { it.url == url }

    /** Pages as on a computer instead of the phone/tablet layout. */
    fun desktop(ctx: Context) = sp(ctx).getBoolean("desktop", false)
    fun setDesktop(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("desktop", on).apply()

    fun lastUrl(ctx: Context): String? = sp(ctx).getString("last", null)
    fun setLastUrl(ctx: Context, url: String) = sp(ctx).edit().putString("last", url).apply()

    /** Language code sent by the launcher; null follows the TV. */
    fun language(ctx: Context): String? = sp(ctx).getString("lang", null)
    fun setLanguage(ctx: Context, code: String) = sp(ctx).edit().putString("lang", code).apply()

    /** Whether the user was already offered uBlock Origin on the first start. */
    fun offeredBlocker(ctx: Context) = sp(ctx).getBoolean("offeredBlocker", false)
    fun setOfferedBlocker(ctx: Context) = sp(ctx).edit().putBoolean("offeredBlocker", true).apply()

    fun hintShown(ctx: Context) = sp(ctx).getBoolean("hint", false)
    fun setHintShown(ctx: Context) = sp(ctx).edit().putBoolean("hint", true).apply()

    /** Typed text as an address: a web address as it is, anything else as a search. */
    fun toUrl(input: String): String {
        val s = input.trim()
        if (s.isEmpty() || s == HOME) return HOME
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(s) && !s.contains(' ')) {
            return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s) || s.startsWith("about:")) s else "https://$s"
        }
        val looksLikeHost = !s.contains(' ') && (
            Regex("^[^/\\s]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$").matches(s) ||
                Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$").matches(s)
            )
        return if (looksLikeHost) "https://$s" else "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
    }

    fun youtubeSearchUrl(q: String) = "https://m.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")
}
