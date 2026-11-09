package de.glas.launcher

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The phone remote: a small web server in the home network. The phone opens
 * http://<TV>:8765 in its browser and gets a page with an address bar, favourites,
 * a touchpad for the pointer, video buttons and volume. The page can be added to the
 * phone's home screen and then works like an app.
 */
object RemoteServer {
    const val PORT = 8765

    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    /** Starts the server if it is switched on; safe to call again. */
    @Synchronized
    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (!Browser.remoteEnabled(app) || server != null) return
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        }.getOrNull() ?: return
        server = socket
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(client) }; runCatching { client.close() } }
            }
        }, "GlasRemote").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    /** "http://192.168.178.173:8765", or null without a home network. */
    fun address(ctx: Context): String? {
        if (!Browser.remoteEnabled(ctx)) return null
        val ips = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("eth") || it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress } }
        }.getOrNull().orEmpty()
        return ips.firstOrNull()?.hostAddress?.let { "http://$it:$PORT" }
    }

    /** Dialog on the TV with a QR code and the address for the phone. */
    fun showConnect(ctx: Context) {
        val url = address(ctx)
        if (!Browser.remoteEnabled(ctx)) return ctx.toast(tr("Die Handy-Fernbedienung ist ausgeschaltet (Einstellungen → Browser)"))
        if (url == null) return ctx.toast(tr("Der Fernseher ist nicht mit dem Netzwerk verbunden."))
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(32), ctx.dp(26), ctx.dp(32), ctx.dp(26))
            isFocusable = true
        }
        column.addView(Ui.text(ctx, 22f).apply { text = tr("Handy als Fernbedienung") })
        Qr.bitmap(url, ctx.dp(220))?.let { bmp ->
            column.addView(ImageView(ctx).apply { setImageBitmap(bmp) }, LinearLayout.LayoutParams(ctx.dp(220), ctx.dp(220)).apply {
                topMargin = ctx.dp(18)
                bottomMargin = ctx.dp(14)
            })
        }
        column.addView(Ui.text(ctx, 24f).apply { text = url; setTextColor(0xFF8AB4F8.toInt()) })
        column.addView(Ui.text(ctx, 14f, color = Ui.MUTED).apply {
            text = tr("Code mit der Handy-Kamera scannen oder die Adresse im Handy-Browser öffnen. Handy und Fernseher müssen im selben WLAN sein. Im Browser-Menü des Handys „Zum Startbildschirm hinzufügen“ wählen, dann ist die Fernbedienung wie eine App da.")
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            setPadding(0, ctx.dp(14), 0, 0)
        })
        dialog.setContentView(column)
        dialog.window?.setBackgroundDrawable(Ui.panel(ctx))
        dialog.show()
        dialog.window?.setLayout(ctx.dp(520), WRAP_CONTENT)
        column.requestFocus()
    }

    // ---- HTTP ----

    private class Request(val method: String, val path: String, val body: String)

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val input = BufferedInputStream(socket.getInputStream())
        val req = read(input) ?: return
        val out = socket.getOutputStream()
        fun send(status: String, type: String, body: ByteArray, cache: Boolean = false) {
            val head = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                (if (cache) "Cache-Control: max-age=86400\r\n" else "Cache-Control: no-store\r\n") +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray())
            out.write(body)
            out.flush()
        }
        when {
            req.method == "GET" && (req.path == "/" || req.path.startsWith("/?")) ->
                send("200 OK", "text/html; charset=utf-8", page().toByteArray())
            req.path == "/manifest.json" -> send("200 OK", "application/manifest+json", manifest().toByteArray())
            req.path == "/icon.png" -> send("200 OK", "image/png", icon(), cache = true)
            req.path == "/api/state" -> send("200 OK", "application/json", state().toString().toByteArray())
            req.method == "POST" && req.path == "/api/cmd" -> {
                val result = runCatching { command(JSONObject(req.body.ifBlank { "{}" })) }
                    .getOrElse { JSONObject().put("error", it.message ?: "error") }
                send("200 OK", "application/json", result.toString().toByteArray())
            }
            else -> send("404 Not Found", "text/plain", "Not found".toByteArray())
        }
    }

    private fun read(input: InputStream): Request? {
        fun line(): String? {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
                if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
                if (b.size() > 8192) return null
                b.write(c)
            }
        }
        val first = line()?.split(' ') ?: return null
        if (first.size < 2) return null
        var length = 0
        while (true) {
            val h = line() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val body = ByteArray(length.coerceIn(0, 64 * 1024))
        var n = 0
        while (n < body.size) {
            val r = input.read(body, n, body.size - n)
            if (r < 0) break
            n += r
        }
        return Request(first[0], first[1], String(body, 0, n, Charsets.UTF_8))
    }

    // ---- Commands from the phone ----

    /** Runs [block] on the main thread and waits for its answer. */
    private fun <T> onMain(block: () -> T): T? {
        var result: T? = null
        val done = CountDownLatch(1)
        main.post { try { result = block() } finally { done.countDown() } }
        done.await(3, TimeUnit.SECONDS)
        return result
    }

    private fun command(cmd: JSONObject): JSONObject {
        val c = cmd.optString("c")
        when (c) {
            "open", "search", "youtube" -> {
                val text = cmd.optString("url").trim()
                val url = when (c) {
                    "search" -> Browser.searchUrl(text)
                    "youtube" -> Browser.youtubeSearchUrl(text)
                    else -> text.ifEmpty { null }
                }
                return openBrowser(url)
            }
            "vol" -> {
                val audio = app.getSystemService(AudioManager::class.java)
                val dir = when (cmd.optInt("d")) {
                    1 -> AudioManager.ADJUST_RAISE
                    -1 -> AudioManager.ADJUST_LOWER
                    else -> AudioManager.ADJUST_TOGGLE_MUTE
                }
                audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                return state()
            }
            "fav-add" -> {
                val url = cmd.optString("url").trim()
                if (url.isNotEmpty()) Browser.addFavorite(app, cmd.optString("title"), Browser.toUrl(url))
                else onMain { Browser.open?.let { Browser.addFavorite(app, it.currentTitle, it.currentUrl) } }
                onMain { Browser.open?.refreshStart() }
                return state()
            }
            "fav-remove" -> {
                Browser.removeFavorite(app, cmd.optString("url"))
                onMain { Browser.open?.refreshStart() }
                return state()
            }
        }
        val error = onMain {
            val b = Browser.open ?: return@onMain tr("Der Browser ist am Fernseher nicht geöffnet.")
            when (c) {
                "home" -> b.load(Browser.HOME)
                "back" -> b.back()
                "forward" -> b.forward()
                "reload" -> b.reload()
                "close" -> b.finish()
                "key" -> b.key(cmd.optString("k"))
                "move" -> b.move(cmd.optDouble("dx", 0.0).toFloat() * scale(), cmd.optDouble("dy", 0.0).toFloat() * scale(), scrollAtEdge = false)
                "tap" -> b.click()
                "scroll" -> b.scroll(cmd.optDouble("dx", 0.0).toFloat() * scale() * 2, cmd.optDouble("dy", 0.0).toFloat() * scale() * 2)
                "text" -> b.type(cmd.optString("t"), cmd.optBoolean("enter"))
                "enter" -> b.pressEnter()
                "play" -> b.media("toggle", 0)
                "seek" -> b.media("seek", cmd.optInt("s"))
                "pointer" -> b.setPointer(!b.pointerEnabled)
            }
            null
        }
        return state().apply { if (error != null) put("error", error) }
    }

    /** Phone pixels to TV pixels: the TV screen is a lot larger than the touchpad. */
    private fun scale() = app.resources.displayMetrics.density * 1.4f

    private fun openBrowser(url: String?): JSONObject {
        val already = onMain { Browser.open } != null
        if (!already) {
            val reason = Browser.restricted(app)
            if (reason != null) return state().put("error", tr("{0}. Bitte am Fernseher mit dem PIN öffnen.", reason))
        }
        onMain { BrowserActivity.open(app, url) }
        if (!already) {
            // Android may refuse to bring up a window while another app is in front
            Thread.sleep(1500)
            if (onMain { Browser.open } == null) return state().put("error", tr("Der Fernseher hat den Browser nicht geöffnet. Öffne ihn einmal am Fernseher über das Dock."))
        }
        return state()
    }

    private fun state(): JSONObject {
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
        for (f in Browser.favorites(app)) favs.put(JSONObject().put("title", f.title).put("url", f.url))
        o.put("favorites", favs)
        return o
    }

    // ---- The phone page ----

    /** The page in the launcher's language; texts marked in the HTML are looked up with tr(). */
    private fun page(): String {
        val html = app.assets.open("remote/index.html").bufferedReader().use { it.readText() }
        val keys = (Regex("data-t(?:p)?=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] } +
            Regex("t\\('([^']+)'\\)").findAll(html).map { it.groupValues[1] }).toSet()
        val table = JSONObject()
        for (k in keys) table.put(k, I18n.lookup(k))
        return html.replace("/*T*/{}", table.toString()).replace("lang=\"de\"", "lang=\"${I18n.lang}\"")
    }

    private fun manifest() = JSONObject()
        .put("name", tr("Glas Fernbedienung"))
        .put("short_name", tr("Glas Fernbedienung"))
        .put("start_url", "/")
        .put("display", "standalone")
        .put("background_color", "#07090d")
        .put("theme_color", "#0d1117")
        .put("icons", JSONArray().put(JSONObject().put("src", "/icon.png").put("sizes", "192x192").put("type", "image/png")))
        .toString()

    private val iconBytes by lazy {
        val d = app.resources.getDrawableForDensity(R.mipmap.ic_launcher, DisplayMetrics.DENSITY_XXXHIGH, null)
        val bmp = (d as? BitmapDrawable)?.bitmap
        val out = ByteArrayOutputStream()
        bmp?.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    private fun icon() = iconBytes
}
