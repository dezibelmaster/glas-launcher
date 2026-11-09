package de.glas.launcher

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Glas Browser from the phone ("Einstellen per Handy" → Browser): typed addresses and text,
 * touchpad for the pointer, video buttons, volume and extensions. Called from [PhoneApi] on its worker
 * threads; the commands go on to the browser app ([GlasBrowser]).
 */
object BrowserRemote {
    private val main = Handler(Looper.getMainLooper())

    fun state(ctx: Context): JSONObject {
        if (!GlasBrowser.installed(ctx)) {
            return JSONObject().put("installed", false).put("open", false).put("favorites", JSONArray())
        }
        return (GlasBrowser.call(ctx, JSONObject().put("c", "state")) ?: JSONObject().put("open", false).put("favorites", JSONArray()))
            .put("installed", true)
    }

    /**
     * One command from the phone; answers with the new state, plus "error" when something didn't work.
     * [pinOk] tells whether the phone gave the right PIN, which opens a locked browser.
     */
    fun command(ctx: Context, cmd: JSONObject, pinOk: () -> Boolean): JSONObject {
        val app = ctx.applicationContext
        val c = cmd.optString("c")
        if (c == "vol") {
            val dir = when (cmd.optInt("d")) {
                1 -> AudioManager.ADJUST_RAISE
                -1 -> AudioManager.ADJUST_LOWER
                else -> AudioManager.ADJUST_TOGGLE_MUTE
            }
            app.getSystemService(AudioManager::class.java)?.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            return state(app)
        }
        if (!GlasBrowser.installed(app)) {
            return state(app).put("error", tr("Der Glas Browser ist nicht installiert. Am Fernseher: Einstellungen → Browser → Glas Browser installieren."))
        }
        if (c == "install-browser") {
            main.post { GlasBrowser.installOrUpdate(app, Prefs(app).updateRepo) }
            return state(app)
        }
        if (c == "open" || c == "youtube") {
            val text = cmd.optString("url").trim()
            val url = if (c == "youtube") "https://m.youtube.com/results?search_query=" + java.net.URLEncoder.encode(text, "UTF-8") else text
            return open(app, url.ifEmpty { null }, pinOk)
        }
        val reply = GlasBrowser.call(app, cmd) ?: return state(app).put("error", tr("Der Glas Browser antwortet nicht."))
        return reply.put("installed", true)
    }

    private fun open(app: Context, url: String?, pinOk: () -> Boolean): JSONObject {
        val already = state(app).optBoolean("open")
        if (!already) {
            val reason = GlasBrowser.restricted(app)
            if (reason != null && !pinOk()) return state(app).put("error", reason).put("needPin", true)
        }
        if (already && url != null) {
            // The window is there: load in it without bringing up a new one
            GlasBrowser.call(app, JSONObject().put("c", "load").put("url", url))
        }
        main.post { runCatching { GlasBrowser.open(app, if (already) null else url) } }
        if (!already) {
            // Android may refuse to bring up a window while another app is in front
            Thread.sleep(2500)
            if (!state(app).optBoolean("open")) {
                return state(app).put("error", tr("Der Fernseher hat den Browser nicht geöffnet. Öffne ihn einmal am Fernseher über das Dock."))
            }
        }
        return state(app)
    }
}
