package de.glas.browser

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The launcher's phone remote steers the browser through here: call("cmd", "<json>") answers with the
 * state as JSON. Only apps signed with the Glas key may call it (permission de.glas.permission.REMOTE).
 */
class RemoteProvider : ContentProvider() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() = true

    private fun <T> onMain(block: () -> T): T? {
        var result: T? = null
        val done = CountDownLatch(1)
        main.post { try { result = block() } finally { done.countDown() } }
        done.await(3, TimeUnit.SECONDS)
        return result
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context!!
        val cmd = runCatching { JSONObject(arg ?: "{}") }.getOrDefault(JSONObject())
        val error = if (method == "cmd") onMain { run(ctx, cmd) } else null
        val state = onMain { state(ctx) } ?: JSONObject()
        if (error != null) state.put("error", error)
        // Collected off the main thread: reading the log takes a moment
        if (cmd.optString("c") == "log") state.put("log", Diag.report(ctx))
        return Bundle().apply { putString("json", state.toString()) }
    }

    /** Runs one command on the main thread; returns an error text or null. */
    private fun run(ctx: Context, cmd: JSONObject): String? {
        val c = cmd.optString("c")
        cmd.optString("lang").takeIf { it.isNotEmpty() }?.let { if (it != Store.language(ctx)) { Store.setLanguage(ctx, it); I18n.init(ctx, it) } }
        when (c) {
            "fav-add" -> BrowserActivity.open?.let { if (it.currentUrl != Store.HOME) Store.addFavorite(ctx, it.currentTitle, it.currentUrl); it.refreshStart() }
            "fav-remove" -> { Store.removeFavorite(ctx, cmd.optString("url")); BrowserActivity.open?.refreshStart() }
            "install" -> BrowserActivity.open?.installExtension(cmd.optString("url")) ?: return tr("Der Browser ist am Fernseher nicht geöffnet.")
            "state", "", "log" -> {}
            else -> {
                val b = BrowserActivity.open ?: return tr("Der Browser ist am Fernseher nicht geöffnet.")
                // Phone pixels to TV pixels: the TV screen is a lot larger than the touchpad
                val scale = ctx.resources.displayMetrics.density * 1.4f
                when (c) {
                    "home" -> b.load(Store.HOME)
                    "load" -> b.load(cmd.optString("url"))
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
            }
        }
        return null
    }

    private fun state(ctx: Context): JSONObject {
        val o = JSONObject()
        val b = BrowserActivity.open
        o.put("open", b != null)
        if (b != null) {
            o.put("url", if (b.currentUrl == Store.HOME) "" else b.currentUrl)
            o.put("title", b.currentTitle)
            o.put("playing", b.playing)
            o.put("pointer", b.pointerEnabled)
        }
        val favs = JSONArray()
        for (f in Store.favorites(ctx)) favs.put(JSONObject().put("title", f.title).put("url", f.url))
        return o.put("favorites", favs)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

/** The launcher's "Einstellen per Handy": its address for the QR code. */
object Phone {
    private val uri = Uri.parse("content://de.glas.launcher.phone")

    private fun ask(ctx: Context, method: String): String? =
        runCatching { ctx.contentResolver.call(uri, method, null, null)?.getString("url") }.getOrNull()

    /** The address while the launcher offers it already, without switching it on. */
    fun current(ctx: Context) = ask(ctx, "current")

    /** Switches "Einstellen per Handy" on if needed and returns its address; null without launcher or network. */
    fun start(ctx: Context) = ask(ctx, "start")
}
