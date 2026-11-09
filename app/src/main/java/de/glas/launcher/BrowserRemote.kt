package de.glas.launcher

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Glas Browser from the phone ("Einstellen per Handy" → Browser): typed addresses and text,
 * touchpad for the pointer, video buttons and volume. Called from [PhoneApi] on its worker threads.
 */
object BrowserRemote {
    private val main = Handler(Looper.getMainLooper())

    /** Runs [block] on the main thread and waits for its answer. */
    private fun <T> onMain(block: () -> T): T? {
        var result: T? = null
        val done = CountDownLatch(1)
        main.post { try { result = block() } finally { done.countDown() } }
        done.await(3, TimeUnit.SECONDS)
        return result
    }

    fun state(ctx: Context): JSONObject {
        val o = JSONObject()
        onMain {
            val b = Browser.open
            o.put("open", b != null)
            if (b != null) {
                o.put("url", if (b.currentUrl == Browser.HOME) "" else b.currentUrl)
                o.put("title", b.currentTitle)
                o.put("playing", b.playing)
                o.put("pointer", b.pointerEnabled)
            }
        }
        val favs = JSONArray()
        for (f in Browser.favorites(ctx)) favs.put(JSONObject().put("title", f.title).put("url", f.url))
        return o.put("favorites", favs)
    }

    /**
     * One command from the phone; answers with the new state, plus "error" when something didn't work.
     * [pinOk] tells whether the phone gave the right PIN, which opens a locked browser.
     */
    fun command(ctx: Context, cmd: JSONObject, pinOk: () -> Boolean): JSONObject {
        val app = ctx.applicationContext
        val c = cmd.optString("c")
        when (c) {
            "open", "youtube" -> {
                val text = cmd.optString("url").trim()
                val url = if (c == "youtube") Browser.youtubeSearchUrl(text) else text.ifEmpty { null }
                return open(app, url, pinOk)
            }
            "vol" -> {
                val dir = when (cmd.optInt("d")) {
                    1 -> AudioManager.ADJUST_RAISE
                    -1 -> AudioManager.ADJUST_LOWER
                    else -> AudioManager.ADJUST_TOGGLE_MUTE
                }
                app.getSystemService(AudioManager::class.java)?.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                return state(app)
            }
            "fav-add" -> {
                onMain { Browser.open?.let { if (it.currentUrl != Browser.HOME) Browser.addFavorite(app, it.currentTitle, it.currentUrl) } }
                onMain { Browser.open?.refreshStart() }
                return state(app)
            }
            "fav-remove" -> {
                Browser.removeFavorite(app, cmd.optString("url"))
                onMain { Browser.open?.refreshStart() }
                return state(app)
            }
        }
        // Phone pixels to TV pixels: the TV screen is a lot larger than the touchpad
        val scale = app.resources.displayMetrics.density * 1.4f
        val error = onMain {
            val b = Browser.open ?: return@onMain tr("Der Browser ist am Fernseher nicht geöffnet.")
            when (c) {
                "home" -> b.load(Browser.HOME)
                "back" -> b.back()
                "forward" -> b.forward()
                "reload" -> b.reload()
                "close" -> b.finish()
                "key" -> b.key(cmd.optString("k"))
                "move" -> b.move(cmd.optDouble("dx", 0.0).toFloat() * scale, cmd.optDouble("dy", 0.0).toFloat() * scale, scrollAtEdge = false)
                "tap" -> b.click()
                "scroll" -> b.scroll(cmd.optDouble("dx", 0.0).toFloat() * scale * 2, cmd.optDouble("dy", 0.0).toFloat() * scale * 2)
                "text" -> b.type(cmd.optString("t"), cmd.optBoolean("enter"))
                "play" -> b.media("toggle", 0)
                "seek" -> b.media("seek", cmd.optInt("s"))
                "pointer" -> b.setPointer(!b.pointerEnabled)
            }
            null
        }
        return state(app).apply { if (error != null) put("error", error) }
    }

    private fun open(app: Context, url: String?, pinOk: () -> Boolean): JSONObject {
        val already = onMain { Browser.open } != null
        if (!already) {
            val reason = Browser.restricted(app)
            if (reason != null) {
                if (!pinOk()) return state(app).put("error", reason).put("needPin", true)
                Browser.unlockedUntil = android.os.SystemClock.elapsedRealtime() + 10_000
            }
        }
        onMain { BrowserActivity.open(app, url) }
        if (!already) {
            // Android may refuse to bring up a window while another app is in front
            Thread.sleep(1500)
            if (onMain { Browser.open } == null) {
                return state(app).put("error", tr("Der Fernseher hat den Browser nicht geöffnet. Öffne ihn einmal am Fernseher über das Dock."))
            }
        }
        return state(app)
    }
}
